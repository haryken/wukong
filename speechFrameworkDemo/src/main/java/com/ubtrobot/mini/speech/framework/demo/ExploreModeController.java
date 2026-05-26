package com.ubtrobot.mini.speech.framework.demo;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.util.Locale;
import java.util.Random;

/**
 * Chế độ khám phá: mỗi bước một skill (GO_AHEAD / BACK_UP / TURN_*).
 * Chờ ~7–8s cho skill chạy hết chu kỳ ROM, gọi stop, rồi mới bước kế.
 */
public final class ExploreModeController {
    private static final String TAG = "ExploreMode";

    private static final int ACTION_FORWARD = 0;
    private static final int ACTION_BACK = 1;
    private static final int ACTION_LEFT = 2;
    private static final int ACTION_RIGHT = 3;
    private static final int ACTION_PAUSE = 4;

    private static final double MAX_RADIUS = 5.0;
    private static final double SOFT_RADIUS = 3.8;
    private static final double FORWARD_STEP = 1.0;
    private static final double BACK_STEP = 0.5;
    private static final double TURN_DEG = 42.0;

    /** Chờ ROM chạy hết một chu kỳ skill (~7–8s) rồi mới stop và đổi hành động. */
    private static final long DURATION_FORWARD_MS = 7_800L;
    private static final long DURATION_BACK_MS = 7_500L;
    private static final long DURATION_TURN_MS = 7_500L;
    private static final long PAUSE_MS_MIN = 400L;
    private static final long PAUSE_MS_MAX = 800L;
    /** Sau stopSkill, chờ motor ổn rồi mới bước kế. */
    private static final long SETTLE_AFTER_STOP_MS = 450L;
    private static final long GAP_BETWEEN_MS = 350L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final HandlerThread workerThread = new HandlerThread("ExploreMode");
    private static final Handler worker;
    private static final Random random = new Random();

