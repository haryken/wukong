package com.ubtrobot.mini.speech.framework.demo

import ai.picovoice.porcupine.Porcupine
import android.content.Context
import android.os.Process
import com.ubtech.utilcode.utils.LogUtils
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Picovoice Porcupine (low-level API) cho "Hey Mini" (assets/hey-mini_en_android_v4_0_0.ppn).
 * Không dùng PorcupineManager vì nó tự mở AudioRecord – mic đã do DemoRecognizer giữ (chung với Xiaozhi).
 * Cần AccessKey (console.picovoice.ai); init cần Internet để kích hoạt, gói free giới hạn số thiết bị.
 */
class PorcupineWakeEngine(private val context: Context) : WakeEngine {

  companion object {
    private const val TAG = "PorcupineWake"
    const val KEYWORD_ASSET = "hey-mini_en_android_v4_0_0.ppn"
    const val PHRASE = "HEY MINI"
    private const val COOLDOWN_MS = 2000L

    /** Tên exception Porcupine → gợi ý tiếng Việt (so tên lớp để không phụ thuộc từng subclass). */
    private fun describe(e: Throwable): String {
      val msg = e.message?.lineSequence()?.firstOrNull()?.take(160).orEmpty()
      val hint = when (e.javaClass.simpleName) {
        "PorcupineActivationLimitException" -> "AccessKey đã hết lượt thiết bị (quota Picovoice)"
        "PorcupineActivationRefusedException" -> "AccessKey bị Picovoice từ chối"
        "PorcupineActivationThrottledException" -> "Picovoice đang giới hạn tần suất kích hoạt, thử lại sau"
        "PorcupineActivationException" -> "Không kích hoạt được AccessKey (robot cần Internet)"
        "PorcupineInvalidArgumentException" -> "AccessKey hoặc file .ppn không hợp lệ"
        "PorcupineIOException" -> "Không đọc được file .ppn / model"
        "UnsatisfiedLinkError", "NoClassDefFoundError" -> "Thiếu thư viện Porcupine trong APK"
        else -> "Porcupine lỗi"
      }
      return if (msg.isEmpty()) hint else "$hint ($msg)"
    }
  }

  override val engineId: String = SwitchableWakeUpDetector.ENGINE_PORCUPINE

  @Volatile override var lastDetectedKeyword: String = ""
  @Volatile override var onDetected: ((String) -> Unit)? = null
  @Volatile override var onFatalError: ((String) -> Unit)? = null
  @Volatile private var errorText: String = ""
  override val lastError: String get() = errorText

  /** Lỗi gần nhất do mạng / throttle (thử lại được), không phải key sai / hết quota. */
  @Volatile var lastErrorRetryable: Boolean = false
    private set

  @Volatile private var accessKey: String = ""
  @Volatile private var sensitivity: Float = 0.5f

  private sealed class WorkerCmd {
    object Init : WorkerCmd()
    object Stop : WorkerCmd()
    class Pcm(val frame: ByteArray) : WorkerCmd()
  }

  private val cmdQueue = ArrayBlockingQueue<WorkerCmd>(512)
  private val workerStarted = AtomicBoolean(false)
  private val streamReady = AtomicBoolean(false)
  @Volatile private var wantRunning = false
  @Volatile private var suppressWakeUntilMs = 0L
  private var pcmQueueDrops = 0

