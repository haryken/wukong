package com.ubtrobot.mini.speech.framework.demo;

import android.content.Context;

/**
 * @deprecated Dùng {@link SpeechBootstrap#startOnce} — giữ alias để không vỡ chỗ gọi cũ.
 */
public final class SpeechServiceLauncher {
    private SpeechServiceLauncher() {}

    public static void startSpeechStack(Context context) {
        SpeechBootstrap.startOnce(context);
    }

    static void setKeepAliveRunning(boolean running) {
        KeepAliveState.setRunning(running);
    }

    static boolean isKeepAliveMarkedOrRunning(Context context) {
        return KeepAliveState.isMarkedOrRunning(context);
    }

    /** Trạng thái keep-alive (tách file để Bootstrap không phụ thuộc vòng). */
    static final class KeepAliveState {
        private static volatile boolean marked;

        private KeepAliveState() {}

        static void setRunning(boolean running) {
            marked = running;
        }

        static boolean isMarkedOrRunning(Context context) {
            if (marked) return true;
            return SpeechBootstrap.isServiceRunning(
                    context.getApplicationContext(), SpeechKeepAliveService.class);
        }
    }
}