    static {
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    private static volatile boolean active;
    private static volatile boolean stopRequested;
    private static volatile boolean stepRunning;

    private static double netX;
    private static double netY;
    private static double headingDeg;
    private static int lastAction = -1;

    private ExploreModeController() {}

    public static boolean isActive() {
        return active;
    }

    public static void start() {
        worker.post(() -> {
            if (active) {
                Log.w(TAG, "Chế độ khám phá đang chạy");
                return;
            }
            active = true;
            stopRequested = false;
            stepRunning = false;
            netX = 0;
            netY = 0;
            headingDeg = 0;
            lastAction = -1;
            Log.i(TAG, "Bắt đầu khám phá — mỗi bước chờ ~7.5–7.8s rồi stop, rồi hành động kế");
            scheduleNext(600L);
        });
    }

    public static void stop() {
        worker.post(() -> {
            if (!active && !stopRequested) {
                return;
            }
            stopRequested = true;
            active = false;
            stepRunning = false;
            worker.removeCallbacksAndMessages(null);
            MAIN.post(MiniRobotActionInvoker::exploreApplyStopAll);
            Log.i(TAG, "Đã dừng khám phá (vị trí ảo x=" + fmt(netX) + " y=" + fmt(netY) + ")");
        });
    }

    public static boolean matchesStopExploreIntent(String text) {
        if (text == null || text.isEmpty()) return false;
        String t = text.toLowerCase(Locale.US).trim();
        if (!active) {
            return t.contains("dừng khám phá") || t.contains("dung kham pha")
                    || t.contains("tắt khám phá") || t.contains("tat kham pha")
                    || t.contains("stop explore") || t.contains("stop exploring")
                    || t.contains("thôi khám phá") || t.contains("thoi kham pha");
        }
        if (t.contains("khám phá") || t.contains("kham pha") || t.contains("explore")) {
            return t.contains("dừng") || t.contains("dung") || t.contains("tắt") || t.contains("tat")
                    || t.contains("stop") || t.contains("thôi") || t.contains("thoi")
                    || t.contains("ngừng") || t.contains("ngung");
        }
        if (t.contains("dừng lại") || t.contains("dung lai") || t.equals("dừng") || t.equals("stop")) {
            return true;
        }
        return false;
    }

    private static void scheduleNext(long delayMs) {
        if (!active || stopRequested) return;
        worker.postDelayed(ExploreModeController::runOneStep, delayMs);
    }

    private static void runOneStep() {
        if (!active || stopRequested) {
            active = false;
            MAIN.post(MiniRobotActionInvoker::exploreApplyStopAll);
            return;
        }
        if (stepRunning) {
            Log.w(TAG, "Bỏ qua runOneStep — bước trước chưa xong");
            return;
        }
        stepRunning = true;

        int action = pickAction();
        lastAction = action;

        if (action == ACTION_PAUSE) {
            long pause = PAUSE_MS_MIN + random.nextInt((int) (PAUSE_MS_MAX - PAUSE_MS_MIN + 1));
            Log.d(TAG, "Khám phá: nghỉ " + pause + "ms (không skill)");
            worker.postDelayed(() -> {
                stepRunning = false;
                scheduleNext(GAP_BETWEEN_MS);
            }, pause);
            return;
        }

        applyVirtualMotion(action);
        long duration = durationFor(action);

        MAIN.post(() -> applyExactlyOneSkill(action, duration));

        worker.postDelayed(() -> {
            if (!active || stopRequested) {
                stepRunning = false;
                return;
            }
            MAIN.post(() -> {
                Log.d(TAG, "Khám phá: stop locomotion sau " + duration + "ms");
                MiniRobotActionInvoker.exploreApplyStopLocomotion();
            });
            worker.postDelayed(() -> {
                stepRunning = false;
                if (!active || stopRequested) return;
                scheduleNext(GAP_BETWEEN_MS);
            }, SETTLE_AFTER_STOP_MS);
        }, duration);
    }

    private static void applyExactlyOneSkill(int action, long durationMs) {
        switch (action) {
            case ACTION_FORWARD:
                Log.i(TAG, "Khám phá: GO_AHEAD chờ " + durationMs + "ms rồi stop (r=" + fmt(radius()) + ")");
                MiniRobotActionInvoker.exploreApplyMove(true);
                break;
            case ACTION_BACK:
                Log.i(TAG, "Khám phá: BACK_UP chờ " + durationMs + "ms rồi stop (r=" + fmt(radius()) + ")");
                MiniRobotActionInvoker.exploreApplyMove(false);
                break;
            case ACTION_LEFT:
                Log.i(TAG, "Khám phá: TURN_LEFT chờ " + durationMs + "ms rồi stop (r=" + fmt(radius()) + ")");
                MiniRobotActionInvoker.exploreApplyTurn(true);
                break;
            case ACTION_RIGHT:
                Log.i(TAG, "Khám phá: TURN_RIGHT chờ " + durationMs + "ms rồi stop (r=" + fmt(radius()) + ")");
                MiniRobotActionInvoker.exploreApplyTurn(false);
                break;
            default:
                break;
        }
    }

    private static long durationFor(int action) {
        switch (action) {
            case ACTION_FORWARD:
                return DURATION_FORWARD_MS;
            case ACTION_BACK:
                return DURATION_BACK_MS;
            case ACTION_LEFT:
            case ACTION_RIGHT:
                return DURATION_TURN_MS;
            default:
                return DURATION_TURN_MS;
        }
    }

    private static int pickAction() {
        double r = radius();
        boolean far = r >= SOFT_RADIUS;

        int action;
        int roll = random.nextInt(100);
        if (far) {
            if (r >= MAX_RADIUS) {
                if (roll < 50) action = ACTION_BACK;
                else if (roll < 75) action = turnTowardOrigin();
                else action = ACTION_PAUSE;
            } else {
                if (roll < 35) action = ACTION_BACK;
                else if (roll < 55) action = ACTION_LEFT;
                else if (roll < 75) action = ACTION_RIGHT;
                else if (roll < 88) action = ACTION_FORWARD;
                else action = ACTION_PAUSE;
            }
        } else {
            if (roll < 48) action = ACTION_FORWARD;
            else if (roll < 66) action = ACTION_LEFT;
            else if (roll < 84) action = ACTION_RIGHT;
            else if (roll < 91) action = ACTION_BACK;
            else action = ACTION_PAUSE;
        }

        int guard = 0;
        while (action == lastAction && guard < 8) {
            action = (action + 1 + random.nextInt(3)) % 5;
            guard++;
        }
        return action;
    }

    private static int turnTowardOrigin() {
        if (Math.abs(netX) < 0.2 && Math.abs(netY) < 0.2) {
            return random.nextBoolean() ? ACTION_LEFT : ACTION_RIGHT;
        }
        double angleToOriginDeg = Math.toDegrees(Math.atan2(-netX, -netY));
        double delta = normalizeDeg(angleToOriginDeg - headingDeg);
        return delta >= 0 ? ACTION_LEFT : ACTION_RIGHT;
    }

    private static void applyVirtualMotion(int action) {
        switch (action) {
            case ACTION_FORWARD: {
                double rad = Math.toRadians(headingDeg);
                netX += Math.sin(rad) * FORWARD_STEP;
                netY += Math.cos(rad) * FORWARD_STEP;
                break;
            }
            case ACTION_BACK: {
                double rad = Math.toRadians(headingDeg);
                netX -= Math.sin(rad) * BACK_STEP;
                netY -= Math.cos(rad) * BACK_STEP;
                break;
            }
            case ACTION_LEFT:
                headingDeg = normalizeDeg(headingDeg + TURN_DEG);
                break;
            case ACTION_RIGHT:
                headingDeg = normalizeDeg(headingDeg - TURN_DEG);
                break;
            default:
                break;
        }
    }

    private static double radius() {
        return Math.sqrt(netX * netX + netY * netY);
    }

    private static double normalizeDeg(double d) {
        double x = d % 360.0;
        if (x > 180.0) x -= 360.0;
        if (x < -180.0) x += 360.0;
        return x;
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }
}
