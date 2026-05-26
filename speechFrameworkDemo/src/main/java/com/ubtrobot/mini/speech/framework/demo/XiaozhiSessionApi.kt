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
    fun dispose()
}
