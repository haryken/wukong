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
import info.dourok.voicebot.protocol.MqttProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.util.Locale

/**
 * Xiaozhi voice session qua MQTT + UDP (file riêng — không đụng [XiaozhiWebSocketSessionManager]).
 * Cơ chế STT/TTS/MCP/LLM emotion giống WebSocket, chỉ khác transport.
 * @param audioSessionId Session dùng chung với AudioRecord (AEC).
 */
class XiaozhiMqttSessionManager(
    private val context: Context,
    private val deviceId: String,
    private val clientId: String,
    private val mqttConfig: XiaozhiMqttConfig,
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
    override fun getTransportLabel(): String = "MQTT + UDP"

    companion object {
        private const val TAG = "XiaozhiMQTT"
        const val SAMPLE_RATE = 16000
        const val TTS_SAMPLE_RATE = 24000
        const val CHANNELS = 1
        const val FRAME_MS = 60
        /** 60ms mono 16-bit @ 24kHz — khớp ESP32 audio_service frame_loss (decoder_frame_size_ zeros). */
        private val TTS_FRAME_LOSS_SILENCE_PCM = ByteArray(TTS_SAMPLE_RATE * FRAME_MS / 1000 * 2)
        const val PCM_GAIN_CONCENTUS = 2f

        @JvmStatic
        fun noteRobotSkillPcmSuppress(durationMs: Long, reason: String) {
            XiaozhiSessionManager.noteRobotSkillPcmSuppress(durationMs, reason)
        }
        /** Delay rất ngắn sau TTS stop rồi chờ phát xong – giao tiếp liên tục, mở mic sớm. */
        private const val MIC_DELAY_MS_AFTER_TTS = 60L
        /** Delay ngắn sau playback xong rồi ting (echo loa: [TING_ECHO_GUARD_MS] sau ting). */
        private const val DELAY_AFTER_PLAYBACK_BEFORE_MIC_MS = 80L
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
        /** Chỉ sau dance/skill: chặn PCM ngắn sau ting (AEC sau motor). */
        private const val SETTLE_AFTER_SKILL_MS = 600L
        /** Mic restart (onTtsStoppedRestartMic) rồi ting – chỉ chờ tối thiểu. */
        private const val PRE_TING_MIC_WARMUP_MS = 80L
        /** Chỉ chặn uplink trong lúc ting vang (~camera_click) – xong là nói được. */
        private const val TING_ECHO_GUARD_MS = 280L
        /** Chỉ khi sau mở UDP mà không có TTS start – tránh cắt giữa câu chào (trước 6s quá ngắn). */
        private const val GREETING_NO_TTS_TIMEOUT_MS = 15_000L
        /** Gửi Opus im lặng khi chặn mic thật – server không timeout goodbye vì 0 uplink. */
        private const val UPLINK_KEEPALIVE_INTERVAL_MS = 400L
        /** 60ms @ 16kHz mono 16-bit – khớp DemoRecognizer frame. */
        private val UPLINK_SILENCE_PCM = ByteArray(1920)
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

    private val protocol = MqttProtocol(context, mqttConfig)

    @Volatile
    private var lastUplinkKeepaliveMs = 0L
    @Volatile
    private var pendingReconnectAfterTts = false
    private var midTtsDisconnectJob: Job? = null

    /** Opus encoder không thread-safe – chỉ một luồng encode tại một thời điểm (tránh internal error + SIGABRT). */
    private val encodeMutex = Mutex()
    private val reconnectMutex = Mutex()
    /** Chỉ một luồng được gửi listen stop+start (tránh gửi trùng stop/start → server lỗi, không trả STT). */
    private val listenSendMutex = Mutex()
    @Volatile
    private var lastListenSentMs = 0L
    /**
     * Cooldown sau listen khi vẫn đang TTS/chưa qua ting.
     * Sau ting: [pcmAllowedRightAfterTingUntilMs] bỏ qua cooldown này.
     */
    private val LISTEN_COOLDOWN_MS = 400L
    /** Sau playTingRestartMicAndFinish – cho phép uplink ngay (chỉ còn [TING_ECHO_GUARD_MS]). */
    @Volatile
    private var pcmAllowedRightAfterTingUntilMs = 0L
    @Volatile
    private var lastReconnectAttemptMs = 0L
    /** Đang reopen (hey mini / auto) – bỏ qua CLOSED giả do close có chủ ý; chặn PCM. */
    @Volatile
    private var reopenInProgress = false
    private var wakeReconnectJob: Job? = null
    private var channelBootstrapJob: Job? = null
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
        if (isTtsPlaying) {
            if (isTtsPlayingSinceMs != 0L && now - isTtsPlayingSinceMs > IS_TTS_PLAYING_MAX_MS) {
                Log.w(TAG, "isTtsPlaying timeout ${now - isTtsPlayingSinceMs}ms – ép mở uplink PCM")
                isTtsPlaying = false
                isTtsPlayingSinceMs = 0L
            } else {
                return "TTS đang phát"
            }
        }
        if (now < suppressServerPcmForSkillUntilMs) return "skill/dance"
        if (now < suppressServerPcmAfterListenUntilMs) return "cooldown sau listen"
        if (!ensureChannelOpen()) return "WS chưa mở"
        if (lastListenSentMs == 0L) return "chưa gửi listen"
        val sinceListen = now - lastListenSentMs
        if (sinceListen < LISTEN_COOLDOWN_MS && now > pcmAllowedRightAfterTingUntilMs) {
            return "listen cooldown ${sinceListen}ms"
        }
        return null
    }

    private fun canSendPcmNow(): Boolean = pcmBlockReason() == null

    /** True trong lúc chào đầu sau wake – không gửi PCM lên server (AEC chưa ổn → echo STT). */
    override fun isInFirstGreetingPhase(): Boolean = suppressServerPcmUntilFirstGreetingDone

    override fun isPlaybackOrGreetingActive(): Boolean =
        suppressServerPcmUntilFirstGreetingDone || isTtsPlaying

    override fun isAcceptingServerPcm(): Boolean = canSendPcmNow()

    private fun preparePlayerForGreetingTts() {
        try {
            player.prepareForIncomingTts()
            Log.i(TAG, "[Greeting] prepareForIncomingTts – sẵn sàng nhận Opus chào")
        } catch (e: Exception) {
            Log.w(TAG, "prepareForIncomingTts", e)
        }
    }

    private var greetingTimeoutJob: Job? = null

    @Volatile
    private var greetingTtsStarted = false

    private fun cancelGreetingTimeout() {
        greetingTimeoutJob?.cancel()
        greetingTimeoutJob = null
    }

    private fun markGreetingTtsStarted() {
        greetingTtsStarted = true
        cancelGreetingTimeout()
    }

    /** Nếu server không gửi TTS sau wake – tránh kẹt im lặng (không cắt khi TTS đã start). */
    /** Server hay goodbye nếu listen mà không nhận uplink – gửi Opus im lặng thay mic thật. */
    private fun maybeSendUplinkKeepalive() {
        val reason = pcmBlockReason() ?: return
        if (reason != "TTS đang phát" && reason != "chờ TTS chào + ting") return
        if (!protocol.isAudioChannelOpened() || lastListenSentMs == 0L) return
        val now = System.currentTimeMillis()
        if (now - lastUplinkKeepaliveMs < UPLINK_KEEPALIVE_INTERVAL_MS) return
        lastUplinkKeepaliveMs = now
        scope.launch {
            try {
                encodeMutex.withLock {
                    if (!protocol.isAudioChannelOpened()) return@withLock
                    val encoded = encoder.encode(UPLINK_SILENCE_PCM) ?: return@withLock
                    protocol.sendAudio(encoded)
                }
            } catch (e: Exception) {
                Log.w(TAG, "uplink keepalive", e)
            }
        }
    }

    /** UDP/goodbye giữa TTS: phát hết buffer rồi reopen – không bắt user wake lại. */
    private fun scheduleMidTtsDisconnectRecovery(trigger: String) {
        if (midTtsDisconnectJob?.isActive == true) return
        midTtsDisconnectJob = scope.launch {
            Log.w(TAG, "[Session] $trigger – chờ buffer TTS xong rồi reopen")
            ttsRecoveryGeneration++
            try {
                withTimeout(WAIT_PLAYBACK_TIMEOUT_MS) {
                    player.waitForPlaybackCompletion()
                }
            } catch (e: Exception) {
                Log.w(TAG, "mid-TTS playback wait", e)
            }
            pendingReconnectAfterTts = false
            isTtsPlaying = false
            isTtsPlayingSinceMs = 0L
            val wasGreeting = suppressServerPcmUntilFirstGreetingDone
            if (protocol.isAudioChannelOpened()) {
                try {
                    protocol.closeAudioChannel()
                } catch (_: Exception) {
                }
                delay(200)
            }
            val ok = reopenChannelAndListen("sau ngắt giữa TTS")
            if (ok) {
                sessionClosedAwaitWake = false
                channelIntentionallyStale = false
                playTingRestartMicAndFinish(wasGreeting, afterRobotSkill = false, trigger)
            } else {
                sessionClosedAwaitWake = true
                Log.w(TAG, "reopen sau ngắt giữa TTS fail – hey mini")
            }
        }
    }

    private fun scheduleGreetingTimeout() {
        cancelGreetingTimeout()
        greetingTtsStarted = false
        greetingTimeoutJob = scope.launch {
            delay(GREETING_NO_TTS_TIMEOUT_MS)
            if (!suppressServerPcmUntilFirstGreetingDone) return@launch
            if (greetingTtsStarted || isTtsPlaying) {
                Log.d(TAG, "[Greeting] timeout bỏ qua – TTS chào đang/đã bắt đầu")
                return@launch
            }
            if (!protocol.isAudioChannelOpened()) return@launch
            Log.w(TAG, "[Greeting] ${GREETING_NO_TTS_TIMEOUT_MS}ms không có TTS – mở PCM (fallback)")
            finishFirstGreetingPhase("MQTT greeting timeout", forceClear = true)
            sendListenStartOnly("greeting timeout", ListeningMode.AUTO_STOP, forceRefresh = true)
            armPcmUnblockAfterTing()
            mainHandler.post { onTtsStoppedRestartMic?.invoke() }
        }
    }

    private fun finishFirstGreetingPhase(reason: String, forceClear: Boolean = false) {
        if (!forceClear && !suppressServerPcmUntilFirstGreetingDone) return
        suppressServerPcmUntilFirstGreetingDone = false
        heyMiniJustTriggered = false
        cancelGreetingTimeout()
        Log.i(TAG, "[Greeting] $reason – mở gửi PCM + hey mini KWS (sau ting)")
        mainHandler.post { onFirstGreetingMicReady?.invoke() }
    }

    /** Sau ting: chỉ guard echo ting ngắn, bỏ listen cooldown 1.5s+ (user nói ngay). */
    private fun armPcmUnblockAfterTing(extraSettleMs: Long = 0L) {
        val guardMs = TING_ECHO_GUARD_MS + extraSettleMs
        suppressServerPcmAfterListenUntilMs = System.currentTimeMillis() + guardMs
        pcmAllowedRightAfterTingUntilMs = System.currentTimeMillis() + 60_000L
        Log.i(TAG, "[Mic] sau ting: uplink PCM sau ${guardMs}ms (ting xong là nói)")
    }

    /** Sau dance/skill: thêm settle AEC motor (vẫn ngắn hơn trước). */
    private fun armPcmSettleAfterListen(extraMs: Long) {
        armPcmUnblockAfterTing(extraMs)
    }

    /**
     * Mic restart → ting → listen → uplink gần như ngay (chỉ [TING_ECHO_GUARD_MS] chặn echo ting).
     */
    private suspend fun playTingRestartMicAndFinish(
        wasGreeting: Boolean,
        afterRobotSkill: Boolean,
        reason: String
    ) {
        isTtsPlaying = true
        isTtsPlayingSinceMs = System.currentTimeMillis()
        Log.i(TAG, "[TTS] ting ngay ($reason)")
        try {
            WakeupAudioPlayer.get(context).play()
        } catch (e: Exception) {
            Log.w(TAG, "Play mic-ready sound failed", e)
        }
        mainHandler.post { onTtsStoppedRestartMic?.invoke() }
        delay(if (wasGreeting || afterRobotSkill) PRE_TING_MIC_WARMUP_MS else 0L)
        finishFirstGreetingPhase(reason, forceClear = wasGreeting)
        if (!protocol.isAudioChannelOpened()) {
            Log.w(TAG, "playTingRestartMicAndFinish: WS đóng sau ting – không gửi listen/PCM")
            return
        }
        sendListenStartOnly(
            if (wasGreeting) "sau chào hey mini" else "sau ting",
            ListeningMode.AUTO_STOP,
            forceRefresh = true,
        )
        if (afterRobotSkill) {
            armPcmSettleAfterListen(SETTLE_AFTER_SKILL_MS)
        } else {
            armPcmUnblockAfterTing()
        }
        isTtsPlaying = false
        isTtsPlayingSinceMs = 0L
        droppedFrameCount = 0L
    }

    /**
     * Một luồng MQTT+UDP mới: teardown broker cũ → connect → MCP → hello → UDP → listen.
     * Dùng cho hey mini và mọi lần cần mở lại khi kênh đã đóng.
     */
    private suspend fun openFreshMqttSessionAndListen(reason: String): Boolean {
        val isHeyMini = reason.contains("hey mini", ignoreCase = true)
        val deferMicRestart = reason.contains("sau skill", ignoreCase = true)
            || reason.contains("sau TTS", ignoreCase = true)
        return reconnectMutex.withLock {
            reopenInProgress = true
            isTtsPlaying = false
            isTtsPlayingSinceMs = 0L
            channelIntentionallyStale = false
            sessionClosedAwaitWake = false
            try {
                val mqtt = protocol as MqttProtocol
                Log.i(TAG, "openFreshMqttSession($reason): đóng UDP → connect (teardown nếu broker chết) → MCP → hello → UDP")
                protocol.closeAudioChannel()
                if (!mqtt.isBrokerConnected()) {
                    mqtt.teardownForNewWake()
                }
                mqtt.prepareBootstrapHandshake()
                if (!mqtt.ensureBrokerConnected()) {
                    Log.e(TAG, "openFreshMqttSession: MQTT connect/subscribe fail")
                    return@withLock false
                }
                XiaozhiMcpResponder.awaitInitializeResponded(8_000)
                val res = protocol.openAudioChannel()
                if (!res.success) {
                    Log.e(TAG, "openFreshMqttSession: openAudioChannel fail")
                    return@withLock false
                }
                resetSttActivityOnChannelReset()
                onWebSocketSessionReady?.invoke()
                if (isHeyMini) {
                    preparePlayerForGreetingTts()
                    protocol.sendWakeWordDetected("hey mini")
                    delay(80)
                    // Server thường chỉ đẩy Opus TTS qua UDP sau listen – uplink PCM vẫn chặn (suppress greeting).
                    sendListenStartOnly("hey mini chào", ListeningMode.AUTO_STOP, forceRefresh = true)
                    scheduleGreetingTimeout()
                    Log.i(
                        TAG,
                        "openFreshMqttSession(hey mini): wake detect + listen (downlink TTS); uplink PCM chặn đến sau ting",
                    )
                } else {
                    sendListenStartOnly(reason, ListeningMode.AUTO_STOP, forceRefresh = false)
                    if (!deferMicRestart) {
                        mainHandler.post { onTtsStoppedRestartMic?.invoke() }
                    }
                }
                Log.i(TAG, "openFreshMqttSession OK ($reason)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "openFreshMqttSession($reason) error: ${e.message}", e)
                false
            } finally {
                reopenInProgress = false
            }
        }
    }

    /**
     * Trong cùng phiên: kênh UDP còn → chỉ listen.
     * Hey mini / kênh đóng → luồng MQTT mới (không reuse broker).
     */
    private suspend fun reopenChannelAndListen(reason: String): Boolean {
        val isHeyMini = reason.contains("hey mini", ignoreCase = true)
        if (sessionClosedAwaitWake && !isHeyMini) {
            Log.i(TAG, "reopenChannelAndListen($reason) bỏ qua – cần hey mini / chạm đầu")
            return false
        }
        if (isHeyMini || !protocol.isAudioChannelOpened()) {
            return openFreshMqttSessionAndListen(reason)
        }
        val deferMicRestart = reason.contains("sau skill", ignoreCase = true)
            || reason.contains("sau TTS", ignoreCase = true)
        return reconnectMutex.withLock {
            reopenInProgress = true
            try {
                Log.i(TAG, "reopenChannelAndListen($reason): kênh còn – chỉ listen")
                sendListenStartOnly(reason, ListeningMode.AUTO_STOP, forceRefresh = false)
                if (!deferMicRestart) {
                    mainHandler.post { onTtsStoppedRestartMic?.invoke() }
                }
                true
            } finally {
                reopenInProgress = false
            }
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
        protocol.shouldDeferGoodbye = {
            val defer = isTtsPlaying || suppressServerPcmUntilFirstGreetingDone
            if (defer) {
                pendingReconnectAfterTts = true
                Log.w(TAG, "[Session] defer MQTT goodbye – chờ buffer TTS xong rồi reopen")
                scheduleMidTtsDisconnectRecovery("MQTT goodbye deferred")
            }
            defer
        }
        MiniRobotActionInvoker.setXiaozhiWireIdentities(deviceId, clientId)
        // Không connect MQTT lúc init — tránh TLS/handshake nền; chỉ mở khi hey mini / wake.
        channelBootstrapJob = scope.launch {
            Log.i(TAG, "[init] MQTT idle – chờ hey mini / chạm đầu để mở luồng mới")
            sessionClosedAwaitWake = true
        }
        // Player + JSON (giống ChatViewModel sau khi mở kênh).
        scope.launch {
            try {
                var pcmFlow = flow {
                    var decodeNulls = 0
                    var decodeOk = 0
                    protocol.incomingAudioFlow.collect { opus: ByteArray ->
                        if (opus.isEmpty()) {
                            emit(TTS_FRAME_LOSS_SILENCE_PCM)
                            return@collect
                        }
                        val pcm = decoder.decode(opus)
                        if (pcm == null) {
                            decodeNulls++
                            if (decodeNulls <= 5 || decodeNulls % 50 == 0) {
                                Log.w(TAG, "[TTS] decode null #$decodeNulls opusLen=${opus.size}")
                            }
                        } else {
                            decodeOk++
                            if (decodeOk <= 3 || decodeOk % 100 == 0) {
                                Log.i(TAG, "[TTS] decode OK #$decodeOk pcm=${pcm.size}B opus=${opus.size}B")
                            }
                            emit(pcm)
                        }
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
                    Log.i(TAG, "[MQTT→] type=$type session_id=${sid.take(8)}… – luồng STT: server gửi gì ta đều log")
                    when (type) {
                        "stt" -> {
                            val text = json.optString("text", "")
                            if (suppressServerPcmUntilFirstGreetingDone) {
                                Log.w(TAG, "[STT] bỏ qua trong lúc chào (echo/wake): \"$text\"")
                                return@collect
                            }
                            lastSttReceivedMs = System.currentTimeMillis()
                            noSttListenOnlyAttempts = 0
                            Log.i(TAG, "[STT] Server trả STT: text=\"$text\" – nếu không thấy khi nói = server không trả lời")
                            if (isLikelySessionEndStt(text)) {
                                sessionClosedAwaitWake = true
                                Log.i(TAG, "[Session] STT kết thúc hội thoại – sau TTS/WS đóng không tự reopen")
                            }
                            // Skill robot chỉ qua MCP tools/call — không khớp keyword STT (tránh chạy 2 lần).
                        }
                        "mcp" -> {
                            try {
                                XiaozhiMcpResponder.handleIncomingMcp(json, protocol)
                            } catch (e: Exception) {
                                Log.e(TAG, "MCP responder: ${e.message}", e)
                            }
                        }
                        "iot" -> {
                            MiniRobotActionInvoker.dispatchFromXiaozhiJson(json)
                        }
                        "llm" -> {
                            if (suppressServerPcmUntilFirstGreetingDone) {
                                Log.w(
                                    TAG,
                                    "[LLM] bỏ qua emotion trong lúc chào: ${json.optString("emotion", "")}",
                                )
                                return@collect
                            }
                            val emotion = json.optString("emotion", "").trim()
                            if (emotion.isNotEmpty()) {
                                Log.i(TAG, "[LLM] emotion=\"$emotion\" → skill ROM (vừa nói vừa hành động)")
                                MiniRobotActionInvoker.applyLlmEmotionFromServer(emotion)
                            }
                        }
                        "alert" -> {
                            if (suppressServerPcmUntilFirstGreetingDone) return@collect
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
                                    markGreetingTtsStarted()
                                    isTtsPlaying = true
                                    isTtsPlayingSinceMs = System.currentTimeMillis()
                                    preparePlayerForGreetingTts()
                                    onTtsStarted?.invoke()
                                    Log.i(TAG, "[TTS] chào bắt đầu (hey mini)")
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
                                val ttsText = json.optString("text", "").trim()
                                if (ttsText.isNotEmpty()) {
                                    Log.i(TAG, "[TTS] << $ttsText")
                                }
                                if (heyMiniJustTriggered && suppressServerPcmUntilFirstGreetingDone) {
                                    markGreetingTtsStarted()
                                    isTtsPlaying = true
                                    isTtsPlayingSinceMs = System.currentTimeMillis()
                                    return@collect
                                }
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
                                    val ttsStopAt = System.currentTimeMillis()
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
                                    Log.i(
                                        TAG,
                                        "[TTS] playback drain xong, ting sau ${System.currentTimeMillis() - ttsStopAt}ms từ tts stop",
                                    )
                                    if (recoveryGen != ttsRecoveryGeneration) {
                                        Log.i(TAG, "TTS recovery bị hủy (WS đóng giữa chừng) – không gửi listen; nói hey mini hoặc chạm đầu")
                                        isTtsPlaying = false
                                        isTtsPlayingSinceMs = 0L
                                        finishFirstGreetingPhase("TTS recovery hủy")
                                        mainHandler.post { onTtsStoppedRestartMic?.invoke() }
                                        return@ttsRecovery
                                    }
                                    val wasGreeting = suppressServerPcmUntilFirstGreetingDone
                                    lastTtsStopClearedAt = System.currentTimeMillis()
                                    if (longTts || afterRobotSkill) {
                                        refreshSessionAfterNextTtsStop = false
                                        suppressServerPcmForSkillUntilMs = 0L
                                        val reason = if (longTts) "sau TTS dài" else "sau skill robot"
                                        val ok = reopenChannelAndListen(reason)
                                        if (ok) {
                                            playTingRestartMicAndFinish(wasGreeting, afterRobotSkill, "$reason + reopen")
                                        } else {
                                            isTtsPlaying = false
                                            isTtsPlayingSinceMs = 0L
                                            Log.w(TAG, "reopen $reason không success – nói hey mini để mở lại")
                                            finishFirstGreetingPhase("$reason reopen fail")
                                            mainHandler.post { onTtsStoppedRestartMic?.invoke() }
                                        }
                                    } else if (protocol.isAudioChannelOpened()) {
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
                        if (!reopenInProgress && (isTtsPlaying || pendingReconnectAfterTts)) {
                            Log.w(TAG, "MQTT/UDP đóng giữa TTS – không kẹt wake, sẽ reopen sau buffer")
                            scheduleMidTtsDisconnectRecovery("UDP CLOSED giữa TTS")
                            return@collect
                        }
                        channelIntentionallyStale = true
                        isTtsPlaying = false
                        isTtsPlayingSinceMs = 0L
                        lastTtsStopClearedAt = 0L
                        ttsRecoveryGeneration++
                        pendingReconnectAfterTts = false
                        if (!reopenInProgress) {
                            sessionClosedAwaitWake = true
                            suppressServerPcmUntilFirstGreetingDone = false
                            cancelGreetingTimeout()
                            heyMiniJustTriggered = false
                            lastListenSentMs = 0L
                            sendFrameCount = 0L
                            Log.i(TAG, "MQTT/UDP đóng – chờ hey mini (mở lại hello/UDP, không ngắt broker nền)")
                            scheduleListenWatchdogIfIdle()
                        } else {
                            Log.d(TAG, "UDP đóng tạm (reopen) – giữ suppress/greeting + heyMini flags")
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
                Log.i(TAG, "[WakeWord] debounced nhưng kênh đóng – chờ bootstrap / mở kênh")
                scope.launch { awaitBootstrapThenWake() }
            } else {
                Log.i(TAG, "[WakeWord] debounced (${now - lastWakeSessionHandledMs}ms) – bỏ wake trùng (handleWakeup + startRecognizing)")
                mainHandler.post { onTtsStoppedRestartMic?.invoke() }
            }
            return
        }
        lastWakeSessionHandledMs = now
        scope.launch { awaitBootstrapThenWake() }
    }

    /** Hey mini: luôn một luồng MQTT+UDP mới (không reuse session cũ). */
    private suspend fun awaitBootstrapThenWake() {
        try {
            channelBootstrapJob?.join()
        } catch (_: Exception) {
        }
        suppressServerPcmUntilFirstGreetingDone = true
        heyMiniJustTriggered = true
        performFullWakeReconnect()
    }

    /** Kênh đóng: mở lại giống ChatViewModel.startListening. */
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
        if (wakeReconnectJob?.isActive == true) {
            Log.i(TAG, "[WakeWord] performFullWakeReconnect đang chạy – bỏ duplicate")
            return
        }
        Log.i(TAG, "[WakeWord] performFullWakeReconnect – open/listen nếu kênh đóng")
        wakeReconnectJob = scope.launch {
            try {
                sessionClosedAwaitWake = false
                channelIntentionallyStale = false
                isTtsPlaying = false
                isTtsPlayingSinceMs = 0L
                lastTtsStopClearedAt = 0L
                suppressServerPcmUntilFirstGreetingDone = true
                heyMiniJustTriggered = true
                Log.i(TAG, "[Greeting] chặn uplink PCM; listen ngay sau wake (server cần để gửi Opus TTS)")
                val ok = reopenChannelAndListen("hey mini")
                if (!ok) {
                    Log.e(TAG, "Hey mini reopenChannelAndListen failed")
                    heyMiniJustTriggered = false
                    suppressServerPcmUntilFirstGreetingDone = false
                    cancelGreetingTimeout()
                    return@launch
                }
                Log.i(TAG, "[WakeWord] Hey mini: MQTT+UDP OK – chờ TTS chào + Opus UDP")
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
        // Chặn uplink khi TTS + cooldown sau ting (pcmBlockReason) – tránh echo STT tự nói tự nghe.

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
                    } else {
                        maybeSendUplinkKeepalive()
                        if (now - lastPcmBlockedLogMs >= DROP_LOG_INTERVAL_MS) {
                            lastPcmBlockedLogMs = now
                            Log.w(TAG, "sendPcmFrameFromJava: WS mở nhưng chặn PCM – ${pcmBlockReason()}")
                        }
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

    override fun dispose() {
        try { player.shutdown() } catch (e: Exception) {}
        try { encoder.release() } catch (e: Exception) {}
        try { decoder.release() } catch (e: Exception) {}
        try { protocol.dispose() } catch (e: Exception) {}
        XiaozhiMcpResponder.resetInitializeHandshake()
        scope.cancel()
    }
}
