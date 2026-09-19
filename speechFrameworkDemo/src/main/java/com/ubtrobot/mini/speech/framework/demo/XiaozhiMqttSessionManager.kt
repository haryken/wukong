package com.ubtrobot.mini.speech.framework.demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import info.dourok.voicebot.OpusStreamPlayer
import info.dourok.voicebot.data.model.DummyDataGenerator
import info.dourok.voicebot.protocol.AudioState
import info.dourok.voicebot.protocol.ListeningMode
import info.dourok.voicebot.protocol.MqttProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MQTT+UDP session bám [ChatViewModel] Xiaozhi_Android:
 * - Kênh mở → luôn gửi Opus (không cờ chặn PCM sticky).
 * - TTS start → SPEAKING; TTS stop → chờ playback → listen → LISTENING.
 * - Wake → wake detect + listen (mở kênh nếu cần).
 *
 * Giữ: MCP / LLM emotion / ting / smile eyes — không gắn cờ dễ kẹt.
 */
class XiaozhiMqttSessionManager(
    private val context: Context,
    private val deviceId: String,
    private val clientId: String,
    private val mqttConfig: XiaozhiMqttConfig,
    private val encoder: IOpusEncoder,
    private val decoder: IOpusDecoder,
    private val pcmGain: Float = 1f,
    private val onTtsStoppedRestartMic: (() -> Unit)? = null,
    private val audioSessionId: Int = 0,
    private val onTtsStarted: (() -> Unit)? = null,
    private val onFirstGreetingMicReady: (() -> Unit)? = null,
    private val usesNativeOpus: Boolean = pcmGain <= 1f
) : XiaozhiSessionApi {

    override fun getTransportLabel(): String = "MQTT + UDP"
    override fun isAudioChannelOpened(): Boolean = protocol.isAudioChannelOpened()

    override fun recoverTalkAfterShowConfig() {
        // MQTT: tạm no-op (lỗi SSL abort thấy trên WebSocket).
        Log.d(TAG, "recoverTalkAfterShowConfig: skip (MQTT)")
    }

    companion object {
        private const val TAG = "XiaozhiMQTT"
        const val SAMPLE_RATE = 16000
        const val TTS_SAMPLE_RATE = 24000
        const val CHANNELS = 1
        const val FRAME_MS = 60
        const val PCM_GAIN_CONCENTUS = 2f

        private const val WAIT_PLAYBACK_TIMEOUT_MS = 30_000L
        private const val WAKE_DEBOUNCE_MS = 800L

        @JvmStatic
        fun noteRobotSkillPcmSuppress(durationMs: Long, reason: String) {
            Log.d(TAG, "noteRobotSkillPcmSuppress ignored (bám ChatViewModel): $reason ${durationMs}ms")
            XiaozhiSessionManager.noteRobotSkillPcmSuppress(durationMs, reason)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val encodeMutex = Mutex()
    private val listenMutex = Mutex()
    private val wakeMutex = Mutex()

    @Suppress("UNUSED_VARIABLE")
    private val deviceInfoStub = DummyDataGenerator.generate(deviceId, clientId)

    private val protocol = MqttProtocol(context, mqttConfig)
    private val player = OpusStreamPlayer(TTS_SAMPLE_RATE, CHANNELS, FRAME_MS, audioSessionId)

    @Volatile
    private var speaking = false

    /** false lúc boot — chỉ true sau gõ đầu / hey mini (onWake). */
    @Volatile
    private var voiceArmed = false

    @Volatile
    private var lastWakeMs = 0L

    @Volatile
    private var ttsStopJob: Job? = null

    @Volatile
    private var onSessionReady: (() -> Unit)? = null

    @Volatile
    private var sendCount = 0L

    init {
        MiniRobotActionInvoker.setXiaozhiWireIdentities(deviceId, clientId)
        // Không defer goodbye bằng cờ sticky — ChatViewModel đóng theo server.
        protocol.shouldDeferGoodbye = { false }
        scope.launch { bootstrap() }
        scope.launch { collectIncomingAudio() }
        scope.launch { collectJson() }
        scope.launch { collectChannelState() }
    }

    private suspend fun bootstrap() {
        try {
            // Boot: chỉ mở kênh + MCP — KHÔNG listen. Mic uplink tắt đến khi gõ đầu / hey mini.
            protocol.start()
            Log.i(TAG, "[init] openAudioChannel (không listen – chờ gõ đầu)")
            val res = protocol.openAudioChannel()
            if (!res.success) {
                Log.e(TAG, "[init] openAudioChannel failed")
                return
            }
            XiaozhiMcpResponder.awaitInitializeResponded()
            onSessionReady?.invoke()
            Log.i(TAG, "[init] sẵn sàng – chờ gõ đầu / hey mini mới listen")
        } catch (e: Exception) {
            Log.e(TAG, "bootstrap: ${e.message}", e)
        }
    }

    private suspend fun collectIncomingAudio() {
        try {
            var pcmFlow = flow {
                protocol.incomingAudioFlow.collect { opus ->
                    decoder.decode(opus)?.let { emit(it) }
                }
            }
            if (pcmGain > 1f) {
                pcmFlow = pcmFlow.map { applyPcmGain(it, pcmGain) }
            }
            if (!usesNativeOpus) {
                pcmFlow = pcmFlow.map { PcmResampler.resample24kTo44100(it) ?: it }
            }
            player.start(pcmFlow)
        } catch (e: Exception) {
            Log.e(TAG, "audio collect: ${e.message}", e)
        }
    }

    private suspend fun collectJson() {
        try {
            protocol.incomingJsonFlow.collect { json ->
                val type = json.optString("type")
                when (type) {
                    "stt" -> {
                        val text = json.optString("text", "")
                        Log.i(TAG, "[STT] \"$text\"")
                    }
                    "mcp" -> scope.launch {
                        try {
                            XiaozhiMcpResponder.handleIncomingMcp(json, protocol)
                        } catch (e: Exception) {
                            Log.e(TAG, "MCP: ${e.message}", e)
                        }
                    }
                    "iot" -> MiniRobotActionInvoker.dispatchFromXiaozhiJson(json)
                    "llm", "alert" -> {
                        val emotion = json.optString("emotion", "").trim()
                        if (emotion.isNotEmpty()) {
                            MiniRobotActionInvoker.applyLlmEmotionFromServer(emotion)
                        }
                    }
                    "tts" -> handleTts(json)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "json collect: ${e.message}", e)
        }
    }

    private fun handleTts(json: JSONObject) {
        when (json.optString("state")) {
            "start", "sentence_start" -> {
                speaking = true
                if (json.optString("state") == "start") {
                    mainHandler.post { onTtsStarted?.invoke() }
                }
                val text = json.optString("text", "")
                if (text.isNotEmpty()) Log.i(TAG, "<< $text")
            }
            "stop", "end" -> {
                ttsStopJob?.cancel()
                ttsStopJob = scope.launch {
                    Log.i(TAG, "TTS stop – chờ playback rồi listen (ChatViewModel)")
                    try {
                        withTimeout(WAIT_PLAYBACK_TIMEOUT_MS) {
                            player.waitForPlaybackCompletion()
                        }
                    } catch (_: TimeoutCancellationException) {
                        Log.w(TAG, "waitForPlaybackCompletion timeout ${WAIT_PLAYBACK_TIMEOUT_MS}ms")
                    } catch (e: Exception) {
                        Log.w(TAG, "waitForPlaybackCompletion: ${e.message}")
                    }
                    speaking = false
                    if (protocol.isAudioChannelOpened()) {
                        sendListen("sau TTS stop")
                        SafeWakeTing.playAsync(context, "sau TTS")
                        mainHandler.post { ActivationEyeDisplay.showWakeupSmileEyes() }
                        mainHandler.post { onTtsStoppedRestartMic?.invoke() }
                        mainHandler.post { onFirstGreetingMicReady?.invoke() }
                    } else {
                        Log.w(TAG, "TTS stop nhưng MQTT/UDP đóng – nói hey mini / chạm đầu")
                    }
                }
            }
        }
    }

    private suspend fun collectChannelState() {
        try {
            protocol.audioChannelStateFlow.collect { state ->
                if (state == AudioState.CLOSED) {
                    speaking = false
                    voiceArmed = false
                    Log.i(TAG, "MQTT audio CLOSED – chờ wake (không cờ sticky)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "channel state: ${e.message}", e)
        }
    }

    private suspend fun sendListen(reason: String) {
        listenMutex.withLock {
            if (!protocol.isAudioChannelOpened()) return
            try {
                protocol.sendStartListening(ListeningMode.AUTO_STOP)
                Log.i(TAG, "Listen start ($reason)")
            } catch (e: Exception) {
                Log.w(TAG, "sendListen: ${e.message}")
            }
        }
    }

    override fun isInFirstGreetingPhase(): Boolean = false

    override fun isPlaybackOrGreetingActive(): Boolean = speaking

    override fun isAcceptingServerPcm(): Boolean = protocol.isAudioChannelOpened()

    override fun setOnWebSocketSessionReady(callback: () -> Unit) {
        onSessionReady = callback
        if (protocol.isAudioChannelOpened()) {
            mainHandler.post { callback() }
        }
    }

    override fun forceStopPlaybackForHeyMini() {
        speaking = false
        ttsStopJob?.cancel()
        try {
            player.interruptPlayback()
        } catch (e: Exception) {
            Log.w(TAG, "interruptPlayback: ${e.message}")
        }
    }

    override fun onWakeOrResumeListening(forceReconnect: Boolean) {
        val now = System.currentTimeMillis()
        if (!forceReconnect && now - lastWakeMs < WAKE_DEBOUNCE_MS) {
            Log.d(TAG, "wake debounce ${now - lastWakeMs}ms")
            return
        }
        lastWakeMs = now
        scope.launch {
            wakeMutex.withLock {
                forceStopPlaybackForHeyMini()
                speaking = false
                try {
                    if (forceReconnect || !protocol.isAudioChannelOpened()) {
                        Log.i(TAG, "[Wake] openAudioChannel")
                        if (protocol.isAudioChannelOpened()) {
                            try {
                                protocol.closeAudioChannel()
                            } catch (_: Exception) {
                            }
                            delay(150)
                        }
                        val res = protocol.openAudioChannel()
                        if (!res.success) {
                            Log.e(TAG, "[Wake] openAudioChannel fail")
                            return@withLock
                        }
                        XiaozhiMcpResponder.awaitInitializeResponded()
                    }
                    protocol.sendWakeWordDetected("hey mini")
                    delay(80)
                    voiceArmed = true
                    sendListen("hey mini")
                    onSessionReady?.invoke()
                    Log.i(TAG, "[Wake] detect + listen OK")
                } catch (e: Exception) {
                    Log.e(TAG, "[Wake] ${e.message}", e)
                }
            }
        }
    }

    override fun onNewConversationTurn() {
        scope.launch {
            if (protocol.isAudioChannelOpened()) {
                sendListen("new turn")
            } else {
                onWakeOrResumeListening(forceReconnect = true)
            }
        }
    }

    override fun wasWakeHandledRecently(): Boolean =
        System.currentTimeMillis() - lastWakeMs < WAKE_DEBOUNCE_MS

    override fun sendPcmFrameFromJava(frame: ByteArray) {
        if (frame.isEmpty()) return
        if (!voiceArmed) return
        if (!protocol.isAudioChannelOpened()) return
        scope.launch {
            try {
                encodeMutex.withLock {
                    if (!voiceArmed || !protocol.isAudioChannelOpened()) return@withLock
                    val encoded = encoder.encode(frame) ?: return@withLock
                    protocol.sendAudio(encoded)
                    sendCount++
                    if (sendCount <= 3L || sendCount % 500L == 0L) {
                        Log.i(TAG, "[GỬI AUDIO] ${encoded.size} bytes frames=$sendCount")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendPcm: ${e.message}", e)
            }
        }
    }

    override fun switchDeviceIdentity(deviceId: String, clientId: String): Boolean {
        // MQTT identity thường gắn OTA config; ApplyDeviceIdentity chủ yếu dùng WS.
        Log.w(TAG, "switchDeviceIdentity không hỗ trợ trên MQTT – bỏ qua")
        return false
    }

    override fun dispose() {
        ttsStopJob?.cancel()
        try {
            player.release()
        } catch (_: Exception) {
        }
        try {
            protocol.dispose()
        } catch (_: Exception) {
        }
        scope.cancel()
    }

    private fun applyPcmGain(pcm: ByteArray, gain: Float): ByteArray {
        if (pcm.size and 1 != 0 || gain <= 1f) return pcm
        val out = ByteArray(pcm.size)
        val inBuf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        while (inBuf.hasRemaining()) {
            val s = inBuf.short.toInt()
            outBuf.putShort((s * gain).toInt().coerceIn(-32768, 32767).toShort())
        }
        return out
    }
}
