package com.ubtrobot.mini.speech.framework.demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ubtech.utilcode.utils.LogUtils
import com.ubtrobot.speech.AbstractWakeUpDetector
import org.json.JSONObject

/**
 * WakeUpDetector đăng ký 1 lần với CompositeSpeechService; bên trong đổi engine (Sherpa Anh / Sherpa Việt /
 * Porcupine) lúc đang chạy mà không phải dựng lại speech service hay mic.
 * Porcupine hoặc Sherpa Việt lỗi → tự quay về Sherpa Anh.
 */
class SwitchableWakeUpDetector(private val context: Context) :
    AbstractWakeUpDetector(),
    WakeWordPcmFeeder {

  companion object {
    private const val TAG = "WakeEngineSwitch"
    const val ENGINE_SHERPA = "sherpa"
    const val ENGINE_SHERPA_VI = "sherpa_vi"
    const val ENGINE_PORCUPINE = "porcupine"
    val ALL_ENGINES = listOf(ENGINE_SHERPA, ENGINE_SHERPA_VI, ENGINE_PORCUPINE)
    private const val PORCUPINE_RETRY_MS = 30_000L
    private const val PORCUPINE_MAX_RETRIES = 10
  }

  private val lock = Any()
  private val handler = Handler(Looper.getMainLooper())
  private var porcupineRetries = 0
  private val retryPorcupine = Runnable {
    synchronized(lock) {
      val p = porcupine ?: return@synchronized
      if (requestedEngine != ENGINE_PORCUPINE || active === p) return@synchronized
      LogUtils.i(TAG, "[WakeWord] thử lại Porcupine (lần $porcupineRetries)")
      fallbackReason = ""
      switchTo(p)
    }
  }
  private val sherpa = SherpaOnnxWakeUpDetector(context, SherpaKwsModel.ENGLISH)
  private val sherpaVi = SherpaOnnxWakeUpDetector(context, SherpaKwsModel.VIETNAMESE)
  private var porcupine: PorcupineWakeEngine? = null
  @Volatile private var active: WakeEngine = sherpa
  /** Engine đang nạp model (Sherpa Việt ~1 phút); [active] vẫn nghe cho tới khi nó sẵn sàng. */
  @Volatile private var pending: WakeEngine? = null
  @Volatile private var started = false
  @Volatile private var suppressUntilMs = 0L
  @Volatile private var requestedEngine = ENGINE_SHERPA

  /** Lý do đang chạy Sherpa Anh dù đã chọn engine khác (rỗng nếu đúng engine đã chọn). */
  @Volatile var fallbackReason: String = ""
    private set

  init {
    sherpa.onDetected = { onEngineDetected(sherpa) }
    sherpaVi.onDetected = { onEngineDetected(sherpaVi) }
    sherpaVi.onFatalError = { reason -> fallbackToSherpa(sherpaVi, "Sherpa tiếng Việt lỗi: $reason", retry = false) }
    sherpaVi.onReady = { onPendingReady(sherpaVi) }
  }

  var lastDetectedKeyword: String
    get() = active.lastDetectedKeyword
    set(value) {
      active.lastDetectedKeyword = value
    }

  val activeEngineId: String get() = active.engineId

  private fun onEngineDetected(source: WakeEngine) {
    if (source !== active) return
    notifyWakeUp(null)
  }

  /** Áp cấu hình đã lưu (gọi lúc khởi động và mỗi lần POST /api/wake_engine). */
  fun applySettings(s: WakeEngineSettings.Snapshot) {
    synchronized(lock) {
      requestedEngine = s.engine
      handler.removeCallbacks(retryPorcupine)
      porcupineRetries = 0
      sherpa.configure(s.sherpaKeywords, s.sherpaSensitivity)
      sherpaVi.configure(s.sherpaViKeywords, s.sherpaViSensitivity)
      fallbackReason = ""
      when (s.engine) {
        ENGINE_SHERPA_VI -> switchTo(sherpaVi)
        ENGINE_PORCUPINE -> {
          val p = porcupine ?: createPorcupine()
          if (p == null) {
            fallbackReason = "Thiếu thư viện Porcupine trong APK"
            switchTo(sherpa)
          } else {
            p.configure(s.porcupineAccessKey, s.porcupineSensitivity)
            switchTo(p)
          }
        }
        else -> switchTo(sherpa)
      }
    }
  }

  private fun createPorcupine(): PorcupineWakeEngine? {
    return try {
      PorcupineWakeEngine(context).also { p ->
        p.onDetected = { onEngineDetected(p) }
        p.onFatalError = { reason -> fallbackToSherpa(p, reason, p.lastErrorRetryable) }
        porcupine = p
      }
    } catch (e: Throwable) {
      LogUtils.e(TAG, "[WakeWord] Porcupine không khởi tạo được: ${e.message}", e)
      null
    }
  }

  private fun fallbackToSherpa(from: WakeEngine, reason: String, retry: Boolean) {
    synchronized(lock) {
      if (pending === from) {
        pending = null
        from.stop()
        fallbackReason = reason
        LogUtils.w(TAG, "[WakeWord] ${from.engineId} nạp lỗi → giữ ${active.engineId}: $reason")
        return
      }
      if (active !== from) return
      fallbackReason = reason
      LogUtils.w(TAG, "[WakeWord] ${from.engineId} lỗi → quay về Sherpa Anh: $reason")
      switchTo(sherpa)
      if (retry && from === porcupine && porcupineRetries < PORCUPINE_MAX_RETRIES) {
        porcupineRetries++
        handler.removeCallbacks(retryPorcupine)
        handler.postDelayed(retryPorcupine, PORCUPINE_RETRY_MS)
      }
    }
  }

  private fun switchTo(target: WakeEngine) {
    pending?.let { if (it !== target) it.stop() }
    pending = null
    val old = active
    if (old === target) {
      if (started) target.start()
      return
    }
    if (!started) {
      active = target
      return
    }
    if (target === sherpaVi && !target.isStreamReady()) {
      pending = target
      target.start()
      LogUtils.i(TAG, "[WakeWord] nạp ${target.engineId}, tạm nghe bằng ${old.engineId}")
      return
    }
    commitSwitch(old, target)
  }

  private fun commitSwitch(old: WakeEngine, target: WakeEngine) {
    active = target
    LogUtils.i(TAG, "[WakeWord] engine ${old.engineId} → ${target.engineId}")
    old.stop()
    val remain = suppressUntilMs - System.currentTimeMillis()
    if (remain > 0) target.suppressWakeFor(remain) else target.clearSuppressWake()
    target.start()
  }

  private fun onPendingReady(engine: WakeEngine) {
    synchronized(lock) {
      if (pending !== engine) return
      pending = null
      if (active !== engine) commitSwitch(active, engine)
    }
  }

  /** Engine cần nạp lâu (Sherpa Việt) → bật Sherpa Anh trước để mic chạy ngay, tự chuyển khi nạp xong. */
  fun start() {
    synchronized(lock) {
      started = true
      val want = active
      if (want === sherpaVi && !want.isStreamReady()) {
        active = sherpa
        sherpa.start()
        switchTo(sherpaVi)
      } else {
        want.start()
      }
    }
  }

  fun stop() {
    synchronized(lock) {
      started = false
      pending?.stop()
      pending = null
      active.stop()
    }
  }

  fun isStreamReady(): Boolean = active.isStreamReady()

  /**
   * Chờ engine đang chọn sẵn sàng. Porcupine / Sherpa Việt lỗi hoặc quá nửa hạn chờ (kích hoạt online chậm,
   * model lớn nạp lâu) → Sherpa Anh thay trong cùng hạn chờ, để DemoSpeech vẫn bật được mic.
   */
  fun waitUntilReady(timeoutMs: Long = 15_000L): Boolean {
    if (!started) start()
    val begin = System.currentTimeMillis()
    val deadline = begin + timeoutMs
    val secondaryDeadline = begin + timeoutMs / 2
    while (System.currentTimeMillis() < deadline) {
      val cur = active
      if (cur.isStreamReady()) return true
      if (cur !== sherpa && System.currentTimeMillis() > secondaryDeadline) {
        fallbackToSherpa(cur, "${cur.engineId} khởi động quá lâu", retry = cur === porcupine)
      }
      try {
        Thread.sleep(50)
      } catch (_: InterruptedException) {
        return false
      }
    }
    LogUtils.e(TAG, "[WakeWord] waitUntilReady timeout (${timeoutMs}ms) engine=${active.engineId}")
    return active.isStreamReady()
  }

  fun suppressWakeFor(ms: Long) {
    suppressUntilMs = System.currentTimeMillis() + ms
    active.suppressWakeFor(ms)
  }

  fun clearSuppressWake() {
    suppressUntilMs = 0L
    active.clearSuppressWake()
  }

  override fun feedPcmFrame(frame: ByteArray) {
    active.feedPcmFrame(frame)
  }

  fun recentPcmWav(): ByteArray? = (active as? SherpaOnnxWakeUpDetector)?.recentPcmWav()

  fun statusJson(): JSONObject = JSONObject().apply {
    put("requested", requestedEngine)
    put("active", active.engineId)
    put("pending", pending?.engineId ?: "")
    put("ready", active.isStreamReady())
    put("fallback_reason", fallbackReason)
    put("error", active.lastError)
    put("last_keyword", active.lastDetectedKeyword)
  }
}
