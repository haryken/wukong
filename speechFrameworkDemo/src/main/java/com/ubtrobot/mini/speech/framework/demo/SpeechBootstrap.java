package com.ubtrobot.mini.speech.framework.demo;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.ubtrobot.mini.speech.framework.MicrophoneArrayService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Khởi động speech stack <b>một lần</b> mỗi process — tránh startService lặp (log bootstrap tắt mặc định).
 */
public final class SpeechBootstrap {
    private static final String TAG = "SpeechBootstrap";

    private static final AtomicBoolean started = new AtomicBoolean(false);

    private SpeechBootstrap() {}

    public static void startOnce(Context context) {
        if (context == null) return;
        if (!started.compareAndSet(false, true)) {
            SpeechLog.bootD(TAG, "đã khởi động — bỏ qua");
            return;
        }
        Context app = context.getApplicationContext();
        SpeechLog.bootI(TAG, "khởi động speech stack (verbose boot)");

        startIfNotRunning(app, DemoMasterService.class);
        startIfNotRunning(app, MicrophoneArrayService.class);
        startKeepAliveOnce(app);
    }

    private static void startIfNotRunning(Context app, Class<?> serviceClass) {
        if (isServiceRunning(app, serviceClass)) {
            SpeechLog.bootD(TAG, serviceClass.getSimpleName() + " đã chạy");
            return;
        }
        try {
            app.startService(new Intent(app, serviceClass));
            SpeechLog.bootD(TAG, "startService " + serviceClass.getSimpleName());
        } catch (Throwable t) {
            SpeechLog.bootE(TAG, "startService " + serviceClass.getSimpleName(), t);
        }
    }

    private static void startKeepAliveOnce(Context app) {
        if (SpeechServiceLauncher.isKeepAliveMarkedOrRunning(app)) {
            SpeechLog.bootD(TAG, "keep-alive đã có");
            return;
        }
        try {
            Intent keep = new Intent(app, SpeechKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(keep);
            } else {
                app.startService(keep);
            }
            SpeechLog.bootD(TAG, "start keep-alive");
        } catch (Throwable t) {
            SpeechLog.bootE(TAG, "start keep-alive", t);
        }
    }

    static boolean isServiceRunning(Context app, Class<?> serviceClass) {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            String want = serviceClass.getName();
            for (ActivityManager.RunningServiceInfo info : am.getRunningServices(128)) {
                if (info.service != null && want.equals(info.service.getClassName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
