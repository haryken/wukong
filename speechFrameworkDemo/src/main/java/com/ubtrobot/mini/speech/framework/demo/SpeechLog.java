package com.ubtrobot.mini.speech.framework.demo;

import android.util.Log;

/**
 * Log bootstrap / keep-alive — mặc định tắt để không tràn logcat (hey mini, Xiaozhi vẫn log tag riêng).
 * Bật tạm khi debug service: đặt {@link #VERBOSE_BOOT} = true rồi build lại.
 */
public final class SpeechLog {
    /** Đặt true chỉ khi cần xem start service / boot. */
    public static final boolean VERBOSE_BOOT = false;

    private SpeechLog() {}

    public static void bootD(String tag, String msg) {
        if (VERBOSE_BOOT) {
            Log.d(tag, msg);
        }
    }

    public static void bootI(String tag, String msg) {
        if (VERBOSE_BOOT) {
            Log.i(tag, msg);
        }
    }

    public static void bootW(String tag, String msg) {
        if (VERBOSE_BOOT) {
            Log.w(tag, msg);
        }
    }

    public static void bootE(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
    }
}
