package com.ubtrobot.mini.speech.framework.demo.stockoverride;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Service riêng: chỉ đảm bảo claim Speech Framework / log mark đè speech stock.
 * Không chứa Xiaozhi. Start từ {@link com.ubtrobot.mini.speech.framework.demo.SpeechBootstrap}.
 */
public class StockSpeechOverrideService extends Service {
  private static final String TAG = StockSpeechOverride.TAG;

  public static void start(Context context) {
    if (context == null) return;
    try {
      context.getApplicationContext()
          .startService(new Intent(context.getApplicationContext(), StockSpeechOverrideService.class));
    } catch (Throwable t) {
      Log.w(TAG, "start StockSpeechOverrideService: " + t.getMessage());
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    // Ép tạo CompositeSpeechService claim sớm (trước khi Xiaozhi boot xong).
    StockSpeechOverride.get(this);
    StockSpeechOverride.logClaimStatus(this);
    Log.i(TAG, "StockSpeechOverrideService onCreate – slot SpeechService đã claim");
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    StockSpeechOverride.logClaimStatus(this);
    return START_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
