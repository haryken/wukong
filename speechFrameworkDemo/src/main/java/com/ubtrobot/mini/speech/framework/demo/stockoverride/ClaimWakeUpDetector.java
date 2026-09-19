package com.ubtrobot.mini.speech.framework.demo.stockoverride;

import android.util.Log;

import com.ubtrobot.speech.AbstractWakeUpDetector;

/**
 * Chỉ chiếm slot WakeUp của Master — không nghe mic, không đụng Xiaozhi/Sherpa.
 * Speech hệ thống bị cắt mic nhờ {@code ubt-master-app=third_part_speechservice};
 * module này khiến Master bind vào APK này thay vì speech stock.
 */
final class ClaimWakeUpDetector extends AbstractWakeUpDetector {
  private static final String TAG = "StockSpeechOverride";

  ClaimWakeUpDetector() {
    Log.i(TAG, "ClaimWakeUpDetector sẵn sàng (no-op – Xiaozhi tự wake)");
  }
}
