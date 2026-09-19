package com.ubtrobot.mini.speech.framework.demo

/**
 * API Xiaozhi (WebSocket hoặc MQTT+UDP) cho DemoRecognizer / DemoSpeech.
 * Luồng nói = commit 2b23f8d. Chỉ thêm identity hooks cho Self-Control Apply.
 */
interface XiaozhiSessionApi {
    fun getTransportLabel(): String
    fun isInFirstGreetingPhase(): Boolean
    /** True khi đang phát TTS hoặc chờ ting sau chào wake – không dispose session. */
    fun isPlaybackOrGreetingActive(): Boolean
    fun isAcceptingServerPcm(): Boolean
    fun setOnWebSocketSessionReady(callback: () -> Unit)
    fun forceStopPlaybackForHeyMini()
    fun onWakeOrResumeListening(forceReconnect: Boolean = false)
    fun onNewConversationTurn()
    fun wasWakeHandledRecently(): Boolean
    fun sendPcmFrameFromJava(frame: ByteArray)
    /** Self-Control Apply: kênh đã mở? */
    fun isAudioChannelOpened(): Boolean
    /** Sau hiện QR: nếu WS đứt thì thử mở lại (không đụng timing nói thường). */
    fun recoverTalkAfterShowConfig()
    /** Self-Control: đóng → Device-Id/Client-Id mới → mở lại. */
    fun switchDeviceIdentity(deviceId: String, clientId: String): Boolean
    fun dispose()
}
