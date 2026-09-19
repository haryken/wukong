package com.ubtrobot.mini.speech.framework.demo;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.ubtrobot.mini.speech.framework.MicrophoneArrayService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Giống develop2222 / 2b23f8d: DemoMaster + MicrophoneArrayService (AAR, process :speech) + keep-alive.
 */
public final class SpeechBootstrap {
    private static final String TAG = "SpeechBootstrap";

    private static final AtomicBoolean started = new AtomicBoolean(false);

    private SpeechBootstrap() {}

    public static void startOnce(Context context) {
        if (context == null) return;
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Context app = context.getApplicationContext();
        Log.i(TAG, "khởi động speech stack (DemoMaster + MicArray AAR)");

        // Encode sẵn cặp mắt Self-Control (IP+QR) trước khi Xiaozhi sẵn sàng.
        try {
            ActivationEyeDisplay.startBootSelfControlEyeWarm(app);
        } catch (Throwable t) {
            Log.w(TAG, "boot eye warm: " + t.getMessage());
        }

        startIfNotRunning(app, DemoMasterService.class);
        startIfNotRunning(app, MicrophoneArrayService.class);
        startKeepAliveOnce(app);
        try {
            com.ubtrobot.mini.speech.framework.demo.wificonfig.WifiProvisionController.start(app);
        } catch (Throwable t) {
            Log.w(TAG, "WifiProvisionController: " + t.getMessage());
        }
    }

    private static void startIfNotRunning(Context app, Class<?> serviceClass) {
        if (isServiceRunning(app, serviceClass)) {
            return;
        }
        try {
            app.startService(new Intent(app, serviceClass));
            Log.i(TAG, "startService " + serviceClass.getSimpleName());
        } catch (Throwable t) {
            Log.e(TAG, "startService " + serviceClass.getSimpleName(), t);
        }
    }

    private static void startKeepAliveOnce(Context app) {
        if (SpeechServiceLauncher.isKeepAliveMarkedOrRunning(app)) {
            return;
        }
        try {
            Intent keep = new Intent(app, SpeechKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(keep);
            } else {
                app.startService(keep);
            }
        } catch (Throwable t) {
            Log.e(TAG, "start keep-alive", t);
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
