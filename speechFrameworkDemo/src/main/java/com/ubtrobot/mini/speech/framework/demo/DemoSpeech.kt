package com.ubtrobot.mini.speech.framework.demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.TextUtils
import com.ubtech.utilcode.utils.LogUtils
import com.ubtech.utilcode.utils.Utils
import com.ubtech.utilcode.utils.notification.NotificationCenter
import com.ubtech.utilcode.utils.thread.ThreadPool
import com.ubtechinc.mini.weinalib.TencentVadRecorder
import com.ubtechinc.mini.weinalib.WeiNaMicApi
import com.ubtechinc.mini.weinalib.WeiNaRecorder
import com.ubtrobot.action.ActionApi
import com.ubtrobot.master.param.ProtoParam
import com.ubtrobot.master.service.MasterSystemService
import com.ubtrobot.master.transport.message.parcel.ParcelableParam
import com.ubtrobot.mini.speech.framework.DingDangManager
import com.ubtrobot.mini.speech.framework.ResourceLoader
import com.ubtrobot.mini.speech.framework.ServiceConstants
import com.ubtrobot.mini.speech.framework.SpeechModuleFactory
import com.ubtrobot.mini.speech.framework.SpeechSettingStub
import com.ubtrobot.mini.speech.framework.WakeupAudioPlayer
import com.ubtrobot.mini.speech.framework.skill.SkillManager
import com.ubtrobot.mini.speech.framework.utils.MicApiHelper
import com.ubtrobot.mini.speech.framework.utils.ShakeHeadUtils
import com.ubtrobot.motor.MotorApi
import com.ubtrobot.parcelable.BaseProgress
import com.ubtrobot.speech.AbstractRecognizer
import com.ubtrobot.speech.AbstractSynthesizer
import com.ubtrobot.speech.AbstractUnderstander
import com.ubtrobot.speech.CompositeSpeechService
import com.ubtrobot.speech.RecognitionException
import com.ubtrobot.speech.RecognitionProgress
import com.ubtrobot.speech.RecognitionResult
import com.ubtrobot.speech.RecognizerListener
import com.ubtrobot.speech.SpeechConstants
import com.ubtrobot.speech.SynthesisException
import com.ubtrobot.speech.SynthesisProgress
import com.ubtrobot.speech.SynthesizerListener
import com.ubtrobot.speech.UnderstanderListener
import com.ubtrobot.speech.UnderstandingException
import com.ubtrobot.speech.UnderstandingResult
import android.os.Parcel
import com.ubtrobot.speech.WakeUp
import com.ubtrobot.speech.parcelable.ASRState
import com.ubtrobot.speech.parcelable.AccessToken
import com.ubtrobot.speech.parcelable.InitResult
import com.ubtrobot.speech.parcelable.MicrophoneWakeupAngle
import com.ubtrobot.speech.parcelable.TTsState
import com.ubtrobot.speech.protos.Speech.WakeupParam
import com.ubtrobot.ulog.FwLoggerFactory2
import info.dourok.voicebot.data.model.DeviceInfo
import info.dourok.voicebot.data.model.DummyDataGenerator
import info.dourok.voicebot.data.model.toJson
import com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlHttpServer
import com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlMqttIdentityPatch
import com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlPresets
import com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlStore
import java.util.concurrent.atomic.AtomicInteger
/**
 * <p>Created 06/03. </p>
 * <p>Copyright 2019 @feng.zhang</p>
 */

object DemoSpeech : SpeechModuleFactory() {
    private val LOGGER = FwLoggerFactory2.getLogger("Speech-Chain")
    private const val TAG = "SpeechFactory"
    /** Bỏ apply cũ khi user đổi khóa liên tục trên web. */
    private val identityApplyGen = AtomicInteger(0)
    /** Device-Id đã apply thành công lần gần nhất (tránh dispose/reopen thừa → đơ). */
    @Volatile private var lastAppliedDeviceId: String? = null
    @Volatile private var lastLocalShowConfigMs = 0L
    @Volatile private var lastLocalShiftUnitMs = 0L

    private val appContext by lazy { Utils.getContext().applicationContext }

    //speechSettings
    private val speechSettingStub: SpeechSettingStub = SpeechSettingStub(
            Utils.getContext().applicationContext)

    //asr
    private var recognizer: AbstractRecognizer? = null
    private var asrRecorder: TencentVadRecorder? = null

    //tts
    private var synthesizer: AbstractSynthesizer? = null

    //nlp
    private var understander: AbstractUnderstander? = null

    private var speechServiceStub: CompositeSpeechService? = null
    /** Wake detector (sherpa-onnx KWS) – cần start lại sau TTS stop để hey mini hoạt động. */
    private var wakeUpDetectorRef: SherpaOnnxWakeUpDetector? = null
    private var xiaozhiSessionRef: XiaozhiSessionManager? = null
    private var xiaozhiAudioSessionId: Int = 0
    @Volatile
    private var localReadySignaled: Boolean = false
    @Volatile
    private var onlineReadySignaled: Boolean = false
    /** Hey mini / chạm đầu vừa xảy ra — dùng khi OTA swap transport hủy wake giữa chừng. */
    @Volatile
    private var lastWakeAtMs: Long = 0L
    /**
     * Chạm đầu lúc DingDang/sherpa còn init: ThreadPool có thể bị [waitUntilReady] chiếm ≤15s
     * → handleWakeup (và ting) xếp hàng trễ. Ting phát ngay; wake đầy đủ chạy khi recognizer sẵn sàng.
     */
    @Volatile
    private var pendingHeadWake: Boolean = false

    private var mRecognizerListener: RecognizerListener? = null
    private var mSynthesizerListener: SynthesizerListener? = null
    private var mUnderstanderListener: UnderstanderListener? = null

    private val mSkillManager by lazy { SkillManager() }

    //After positioning the sound source, move the head
    private fun shakeHead() {
        val lastLockAngel = MicApiHelper.getMicLockAngle()
        LOGGER.d("shakeHead ---lastLockAngel = $lastLockAngel")

        val callback = object : ShakeHeadUtils.MoveHeadCallback {

            override fun onProgress(moveAngel: Int, currmotorAngel: Int) {
                val lastLockAngle = MicApiHelper.getMicLockAngle()
                val newMicAngle = (360 + lastLockAngle + moveAngel) % 360
                MicApiHelper.setMicLockAngle(newMicAngle.toShort(), true)
            }

            override fun onSucc(moveAngel: Int, currMotorAngel: Int) {
                val lastLockAngle = MicApiHelper.getMicLockAngle()
                val newMicAngle = (360 + lastLockAngle + moveAngel) % 360
                MicApiHelper.setMicLockAngle(newMicAngle.toShort(), true)
                LOGGER.d("onSucc---" + "lastLockAngelxx = " + lastLockAngle +
                        ", moveAngel = " + moveAngel + ", finalMicAngle = " +
                        MicApiHelper.getMicLockAngle())
            }

            override fun onError(moveAngel: Int, currMotorAngel: Int) {
                val lastLockAngle = MicApiHelper.getMicLockAngle()
                val newMicAngle = (360 + lastLockAngle + moveAngel) % 360
                MicApiHelper.setMicLockAngle(newMicAngle.toShort(), true)
            }

        }
        ShakeHeadUtils.shakeHead(lastLockAngel, callback)

    }

    fun destroy() {
        WeiNaMicApi.get().release()
    }

