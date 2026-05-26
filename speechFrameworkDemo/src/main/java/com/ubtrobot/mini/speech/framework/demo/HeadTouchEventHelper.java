package com.ubtrobot.mini.speech.framework.demo;

import android.util.Log;

import com.ubtrobot.mini.sysevent.SysEventApi;
import com.ubtrobot.mini.sysevent.event.HeadEvent;
import com.ubtrobot.mini.sysevent.event.base.KeyEvent;
import com.ubtrobot.mini.sysevent.receiver.SingleClickReceiver;

/**
 * Subscribe {@link HeadEvent} (chạm đầu 1 lần) — same pattern as mini-outer-sdk-demo SysEventApiActivity.
 */
public final class HeadTouchEventHelper {

  private static final String TAG = "HeadTouchEvent";

  private static SingleClickReceiver sHeadReceiver;

  public interface OnHeadTapListener {
    void onHeadTap(KeyEvent event);
  }

  /** Register head-touch; safe to call once (ignores duplicate). */
  public static synchronized void subscribe(OnHeadTapListener listener) {
    if (sHeadReceiver != null) {
      Log.d(TAG, "HeadEvent already subscribed");
      return;
    }
    sHeadReceiver = new SingleClickReceiver() {
      @Override
      public boolean onSingleClick(KeyEvent event) {
        Log.d(TAG, "HeadEvent=======onSingleClick");
        if (listener != null) {
          listener.onHeadTap(event);
        }
        return true;
      }
    };
    SysEventApi.get().subscribe(HeadEvent.newInstance(), sHeadReceiver);
    Log.i(TAG, "HeadEvent subscribed via SysEventApi");
  }

  public static synchronized void unsubscribe() {
    if (sHeadReceiver == null) {
      return;
    }
    try {
      SysEventApi.get().unsubscribe(sHeadReceiver);
    } catch (Exception e) {
      Log.w(TAG, "HeadEvent unsubscribe: " + e.getMessage());
    }
    sHeadReceiver = null;
    Log.i(TAG, "HeadEvent unsubscribed");
  }

  private HeadTouchEventHelper() {
  }
}
