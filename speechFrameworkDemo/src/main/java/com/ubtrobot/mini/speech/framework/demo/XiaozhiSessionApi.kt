package com.ubtrobot.mini.speech.framework.demo

/**
 * API Xiaozhi (WebSocket hoặc MQTT+UDP) cho DemoRecognizer / DemoSpeech.
 * Luồng nói = 2b23f8d. Chỉ thêm hook đổi Device-Id/Client-Id khi Apply Self-Control.
 */
interface XiaozhiSessionApi {
    fun getTransportLabel(): String
    fun isInFirstGreetingPhase(): Boolean
    /** True khi đang phát TTS hoặc chờ ting sau chào wake – không dispose session. */
    fun isPlaybackOrGreetingActive(): Boolean
    fun isAcceptingServerPcm(): Boolean
    fun setOnWebSocketSessionReady(callback: () -> Unit)
    fun forceStopPlaybackForHeyMini()
    /**
     * Otto EnterMusicOnlyMode: abort TTS, đóng WS, idle + wake word vẫn bật.
     * Chỉ gọi khi MediaPlayer đã start (không gọi lúc TIM NHAC / search).
     */
    fun enterMusicOnlyMode()
    /** Clear flag music-only (không đóng/mở kênh) — tránh kẹt chặn mic/PCM luồng chat cũ. */
    fun exitMusicOnlyMode()
    /**
     * Otto WakeWordInvoke sau hết/fail nhạc: mở lại kênh nếu đã đóng, gửi detect [text].
     */
    fun sendSyntheticWakeDetect(text: String)
    fun onWakeOrResumeListening(forceReconnect: Boolean = false)
    fun onNewConversationTurn()
    fun wasWakeHandledRecently(): Boolean
    fun sendPcmFrameFromJava(frame: ByteArray)
    fun isAudioChannelOpened(): Boolean
    fun recoverTalkAfterShowConfig()
    fun switchDeviceIdentity(deviceId: String, clientId: String): Boolean
    fun dispose()
}
