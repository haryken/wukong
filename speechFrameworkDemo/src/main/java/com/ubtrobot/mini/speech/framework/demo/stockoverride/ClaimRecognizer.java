package com.ubtrobot.mini.speech.framework.demo.stockoverride;

import android.util.Log;

import com.ubtrobot.speech.AbstractRecognizer;
import com.ubtrobot.speech.RecognitionOption;

/**
 * Chiếm slot Recognizer Master — không ASR. Xiaozhi dùng AudioRecord riêng.
 */
final class ClaimRecognizer extends AbstractRecognizer {
  private static final String TAG = "StockSpeechOverride";

  @Override
  protected void startRecognizing(RecognitionOption recognitionOption) {
    Log.d(TAG, "ClaimRecognizer.startRecognizing – bỏ qua (Xiaozhi tự nghe)");
  }

  @Override
  protected void stopRecognizing() {
    Log.d(TAG, "ClaimRecognizer.stopRecognizing – no-op");
  }
}
