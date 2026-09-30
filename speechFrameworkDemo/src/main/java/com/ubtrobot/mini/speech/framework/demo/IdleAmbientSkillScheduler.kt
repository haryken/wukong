package com.ubtrobot.mini.speech.framework.demo

import android.util.Log
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hành động ngẫu nhiên **chỉ khi không giao tiếp** (WS đóng, không TTS/nhạc):
 * - Thỉnh thoảng: FART / DOZE / BURP / SNEEZE
 * - Đứng chờ đủ lâu → POWER_SAVING
 *
 * Khi đang chat: chỉ LLM emotion → skill + mắt map (không chạy scheduler này).
 */
object IdleAmbientSkillScheduler {
    private const val TAG = "IdleAmbientSkill"
    private const val MIN_GAP_MS = 55_000L
    private const val MAX_GAP_MS = 140_000L
    private const val INITIAL_DELAY_MS = 40_000L
    private const val POWER_SAVING_AFTER_IDLE_MS = 270_000L
    private const val POWER_SAVING_COOLDOWN_MS = 600_000L
    /** Sau khi hết chat / đóng WS — chờ thêm rồi mới ambient. */
    private const val IDLE_GRACE_AFTER_CHAT_MS = 25_000L

    private val SHORT_SKILL_CANDIDATES = arrayOf(
        arrayOf("FART", "BREAK_WIND", "break_wind"),
        arrayOf("DOZE", "doze"),
        arrayOf("BURP", "burp"),
        arrayOf("SNEEZE", "sneeze")
    )

    private val POWER_SAVING_CANDIDATES = arrayOf(
        "POWER_SAVING",
        "power_saving",
        "how_rest",
        "HOW_REST"
    )

    private val random = Random()
    private val started = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "IdleAmbientSkill").apply { isDaemon = true }
    }
    @Volatile private var future: ScheduledFuture<*>? = null
    @Volatile private var sessionProvider: (() -> XiaozhiSessionApi?)? = null
    @Volatile private var idleSinceMs = 0L
    @Volatile private var lastPowerSavingAtMs = 0L
    @Volatile private var lastCommunicatingAtMs = 0L

    fun start(sessionProvider: () -> XiaozhiSessionApi?) {
        this.sessionProvider = sessionProvider
        if (!started.compareAndSet(false, true)) {
            Log.i(TAG, "already started")
            return
        }
        Log.i(
            TAG,
            "start – ambient chỉ khi KHÔNG giao tiếp; gap ${MIN_GAP_MS / 1000}–${MAX_GAP_MS / 1000}s; " +
                "POWER_SAVING sau idle ${POWER_SAVING_AFTER_IDLE_MS / 1000}s; grace sau chat ${IDLE_GRACE_AFTER_CHAT_MS / 1000}s"
        )
        scheduleNext(INITIAL_DELAY_MS)
    }

    fun stop() {
        started.set(false)
        future?.cancel(false)
        future = null
        idleSinceMs = 0L
        Log.i(TAG, "stopped")
    }

    @JvmStatic
    fun noteCommunicating() {
        lastCommunicatingAtMs = System.currentTimeMillis()
        idleSinceMs = 0L
    }

    private fun scheduleNext(delayMs: Long) {
        if (!started.get()) return
        future?.cancel(false)
        future = exec.schedule({
            tryTick()
            scheduleNext(nextGapMs())
        }, delayMs.coerceAtLeast(5_000L), TimeUnit.MILLISECONDS)
    }

    private fun nextGapMs(): Long {
        val span = (MAX_GAP_MS - MIN_GAP_MS).coerceAtLeast(1L)
        return MIN_GAP_MS + (random.nextLong() % span + span) % span
    }

    private fun tryTick() {
        if (!started.get()) return
        if (!isIdleStandby()) {
            if (idleSinceMs != 0L) {
                Log.d(TAG, "skip ambient – đang giao tiếp / chưa idle")
            }
            idleSinceMs = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (idleSinceMs == 0L) {
            idleSinceMs = now
            Log.d(TAG, "bắt đầu đếm đứng chờ (không giao tiếp)")
        }
        val idleFor = now - idleSinceMs
        val powerReady = idleFor >= POWER_SAVING_AFTER_IDLE_MS
                && now - lastPowerSavingAtMs >= POWER_SAVING_COOLDOWN_MS

        if (powerReady) {
            if (tryPlayCandidates(POWER_SAVING_CANDIDATES, "POWER_SAVING")) {
                lastPowerSavingAtMs = now
                return
            }
            Log.w(TAG, "POWER_SAVING không chạy được trên ROM – fallback ambient ngắn")
        }

        val group = SHORT_SKILL_CANDIDATES[random.nextInt(SHORT_SKILL_CANDIDATES.size)]
        if (!tryPlayCandidates(group, "short")) {
            Log.w(TAG, "idle ambient thất bại pool=${group.joinToString("/")}")
        }
    }

    private fun tryPlayCandidates(names: Array<String>, tag: String): Boolean {
        // Double-check: không chen khi vừa vào chat.
        if (!isIdleStandby()) return false
        for (name in names) {
            try {
                MiniRobotActionInvoker.suppressLlmEmotionForRobotAction(20_000L)
                val ok = MiniRobotActionInvoker.tryStartSkillApiOnly(name)
                if (ok) {
                    Log.i(TAG, "idle ambient [$tag] → $name (không giao tiếp)")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "try $name: ${e.message}")
            }
        }
        return false
    }

    /**
     * Idle = không giao tiếp: WS đóng, không TTS/chào/wake gần, không nhạc/QR/explore,
     * và đã qua grace sau chat.
     */
    private fun isIdleStandby(): Boolean {
        val now = System.currentTimeMillis()
        try {
            if (OttoMusicPlayer.isPlaying()) {
                lastCommunicatingAtMs = now
                return false
            }
        } catch (_: Throwable) {
        }
        try {
            if (ActivationEyeDisplay.isQrShowing()) return false
            if (ActivationEyeDisplay.isMusicExpressActive()) {
                lastCommunicatingAtMs = now
                return false
            }
        } catch (_: Throwable) {
        }
        try {
            if (ExploreModeController.isActive()) {
                lastCommunicatingAtMs = now
                return false
            }
        } catch (_: Throwable) {
        }
        val session = try {
            sessionProvider?.invoke()
        } catch (_: Throwable) {
            null
        }
        if (session != null) {
            try {
                // Đang mở kênh / TTS / vừa wake = đang giao tiếp → chỉ LLM map, không ambient.
                if (session.isAudioChannelOpened()
                    || session.isPlaybackOrGreetingActive()
                    || session.wasWakeHandledRecently()
                ) {
                    lastCommunicatingAtMs = now
                    return false
                }
            } catch (_: Throwable) {
            }
        }
        if (lastCommunicatingAtMs != 0L && now - lastCommunicatingAtMs < IDLE_GRACE_AFTER_CHAT_MS) {
            return false
        }
        return true
    }
}
