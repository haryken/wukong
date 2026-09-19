package com.ubtrobot.mini.speech.framework.demo

import android.util.Log
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Khi robot đứng chờ (không WS chat, không nhạc, không QR/explore):
 * - Thỉnh thoảng: {@code FART}/{@code DOZE}/{@code BURP}/{@code SNEEZE}
 * - Đứng chờ đủ lâu → {@code POWER_SAVING}
 */
object IdleAmbientSkillScheduler {
    private const val TAG = "IdleAmbientSkill"
    /** Khoảng cách tối thiểu giữa 2 lần ambient ngắn. */
    private const val MIN_GAP_MS = 55_000L
    /** Khoảng cách tối đa (random trong [MIN, MAX]). */
    private const val MAX_GAP_MS = 140_000L
    /** Chờ sau khi start trước lần ambient đầu. */
    private const val INITIAL_DELAY_MS = 40_000L
    /**
     * Đứng chờ liên tục ≥ ngưỡng này → ưu tiên POWER_SAVING thay vì fart/doze/burp/sneeze.
     * (~4.5 phút)
     */
    private const val POWER_SAVING_AFTER_IDLE_MS = 270_000L
    /** Không spam POWER_SAVING — tối thiểu giữa 2 lần. */
    private const val POWER_SAVING_COOLDOWN_MS = 600_000L

    /** Ambient ngắn — thử lần lượt tên enum/intent ROM. */
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
    /** Thời điểm bắt đầu chuỗi idle liên tục (0 = đang không idle). */
    @Volatile private var idleSinceMs = 0L
    @Volatile private var lastPowerSavingAtMs = 0L

    fun start(sessionProvider: () -> XiaozhiSessionApi?) {
        this.sessionProvider = sessionProvider
        if (!started.compareAndSet(false, true)) {
            Log.i(TAG, "already started")
            return
        }
        Log.i(
            TAG,
            "start – ambient FART/DOZE/BURP/SNEEZE gap ${MIN_GAP_MS / 1000}–${MAX_GAP_MS / 1000}s; " +
                "POWER_SAVING sau idle ${POWER_SAVING_AFTER_IDLE_MS / 1000}s"
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
                Log.d(TAG, "rời idle standby – reset đồng hồ đứng chờ")
            }
            idleSinceMs = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (idleSinceMs == 0L) {
            idleSinceMs = now
            Log.d(TAG, "bắt đầu đếm đứng chờ idle")
        }
        val idleFor = now - idleSinceMs
        val powerReady = idleFor >= POWER_SAVING_AFTER_IDLE_MS
                && now - lastPowerSavingAtMs >= POWER_SAVING_COOLDOWN_MS

        if (powerReady) {
            if (tryPlayCandidates(POWER_SAVING_CANDIDATES, "POWER_SAVING")) {
                lastPowerSavingAtMs = now
                // Sau power saving coi như vẫn idle — không reset idleSince (đứng tiếp).
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
        for (name in names) {
            try {
                MiniRobotActionInvoker.suppressLlmEmotionForRobotAction(20_000L)
                val ok = MiniRobotActionInvoker.tryStartSkillApiOnly(name)
                if (ok) {
                    Log.i(TAG, "idle ambient [$tag] → $name")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "try $name: ${e.message}")
            }
        }
        return false
    }

    /**
     * Đứng chờ: không nhạc, không QR, không explore, không TTS/chào,
     * kênh Xiaozhi đóng (chờ hey mini / chạm đầu).
     */
    private fun isIdleStandby(): Boolean {
        try {
            if (OttoMusicPlayer.isPlaying()) return false
        } catch (_: Throwable) {
        }
        try {
            if (ActivationEyeDisplay.isQrShowing()) return false
            if (ActivationEyeDisplay.isMusicExpressActive()) return false
        } catch (_: Throwable) {
        }
        try {
            if (ExploreModeController.isActive()) return false
        } catch (_: Throwable) {
        }
        val session = try {
            sessionProvider?.invoke()
        } catch (_: Throwable) {
            null
        }
        if (session != null) {
            try {
                if (session.isPlaybackOrGreetingActive()) return false
                if (session.wasWakeHandledRecently()) return false
                if (session.isAudioChannelOpened()) return false
            } catch (_: Throwable) {
            }
        }
        return true
    }
}
