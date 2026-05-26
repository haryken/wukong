package com.ubtrobot.mini.speech.framework.demo;

import com.ubtech.utilcode.utils.thread.ThreadPool;
import com.ubtrobot.master.service.MasterSystemService;

public class DemoMasterService extends MasterSystemService {
  @Override protected void onServiceCreate() {
    super.onServiceCreate();
    // DemoSpeech.init() block 10-25s (DingDang/ggbond load). Run in background to avoid ANR.
    android.util.Log.i("DemoMaster", "onServiceCreate – starting DemoSpeech.init (sherpa KWS wake word)");
    ThreadPool.runOnNonUIThread(() -> {
      try {
        DemoSpeech.INSTANCE.init(this);
        android.util.Log.i("DemoMaster", "DemoSpeech.init returned");
      } catch (Throwable t) {
        android.util.Log.e("DemoMaster", "DemoSpeech.init crashed", t);
      }
    });
  }

  @Override protected void onServiceDestroy() {
    super.onServiceDestroy();
  }
}
