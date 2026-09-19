package com.ubtrobot.mini.speech.framework.demo;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ubtrobot.mini.sysevent.SysEventApi;
import com.ubtrobot.mini.sysevent.event.HeadEvent;
import com.ubtrobot.mini.sysevent.event.base.KeyEvent;
import com.ubtrobot.mini.sysevent.receiver.KeyEventReceiver;

/**
 * Head tap:
 * - Mỗi lần chạm (onSingleClick): {@link OnHeadTapListener#onHeadTapDownInterrupt()} ngay
 *   → ngắt TTS liền (không chờ phân single/double).
 * - 1 lần (sau cửa sổ) → wake / giao tiếp
 * - 2 lần liên tục → QR cấu hình (hủy wake đang chờ)
 */
public final class HeadTouchEventHelper {

  private static final String TAG = "HeadTouchEvent";

  private static final long DOUBLE_WINDOW_MS = 450L;
  private static final long DOUBLE_DISPATCH_DEBOUNCE_MS = 900L;
  private static final long SUPPRESS_SINGLE_AFTER_DOUBLE_MS = 1600L;

  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  private static KeyEventReceiver sHeadReceiver;
  private static OnHeadTapListener sListener;
  private static Runnable pendingSingle;
  private static KeyEvent pendingEvent;
  private static long lastTapMs;
  private static long lastDoubleDispatchMs;
  private static long suppressSingleUntilMs;

  public interface OnHeadTapListener {
    /** Ngắt TTS ngay khi đầu bị chạm — trước khi biết single hay double. */
    default void onHeadTapDownInterrupt() {}

    void onHeadSingleTap(KeyEvent event);

    void onHeadDoubleTap(KeyEvent event);
  }

  public static synchronized void subscribe(OnHeadTapListener listener) {
    if (sHeadReceiver != null) {
      Log.d(TAG, "HeadEvent already subscribed");
      return;
    }
    sListener = listener;
    sHeadReceiver = new KeyEventReceiver() {
      @Override
      public boolean onSingleClick(KeyEvent event) {
        Log.i(TAG, "HeadEvent=======onSingleClick");
        handleTap(event);
        return true;
      }

      @Override
      public boolean onDoubleClick(KeyEvent event) {
        Log.i(TAG, "HeadEvent=======onDoubleClick (framework)");
        cancelPendingSingle();
        lastTapMs = 0L;
        dispatchDouble(event);
        return true;
      }

      @Override
      public boolean onLongClick(KeyEvent event) {
        Log.d(TAG, "HeadEvent=======onLongClick (pass)");
        return false;
      }
    };
    SysEventApi.get().subscribe(HeadEvent.newInstance(), sHeadReceiver);
    Log.i(TAG, "HeadEvent subscribed (interrupt-now, soft-double " + DOUBLE_WINDOW_MS + "ms)");
  }

  private static void handleTap(KeyEvent event) {
    // Luôn ngắt TTS ngay — giống 2b23f8d cảm giác “chạm là cắt”, không chờ 450ms.
    dispatchInterrupt();

    long now = System.currentTimeMillis();
    if (pendingSingle != null && now - lastTapMs <= DOUBLE_WINDOW_MS) {
      long gap = now - lastTapMs;
      cancelPendingSingle();
      lastTapMs = 0L;
      Log.i(TAG, "soft-double from 2x onSingleClick (gap=" + gap + "ms)");
      dispatchDouble(event);
      return;
    }
    cancelPendingSingle();
    lastTapMs = now;
    pendingEvent = event;
    pendingSingle = new Runnable() {
      @Override
      public void run() {
        synchronized (HeadTouchEventHelper.class) {
          if (pendingSingle != this) return;
          pendingSingle = null;
          KeyEvent e = pendingEvent;
          pendingEvent = null;
          lastTapMs = 0L;
          dispatchSingle(e);
        }
      }
    };
    MAIN.postDelayed(pendingSingle, DOUBLE_WINDOW_MS);
  }

  private static void cancelPendingSingle() {
    if (pendingSingle != null) {
      MAIN.removeCallbacks(pendingSingle);
      pendingSingle = null;
    }
    pendingEvent = null;
  }

  private static void dispatchInterrupt() {
    if (ActivationEyeDisplay.isQrShowing()) {
      return;
    }
    OnHeadTapListener l = sListener;
    if (l == null) return;
    try {
      l.onHeadTapDownInterrupt();
    } catch (Exception e) {
      Log.w(TAG, "onHeadTapDownInterrupt: " + e.getMessage(), e);
    }
  }

  private static void dispatchSingle(KeyEvent event) {
    long now = System.currentTimeMillis();
    if (now < suppressSingleUntilMs) {
      Log.i(TAG, "single suppressed after double (" + (suppressSingleUntilMs - now) + "ms left)");
      return;
    }
    // QR đang hiện: single = tắt QR + wake (không để cờ/sticky kẹt nửa vời).
    if (ActivationEyeDisplay.isQrShowing()) {
      Log.i(TAG, "single while QR – dismiss QR + wake");
      ActivationEyeDisplay.dismissQrEyes();
    }
    OnHeadTapListener l = sListener;
    if (l == null) return;
    try {
      Log.i(TAG, "dispatch onHeadSingleTap (wake)");
      l.onHeadSingleTap(event);
    } catch (Exception e) {
      Log.w(TAG, "onHeadSingleTap: " + e.getMessage(), e);
    } catch (Throwable t) {
      Log.e(TAG, "onHeadSingleTap fatal: " + t.getMessage(), t);
    }
  }

  private static void dispatchDouble(KeyEvent event) {
    long now = System.currentTimeMillis();
    if (now - lastDoubleDispatchMs < DOUBLE_DISPATCH_DEBOUNCE_MS) {
      Log.i(TAG, "double debounce skip (" + (now - lastDoubleDispatchMs) + "ms)");
      return;
    }
    lastDoubleDispatchMs = now;
    suppressSingleUntilMs = now + SUPPRESS_SINGLE_AFTER_DOUBLE_MS;
    cancelPendingSingle();
    lastTapMs = 0L;
    OnHeadTapListener l = sListener;
    if (l == null) return;
    try {
      Log.i(TAG, "dispatch onHeadDoubleTap (QR/config)");
      l.onHeadDoubleTap(event);
    } catch (Exception e) {
      Log.w(TAG, "onHeadDoubleTap: " + e.getMessage(), e);
    }
  }

  public static synchronized void unsubscribe() {
    cancelPendingSingle();
    lastTapMs = 0L;
    lastDoubleDispatchMs = 0L;
    suppressSingleUntilMs = 0L;
    if (sHeadReceiver == null) {
      return;
    }
    try {
      SysEventApi.get().unsubscribe(sHeadReceiver);
    } catch (Exception e) {
      Log.w(TAG, "HeadEvent unsubscribe: " + e.getMessage());
    }
    sHeadReceiver = null;
    sListener = null;
    Log.i(TAG, "HeadEvent unsubscribed");
  }

  private HeadTouchEventHelper() {
  }
}
