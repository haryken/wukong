package com.ubtrobot.mini.speech.framework.demo

/**
 * API Xiaozhi (WebSocket hoặc MQTT+UDP) cho DemoRecognizer / DemoSpeech.
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
    /** True khi WS/MQTT audio channel đã mở và nhận server hello. */
    fun isAudioChannelOpened(): Boolean
    /**
     * Sau show_config_page: nếu WS đứt (SSL abort khi vẽ mắt) thì tự mở lại kênh nói,
     * không bắt buộc hey mini ngay.
     */
    fun recoverTalkAfterShowConfig()
    /**
     * Otto ApplyDeviceIdentity: đóng kênh cũ sạch → Device-Id mới → mở kênh mới (giữ session/Opus).
     * @return true nếu kênh mới đã mở.
     */
    fun switchDeviceIdentity(deviceId: String, clientId: String): Boolean
    fun dispose()
}
