package com.ubtrobot.mini.speech.framework.demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ubtech.utilcode.utils.LogUtils
import com.ubtrobot.speech.AbstractWakeUpDetector
import org.json.JSONObject

/**
 * WakeUpDetector đăng ký 1 lần với CompositeSpeechService; bên trong đổi engine (Sherpa / Porcupine)
 * lúc đang chạy mà không phải dựng lại speech service hay mic. Porcupine lỗi → tự quay về Sherpa.
 */
class SwitchableWakeUpDetector(private val context: Context) :
    AbstractWakeUpDetector(),
    WakeWordPcmFeeder {

  companion object {
    private const val TAG = "WakeEngineSwitch"
    const val ENGINE_SHERPA = "sherpa"
    const val ENGINE_PORCUPINE = "porcupine"
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
  private val sherpa = SherpaOnnxWakeUpDetector(context)
  private var porcupine: PorcupineWakeEngine? = null
  @Volatile private var active: WakeEngine = sherpa
  @Volatile private var started = false
  @Volatile private var suppressUntilMs = 0L
  @Volatile private var requestedEngine = ENGINE_SHERPA

  /** Lý do đang chạy Sherpa dù đã chọn Porcupine (rỗng nếu đúng engine đã chọn). */
  @Volatile var fallbackReason: String = ""
    private set

  init {
    sherpa.onDetected = { onEngineDetected(sherpa) }
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
      if (s.engine != ENGINE_PORCUPINE) {
        fallbackReason = ""
        switchTo(sherpa)
        return
      }
      val p = porcupine ?: createPorcupine()
      if (p == null) {
        fallbackReason = "Thiếu thư viện Porcupine trong APK"
        switchTo(sherpa)
        return
      }
      fallbackReason = ""
      p.configure(s.porcupineAccessKey, s.porcupineSensitivity)
      switchTo(p)
    }
  }

  private fun createPorcupine(): PorcupineWakeEngine? {
    return try {
      PorcupineWakeEngine(context).also { p ->
        p.onDetected = { onEngineDetected(p) }
        p.onFatalError = { reason -> onPorcupineFatal(p, reason) }
        porcupine = p
      }
    } catch (e: Throwable) {
      LogUtils.e(TAG, "[WakeWord] Porcupine không khởi tạo được: ${e.message}", e)
      null
    }
  }

  private fun onPorcupineFatal(p: PorcupineWakeEngine, reason: String) {
    fallbackFromPorcupine(p, reason, p.lastErrorRetryable)
  }

  private fun fallbackFromPorcupine(p: PorcupineWakeEngine, reason: String, retry: Boolean) {
    synchronized(lock) {
      if (active !== p) return
      fallbackReason = reason
      LogUtils.w(TAG, "[WakeWord] Porcupine lỗi → quay về Sherpa: $reason")
      switchTo(sherpa)
      if (retry && porcupineRetries < PORCUPINE_MAX_RETRIES) {
        porcupineRetries++
        handler.removeCallbacks(retryPorcupine)
        handler.postDelayed(retryPorcupine, PORCUPINE_RETRY_MS)
      }
    }
  }

  private fun switchTo(target: WakeEngine) {
    val old = active
    if (old === target) {
      if (started) target.start()
      return
    }
    active = target
    LogUtils.i(TAG, "[WakeWord] engine ${old.engineId} → ${target.engineId}")
    if (!started) return
    old.stop()
    val remain = suppressUntilMs - System.currentTimeMillis()
    if (remain > 0) target.suppressWakeFor(remain) else target.clearSuppressWake()
    target.start()
  }

  fun start() {
    started = true
    active.start()
  }

  fun stop() {
    started = false
    active.stop()
  }

  fun isStreamReady(): Boolean = active.isStreamReady()

  /**
   * Chờ engine đang chọn sẵn sàng. Porcupine lỗi hoặc quá nửa hạn chờ (kích hoạt online chậm)
   * → Sherpa thay trong cùng hạn chờ, để DemoSpeech vẫn bật được mic.
   */
  fun waitUntilReady(timeoutMs: Long = 15_000L): Boolean {
    if (!started) start()
    val begin = System.currentTimeMillis()
    val deadline = begin + timeoutMs
    val porcupineDeadline = begin + timeoutMs / 2
    while (System.currentTimeMillis() < deadline) {
      if (active.isStreamReady()) return true
      val p = porcupine
      if (p != null && active === p && System.currentTimeMillis() > porcupineDeadline) {
        fallbackFromPorcupine(p, "Porcupine khởi động quá lâu (mạng chậm?)", retry = true)
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

  fun statusJson(): JSONObject = JSONObject().apply {
    put("requested", requestedEngine)
    put("active", active.engineId)
    put("ready", active.isStreamReady())
    put("fallback_reason", fallbackReason)
    put("error", active.lastError)
    put("last_keyword", active.lastDetectedKeyword)
  }
}