    fun init(service: MasterSystemService?) {
        XiaozhiMqttConfigStore.init(appContext)
        SelfControlStore.init(appContext)
        SelfControlHttpServer.setOnIdentityChanged { applyDeviceIdentityFromSelfControl() }
        SelfControlHttpServer.start(appContext)
        LogUtils.i(TAG, "Self-Control HTTP ${SelfControlHttpServer.configUrl()}")
        try {
            ActivationEyeDisplay.bindAppContext(appContext)
            ActivationEyeDisplay.warmSelfControlEyeCache(SelfControlHttpServer.configUrl())
        } catch (_: Exception) {
        }
        //Load the wake-up sound effect in advance
        WakeupAudioPlayer.get(appContext)

        val hostService = service!!

        // DOA (direction of arrival) – dùng WeiNa/MNano. Lỗi MNPCJni -10205 "原始数据验证错误" (can't find 0x100)
        // xuất phát từ luồng đọc audio của WeiNa, KHÔNG phải từ luồng Xiaozhi (directRecord). Nếu lỗi -10205
        // spam nhiều có thể thử tắt DOA tạm (comment block dưới) để kiểm tra STT/hey mini.
        WeiNaMicApi.get().addDoaAngleCallback { angle: Short ->

            //External applications may need to use the wake-up angle to publish it as an event
            hostService.publishCarefully(
                    ServiceConstants.PATH_MICROPHONE_ARRAY_WAKEUP_ANGLE,
                    ParcelableParam.create(
                            MicrophoneWakeupAngle(angle.toInt())))

            // act according to the angle, and turn the head to the sound source
            MicApiHelper.setMicLockAngle(angle, false)
            if (createSpeechSettings().isSpeechLinkable && ShakeHeadUtils.shakeHeadTiming == ShakeHeadUtils.ShakeHeadTiming.BeforeRecord) {
                if (!ActionApi.get().unsafeAction()) {
                    shakeHead()
                }
            }
        }

        mRecognizerListener = object : RecognizerListener {
            override fun onRecognizingFailure(p0: RecognitionException?) {
                val code = if (p0!!.extCode != 0) {
                    p0.extCode
                } else {
                    p0.code
                }
                //when asr recognizing failure, If you need to use the built-in expressiveness, you can publish the event
                hostService.publishCarefully(
                        ServiceConstants.ACTION_SPEECH_ASR_STATE,
                        ParcelableParam.create(ASRState(p0.message, code)))

                LogUtils.w(TAG,
                        "onRecognizingFailure:(code=" + p0.code + ", extCode = " + p0.extCode + ", msg=" + p0.message)
            }

            override fun onRecognizingResult(p0: RecognitionResult?) {
            }

            override fun onRecognizingProgress(p0: RecognitionProgress?) {
                p0?.let {
                    when (it.progress) {
                        //when asr recognizing begin, If you need to use the built-in expressiveness, you can publish the event
                        BaseProgress.PROGRESS_BEGAN -> {
                            hostService.publishCarefully(
                                    ServiceConstants.ACTION_SPEECH_ASR_STATE,
                                    ParcelableParam.create(ASRState(BaseProgress.PROGRESS_BEGAN)))
                        }
                        //when asr recognizing end, If you need to use the built-in expressiveness, you can publish the event
                        BaseProgress.PROGRESS_ENDED -> {
                            if (recognizer!!.isRecognizing) {//如果外部cancel掉了一次asr,则不需要发布PROGRESS_ENDED
                                hostService.publishCarefully(
                                        ServiceConstants.ACTION_SPEECH_ASR_STATE,
                                        ParcelableParam.create(
                                                ASRState(BaseProgress.PROGRESS_ENDED)))
                            }

                            // act according to the angle, and turn the head to the sound source
                            if (createSpeechSettings().isSpeechLinkable && ShakeHeadUtils.shakeHeadTiming == ShakeHeadUtils.ShakeHeadTiming.AfterRecord) {
                                if (!ActionApi.get().unsafeAction()) {
                                    shakeHead()
                                }
                            }

                            // stop recording skill
                            mSkillManager.stopSkill(
                                    SkillManager.SKILL_AUDIORECORD)

                        }
                    }
                }
            }
        }

        mSynthesizerListener = object : SynthesizerListener {
            override fun onSynthesizingProgress(p0: SynthesisProgress?) {
                p0?.let {
                    when (it.progress) {
                        //when tts synthesizing begin, If you need to use the built-in expressiveness, you can publish the event
                        BaseProgress.PROGRESS_BEGAN -> hostService.publishCarefully(
                                ServiceConstants.ACTION_SPEECH_TTS_STATE,
                                ParcelableParam.create(TTsState(
                                        BaseProgress.PROGRESS_BEGAN)))
                        //when tts synthesizing end, If you need to use the built-in expressiveness, you can publish the event
                        BaseProgress.PROGRESS_ENDED -> {
                            hostService.publishCarefully(
                                    ServiceConstants.ACTION_SPEECH_TTS_STATE,
                                    ParcelableParam.create(TTsState(
                                            BaseProgress.PROGRESS_ENDED)))
                            // stop Chat skill
                            mSkillManager.stopSkill(
                                    SkillManager.SKILL_CHAT)
                        }
                    }
                }
            }

            override fun onSynthesizingResult() {
            }

            override fun onSynthesizingFailure(p0: SynthesisException?) {
                LogUtils.w(TAG, "onSynthesizingFailure $p0")
                mSkillManager.stopSkill(SkillManager.SKILL_CHAT)
            }
        }

        mUnderstanderListener = object : UnderstanderListener {
            override fun onUnderstandingFailure(p0: UnderstandingException?) {
                val code = if (p0!!.extCode != 0) {
                    p0.extCode
                } else {
                    p0.code
                }

                //when nlp failure , If you need to use the built-in expressiveness, you can publish the event
                if (code == 403) {
                    hostService.publishCarefully(
                            ServiceConstants.ACTION_SPEECH_ASR_STATE,
                            ParcelableParam.create(
                                    ASRState(p0.message, ASRState.CODE_UNAUTHENTICATED)))
                } else {
                    hostService.publishCarefully(
                            ServiceConstants.ACTION_SPEECH_ASR_STATE,
                            ParcelableParam.create(ASRState(p0.message, p0.code)))
                }
                LogUtils.w(TAG,
                        "onUnderstandingFailure:(code=" + p0.code + ", extCode = " + p0.extCode + ", msg=" + p0.message)
            }

            override fun onUnderstandingResult(p0: UnderstandingResult?) {
                //when nlp completed, If you need to use the built-in expressiveness, you can publish the event
                hostService.publishCarefully(
                        ServiceConstants.ACTION_SPEECH_ASR_STATE,
                        ParcelableParam.create(ASRState("recognized")))
            }
        }

        // sherpa-onnx KWS (local, assets/sherpa-kws) – no Picovoice device quota
        val wakeUpDetector = SherpaOnnxWakeUpDetector(appContext)
        wakeUpDetectorRef = wakeUpDetector
        wakeUpDetector.registerListener { wakeUp: WakeUp? ->
            handleWakeup(hostService, wakeUp, service)
        }
        setupHeadTouchTrigger(hostService, service)

        // StandUp sớm + retry — motor/Master đôi khi chưa sẵn lúc WS ready → “app mở mà không đứng”.
        scheduleBootStandUpRetries()

        // Boot nhanh: Xiaozhi + mic + sherpa SONG SONG, không chờ DingDang (hay chậm 5–20s).
        Thread({
            bootstrapXiaozhiFastPath(hostService, service, wakeUpDetector)
        }, "XiaozhiFastBoot").start()
        Thread({
            startSherpaAndMicWhenReady(wakeUpDetector, hostService, service)
        }, "SherpaKwsReady").start()

        // DingDang: framework VAD / CompositeSpeechService (song song; không chặn head-wake).
        DingDangManager.load(appContext) { success ->
            if (success) {
                ThreadPool.runOnNonUIThread {
                    completeDingDangSpeechStack(hostService, service, wakeUpDetector)
                }
            } else {
                LogUtils.e(
                        "Initialization configuration of wake-up module failed, restart application...")
                Process.killProcess(Process.myPid())
            }
        }
    }

