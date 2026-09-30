package com.ubtrobot.mini.speech.framework.demo

/**
 * Một engine wake word nhận PCM 16 kHz mono 16-bit từ mic dùng chung (DemoRecognizer).
 * [SwitchableWakeUpDetector] chọn engine đang chạy và chuyển tiếp PCM / suppress.
 */
interface WakeEngine : WakeWordPcmFeeder {
  val engineId: String

  /** Cụm vừa nhận (vd. "HEY MINI") – cho log / wake hint. */
  var lastDetectedKeyword: String

  /** Gọi trên worker thread của engine khi nhận đúng từ đánh thức. */
  var onDetected: ((String) -> Unit)?

  /** Lỗi không tự hồi phục (AccessKey sai, hết quota, thiếu model...) – switcher sẽ quay về Sherpa. */
  var onFatalError: ((String) -> Unit)?

  /** Lỗi gần nhất (rỗng nếu đang ổn). */
  val lastError: String

  fun start()
  fun stop()
  fun isStreamReady(): Boolean
  fun suppressWakeFor(ms: Long)
  fun clearSuppressWake()
}
