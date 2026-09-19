package com.ubtrobot.mini.speech.framework.demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ubtrobot.mini.speech.framework.WakeupAudioPlayer
import info.dourok.voicebot.OpusStreamPlayer
import info.dourok.voicebot.data.model.DeviceInfo
import info.dourok.voicebot.data.model.DummyDataGenerator
import info.dourok.voicebot.protocol.AudioState
import info.dourok.voicebot.protocol.ListeningMode
import info.dourok.voicebot.protocol.OpenChannelResult
import info.dourok.voicebot.protocol.WebsocketProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Xiaozhi voice session qua WebSocket.
 * @param audioSessionId Session dùng chung với AudioRecord (AEC).
 */
class XiaozhiWebSocketSessionManager(
    private val context: Context,
    private val deviceId: String,
    private val clientId: String,
    private val websocketUrl: String,
    private val accessToken: String,
    private val encoder: IOpusEncoder,
    private val decoder: IOpusDecoder,
    /** 1f = native. >1f = Concentus (bù PCM nhỏ). */
    private val pcmGain: Float = 1f,
    private val onTtsStoppedRestartMic: (() -> Unit)? = null,
    private val audioSessionId: Int = 0,
    /** Gọi khi TTS bắt đầu phát – dùng để tạm chặn KWS (echo loa). */
    private val onTtsStarted: (() -> Unit)? = null,
    /** Sau lần chào đầu (TTS stop + ting): mở lại KWS / bỏ chặn PCM greeting. */
    private val onFirstGreetingMicReady: (() -> Unit)? = null,
    private val usesNativeOpus: Boolean = pcmGain <= 1f
) : XiaozhiSessionApi {
    override fun getTransportLabel(): String = "WebSocket"

    companion object {
        private const val TAG = "XiaozhiWS"
        const val SAMPLE_RATE = 16000
        const val TTS_SAMPLE_RATE = 24000
        const val CHANNELS = 1
        const val FRAME_MS = 60
        const val PCM_GAIN_CONCENTUS = 2f

        @JvmStatic
        fun noteRobotSkillPcmSuppress(durationMs: Long, reason: String) {
            XiaozhiSessionManager.noteRobotSkillPcmSuppress(durationMs, reason)
        }
        /** Delay rất ngắn sau TTS stop rồi chờ phát xong – giao tiếp liên tục, mở mic sớm. */
        private const val MIC_DELAY_MS_AFTER_TTS = 60L
        /** Delay sau khi playback xong rồi send listen + ting – càng nhỏ mic mở càng nhanh. */
        private const val DELAY_AFTER_PLAYBACK_BEFORE_MIC_MS = 120L
        /** Timeout chờ phát xong (nhạc/TTS dài) – tránh kẹt vô hạn. */
        private const val WAIT_PLAYBACK_TIMEOUT_MS = 20_000L
        /** TTS/nhạc phát quá ngưỡng này (ms) thì sau stop ép đóng WS + mở mới để giao tiếp/hey mini hoạt động lại (session dài dễ treo). */
        private const val LONG_TTS_REOPEN_THRESHOLD_MS = 45_000L
        private const val RECONNECT_THROTTLE_MS = 5000L
        /** Tránh gọi full reopen WS hai lần liên tiếp (handleWakeup + framework startRecognizing). */
        private const val WAKE_SESSION_DEBOUNCE_MS = 1200L
        /** Sau TTS: chỉ gửi listen lại trên cùng WS (nói liên tục). Wake (hey mini) luôn mở WS mới. */
        private const val SESSION_REUSE_MS = 30_000L

        /** Chặn gửi PCM server khi robot đang dance/TAIJI (motor + loa → server không STT). */
        @Volatile
        private var suppressServerPcmForSkillUntilMs = 0L
        /** Sau skill MCP: TTS stop tiếp theo ép reopen WS (không chỉ listen refresh). */
        @Volatile
        private var refreshSessionAfterNextTtsStop = false
        @Volatile
        private var lastSkillPcmBlockLogMs = 0L
        /** Chỉ sau dance/skill: chặn PCM ngắn sau ting (AEC sau motor). Không dùng sau chào hey mini. */
        private const val SETTLE_AFTER_SKILL_MS = 1200L
        /** Mic restart xong rồi mới ting – user nói ngay sau ting không bị frame im lặng. */
        private const val PRE_TING_MIC_WARMUP_MS = 400L
        /** Sau ting: listen mới + chờ ngắn rồi mới PCM (mic đã restart xong). */
        private const val POST_TING_PCM_DELAY_MS = 200L
        /** Sau listen+ting: chặn PCM thêm vài giây (mic/AEC ổn sau dance). */
        @Volatile
        private var suppressServerPcmAfterListenUntilMs = 0L
        @Volatile
        private var lastPcmSettleLogMs = 0L
        @Volatile
        private var lastSmallOpusWarnMs = 0L

    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val player = OpusStreamPlayer(TTS_SAMPLE_RATE, CHANNELS, FRAME_MS, audioSessionId)

    private val deviceInfoStub: DeviceInfo =
        DummyDataGenerator.generate(deviceId, clientId)

    private val protocol =
        WebsocketProtocol(deviceInfoStub, websocketUrl, accessToken)

    /** Opus encoder không thread-safe – chỉ một luồng encode tại một thời điểm (tránh internal error + SIGABRT). */
    private val encodeMutex = Mutex()
    private val reconnectMutex = Mutex()
    /** Chỉ một luồng được gửi listen stop+start (tránh gửi trùng stop/start → server lỗi, không trả STT). */
    private val listenSendMutex = Mutex()
    @Volatile
    private var lastListenSentMs = 0L
    /** Sau send listen: server cần vài trăm ms trước khi nhận Opus. */
    private val LISTEN_COOLDOWN_MS = 300L
    @Volatile
    private var lastReconnectAttemptMs = 0L
    /** Đang reopen (hey mini / auto) – bỏ qua CLOSED giả do close có chủ ý; chặn PCM. */
    @Volatile
    private var reopenInProgress = false
    /** Lần cuối xử lý wake (full reopen hoặc reuse) – chống double onNewConversationTurn. */
    @Volatile
    private var lastWakeSessionHandledMs = 0L
    /** Lần cuối WS đóng (server 1005 / idle) – không reuse session ngay sau đó. */
    @Volatile
    private var lastAudioChannelClosedMs = 0L
    /** Server đóng session (Goodbye / 1005) – không tự reopen/ting; chỉ hey mini / chạm đầu. */
    @Volatile
    private var sessionClosedAwaitWake = false

    /** WS đang mở + đã gửi listen + hết cooldown mới gửi Opus (tránh audio trước listen → server không STT). */
    private fun ensureChannelOpen(): Boolean = protocol.isAudioChannelOpened()

    private fun isLikelySessionEndStt(text: String): Boolean {
        val t = text.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return false
        return t == "goodbye" || t == "bye" || t.contains("goodbye") || t.contains("good bye")
            || t.contains("tạm biệt") || t.contains("tam biet") || t.contains("hẹn gặp")
            || t.contains("hen gap") || t.contains("bye bye")
    }

    private fun pcmBlockReason(): String? {
        if (reopenInProgress) return "đang reopen WS"
        if (suppressServerPcmUntilFirstGreetingDone) return "chờ TTS chào + ting"
        val now = System.currentTimeMillis()
        if (now < suppressServerPcmForSkillUntilMs) return "skill/dance"
        if (now < suppressServerPcmAfterListenUntilMs) return "cooldown sau listen"
        if (!ensureChannelOpen()) return "WS chưa mở"
        if (lastListenSentMs == 0L) return "chưa gửi listen"
        val sinceListen = now - lastListenSentMs
        if (sinceListen < LISTEN_COOLDOWN_MS) return "listen cooldown ${sinceListen}ms"
        return null
    }

    private fun canSendPcmNow(): Boolean = pcmBlockReason() == null

    /** True trong lúc chào đầu sau wake – không gửi PCM lên server (AEC chưa ổn → echo STT). */
    override fun isInFirstGreetingPhase(): Boolean = suppressServerPcmUntilFirstGreetingDone

    override fun isPlaybackOrGreetingActive(): Boolean =
        suppressServerPcmUntilFirstGreetingDone || isTtsPlaying

    override fun isAcceptingServerPcm(): Boolean = canSendPcmNow()

    private fun finishFirstGreetingPhase(reason: String, forceClear: Boolean = false) {
        if (!forceClear && !suppressServerPcmUntilFirstGreetingDone) return
        suppressServerPcmUntilFirstGreetingDone = false
        heyMiniJustTriggered = false
        Log.i(TAG, "[Greeting] $reason – mở gửi PCM + hey mini KWS (sau ting)")
        mainHandler.post { onFirstGreetingMicReady?.invoke() }
    }

    /** Sau listen + ting (chỉ sau dance/skill): đợi mic/AEC ổn rồi mới gửi Opus. */
    private fun armPcmSettleAfterListen(extraMs: Long) {
        suppressServerPcmAfterListenUntilMs = System.currentTimeMillis() + LISTEN_COOLDOWN_MS + extraMs
        Log.i(TAG, "[Mic] settle ${LISTEN_COOLDOWN_MS + extraMs}ms sau ting (skill/dance)")
    }

    /**
     * Mic restart → ting → listen mới (sau mic ổn) → mở PCM.
     * Listen trước ting dễ khiến server "nghe" trong lúc mic vừa stop/start → user nói sau ting không STT.
     */
    private suspend fun playTingRestartMicAndFinish(
        wasGreeting: Boolean,
        afterRobotSkill: Boolean,
        reason: String
    ) {
        mainHandler.post { onTtsStoppedRestartMic?.invoke() }
        delay(if (wasGreeting || afterRobotSkill) PRE_TING_MIC_WARMUP_MS else 150L)
        try {
            WakeupAudioPlayer.get(context).play()
        } catch (e: Exception) {
            Log.w(TAG, "Play mic-ready sound failed", e)
        }
        finishFirstGreetingPhase(reason, forceClear = wasGreeting)
        if (!protocol.isAudioChannelOpened()) {
            Log.w(TAG, "playTingRestartMicAndFinish: WS đóng sau ting – không gửi listen/PCM")
            return
        }
        sendListenStartOnly("sau ting", ListeningMode.AUTO_STOP, forceRefresh = true)
        if (afterRobotSkill) {
            armPcmSettleAfterListen(SETTLE_AFTER_SKILL_MS)
        } else {
            suppressServerPcmAfterListenUntilMs =
                System.currentTimeMillis() + LISTEN_COOLDOWN_MS + POST_TING_PCM_DELAY_MS
            Log.i(
                TAG,
                "[STT flow] sau ting: listen mới + PCM sau ${LISTEN_COOLDOWN_MS + POST_TING_PCM_DELAY_MS}ms"
            )
        }
    }

    /**
     * Giống Xiaozhi_Android ChatViewModel: kênh mở → listen; kênh đóng → openAudioChannel().
     */
    private suspend fun reopenChannelAndListen(reason: String): Boolean {
        val isHeyMini = reason.contains("hey mini", ignoreCase = true)
        if (sessionClosedAwaitWake && !isHeyMini) {
            Log.i(TAG, "reopenChannelAndListen($reason) bỏ qua – cần hey mini / chạm đầu")
            return false
        }
        val deferMicRestart = reason.contains("sau skill", ignoreCase = true)
            || reason.contains("sau TTS", ignoreCase = true)
        return reconnectMutex.withLock {
            reopenInProgress = true
            val now = System.currentTimeMillis()
            if (protocol.isAudioChannelOpened() && lastListenSentMs != 0L
                && now - lastListenSentMs < 15_000L && reason.contains("auto reconnect")
            ) {
                reopenInProgress = false
                return@withLock true
            }
            if (reason.contains("auto reconnect") && now - lastReconnectAttemptMs < RECONNECT_THROTTLE_MS) {
                reopenInProgress = false
                return@withLock false
            }
            lastReconnectAttemptMs = now
            isTtsPlaying = false
            isTtsPlayingSinceMs = 0L
            channelIntentionallyStale = false
            sessionClosedAwaitWake = false
            try {
                if (protocol.isAudioChannelOpened()) {
                    Log.i(TAG, "reopenChannelAndListen($reason): WS đã mở – listen")
                    if (isHeyMini) {
                        protocol.sendWakeWordDetected("hey mini")
                        delay(80)
                    }
                    sendListenStartOnly(reason, ListeningMode.AUTO_STOP, forceRefresh = isHeyMini)
                    onWebSocketSessionReady?.invoke()
                    if (!isHeyMini && !deferMicRestart) {
                        mainHandler.post { onTtsStoppedRestartMic?.invoke() }
                    }
                    return@withLock true
                }
                Log.i(TAG, "reopenChannelAndListen($reason): openAudioChannel")
                val res = protocol.openAudioChannel()
                if (!res.success) {
                    Log.w(TAG, "reopenChannelAndListen($reason): openAudioChannel failed")
                    return@withLock false
                }
                XiaozhiMcpResponder.awaitInitializeResponded()
                resetSttActivityOnChannelReset()
                if (isHeyMini) {
                    protocol.sendWakeWordDetected("hey mini")
                    delay(80)
                }
                sendListenStartOnly(reason, ListeningMode.AUTO_STOP)
                onWebSocketSessionReady?.invoke()
                if (!isHeyMini && !deferMicRestart) {
                    mainHandler.post { onTtsStoppedRestartMic?.invoke() }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "reopenChannelAndListen($reason) error: " + e.message, e)
                false
            } finally {
                reopenInProgress = false
            }
        }
    }

    private suspend fun bootstrapChannelLikeReference() {
        try {
            protocol.start()
            Log.i(TAG, "[init] WebSocket → openAudioChannel (giống ChatViewModel)")
            val res = protocol.openAudioChannel()
            if (!res.success) {
                Log.e(TAG, "[init] openAudioChannel failed")
                return
            }
            XiaozhiMcpResponder.awaitInitializeResponded()
            sessionClosedAwaitWake = false
            channelIntentionallyStale = false
            sendListenStartOnly("init", ListeningMode.AUTO_STOP)
            onWebSocketSessionReady?.invoke()
            Log.i(TAG, "[init] WebSocket channel OK")
        } catch (e: Exception) {
            Log.e(TAG, "[init] bootstrap: ${e.message}", e)
        }
    }

    /** WS đóng/idle: không tự mở lại khi gửi PCM – user nói hey mini (giống Xiaozhi_Android). */
    private suspend fun tryReconnectIfNeeded() {
        // Wake thủ công → performFullWakeReconnect.
    }

    /** Chỉ gửi listen start (giống Xiaozhi_Android + esp32: không gửi listen stop khi resume – server dễ lỗi STT nếu nhận stop). */
    private suspend fun sendListenStartOnly(
        reason: String,
        mode: ListeningMode = ListeningMode.AUTO_STOP,
        forceRefresh: Boolean = false
    ): Boolean {
        return listenSendMutex.withLock {
            if (!protocol.isAudioChannelOpened()) {
                Log.w(TAG, "Listen start skipped ($reason): kênh chưa mở")
                return@withLock false
            }
            val now = System.currentTimeMillis()
            if (!forceRefresh && now - lastListenSentMs < 5_000L) {
                Log.d(TAG, "Listen start skipped ($reason): vừa gửi ${now - lastListenSentMs}ms trước")
                return@withLock true
            }
            protocol.sendStartListening(mode)
            lastListenSentMs = now
            Log.i(TAG, "Listen start: $reason (mode=${if (mode == ListeningMode.AUTO_STOP) "auto" else "realtime"}, giống Xiaozhi_Android)")
            true
        }
    }

    private fun handleClosing(heyMiniFromSession: Boolean) {
        channelIntentionallyStale = true
        noSttWatchdogJob?.cancel()
        if (heyMiniFromSession) {
            Log.i(TAG, "WebSocket closing (hey mini session) – không gửi listen stop")
        } else {
            Log.i(TAG, "WebSocket closing – không gửi listen stop (giống Xiaozhi_Android resume)")
        }
        scheduleListenWatchdogIfIdle()
    }

    private fun onWebSocketClosed() {
        channelIntentionallyStale = false
        noSttWatchdogJob?.cancel()
        scheduleListenWatchdogIfIdle()
    }

    /** WS đóng: sau 1.5s nếu vẫn im lặng thì full reconnect (hey mini). */
    private fun scheduleListenWatchdogIfIdle() {
        noSttWatchdogJob?.cancel()
        noSttWatchdogJob = scope.launch {
            delay(1500)
            if (!protocol.isAudioChannelOpened() && channelIntentionallyStale) {
                Log.i(TAG, "[Watchdog] WS đóng – không tự mở mic/WS; hey mini / chạm đầu để wake")
            }
        }
    }

    init {
        MiniRobotActionInvoker.setXiaozhiWireIdentities(deviceId, clientId)
        scope.launch {
            bootstrapChannelLikeReference()
        }
        scope.launch {
            try {
                var pcmFlow = flow {
                    protocol.incomingAudioFlow.collect { opus: ByteArray ->
                        decoder.decode(opus)?.let { emit(it) }
                    }
                }
                if (pcmGain > 1f) {
                    pcmFlow = pcmFlow.map { pcm: ByteArray -> applyPcmGain(pcm, pcmGain) }
                }
                if (!usesNativeOpus) {
                    pcmFlow = pcmFlow.map { pcm: ByteArray ->
                        PcmResampler.resample24kTo44100(pcm) ?: pcm
                    }
                }
                player.start(pcmFlow)

            } catch (e: Exception) {
                Log.e(TAG, "Init error: " + e.message, e)
            }
        }

        // JSON LISTENER – cập nhật isTtsPlaying: trong lúc TTS/nhạc không gửi PCM (tránh echo). Sau "stop" hoặc CLOSED mới reconnect + listen rồi mới mở mic (giống Xiaozhi_Android: listen sau khi session sẵn sàng).
        scope.launch {
            try {
                protocol.incomingJsonFlow.collect { json: JSONObject ->
                    val type = json.optString("type")
                    val sid = json.optString("session_id", "")
                    Log.i(TAG, "[WS→] type=$type session_id=${sid.take(8)}… – luồng STT: server gửi gì ta đều log")
                    when (type) {
                        "stt" -> {
                            lastSttReceivedMs = System.currentTimeMillis()
                            noSttListenOnlyAttempts = 0
                            val text = json.optString("text", "")
                            Log.i(TAG, "[STT] Server trả STT: text=\"$text\" – nếu không thấy khi nói = server không trả lời")
                            if (isLikelySessionEndStt(text)) {
                                sessionClosedAwaitWake = true
                                Log.i(TAG, "[Session] STT kết thúc hội thoại – sau TTS/WS đóng không tự reopen")
                            }
                            // Skill robot chỉ qua MCP tools/call — không khớp keyword STT (tránh chạy 2 lần).
                        }
                        "mcp" -> {
                            scope.launch {
                                try {
                                    XiaozhiMcpResponder.handleIncomingMcp(json, protocol)
                                } catch (e: Exception) {
                                    Log.e(TAG, "MCP responder: ${e.message}", e)
                                }
                            }
                        }
                        "iot" -> {
                            MiniRobotActionInvoker.dispatchFromXiaozhiJson(json)
                        }
                        "llm" -> {
                            val emotion = json.optString("emotion", "").trim()
                            if (emotion.isNotEmpty()) {
                                Log.i(TAG, "[LLM] emotion=\"$emotion\" → skill ROM (vừa nói vừa hành động)")
                                MiniRobotActionInvoker.applyLlmEmotionFromServer(emotion)
                            }
                        }
                        "alert" -> {
                            val emotion = json.optString("emotion", "").trim()
                            if (emotion.isNotEmpty()) {
                                MiniRobotActionInvoker.applyLlmEmotionFromServer(emotion)
                            }
                        }
                        else -> { }
                    }
                    if (type == "tts") {
                        when (json.optString("state")) {
                            "start" -> {
                                if (heyMiniJustTriggered && suppressServerPcmUntilFirstGreetingDone) {
                                    onTtsStarted?.invoke()
                                    return@collect
                                }
                                if (heyMiniJustTriggered) return@collect
                                if (lastTtsStopClearedAt != 0L) lastTtsStopClearedAt = 0L
                                if (protocol.isAudioChannelOpened()) {
                                    isTtsPlaying = true
                                    isTtsPlayingSinceMs = System.currentTimeMillis()
                                    onTtsStarted?.invoke()
                                }
                            }
                            "sentence_start" -> {
                                if (heyMiniJustTriggered && suppressServerPcmUntilFirstGreetingDone) return@collect
                                if (heyMiniJustTriggered) return@collect
                                if (lastTtsStopClearedAt != 0L && System.currentTimeMillis() - lastTtsStopClearedAt < TTS_STOP_COOLDOWN_MS) {
                                    Log.d(TAG, "Bỏ qua sentence_start trễ (${System.currentTimeMillis() - lastTtsStopClearedAt}ms < ${TTS_STOP_COOLDOWN_MS}ms) – event cũ")
                                    return@collect
                                }
                                if (lastTtsStopClearedAt != 0L) lastTtsStopClearedAt = 0L
                                if (protocol.isAudioChannelOpened()) {
                                    isTtsPlaying = true
                                    isTtsPlayingSinceMs = System.currentTimeMillis()
                                }
                            }
                            "end", "stop" -> {
                                val ttsDurationMs = if (isTtsPlayingSinceMs != 0L) System.currentTimeMillis() - isTtsPlayingSinceMs else 0L
                                val longTts = ttsDurationMs >= LONG_TTS_REOPEN_THRESHOLD_MS
                                val afterRobotSkill = refreshSessionAfterNextTtsStop
                                if (longTts) {
                                    Log.i(TAG, "TTS state=stop received – TTS/nhạc dài (${ttsDurationMs}ms >= ${LONG_TTS_REOPEN_THRESHOLD_MS}ms) → sau playback sẽ force đóng WS + mở mới để giao tiếp/hey mini hoạt động lại")
                                } else if (afterRobotSkill) {
                                    Log.i(TAG, "TTS state=stop sau skill robot (dance/taiji…) → sau playback reopen WS (session server dễ kẹt)")
                                } else {
                                    Log.i(TAG, "TTS state=${json.optString("state")} received – chờ playback xong rồi send listen cùng session (1 WebSocket)")
                                }
                                val recoveryGen = ++ttsRecoveryGeneration
                                scope.launch ttsRecovery@{
                                    delay(MIC_DELAY_MS_AFTER_TTS)
                                    try {
                                        withTimeout(WAIT_PLAYBACK_TIMEOUT_MS) {
                                            player.waitForPlaybackCompletion()
                                        }
                                    } catch (e: TimeoutCancellationException) {
                                        Log.w(TAG, "waitForPlaybackCompletion timeout (${WAIT_PLAYBACK_TIMEOUT_MS}ms) – vẫn mở lại để giao tiếp")
                                    } catch (e: Exception) {
                                        Log.w(TAG, "waitForPlaybackCompletion", e)
                                    }
                                    delay(DELAY_AFTER_PLAYBACK_BEFORE_MIC_MS)
                                    if (recoveryGen != ttsRecoveryGeneration) {
                                        Log.i(TAG, "TTS recovery bị hủy (WS đóng giữa chừng) – không gửi listen; nói hey mini hoặc chạm đầu")
                                        isTtsPlaying = false
                                        isTtsPlayingSinceMs = 0L
                                        finishFirstGreetingPhase("TTS recovery hủy")
                                        mainHandler.post { onTtsStoppedRestartMic?.invoke() }
                                        return@ttsRecovery
                                    }
                                    val wasGreeting = suppressServerPcmUntilFirstGreetingDone
                                    if (longTts || afterRobotSkill) {
                                        refreshSessionAfterNextTtsStop = false
                                        suppressServerPcmForSkillUntilMs = 0L
                                        val reason = if (longTts) "sau TTS dài" else "sau skill robot"
                                        val ok = reopenChannelAndListen(reason)
                                        isTtsPlaying = false
                                        isTtsPlayingSinceMs = 0L
                                        droppedFrameCount = 0L
                                        lastTtsStopClearedAt = System.currentTimeMillis()
                                        if (ok) {
                                            playTingRestartMicAndFinish(wasGreeting, afterRobotSkill, "$reason + reopen")
                                        } else {
                                            Log.w(TAG, "reopen $reason không success – nói hey mini để mở lại")
                                            finishFirstGreetingPhase("$reason reopen fail")
                                            mainHandler.post { onTtsStoppedRestartMic?.invoke() }
                                        }
                                    } else if (protocol.isAudioChannelOpened()) {
                                        isTtsPlaying = false
                                        isTtsPlayingSinceMs = 0L
                                        droppedFrameCount = 0L
                                        lastTtsStopClearedAt = System.currentTimeMillis()
                                        Log.i(TAG, "[STT flow] Sau TTS: listen → restart mic → ting (lần nói đầu sau ting)")
                                        playTingRestartMicAndFinish(wasGreeting, afterRobotSkill = false, "sau TTS stop + listen")
                                    } else {
                                        sessionClosedAwaitWake = true
                                        channelIntentionallyStale = true
                                        isTtsPlaying = false
                                        isTtsPlayingSinceMs = 0L
                                        lastTtsStopClearedAt = System.currentTimeMillis()
                                        lastListenSentMs = 0L
                                        sendFrameCount = 0L
                                        Log.i(
                                            TAG,
                                            "WebSocket đã đóng – không tự reopen/ting/listen; hey mini hoặc chạm đầu"
                                        )
                                        finishFirstGreetingPhase("WS đóng chờ wake", forceClear = true)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "JSON channel error: " + e.message, e)
            }
        }

        // WebSocket đóng → không auto reconnect; user nói hey mini để mở lại (giống Xiaozhi_Android)
        scope.launch {
            try {
                protocol.audioChannelStateFlow.collect { state ->
                    if (state == AudioState.CLOSED) {
                        lastAudioChannelClosedMs = System.currentTimeMillis()
                        channelIntentionallyStale = true
                        isTtsPlaying = false
                        isTtsPlayingSinceMs = 0L
                        lastTtsStopClearedAt = 0L
                        ttsRecoveryGeneration++
                        if (!reopenInProgress) {
                            sessionClosedAwaitWake = true
                            suppressServerPcmUntilFirstGreetingDone = false
                            heyMiniJustTriggered = false
                            lastListenSentMs = 0L
                            sendFrameCount = 0L
                            Log.i(TAG, "WebSocket closed – chờ hey mini / chạm đầu (không tự mở lại)")
                            scheduleListenWatchdogIfIdle()
                        } else {
                            Log.d(TAG, "WebSocket closed (reopen) – giữ suppress/greeting + heyMini flags")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "audioChannelStateFlow error: " + e.message, e)
            }
        }
    }

    private fun applyPcmGain(pcm: ByteArray, gain: Float): ByteArray {
        if (pcm.size and 1 != 0 || gain <= 1f) return pcm
        val out = ByteArray(pcm.size)
        val inBuf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        while (inBuf.hasRemaining()) {
            val s = inBuf.short.toInt()
            val v = (s * gain).toInt().coerceIn(-32768, 32767)
            outBuf.putShort(v.toShort())
        }
        return out
    }

    private var sendFrameCount = 0L
    /** Thời điểm nhận STT gần nhất (để cảnh báo khi gửi lâu không thấy STT). */
    @Volatile
    private var lastSttReceivedMs = 0L
    @Volatile
    private var lastNoSttWarnMs = 0L
    /** Chỉ refresh listen khi không có STT lâu (tránh spam server 30s/lần). */
    private val NO_STT_WARN_INTERVAL_MS = 30_000L
    /** Sau ngưỡng này không reuse WS / chỉ listen – ép full reconnect (session server treo sau dance/TTS dài). */
    private val NO_STT_FORCE_RECONNECT_MS = 45_000L
    /** Số lần đã thử chỉ gửi listen mà vẫn không STT → lần sau đóng+mở WS. */
    @Volatile
    private var noSttListenOnlyAttempts = 0
    /** Chỉ log "Bỏ qua refresh listen" tối đa 1 lần / 10s (tránh spam log mỗi frame). */
    @Volatile
    private var lastSkipRefreshLogMs = 0L
    private val SKIP_REFRESH_LOG_INTERVAL_MS = 10_000L
    /** Số frame PCM nhận từ mic nhưng bỏ qua vì isTtsPlaying (để log). */
    @Volatile
    private var droppedFrameCount = 0L
    /** Thời điểm log gần nhất khi drop do isTtsPlaying (tránh spam log). */
    @Volatile
    private var lastDropLogMs = 0L
    @Volatile
    private var lastChannelClosedLogMs = 0L
    @Volatile
    private var lastGreetingPcmBlockLogMs = 0L
    @Volatile
    private var lastPcmBlockedLogMs = 0L
    private val DROP_LOG_INTERVAL_MS = 2000L
    @Volatile
    private var isTtsPlaying = false
    /** Thời điểm set isTtsPlaying = true; dùng để timeout tránh chặn mic vĩnh viễn khi nhạc dài / thiếu event stop. */
    @Volatile
    private var isTtsPlayingSinceMs = 0L
    /** Nếu isTtsPlaying quá này ms thì ép false để mic hoạt động lại (phát nhạc dài rồi hey mini vẫn nhận). */
    private val IS_TTS_PLAYING_MAX_MS = 120_000L
    /** Server đóng WS có chủ ý (idle) – không tự reopen từ sendPcm. */
    @Volatile
    private var channelIntentionallyStale = false
    /** Sau wake: không gửi PCM đến server cho đến TTS chào xong. */
    @Volatile
    private var suppressServerPcmUntilFirstGreetingDone = false
    /** Callback khi MCP init xong và kênh WS sẵn sàng (sau hey mini reopen). */
    @Volatile
    private var onWebSocketSessionReady: (() -> Unit)? = null
    private var noSttWatchdogJob: Job? = null
    /** Bất chấp isTtsPlaying: khi user nói hey mini thì luôn mở session mới và gửi PCM; TTS event trễ không được set isTtsPlaying cho đến khi xong. */
    @Volatile
    private var heyMiniJustTriggered = false
    /** Sau khi clear isTtsPlaying: chỉ bỏ qua "sentence_start" trễ trong ~450ms (event cũ). Sau đó mọi sentence_start = TTS mới → set isTtsPlaying để chặn PCM, tránh echo. */
    @Volatile
    private var lastTtsStopClearedAt = 0L
    private val TTS_STOP_COOLDOWN_MS = 450L
    /** Hủy coroutine TTS→listen cũ khi WS đóng giữa chừng (server goodbye 1005). */
    @Volatile
    private var ttsRecoveryGeneration = 0

    override fun setOnWebSocketSessionReady(callback: () -> Unit) {
        onWebSocketSessionReady = callback
    }

    /** Session mới / reconnect: không tính im lặng từ lúc mở app. */
    private fun resetSttActivityOnChannelReset() {
        lastSttReceivedMs = System.currentTimeMillis()
        lastNoSttWarnMs = 0L
        noSttListenOnlyAttempts = 0
    }

    /**
     * Gọi ngay khi phát hiện "hey mini" – dừng loa lập tức (ưu tiên hàng đầu), set flags để TTS event trễ không chặn.
     * Gọi từ main thread khi wake word detected, trước khi publish / startRecognitionAfterWakeup.
     */
    override fun forceStopPlaybackForHeyMini() {
        isTtsPlaying = false
        isTtsPlayingSinceMs = 0L
        heyMiniJustTriggered = true
        Log.i(TAG, "[WakeWord] forceStopPlaybackForHeyMini – interruptPlayback")
        try {
            player.interruptPlayback()
        } catch (e: Exception) {
            Log.w(TAG, "forceStopPlaybackForHeyMini interruptPlayback", e)
        }
        Log.i(TAG, "[WakeWord] Hey mini ưu tiên: đã dừng phát ngay")
    }

    /**
     * Hey mini / chạm đầu: luôn đóng WS hiện tại (nếu có) và mở WS mới – giống ESP32/Android nút wake.
     * Nói liên tục sau TTS: cùng WS, chỉ send listen (không gọi hàm này).
     * WS tự đóng (idle): lần hey mini tiếp theo cũng vào đây → WS mới.
     */
    @JvmOverloads
    override fun onWakeOrResumeListening(forceReconnect: Boolean) {
        if (forceReconnect) {
            lastWakeSessionHandledMs = 0L
        }
        val now = System.currentTimeMillis()
        if (!forceReconnect && now - lastWakeSessionHandledMs < WAKE_SESSION_DEBOUNCE_MS) {
            if (!protocol.isAudioChannelOpened()) {
                Log.i(TAG, "[WakeWord] debounced nhưng kênh đóng – vẫn performFullWakeReconnect")
                performFullWakeReconnect()
            } else {
                Log.i(TAG, "[WakeWord] debounced (${now - lastWakeSessionHandledMs}ms) – bỏ wake trùng (handleWakeup + startRecognizing)")
                mainHandler.post { onTtsStoppedRestartMic?.invoke() }
            }
            return
        }
        lastWakeSessionHandledMs = now
        if (protocol.isAudioChannelOpened() && !forceReconnect) {
            Log.i(TAG, "[WakeWord] WS đã mở – listen (ChatViewModel)")
            scope.launch {
                sessionClosedAwaitWake = false
                suppressServerPcmUntilFirstGreetingDone = true
                heyMiniJustTriggered = true
                try {
                    player.interruptPlayback()
                } catch (e: Exception) {
                    Log.w(TAG, "player.interruptPlayback", e)
                }
                protocol.sendWakeWordDetected("hey mini")
                delay(80)
                sendListenStartOnly("hey mini", ListeningMode.AUTO_STOP, forceRefresh = true)
                mainHandler.post { onTtsStoppedRestartMic?.invoke() }
            }
            return
        }
        performFullWakeReconnect()
    }

    override fun onNewConversationTurn() {
        scope.launch {
            if (protocol.isAudioChannelOpened()) {
                sendListenStartOnly("new turn", ListeningMode.AUTO_STOP, forceRefresh = true)
            } else {
                reopenChannelAndListen("new turn")
            }
        }
    }

    private fun performFullWakeReconnect() {
        Log.i(TAG, "[WakeWord] performFullWakeReconnect – open/listen nếu WS đóng")
        scope.launch {
            try {
                sessionClosedAwaitWake = false
                channelIntentionallyStale = false
                isTtsPlaying = false
                isTtsPlayingSinceMs = 0L
                lastTtsStopClearedAt = 0L
                suppressServerPcmUntilFirstGreetingDone = true
                heyMiniJustTriggered = true
                Log.i(TAG, "[Greeting] chặn PCM lên server đến khi TTS chào xong (tránh echo lần đầu)")
                try {
                    player.interruptPlayback()
                } catch (e: Exception) {
                    Log.w(TAG, "player.interruptPlayback", e)
                }
                val ok = reopenChannelAndListen("hey mini")
                if (!ok) {
                    Log.e(TAG, "Hey mini reopenChannelAndListen failed")
                    heyMiniJustTriggered = false
                    suppressServerPcmUntilFirstGreetingDone = false
                    return@launch
                }
                Log.i(TAG, "[WakeWord] Hey mini: WS + listen OK – chờ TTS chào xong mới gửi PCM server (mic local/KWS vẫn chạy)")
            } catch (e: Exception) {
                Log.e(TAG, "performFullWakeReconnect error: ${e.message}", e)
                heyMiniJustTriggered = false
                suppressServerPcmUntilFirstGreetingDone = false
            }
        }
    }

    /** True nếu wake vừa xử lý (framework startRecognizing không gọi onWakeOrResumeListening lần 2). */
    override fun wasWakeHandledRecently(): Boolean {
        val now = System.currentTimeMillis()
        return now - lastWakeSessionHandledMs < WAKE_SESSION_DEBOUNCE_MS
    }

    override fun sendPcmFrameFromJava(frame: ByteArray) {
        if (frame.isEmpty()) return
        // Giống Xiaozhi_Android: LUÔN gửi audio lên server (không chặn khi TTS). Server nhận stream liên tục → sau TTS dài vẫn STT bình thường. Echo nhờ AEC (cùng audioSessionId với TTS).
        // (Trước đây chặn khi isTtsPlaying → server nhận 0 byte vài phút → dễ idle/không trả STT khi gửi lại.)

        scope.launch {
            try {
                if (!canSendPcmNow()) {
                    val now = System.currentTimeMillis()
                    if (!protocol.isAudioChannelOpened()) {
                        if (now - lastChannelClosedLogMs >= DROP_LOG_INTERVAL_MS) {
                            lastChannelClosedLogMs = now
                            Log.w(TAG, "sendPcmFrameFromJava: kênh chưa mở – nói hey mini / chạm đầu")
                        }
                        scheduleListenWatchdogIfIdle()
                    } else if (now - lastPcmBlockedLogMs >= DROP_LOG_INTERVAL_MS) {
                        lastPcmBlockedLogMs = now
                        Log.w(TAG, "sendPcmFrameFromJava: WS mở nhưng chặn PCM – ${pcmBlockReason()}")
                    }
                    return@launch
                }
                val now = System.currentTimeMillis()

                encodeMutex.withLock {
                    if (!canSendPcmNow()) return@withLock
                    val encoded = encoder.encode(frame)
                    if (encoded != null) {
                        if (!canSendPcmNow()) return@withLock
                        protocol.sendAudio(encoded)
                        sendFrameCount++
                        val now = System.currentTimeMillis()
                        if (encoded.size < 200 && sendFrameCount > 20L
                            && now - lastSmallOpusWarnMs >= 8000L
                        ) {
                            lastSmallOpusWarnMs = now
                            Log.w(
                                TAG,
                                "[Mic] Opus chỉ ${encoded.size} bytes (thường ~450+) – có thể im lặng/AEC; " +
                                    "sau dance cần restart mic"
                            )
                        }
                        if (sendFrameCount <= 3 || sendFrameCount % 500 == 0L) {
                            Log.i(TAG, "[GỬI AUDIO] encoded=${encoded.size} bytes, total frames=$sendFrameCount → nếu thấy log WS gửi thật = đã gửi lên server, không có [STT] = server không trả")
                        }
                        if (lastSttReceivedMs != 0L && now - lastSttReceivedMs > NO_STT_WARN_INTERVAL_MS && now - lastNoSttWarnMs > NO_STT_WARN_INTERVAL_MS) {
                            lastNoSttWarnMs = now
                            if (lastListenSentMs != 0L && now - lastListenSentMs < 10_000L) {
                                if (now - lastSkipRefreshLogMs >= SKIP_REFRESH_LOG_INTERVAL_MS) {
                                    lastSkipRefreshLogMs = now
                                    Log.d(TAG, "Bỏ qua refresh listen (vừa gửi listen ${now - lastListenSentMs}ms trước)")
                                }
                            } else if (now - lastSttReceivedMs > NO_STT_FORCE_RECONNECT_MS && !sessionClosedAwaitWake) {
                                val staleSec = (now - lastSttReceivedMs) / 1000
                                if (noSttListenOnlyAttempts >= 1) {
                                    Log.w(TAG, "Đã gửi PCM ${staleSec}s không STT – reopen WS (listen refresh không đủ)")
                                    noSttListenOnlyAttempts = 0
                                    reopenChannelAndListen("không STT ${staleSec}s")
                                } else {
                                    Log.w(TAG, "Đã gửi PCM ${staleSec}s không STT – thử listen refresh trước")
                                    noSttListenOnlyAttempts++
                                    sendListenStartOnly("refresh không STT", ListeningMode.AUTO_STOP)
                                }
                            }
                        }
                    } else {
                        if (sendFrameCount % 200 == 0L) Log.w(TAG, "encode(frame) trả null – không gửi được frame này")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "send error: " + e.message, e)
            }
        }
    }

    override fun isAudioChannelOpened(): Boolean = protocol.isAudioChannelOpened()

    override fun recoverTalkAfterShowConfig() {
        scope.launch {
            try {
                for (attempt in 1..4) {
                    delay(if (attempt == 1) 1_200L else 2_000L)
                    if (protocol.isAudioChannelOpened()) return@launch
                    sessionClosedAwaitWake = false
                    channelIntentionallyStale = false
                    if (reopenChannelAndListen("sau show_config")) return@launch
                }
            } catch (e: Exception) {
                Log.e(TAG, "recoverTalkAfterShowConfig: ${e.message}", e)
            }
        }
    }

    /** Chỉ khi Self-Control lưu đổi MAC — không đụng luồng nói thường. */
    override fun switchDeviceIdentity(deviceId: String, clientId: String): Boolean {
        return try {
            runBlocking {
                forceStopPlaybackForHeyMini()
                isTtsPlaying = false
                isTtsPlayingSinceMs = 0L
                ttsRecoveryGeneration++
                try { protocol.closeAudioChannel() } catch (_: Exception) {}
                protocol.updateIdentity(deviceId, clientId)
                MiniRobotActionInvoker.setXiaozhiWireIdentities(deviceId, clientId)
                XiaozhiMcpResponder.resetInitializeHandshake()
                delay(200)
                sessionClosedAwaitWake = false
                channelIntentionallyStale = false
                suppressServerPcmUntilFirstGreetingDone = true
                heyMiniJustTriggered = true
                val ok = reopenChannelAndListen("hey mini ApplyDeviceIdentity")
                if (!ok) {
                    suppressServerPcmUntilFirstGreetingDone = false
                    heyMiniJustTriggered = false
                }
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "switchDeviceIdentity: ${e.message}", e)
            false
        }
    }

    override fun dispose() {

        try { player.shutdown() } catch (e: Exception) {}
        try { encoder.release() } catch (e: Exception) {}
        try { decoder.release() } catch (e: Exception) {}
        try { protocol.dispose() } catch (e: Exception) {}

        scope.cancel()
    }
}