    private val xiaozhiFastBootLock = Any()
    @Volatile
    private var xiaozhiFastBootDone: Boolean = false
    @Volatile
    private var bootStandUpAttempted: Boolean = false

    /** Gọi StandUp vài lần sau boot — lần đầu thường fail nếu fallclimb/Master chưa lên. */
    private fun scheduleBootStandUpRetries() {
        val delaysMs = longArrayOf(1_200L, 3_500L, 7_000L)
        for (d in delaysMs) {
            Handler(Looper.getMainLooper()).postDelayed({
                Thread({
                    try {
                        LogUtils.i(TAG, "[Ready] Boot StandUp retry (delay=${d}ms)")
                        MiniRobotActionInvoker.performStandUpSync()
                        bootStandUpAttempted = true
                    } catch (e: Exception) {
                        LogUtils.w(TAG, "Boot StandUp: ${e.message}")
                    }
                }, "BootStandUp").start()
            }, d)
        }
    }

    private fun signalOnlineReadyAndStandUp() {
        if (onlineReadySignaled) return
        onlineReadySignaled = true
        try {
            lastAppliedDeviceId = XiaozhiDeviceIdentityStore.getOrCreate(appContext).deviceId
        } catch (_: Exception) {
        }
        Handler(Looper.getMainLooper()).post {
            // Giống 2b23f8d: WakeupAudioPlayer (không SafeWakeTing).
            try {
                WakeupAudioPlayer.get(appContext).play()
            } catch (_: Exception) {
            }
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    WakeupAudioPlayer.get(appContext).play()
                } catch (_: Exception) {
                }
            }, 160L)
        }
        Thread({
            try {
                LogUtils.i(TAG, "[Ready] Online ready → StandUp")
                MiniRobotActionInvoker.performStandUpSync()
                bootStandUpAttempted = true
            } catch (e: Exception) {
                LogUtils.w(TAG, "StandUp on online-ready: ${e.message}")
            }
        }, "OnlineStandUp").start()
    }

    /**
     * Không chờ DingDang: mở WS + tạo DemoRecognizer (VadRecorder=null) → chạm đầu / wake được sớm.
     */
    private fun bootstrapXiaozhiFastPath(
        hostService: MasterSystemService,
        service: MasterSystemService,
        wakeUpDetector: SherpaOnnxWakeUpDetector
    ) {
        synchronized(xiaozhiFastBootLock) {
            if (xiaozhiFastBootDone && xiaozhiSessionRef != null && recognizer != null) {
                flushPendingHeadWake(hostService, service)
                return
            }
            try {
                val audioSessionId = 0
                xiaozhiAudioSessionId = audioSessionId
                if (xiaozhiSessionRef == null) {
                    val xiaozhi = createXiaozhiSessionManager(audioSessionId)
                    xiaozhiSessionRef = xiaozhi
                    xiaozhi?.setOnWebSocketSessionReady {
                        signalOnlineReadyAndStandUp()
                    }
                    val opusLabel = when {
                        xiaozhi == null -> "NULL (Opus init fail)"
                        NativeOpusBootstrap.isNativeAvailable() ->
                            "OK (${xiaozhiTransportLabel()} + native libapp.so, fast-boot)"
                        else ->
                            "OK (${xiaozhiTransportLabel()} + Concentus, fast-boot)"
                    }
                    LogUtils.i(TAG, "XiaozhiSessionManager: $opusLabel")
                }
                if (recognizer == null) {
                    recognizer = DemoRecognizer(null, xiaozhiSessionRef, audioSessionId)
                    (recognizer as? DemoRecognizer)?.setWakeWordPcmFeeder(wakeUpDetector)
                    if (mRecognizerListener != null) {
                        recognizer!!.registerListener(mRecognizerListener)
                    }
                    // Mic sớm cho head-wake / PCM (sherpa có thể chưa ready — vẫn feed khi ready).
                    (recognizer as? DemoRecognizer)?.startMicForWakeWord()
                    LogUtils.i(TAG, "[Boot] fast-path: recognizer+mic OK – chạm đầu / wake được")
                }
                xiaozhiFastBootDone = true
                flushPendingHeadWake(hostService, service)
            } catch (e: Exception) {
                LogUtils.e(TAG, "bootstrapXiaozhiFastPath: ${e.message}", e)
            }
        }
    }

    private fun startSherpaAndMicWhenReady(
        wakeUpDetector: SherpaOnnxWakeUpDetector,
        hostService: MasterSystemService,
        service: MasterSystemService
    ) {
        try {
            wakeUpDetector.start()
            val kwsReady = wakeUpDetector.waitUntilReady(15_000L)
            if (kwsReady) {
                (recognizer as? DemoRecognizer)?.startMicForWakeWord()
                LogUtils.i(TAG, "[WakeWord] mic started – sherpa ready, say HEY MINI")
                if (!localReadySignaled) {
                    localReadySignaled = true
                    try {
                        WakeupAudioPlayer.get(appContext).play()
                    } catch (_: Exception) {
                    }
                }
            } else {
                LogUtils.e(TAG, "[WakeWord] sherpa not ready – check sherpa-kws ONNX bundle")
            }
            flushPendingHeadWake(hostService, service)
        } catch (e: Exception) {
            LogUtils.e(TAG, "startSherpaAndMicWhenReady: ${e.message}", e)
        }
    }

    /** Sau DingDang: gắn VAD + CompositeSpeechService; tái dùng Xiaozhi nếu fast-boot đã tạo. */
    private fun completeDingDangSpeechStack(
        hostService: MasterSystemService,
        service: MasterSystemService,
        wakeUpDetector: SherpaOnnxWakeUpDetector
    ) {
        try {
            asrRecorder = TencentVadRecorder(ResourceLoader.vad_path)
            val audioSessionId = xiaozhiAudioSessionId
            synchronized(xiaozhiFastBootLock) {
                if (xiaozhiSessionRef == null) {
                    val xiaozhi = createXiaozhiSessionManager(audioSessionId)
                    xiaozhiSessionRef = xiaozhi
                    xiaozhi?.setOnWebSocketSessionReady {
                        signalOnlineReadyAndStandUp()
                    }
                }
                if (recognizer == null) {
                    recognizer = DemoRecognizer(asrRecorder, xiaozhiSessionRef, audioSessionId)
                    (recognizer as? DemoRecognizer)?.setWakeWordPcmFeeder(wakeUpDetector)
                    recognizer!!.registerListener(mRecognizerListener)
                    (recognizer as? DemoRecognizer)?.startMicForWakeWord()
                } else {
                    (recognizer as? DemoRecognizer)?.attachVadRecorder(asrRecorder!!)
                    (recognizer as? DemoRecognizer)?.updateXiaozhiSession(xiaozhiSessionRef)
                }
            }
            synthesizer = DemoSynthesizer()
            synthesizer!!.registerListener(mSynthesizerListener)
            understander = DemoUnderstander()
            understander!!.registerListener(mUnderstanderListener)
            speechServiceStub = CompositeSpeechService.Builder()
                    .setRecognizer(recognizer)
                    .setSynthesizer(synthesizer)
                    .setUnderstander(understander)
                    .setWakeUpDetector(wakeUpDetector)
                    .build()
            LogUtils.i(TAG, "init success (DingDang stack).")
            flushPendingHeadWake(hostService, service)

            Handler(Looper.getMainLooper()).post {
                hostService.publishCarefully(
                        ServiceConstants.ACTION_SPEECH_INIT_RESULT,
                        ParcelableParam.create(InitResult(0)))
                NotificationCenter.defaultCenter().publish(
                        ServiceConstants.PATH_MICROPHONE_ARRAY_INIT_RESULT, this)
                ThreadPool.runOnNonUIThread { runXiaozhiOtaAndShowActivation() }
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "completeDingDangSpeechStack: ${e.message}", e)
        }
    }

    /**
     * Build a minimal device identifier for Xiaozhi backend.
     * You should replace the hard-coded values with real IDs from your robot.
     */
    private fun obtainXiaozhiIds(): Pair<String, String> {
        val identity = XiaozhiDeviceIdentityStore.getOrCreate(appContext)
        return identity.deviceId to identity.clientId
    }

    /**
     * Creates XiaozhiSessionManager for WebSocket voice (Opus encode/decode).
     * [audioSessionId] dùng chung với DemoRecognizer để AEC (AcousticEchoCanceler) có reference từ TTS – giống Xiaozhi_Android-main.
     * Tries native (libapp.so) first; if not available, uses Concentus (pure Java Opus).
     * Returns null only if both fail.
     */
    private fun xiaozhiTransportLabel(): String =
        xiaozhiSessionRef?.getTransportLabel() ?: "WebSocket"

    private fun createXiaozhiSessionManager(audioSessionId: Int): XiaozhiSessionManager? {
        // Mỗi lần mở session: random Client-Id mới (Device-Id = MAC khóa).
        val identity = XiaozhiDeviceIdentityStore.rotateClientId(appContext)
        val deviceId = identity.deviceId
        val clientId = identity.clientId
        val websocketUrl = "wss://api.tenclass.net/xiaozhi/v1/"
        val accessToken = ""
        NativeOpusBootstrap.probe()
        val useNative = NativeOpusBootstrap.isNativeAvailable()
        val encoder: IOpusEncoder
        val decoder: IOpusDecoder
        try {
            if (useNative) {
                encoder = NativeOpusEncoderAdapter(
                    XiaozhiSessionManager.SAMPLE_RATE,
                    XiaozhiSessionManager.CHANNELS,
                    XiaozhiSessionManager.FRAME_MS
                )
                decoder = NativeOpusDecoderAdapter(
                    XiaozhiSessionManager.TTS_SAMPLE_RATE,
                    XiaozhiSessionManager.CHANNELS,
                    XiaozhiSessionManager.FRAME_MS
                )
                LogUtils.i(TAG, "XiaozhiSessionManager: OK (native libapp.so) – âm chuẩn, không gain PCM")
            } else {
                encoder = ConcentusOpusEncoder(
                    XiaozhiSessionManager.SAMPLE_RATE,
                    XiaozhiSessionManager.CHANNELS,
                    XiaozhiSessionManager.FRAME_MS
                )
                decoder = ConcentusOpusDecoder(
                    XiaozhiSessionManager.TTS_SAMPLE_RATE,
                    XiaozhiSessionManager.CHANNELS,
                    XiaozhiSessionManager.FRAME_MS
                )
                LogUtils.w(
                    TAG,
                    "XiaozhiSessionManager: Concentus fallback + gain ${XiaozhiSessionManager.PCM_GAIN_CONCENTUS}x — " +
                        "âm dễ méo (vd. chí); build: gradlew assembleDebug KHÔNG -PskipNdk → OK (native libapp.so)"
                )
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Xiaozhi Opus init failed: " + e.message, e)
            return null
        }
        val pcmGain = if (useNative) 1f else XiaozhiSessionManager.PCM_GAIN_CONCENTUS
        val transport = XiaozhiTransportPreference.get(appContext)
        if (transport == XiaozhiTransportType.MQTT && !XiaozhiMqttConfigStore.hasConfig()) {
            LogUtils.w(
                TAG,
                "UI chọn MQTT nhưng OTA chưa trả mqtt — fallback WebSocket (đợi OTA xong rồi khởi động lại)"
            )
        }
        val effectiveTransport = if (transport == XiaozhiTransportType.MQTT && XiaozhiMqttConfigStore.hasConfig()) {
            XiaozhiTransportType.MQTT
        } else {
            XiaozhiTransportType.WEBSOCKET
        }
        return XiaozhiSessionManager.create(
            appContext,
            deviceId,
            clientId,
            encoder!!,
            decoder!!,
            pcmGain,
            usesNativeOpus = useNative,
            onTtsStoppedRestartMic = { resumeXiaozhiMicAfterTts() },
            audioSessionId = audioSessionId,
            onTtsStarted = {
                Handler(Looper.getMainLooper()).post {
                    // Giống 2b23f8d: suppress dài khi TTS chào (greeting), ngắn khi TTS thường.
                    if (xiaozhiSessionRef?.isInFirstGreetingPhase() == true) {
                        LogUtils.d(TAG, "[WakeWord] TTS chào – suppress KWS đến sau ting (echo loa)")
                        wakeUpDetectorRef?.suppressWakeFor(12_000L)
                    } else {
                        wakeUpDetectorRef?.suppressWakeFor(800L)
                    }
                }
            },
            onFirstGreetingMicReady = {
                Handler(Looper.getMainLooper()).post {
                    wakeUpDetectorRef?.clearSuppressWake()
                    LogUtils.i(TAG, "[WakeWord] sau ting – hey mini KWS bật lại")
                }
            },
            transport = effectiveTransport,
            websocketUrl = websocketUrl,
            accessToken = accessToken,
            mqttConfig = XiaozhiMqttConfigStore.get()
        )
    }

    /**
     * ApplyDeviceIdentity (Otto / ESP32 fast path): đóng session → Device-Id mới → mở WS/MQTT → auto chào.
     * Không CheckVersion / không OTA. Cùng MAC + kênh đang mở → bỏ qua (tránh cắt TTS / đơ mạng).
     */
    @JvmStatic
    fun applyDeviceIdentityFromSelfControl() {
        val gen = identityApplyGen.incrementAndGet()
        ThreadPool.runOnNonUIThread {
            try {
                SelfControlStore.init(appContext)
                val deviceId = SelfControlStore.resolveDeviceId()

                val cur = xiaozhiSessionRef
                if (deviceId.equals(lastAppliedDeviceId, ignoreCase = true)
                    && cur?.isAudioChannelOpened() == true
                ) {
                    LogUtils.i(TAG, "ApplyDeviceIdentity skip – cùng Device-Id=$deviceId, kênh đang mở")
                    return@runOnNonUIThread
                }

                // Đổi cấu hình: random Client-Id mới + MAC khóa mới → mở WS mới.
                val clientId = XiaozhiDeviceIdentityStore.rotateClientId(appContext).clientId
                XiaozhiDeviceIdentityStore.updateDeviceIdSnapshot(appContext, deviceId)
                LogUtils.i(TAG, "ApplyDeviceIdentity Device-Id=$deviceId Client-Id=$clientId gen=$gen")

                if (XiaozhiTransportPreference.get(appContext) == XiaozhiTransportType.MQTT
                    || XiaozhiMqttConfigStore.hasConfig()
                ) {
                    SelfControlMqttIdentityPatch.patchStoreToDeviceId(deviceId)
                }

                if (gen != identityApplyGen.get()) {
                    LogUtils.i(TAG, "ApplyDeviceIdentity gen=$gen superseded before dispose")
                    return@runOnNonUIThread
                }

                val old = xiaozhiSessionRef
                try {
                    old?.forceStopPlaybackForHeyMini()
                } catch (_: Exception) {
                }

                // Otto fast path (WS): Close sạch → Device-Id mới → Open — không dispose cả Opus/OkHttp.
                if (old != null && old.getTransportLabel().contains("WebSocket", ignoreCase = true)) {
                    LogUtils.i(TAG, "ApplyDeviceIdentity Otto in-place switch Device-Id=$deviceId")
                    val switched = try {
                        old.switchDeviceIdentity(deviceId, clientId)
                    } catch (e: Exception) {
                        LogUtils.w(TAG, "switchDeviceIdentity: ${e.message}")
                        false
                    }
                    if (gen != identityApplyGen.get()) return@runOnNonUIThread
                    if (switched && old.isAudioChannelOpened()) {
                        lastAppliedDeviceId = deviceId
                        LogUtils.i(TAG, "ApplyDeviceIdentity in-place OK — auto greet")
                        Handler(Looper.getMainLooper()).post {
                            try {
                                if (gen != identityApplyGen.get()) return@post
                                wakeUpDetectorRef?.lastDetectedKeyword = "HEY MINI"
                                (recognizer as? DemoRecognizer)?.startRecognitionAfterWakeup(false)
                            } catch (e: Exception) {
                                LogUtils.w(TAG, "ApplyDeviceIdentity auto greet: ${e.message}")
                            }
                        }
                        return@runOnNonUIThread
                    }
                    LogUtils.w(TAG, "ApplyDeviceIdentity in-place fail — fallback dispose/recreate")
                }

                try {
                    old?.dispose()
                } catch (e: Exception) {
                    LogUtils.w(TAG, "ApplyDeviceIdentity dispose: ${e.message}")
                }

                // Fallback recreate: nghỉ ngắn rồi mở TCP (không sleep 500ms như bản sau).
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                }

                if (gen != identityApplyGen.get()) {
                    LogUtils.i(TAG, "ApplyDeviceIdentity gen=$gen superseded after dispose")
                    return@runOnNonUIThread
                }

                val session = createXiaozhiSessionManager(xiaozhiAudioSessionId)
                if (gen != identityApplyGen.get()) {
                    try {
                        session?.dispose()
                    } catch (_: Exception) {
                    }
                    LogUtils.i(TAG, "ApplyDeviceIdentity gen=$gen superseded after create")
                    return@runOnNonUIThread
                }
                xiaozhiSessionRef = session
                (recognizer as? DemoRecognizer)?.updateXiaozhiSession(session)
                LogUtils.i(
                    TAG,
                    "ApplyDeviceIdentity session OK transport=${session?.getTransportLabel()} — chờ kênh mở",
                )

                // Chờ ngắn (WS connectTimeout 12s); fail nhanh rồi retry 1 lần — không đơ 60s.
                fun waitOpen(ms: Long): Boolean {
                    val deadline = System.currentTimeMillis() + ms
                    while (System.currentTimeMillis() < deadline && gen == identityApplyGen.get()) {
                        if (session?.isAudioChannelOpened() == true) return true
                        try {
                            Thread.sleep(150)
                        } catch (_: InterruptedException) {
                            return false
                        }
                    }
                    return session?.isAudioChannelOpened() == true
                }

                var opened = waitOpen(14_000L)
                if (gen != identityApplyGen.get()) {
                    LogUtils.i(TAG, "ApplyDeviceIdentity gen=$gen superseded while waiting channel")
                    return@runOnNonUIThread
                }
                if (!opened) {
                    LogUtils.w(TAG, "ApplyDeviceIdentity kênh chưa mở — retry wake forceReconnect")
                    Handler(Looper.getMainLooper()).post {
                        try {
                            if (gen != identityApplyGen.get()) return@post
                            wakeUpDetectorRef?.lastDetectedKeyword = "HEY MINI"
                            (recognizer as? DemoRecognizer)?.startRecognitionAfterWakeup(true)
                        } catch (e: Exception) {
                            LogUtils.w(TAG, "ApplyDeviceIdentity retry greet: ${e.message}")
                        }
                    }
                    opened = waitOpen(16_000L)
                }
                if (gen != identityApplyGen.get()) return@runOnNonUIThread
                if (session?.isAudioChannelOpened() == true) {
                    lastAppliedDeviceId = deviceId
                    LogUtils.i(TAG, "ApplyDeviceIdentity kênh OK — auto greet")
                    Handler(Looper.getMainLooper()).post {
                        try {
                            if (gen != identityApplyGen.get()) return@post
                            wakeUpDetectorRef?.lastDetectedKeyword = "HEY MINI"
                            (recognizer as? DemoRecognizer)?.startRecognitionAfterWakeup(false)
                        } catch (e: Exception) {
                            LogUtils.w(TAG, "ApplyDeviceIdentity auto greet: ${e.message}")
                        }
                    }
                } else {
                    LogUtils.e(TAG, "ApplyDeviceIdentity vẫn chưa mở kênh — nói hey mini / chạm đầu")
                }
            } catch (e: Exception) {
                LogUtils.e(TAG, "ApplyDeviceIdentity error: $e", e)
            }
        }
    }

    /** MCP set_course / voice đổi khóa. */
    @JvmStatic
    fun selfControlSetCourse(courseIdx: Int, customMac: String?): String {
        SelfControlStore.init(appContext)
        val oldIdx = SelfControlStore.getPresetMacIdx()
        val body = org.json.JSONObject().apply {
            put("preset_mac_idx", courseIdx.coerceIn(0, 7))
            put("student_name", SelfControlStore.getStudentName())
            put("custom_mac", customMac ?: "")
            put("all_units", org.json.JSONObject().apply {
                for (i in 1..5) put(i.toString(), SelfControlStore.getUnitsFlat(i))
            })
            put("ex_sub", SelfControlStore.getExSub())
            put("ex_units", org.json.JSONObject().apply {
                for (i in 0..4) put(i.toString(), SelfControlStore.getExUnit(i))
            })
            put("yi_sub", SelfControlStore.getYiSub())
            put("yi_units", org.json.JSONObject().apply {
                for (i in 0..2) put(i.toString(), SelfControlStore.getYiUnit(i))
            })
            put("fl_sub", SelfControlStore.getFlSub())
            put("fl_units", org.json.JSONObject().apply {
                put("0", SelfControlStore.getFlUnit(0))
            })
        }
        if (courseIdx == SelfControlPresets.MANUAL_CUSTOM_MAC_IDX
            && !SelfControlPresets.isValidMac(customMac)
        ) {
            return """{"success":false,"error":"Invalid MAC"}"""
        }
        val result = SelfControlStore.applyPostConfig(body)
        if (!result.success) {
            return """{"success":false,"error":"${result.error ?: "fail"}"}"""
        }
        if (result.identityChanged) {
            applyDeviceIdentityFromSelfControl()
        }
        return org.json.JSONObject().apply {
            put("success", true)
            put("preset_mac_idx", SelfControlStore.getPresetMacIdx())
            put("device_id", SelfControlStore.resolveDeviceId())
            put("identity_changed", result.identityChanged)
            put("was_idx", oldIdx)
        }.toString()
    }

    @JvmStatic
    fun selfControlGetStudentInfoJson(): String {
        SelfControlStore.init(appContext)
        val idx = SelfControlStore.getPresetMacIdx()
        val course = com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlCourses.get(idx)
        val sub = SelfControlStore.activeSubIdx()
        val unitSel = SelfControlStore.activeUnitSelection()
        val unitName = com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlCourses
            .resolveUnitName(idx, sub, unitSel)
        val subName = course?.subs?.getOrNull(sub)?.name
        return org.json.JSONObject().apply {
            put("student_name", SelfControlStore.getStudentName())
            put("course", course?.id ?: "")
            put("course_name", course?.displayName ?: "")
            put("course_idx", idx)
            put("device_id", SelfControlStore.resolveDeviceId())
            put("sub_idx", sub)
            if (subName != null) put("sub_name", subName)
            put("units", unitSel)
            put("unit_names", unitName)
        }.toString()
    }

    /**
     * MCP / voice: unit tiếp theo hoặc unit trước trong khóa + sách đang chọn.
     * @param direction "next" | "prev" (hoặc tiếng Việt)
     */
    @JvmStatic
    fun selfControlShiftUnit(direction: String?): String {
        SelfControlStore.init(appContext)
        val d = direction?.trim()?.lowercase().orEmpty()
        val delta = when {
            d == "next" || d.contains("tiếp") || d.contains("tiep") || d == "+" || d == "1" -> 1
            d == "prev" || d == "previous" || d.contains("trước") || d.contains("truoc")
                || d == "-" || d == "-1" -> -1
            else -> 0
        }
        val r = SelfControlStore.shiftActiveUnit(delta)
        return org.json.JSONObject().apply {
            put("success", r.success)
            put("message", r.message)
            if (r.success) {
                put("course_idx", r.courseIdx)
                put("sub_idx", r.subIdx)
                put("unit_idx", r.unitIdx)
                put("unit_name", r.unitName)
                put("clamped", r.clamped)
            }
        }.toString()
    }

    @JvmStatic
    fun selfControlConfigUrl(): String = SelfControlHttpServer.configUrl()

    /**
     * MCP / voice show_config_page: mắt trái IP+URL, mắt phải QR, 60s.
     * Tool result **không** chứa URL — tránh TTS đọc đường dẫn.
     */
    @JvmStatic
    fun selfControlShowConfigPage(): String {
        SelfControlHttpServer.start(appContext)
        val url = SelfControlHttpServer.configUrl()
        if (url.contains("0.0.0.0") || url.contains("127.0.0.1")) {
            return "Chưa có Wi‑Fi — không hiện được trang cấu hình trên mắt."
        }
        // Thread riêng — không xếp hàng ThreadPool (tránh không bao giờ vẽ QR).
        Thread({
            try {
                LogUtils.i(TAG, "show_config_page → vẽ QR mắt url=$url")
                ActivationEyeDisplay.markQrShowingForHeadTap()
                // Sticky giống mở bằng đầu — double-tap luôn tắt được.
                com.ubtrobot.mini.speech.framework.demo.wificonfig.WifiProvisionController
                    .markConfigQrSticky(true)
                ActivationEyeDisplay.showSelfControlUrlAndQr(url)
            } catch (e: Exception) {
                LogUtils.e(TAG, "showSelfControlUrlAndQr: ${e.message}", e)
                ActivationEyeDisplay.clearQrShowingFlag("show-config-fail")
                com.ubtrobot.mini.speech.framework.demo.wificonfig.WifiProvisionController
                    .markConfigQrSticky(false)
            } finally {
                com.ubtrobot.mini.speech.framework.demo.wificonfig.WifiProvisionController
                    .markConfigQrSticky(false)
            }
        }, "ShowConfigQr").start()
        // Log: vẽ mắt đôi khi SSL abort WS ngay sau MCP — tự mở lại kênh nói.
        try {
            xiaozhiSessionRef?.recoverTalkAfterShowConfig()
        } catch (e: Exception) {
            LogUtils.w(TAG, "recoverTalkAfterShowConfig: ${e.message}")
        }
        return "Đã mở mã QR."
    }

    /** Head double-tap hiện QR — cùng recover WS nếu SSL abort. */
    @JvmStatic
    fun recoverTalkAfterShowConfigFromHead() {
        try {
            xiaozhiSessionRef?.recoverTalkAfterShowConfig()
        } catch (e: Exception) {
            LogUtils.w(TAG, "recoverTalkAfterShowConfigFromHead: ${e.message}")
        }
    }

    /**
     * Fallback khi server không gọi MCP: STT chứa "mở cấu hình / hiện QR" → vẫn vẽ mắt.
     * @return true nếu đã xử lý local (caller có thể bỏ qua).
     */
    @JvmStatic
    fun tryLocalShowConfigFromStt(sttText: String?): Boolean {
        val t = sttText?.lowercase()?.trim().orEmpty()
        if (t.isEmpty()) return false
        val hit = listOf(
            "mở trang cấu hình", "mo trang cau hinh",
            "mở cấu hình", "mo cau hinh",
            "hiện qr", "hien qr", "mở qr", "mo qr",
            "hiện mã qr", "show config", "self control",
            "trang cấu hình", "cài đặt robot"
        ).any { t.contains(it) }
        if (!hit) return false
        val now = System.currentTimeMillis()
        if (now - lastLocalShowConfigMs < 8_000L) {
            LogUtils.i(TAG, "STT local show_config debounce")
            return true
        }
        lastLocalShowConfigMs = now
        LogUtils.i(TAG, "STT local show_config: \"$sttText\"")
        selfControlShowConfigPage()
        return true
    }

    /**
     * Fallback STT: "unit tiếp theo / bài trước" → shift unit local nếu server không gọi MCP.
     */
    @JvmStatic
    fun tryLocalShiftUnitFromStt(sttText: String?): Boolean {
        val t = sttText?.lowercase()?.trim().orEmpty()
        if (t.isEmpty()) return false
        val nextHit = listOf(
            "unit tiếp theo", "bài tiếp theo", "unit tiep theo", "bai tiep theo",
            "next unit", "sang unit sau", "unit kế tiếp", "bài kế tiếp",
            "nextunit", "chuyển unit tiếp", "chuyen unit tiep"
        ).any { t.contains(it) }
        val prevHit = listOf(
            "unit trước", "bài trước", "unit truoc", "bai truoc",
            "previous unit", "prev unit", "unit vừa rồi", "lùi unit",
            "previousunit", "prevunit", "chuyển unit trước", "chuyen unit truoc"
        ).any { t.contains(it) }
        if (!nextHit && !prevHit) return false
        val now = System.currentTimeMillis()
        if (now - lastLocalShiftUnitMs < 4_000L) {
            LogUtils.i(TAG, "STT local shift_unit debounce")
            return true
        }
        lastLocalShiftUnitMs = now
        val dir = if (nextHit) "next" else "prev"
        val r = selfControlShiftUnit(dir)
        LogUtils.i(TAG, "STT local shift_unit ($dir): $r")
        return true
    }

    /**
     * Đổi transport từ UI (sau khi OTA đã có mqtt config nếu chọn MQTT).
     * Gọi từ MainActivity — swap session trên recognizer đang chạy.
     */
    @JvmStatic
    fun applyTransportFromUi(@Suppress("UNUSED_PARAMETER") context: Context): String {
        val transport = XiaozhiTransportPreference.get(context)
        val newLabel = when {
            transport == XiaozhiTransportType.MQTT && XiaozhiMqttConfigStore.hasConfig() ->
                "MQTT + UDP"
            else -> "WebSocket"
        }
        val old = xiaozhiSessionRef
        if (old != null && old.getTransportLabel() == newLabel) {
            LogUtils.i(
                TAG,
                "applyTransportFromUi: giữ session $newLabel (1 transport — giống ChatViewModel)",
            )
            return newLabel
        }
        try {
            old?.dispose()
        } catch (e: Exception) {
            LogUtils.w(TAG, "dispose old session: ${e.message}")
        }
        val session = createXiaozhiSessionManager(xiaozhiAudioSessionId)
        xiaozhiSessionRef = session
        (recognizer as? DemoRecognizer)?.updateXiaozhiSession(session)
        LogUtils.i(TAG, "applyTransportFromUi: đổi transport → $newLabel (WS/MQTT tách bạch)")
        retryWakeAfterTransportSwapIfNeeded()
        return newLabel
    }

    /** Sau OTA đổi WS→MQTT: wake trước đó bị dispose/cancel — mở lại kênh. */
    private fun retryWakeAfterTransportSwapIfNeeded() {
        val wakeAge = System.currentTimeMillis() - lastWakeAtMs
        if (wakeAge > 20_000L) return
        val session = xiaozhiSessionRef ?: return
        LogUtils.i(
            TAG,
            "Sau swap transport (${wakeAge}ms từ wake) – thử lại hey mini trên ${session.getTransportLabel()}",
        )
        Handler(Looper.getMainLooper()).postDelayed({
            (recognizer as? DemoRecognizer)?.startRecognitionAfterWakeup(true)
        }, 800)
    }

    /**
     * Boot OTA: lấy mqtt config nếu có. Chỉ hiện mã trên UI khi server trả activation
     * (thiết bị chưa bind). Không hiện mắt; không tạo mã local giả khi timeout.
     * Đổi MAC / ApplyDeviceIdentity — không gọi lại hàm này (giống ESP32).
     */
    private fun runXiaozhiOtaAndShowActivation() {
        try {
            XiaozhiActivationStore.init(appContext)
            val (deviceId, clientId) = obtainXiaozhiIds()
            val otaUrl = "https://api.tenclass.net/xiaozhi/ota/"
            val deviceInfoJson = DummyDataGenerator.generate(deviceId, clientId).toJson()
            val client = XiaozhiOtaClient(appContext, deviceId, clientId)
            XiaozhiOtaActivationCoordinator.bindClient(client)
            val ok = client.checkVersionBlocking(otaUrl, deviceInfoJson)
            client.otaResult?.mqttConfig?.let { cfg ->
                XiaozhiMqttConfigStore.set(cfg)
                LogUtils.i(
                    TAG,
                    "OTA mqtt config OK — uri=" +
                        info.dourok.voicebot.protocol.MqttProtocol.buildMqttServerUri(cfg.endpoint) +
                        " publish=${cfg.publishTopic} subscribe=${cfg.subscribeTopic}"
                )
                LogUtils.i(
                    TAG,
                    "OTA mqtt config đã lưu — không dispose session. " +
                        "Chọn MQTT trên UI hoặc khởi động lại app để dùng MQTT.",
                )
            }
            if (!ok) {
                // ESP32 cũng không invent mã local khi CheckVersion fail — không vẽ mắt / không spam UI.
                LogUtils.w(TAG, "Xiaozhi OTA check failed — bỏ qua hiện mã (đã activate thì WS vẫn dùng được)")
                return
            }
            val activation = client.otaResult?.activation
            if (activation == null || activation.code.isEmpty()) {
                // Không có activation trong OTA = server coi device đã bind — giống ESP32 skip UI.
                XiaozhiActivationStore.markActivated()
                LogUtils.i(TAG, "OTA không trả activation — coi như đã kích hoạt, không hiện mã")
                return
            }
            LogUtils.i(
                TAG,
                "Xiaozhi activation code: ${activation.code} challenge=" +
                    if (activation.challenge.isEmpty()) "MISSING" else "ok"
            )
            // Chỉ UI (MainActivity TextView), không hiện trên mắt robot.
            ActivationEyeDisplay.showCodeOnUiOnly(activation.code)
            XiaozhiOtaActivationCoordinator.startPollIfNeeded("sau OTA")
        } catch (e: Exception) {
            LogUtils.e(TAG, "runXiaozhiOtaAndShowActivation error: $e")
        }
    }

    /**
     * Start sending microphone audio to Xiaozhi over WebSocket,
     * without requiring wakeup word. Call this when you want to
     * begin streaming (for example from a head-touch event).
     */
    fun startXiaozhiStreaming() {
        try {
            asrRecorder?.start()
            LogUtils.i("startXiaozhiStreaming: recorder started.")
        } catch (e: Exception) {
            LogUtils.e(TAG, "startXiaozhiStreaming error: $e")
        }
    }

    /**
     * Stop sending microphone audio to Xiaozhi.
     */
    fun stopXiaozhiStreaming() {
        try {
            asrRecorder?.stop()
            LogUtils.i("stopXiaozhiStreaming: recorder stopped.")
        } catch (e: Exception) {
            LogUtils.e(TAG, "stopXiaozhiStreaming error: $e")
        }
    }

    /** Khởi động lại recognition (hey mini / head-touch) – đóng WS cũ, mở mới, send listen. */
    fun restartXiaozhiRecognition() {
        ThreadPool.runOnNonUIThread {
            try {
                LogUtils.i(TAG, "restartXiaozhiRecognition: starting off main")
                (recognizer as? DemoRecognizer)?.startRecognitionAfterWakeup()
                LogUtils.i(TAG, "restartXiaozhiRecognition: done")
            } catch (e: Exception) {
                LogUtils.e(TAG, "restartXiaozhiRecognition error: $e", e)
            }
        }
    }

    /** Sau TTS stop: chỉ ensure mic đang chạy — không stop+start (Alpha Mini + ting → AudioFlinger chết). */
    private fun resumeXiaozhiMicAfterTts() {
        ThreadPool.runOnNonUIThread {
            try {
                LogUtils.i(TAG, "resumeXiaozhiMicAfterTts: ensure mic (không restart nếu đang chạy)")
                (recognizer as? DemoRecognizer)?.startRecognizingAfterTtsReconnect()
            } catch (e: Exception) {
                LogUtils.e(TAG, "resumeXiaozhiMicAfterTts error: $e", e)
            }
        }
    }

    /**
     * Chạm đầu:
     * - 1 lần → wake (giống hey mini)
     * - 2 lần liên tục → hiện IP Wi‑Fi trên mắt (máy gốc)
     * Logcat: HeadTouchEvent → onSingleClick / onDoubleClick
     */
    private fun setupHeadTouchTrigger(hostService: MasterSystemService, service: MasterSystemService) {
        try {
            HeadTouchEventHelper.subscribe(object : HeadTouchEventHelper.OnHeadTapListener {
                override fun onHeadTapDownInterrupt() {
                    if (ActivationEyeDisplay.isQrShowing()) return
                    // Ngắt loa NGAY khi chạm — không chờ 450ms phân single/double.
                    try {
                        xiaozhiSessionRef?.forceStopPlaybackForHeyMini()
                            ?: (recognizer as? DemoRecognizer)?.forceStopForHeyMini()
                    } catch (e: Exception) {
                        LogUtils.w(TAG, "head interrupt: ${e.message}")
                    }
                }

                override fun onHeadSingleTap(event: com.ubtrobot.mini.sysevent.event.base.KeyEvent?) {
                    // Không chặn wake vì QR — double-tap suppress chỉ ~1.6s trong HeadTouchEventHelper.
                    playWakeTingNow("head-tap wake")
                    Thread({
                        if (recognizer == null || xiaozhiSessionRef == null) {
                            pendingHeadWake = true
                            LogUtils.i(TAG, "[Head] speech chưa sẵn sàng – đã ting, xếp wake khi init xong")
                            return@Thread
                        }
                        // Kênh đóng: phải mở WS (log hay thấy “kênh chưa mở”).
                        LogUtils.i(
                            TAG,
                            "[Head] wake channelOpen=${xiaozhiSessionRef?.isAudioChannelOpened()}"
                        )
                        handleManualWake(hostService, service, fromHeadTouch = true, playTing = false)
                    }, "HeadWake").start()
                }

                override fun onHeadDoubleTap(event: com.ubtrobot.mini.sysevent.event.base.KeyEvent?) {
                    LogUtils.i(TAG, "[Head] double-tap → toggle QR")
                    try {
                        com.ubtrobot.mini.speech.framework.demo.wificonfig.WifiProvisionController
                            .onHeadDoubleTap(appContext)
                    } catch (e: Exception) {
                        LogUtils.w(TAG, "onHeadDoubleTap: ${e.message}", e)
                    }
                }
            })
            LogUtils.i(TAG, "Head-touch: single=wake, double=IP hoặc SoftAP QR")
        } catch (e: Exception) {
            LogUtils.w(TAG, "Head-touch subscribe failed: ${e.message}", e)
        }
    }

    private fun playWakeTingNow(reason: String) {
        // Giống 2b23f8d: WakeupAudioPlayer.play() (off-main để tránh ANR AudioPolicy).
        Thread({
            try {
                WakeupAudioPlayer.get(appContext).play()
                LogUtils.i(TAG, "[WakeWord] ting – $reason")
            } catch (e: Exception) {
                LogUtils.w(TAG, "ting wake: ${e.message}")
            }
        }, "WakeTing").start()
    }

    private fun flushPendingHeadWake(hostService: MasterSystemService, service: MasterSystemService) {
        if (!pendingHeadWake) return
        if (recognizer == null || xiaozhiSessionRef == null) return
        pendingHeadWake = false
        LogUtils.i(TAG, "[Head] flush pending wake (init vừa xong)")
        Thread({
            handleManualWake(hostService, service, fromHeadTouch = true, playTing = false)
        }, "HeadWakeFlush").start()
    }

    /** Create a minimal WakeUp for ACTION_WAKE_UP when Porcupine/head-touch passes null. */
    private fun createDummyWakeUp(): WakeUp? {
        val parcel = Parcel.obtain()
        return try {
            parcel.setDataPosition(0)
            val creator = WakeUp::class.java.getField("CREATOR").get(null)
            val createFromParcel = creator.javaClass.getMethod("createFromParcel", Parcel::class.java)
            @Suppress("UNCHECKED_CAST")
            createFromParcel.invoke(creator, parcel) as? WakeUp
        } catch (e: Exception) {
            LogUtils.w(TAG, "createDummyWakeUp failed: ${e.message}")
            null
        } finally {
            parcel.recycle()
        }
    }

  /** Chạm đầu hoặc hey mini — forceReconnect chỉ khi chạm đầu (đúng 2b23f8d). */
  private fun handleManualWake(
      hostService: MasterSystemService,
      service: MasterSystemService,
      fromHeadTouch: Boolean,
      playTing: Boolean = true
  ) {
    if (fromHeadTouch) {
      wakeUpDetectorRef?.lastDetectedKeyword = "HEY MINI"
    }
    handleWakeup(hostService, null, service, forceReconnect = fromHeadTouch, playTing = playTing)
  }

    private fun handleWakeup(hostService: MasterSystemService,
            wakeUp: WakeUp?,
            service: MasterSystemService,
            forceReconnect: Boolean = false,
            playTing: Boolean = true) {
        val kw = wakeUpDetectorRef?.lastDetectedKeyword?.takeIf { it.isNotBlank() } ?: "wake word"
        lastWakeAtMs = System.currentTimeMillis()
        LogUtils.i(TAG, "[WakeWord] handleWakeup – detected \"$kw\", force dừng phát + publish + start recognition")
        // Thứ tự 2b23f8d: ting trước → suppress → forceStop → publish → startRecognition.
        if (playTing && xiaozhiSessionRef != null) {
            try {
                WakeupAudioPlayer.get(appContext).play()
                LogUtils.i(TAG, "[WakeWord] ting – đánh thức OK (ngay khi wake)")
            } catch (e: Exception) {
                LogUtils.w(TAG, "ting wake: ${e.message}")
            }
        }
        // Không clearSuppressWake ở đây — tránh KWS nghe echo/ting/TTS chào (clear sau ting trong onFirstGreetingMicReady).
        wakeUpDetectorRef?.suppressWakeFor(14_000L)
        (recognizer as? DemoRecognizer)?.forceStopForHeyMini()
        LogUtils.i(TAG, "[WakeWord] publish SPEECH_WAKEUP + ACTION_WAKE_UP")
        hostService.publishCarefully(
                ServiceConstants.ACTION_SPEECH_WAKEUP,
                ProtoParam.create(
                        WakeupParam.newBuilder().build()))
        val wakeUpForPublish = wakeUp ?: createDummyWakeUp()
        if (wakeUpForPublish != null) {
            hostService.publishCarefully(SpeechConstants.ACTION_WAKE_UP,
                    ParcelableParam.create(wakeUpForPublish))
            LOGGER.w("ACTION_WAKE_UP published, recognizer should start.")
        } else {
            LogUtils.e(TAG, "ACTION_WAKE_UP NOT published (dummy WakeUp failed), recognizer may not start.")
        }
        LogUtils.i(TAG, "[WakeWord] startRecognitionAfterWakeup → onWake")
        (recognizer as? DemoRecognizer)?.startRecognitionAfterWakeup(forceReconnect)

        if (playTing && xiaozhiSessionRef == null) {
            try {
                WakeupAudioPlayer.get(appContext).play()
                LogUtils.i(TAG, "[WakeWord] ting – đánh thức (không Xiaozhi)")
            } catch (e: Exception) {
                LogUtils.w(TAG, "ting wake: ${e.message}")
            }
        }
        ThreadPool.runOnNonUIThread {
            MotorApi.get().clearProtectFlag(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
                    14)
        }
    }

    override fun createSpeechService(): CompositeSpeechService? {
        return speechServiceStub
    }

    override fun createSpeechSettings(): SpeechSettingStub {
        return speechSettingStub
    }

    override fun refreshUnderstanderCode(token: AccessToken?, callback: Callback?) {
        if (!TextUtils.isEmpty(token!!.code) && !TextUtils.isEmpty(token.codeVerifier)) {
            LOGGER.i("refresh Code: $token.code, codeVerifier:$token.codeVerifier")
            //todo
        }
    }
}