  private val worker = Thread({
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    var porcupine: Porcupine? = null
    var frame = ShortArray(0)
    var fill = 0
    var lastWakeMs = 0L

    fun release() {
      try {
        porcupine?.delete()
      } catch (_: Throwable) {
      }
      porcupine = null
      fill = 0
      streamReady.set(false)
    }

    fun fail(e: Throwable) {
      release()
      val reason = describe(e)
      lastErrorRetryable = e.javaClass.simpleName == "PorcupineActivationException" ||
        e.javaClass.simpleName == "PorcupineActivationThrottledException"
      errorText = reason
      LogUtils.e(TAG, "[WakeWord] $reason", e)
      onFatalError?.invoke(reason)
    }

    fun initEngine() {
      release()
      val key = accessKey
      if (key.isBlank()) {
        lastErrorRetryable = false
        errorText = "Chưa nhập AccessKey Picovoice"
        LogUtils.e(TAG, "[WakeWord] $errorText")
        onFatalError?.invoke(errorText)
        return
      }
      val s = sensitivity
      val p = Porcupine.Builder()
        .setAccessKey(key)
        .setKeywordPath(KEYWORD_ASSET)
        .setSensitivity(s)
        .build(context)
      porcupine = p
      frame = ShortArray(p.frameLength)
      fill = 0
      errorText = ""
      lastErrorRetryable = false
      streamReady.set(true)
      LogUtils.i(
        TAG,
        "[WakeWord] Porcupine ${p.version} ready – keyword=$KEYWORD_ASSET sensitivity=$s " +
          "frame=${p.frameLength} rate=${p.sampleRate}"
      )
    }

    fun process(bytes: ByteArray) {
      val p = porcupine ?: return
      if (System.currentTimeMillis() < suppressWakeUntilMs) return
      val n = bytes.size / 2
      var i = 0
      while (i < n) {
        val lo = bytes[i * 2].toInt() and 0xff
        val hi = bytes[i * 2 + 1].toInt()
        frame[fill++] = ((hi shl 8) or lo).toShort()
        i++
        if (fill < frame.size) continue
        fill = 0
        if (p.process(frame) < 0) continue
        val now = System.currentTimeMillis()
        if (now < suppressWakeUntilMs || now - lastWakeMs < COOLDOWN_MS) continue
        lastWakeMs = now
        lastDetectedKeyword = PHRASE
        LogUtils.i(TAG, "[WakeWord] detected: $PHRASE (Porcupine)")
        onDetected?.invoke(PHRASE)
      }
    }

    while (true) {
      when (val cmd = cmdQueue.take()) {
        is WorkerCmd.Init -> try {
          if (wantRunning) initEngine()
        } catch (e: Throwable) {
          fail(e)
        }
        is WorkerCmd.Stop -> release()
        is WorkerCmd.Pcm -> try {
          if (wantRunning) process(cmd.frame)
        } catch (e: Throwable) {
          fail(e)
        }
      }
    }
  }, "PorcupineWakeWorker")

  /** Đổi AccessKey / độ nhạy; đang chạy thì init lại Porcupine trên worker. */
  fun configure(newAccessKey: String, newSensitivity: Float) {
    val k = newAccessKey.trim()
    val s = newSensitivity.coerceIn(0f, 1f)
    if (k == accessKey && s == sensitivity && lastError.isEmpty()) return
    accessKey = k
    sensitivity = s
    LogUtils.i(TAG, "[WakeWord] configure sensitivity=$s key=${if (k.isEmpty()) "(trống)" else "***"}")
    if (wantRunning) {
      streamReady.set(false)
      cmdQueue.offer(WorkerCmd.Init)
    }
  }

  override fun start() {
    if (wantRunning) return
    wantRunning = true
    if (workerStarted.compareAndSet(false, true)) {
      worker.isDaemon = true
      worker.start()
    }
    cmdQueue.offer(WorkerCmd.Init)
    LogUtils.i(TAG, "[WakeWord] start requested")
  }

  override fun stop() {
    if (!wantRunning) return
    wantRunning = false
    streamReady.set(false)
    cmdQueue.offer(WorkerCmd.Stop)
    LogUtils.d(TAG, "[WakeWord] stop")
  }

  override fun isStreamReady(): Boolean = streamReady.get()

  override fun suppressWakeFor(ms: Long) {
    suppressWakeUntilMs = System.currentTimeMillis() + ms
  }

  override fun clearSuppressWake() {
    suppressWakeUntilMs = 0L
  }

  override fun feedPcmFrame(frame: ByteArray) {
    if (!wantRunning || !streamReady.get() || frame.isEmpty()) return
    if (System.currentTimeMillis() < suppressWakeUntilMs) return
    if (cmdQueue.remainingCapacity() < 64 || !cmdQueue.offer(WorkerCmd.Pcm(frame.copyOf()))) {
      pcmQueueDrops++
      if (pcmQueueDrops == 1 || pcmQueueDrops % 100 == 0) {
        LogUtils.w(TAG, "[WakeWord] PCM queue busy – dropped $pcmQueueDrops frames")
      }
    }
  }
}
