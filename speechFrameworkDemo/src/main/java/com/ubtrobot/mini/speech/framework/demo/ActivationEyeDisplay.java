package com.ubtrobot.mini.speech.framework.demo;

import android.util.Log;

import com.ubtrobot.commons.Priority;
import com.ubtrobot.express.ExpressApi;
import com.ubtrobot.express.listeners.AnimationListener;

/**
 * Helper to display the 6-digit activation code on robot's face/eyes.
 *
 * This implementation assumes you have pre-defined express resources
 * for each digit: "digit_0" ... "digit_9", or you can customize the
 * mapping to your own express names.
 */
public class ActivationEyeDisplay {

  private static final String TAG = "ActivationEyeDisplay";

  public interface CodeDisplayListener {
    void onCodeReceived(String code);
  }

  private static volatile CodeDisplayListener uiListener;

  /** Set listener to show code on UI (e.g. MainActivity TextView). */
  public static void setCodeDisplayListener(CodeDisplayListener listener) {
    uiListener = listener;
  }

  private ActivationEyeDisplay() {
  }

  public static void showCode(String code) {
    if (code == null || code.length() == 0) {
      Log.w(TAG, "Activation code is empty");
      return;
    }

    CodeDisplayListener listener = uiListener;
    if (listener != null) {
      listener.onCodeReceived(code);
    }

    // If you have a single express for whole code, you can use:
    // ExpressApi.get().doExpress("activation_" + code, 1, Priority.NORMAL, null);
    // Here we demonstrate per-digit express calls.

    ExpressApi api = ExpressApi.get();
    final int len = code.length();
    for (int i = 0; i < len; i++) {
      char c = code.charAt(i);
      if (c < '0' || c > '9') {
        continue;
      }
      String expressName = "digit_" + c;
      Log.i(TAG, "Play express for digit: " + expressName);
      api.doExpress(expressName, 1, Priority.NORMAL, new AnimationListener() {
        @Override public void onAnimationStart() {
        }

        @Override public void onAnimationEnd(int i) {
        }

        @Override public void onAnimationRepeat(int loopNumber) {
        }
      });
    }
  }
}

