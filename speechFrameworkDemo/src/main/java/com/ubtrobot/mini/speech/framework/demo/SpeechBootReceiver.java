package com.ubtrobot.mini.speech.framework.demo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * Robot bật nguồn → tự chạy speech demo sau khi hệ thống UBT/Master ổn định.
 */
public class SpeechBootReceiver extends BroadcastReceiver {
    private static final String TAG = "SpeechBoot";
    /** Master + ROM cần vài chục giây sau BOOT trước khi bind service ổn định. */
    private static final long BOOT_DELAY_MS = 25_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        SpeechLog.bootI(TAG, "boot " + action + " → start sau " + (BOOT_DELAY_MS / 1000) + "s");
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                SpeechBootstrap.startOnce(app);
                SpeechLog.bootI(TAG, "auto-start sau boot");
            } catch (Throwable t) {
                SpeechLog.bootE(TAG, "auto-start sau boot", t);
            } finally {
                pending.finish();
            }
        }, Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ? 3_000L : BOOT_DELAY_MS);
    }
}
