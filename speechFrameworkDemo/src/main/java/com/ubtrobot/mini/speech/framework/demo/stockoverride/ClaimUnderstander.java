package com.ubtrobot.mini.speech.framework.demo.stockoverride;

import android.util.Log;

import com.ubtrobot.async.Deferred;
import com.ubtrobot.speech.AbstractUnderstander;
import com.ubtrobot.speech.UnderstandingException;
import com.ubtrobot.speech.UnderstandingOption;
import com.ubtrobot.speech.UnderstandingResult;

/** Chiếm slot NLP Master — NLP thật do Xiaozhi cloud. */
final class ClaimUnderstander extends AbstractUnderstander {
  private static final String TAG = "StockSpeechOverride";

  @Override
  protected void understand(
      UnderstandingOption understandingOption,
      Deferred<UnderstandingResult, UnderstandingException> deferred) {
    Log.d(TAG, "ClaimUnderstander.understand – bỏ qua (Xiaozhi NLP)");
    // Không resolve/reject — Master không dùng NLP stock; Xiaozhi tự xử lý.
  }
}
