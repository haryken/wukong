package com.ubtrobot.mini.speech.framework.demo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

/**
 * Chỉ giữ process (foreground). <b>Không</b> start DemoMaster / mic — đã do {@link SpeechBootstrap}.
 */
public class SpeechKeepAliveService extends Service {
    private static final String TAG = "SpeechKeepAlive";
    private static final int NOTIFICATION_ID = 0x5A01;
    private static final String CHANNEL_ID = "speech_demo_keepalive";

    @Override
    public void onCreate() {
        super.onCreate();
        SpeechServiceLauncher.setKeepAliveRunning(true);
        startInForeground();
        SpeechLog.bootD(TAG, "onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        SpeechServiceLauncher.setKeepAliveRunning(false);
        SpeechLog.bootD(TAG, "onDestroy");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.keepalive_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.keepalive_channel_desc));
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.keepalive_notification_title))
                .setContentText(getString(R.string.keepalive_notification_text))
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }
}
