package com.ubtrobot.mini.speech.framework.demo

import android.content.Context
import android.util.Log

/**
 * Facade Xiaozhi — delegate WebSocket hoặc MQTT tùy [XiaozhiTransportPreference].
 * Logic WebSocket: [XiaozhiWebSocketSessionManager] (= commit 2b23f8d).
 */
class XiaozhiSessionManager private constructor(
    private val delegate: XiaozhiSessionApi
) : XiaozhiSessionApi {

    companion object {
        private const val TAG = "XiaozhiSessionManager"

        const val SAMPLE_RATE = XiaozhiWebSocketSessionManager.SAMPLE_RATE
        const val TTS_SAMPLE_RATE = XiaozhiWebSocketSessionManager.TTS_SAMPLE_RATE
        const val TTS_PLAYBACK_SAMPLE_RATE = 44100
        const val CHANNELS = XiaozhiWebSocketSessionManager.CHANNELS
        const val FRAME_MS = XiaozhiWebSocketSessionManager.FRAME_MS
        const val PCM_GAIN_CONCENTUS = XiaozhiWebSocketSessionManager.PCM_GAIN_CONCENTUS

        @JvmStatic
        fun noteRobotSkillPcmSuppress(durationMs: Long, reason: String) {
            // Ủy quyền sang WS companion (pcmBlockReason đọc flag ở đó).
            XiaozhiWebSocketSessionManager.noteRobotSkillPcmSuppress(durationMs, reason)
        }

        fun create(
            context: Context,
            deviceId: String,
            clientId: String,
            encoder: IOpusEncoder,
            decoder: IOpusDecoder,
            pcmGain: Float,
            usesNativeOpus: Boolean,
            onTtsStoppedRestartMic: (() -> Unit)?,
            audioSessionId: Int,
            onTtsStarted: (() -> Unit)?,
            onFirstGreetingMicReady: (() -> Unit)?,
            transport: XiaozhiTransportType,
            websocketUrl: String,
            accessToken: String,
            mqttConfig: XiaozhiMqttConfig?
        ): XiaozhiSessionManager? {
            val api: XiaozhiSessionApi = when (transport) {
                XiaozhiTransportType.WEBSOCKET -> XiaozhiWebSocketSessionManager(
                    context,
                    deviceId,
                    clientId,
                    websocketUrl,
                    accessToken,
                    encoder,
                    decoder,
                    pcmGain,
                    onTtsStoppedRestartMic,
                    audioSessionId,
                    onTtsStarted,
                    onFirstGreetingMicReady,
                    usesNativeOpus
                )
                XiaozhiTransportType.MQTT -> {
                    val cfg = mqttConfig ?: run {
                        Log.e(TAG, "MQTT transport nhưng chưa có mqtt config từ OTA")
                        return null
                    }
                    XiaozhiMqttSessionManager(
                        context,
                        deviceId,
                        clientId,
                        cfg,
                        encoder,
                        decoder,
                        pcmGain,
                        onTtsStoppedRestartMic,
                        audioSessionId,
                        onTtsStarted,
                        onFirstGreetingMicReady,
                        usesNativeOpus
                    )
                }
            }
            return XiaozhiSessionManager(api)
        }
    }

    override fun getTransportLabel(): String = delegate.getTransportLabel()
    override fun isInFirstGreetingPhase(): Boolean = delegate.isInFirstGreetingPhase()
    override fun isPlaybackOrGreetingActive(): Boolean = delegate.isPlaybackOrGreetingActive()
    override fun isAcceptingServerPcm(): Boolean = delegate.isAcceptingServerPcm()
    override fun setOnWebSocketSessionReady(callback: () -> Unit) = delegate.setOnWebSocketSessionReady(callback)
    override fun forceStopPlaybackForHeyMini() = delegate.forceStopPlaybackForHeyMini()
    override fun onWakeOrResumeListening(forceReconnect: Boolean) =
        delegate.onWakeOrResumeListening(forceReconnect)
    override fun onNewConversationTurn() = delegate.onNewConversationTurn()
    override fun wasWakeHandledRecently(): Boolean = delegate.wasWakeHandledRecently()
    override fun sendPcmFrameFromJava(frame: ByteArray) = delegate.sendPcmFrameFromJava(frame)
    override fun isAudioChannelOpened(): Boolean = delegate.isAudioChannelOpened()
    override fun recoverTalkAfterShowConfig() = delegate.recoverTalkAfterShowConfig()
    override fun switchDeviceIdentity(deviceId: String, clientId: String): Boolean =
        delegate.switchDeviceIdentity(deviceId, clientId)
    override fun dispose() = delegate.dispose()
}
