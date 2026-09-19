package com.ubtrobot.mini.speech.framework.demo;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import com.ubtrobot.mini.speech.framework.WakeupAudioPlayer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ting an toàn: không bao giờ gọi {@link WakeupAudioPlayer#play()} trên main thread.
 * Khi AudioFlinger/AudioPolicy chết, {@code play()} block vô hạn trên main → ANR.
 */
public final class SafeWakeTing {
  private static final String TAG = "SafeWakeTing";
  private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "SafeWakeTing");
    t.setDaemon(true);
    return t;
  });
  private static final AtomicBoolean queuedOrPlaying = new AtomicBoolean(false);

  private SafeWakeTing() {}

  /** Luôn async — an toàn gọi từ main / head-tap / coroutine. */
  public static void playAsync(Context context, String reason) {
    if (context == null) return;
    final Context app = context.getApplicationContext();
    final String why = reason != null ? reason : "ting";
    if (!queuedOrPlaying.compareAndSet(false, true)) {
      Log.d(TAG, "ting skip (đang phát/queue) – " + why);
      return;
    }
    EXEC.execute(() -> {
      try {
        if (Looper.myLooper() == Looper.getMainLooper()) {
          Log.e(TAG, "BUG: SafeWakeTing chạy trên main – bỏ play");
          return;
        }
        WakeupAudioPlayer.get(app).play();
        Log.i(TAG, "ting OK – " + why);
      } catch (Throwable t) {
        Log.w(TAG, "ting fail (" + why + "): " + t.getMessage()
            + " – nếu AudioFlinger chết thì reboot robot");
      } finally {
        queuedOrPlaying.set(false);
      }
    });
  }
}
