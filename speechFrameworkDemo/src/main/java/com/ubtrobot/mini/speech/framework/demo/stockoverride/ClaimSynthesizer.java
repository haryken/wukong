package com.ubtrobot.mini.speech.framework.demo.stockoverride;

import android.util.Log;

import com.ubtrobot.speech.AbstractSynthesizer;
import com.ubtrobot.speech.SpeakingVoice;
import com.ubtrobot.speech.SynthesisOption;

import java.util.Collections;
import java.util.List;

/** Chiếm slot TTS Master — TTS thật do Xiaozhi. */
final class ClaimSynthesizer extends AbstractSynthesizer {
  private static final String TAG = "StockSpeechOverride";

  @Override
  protected void startSynthesizing(SynthesisOption synthesisOption) {
    Log.d(TAG, "ClaimSynthesizer.start – bỏ qua (Xiaozhi TTS)");
  }

  @Override
  protected void stopSynthesizing() {
  }

  @Override
  public List<SpeakingVoice> getSpeakingVoiceList() {
    return Collections.emptyList();
  }
}
