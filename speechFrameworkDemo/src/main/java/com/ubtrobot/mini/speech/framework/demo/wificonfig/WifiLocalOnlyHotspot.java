package com.ubtrobot.mini.speech.framework.demo.wificonfig;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chỉ gọi khi API ≥ 26 — tách class để Android 7 không VerifyError khi load.
 */
final class WifiLocalOnlyHotspot {
  private static final String TAG = "WifiProvision";

  private static volatile Object reservation; // LocalOnlyHotspotReservation

  private WifiLocalOnlyHotspot() {}

  static WifiSoftApHelper.ApInfo start(WifiManager wifi, String preferredSsid) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<WifiSoftApHelper.ApInfo> infoRef = new AtomicReference<>();
    final AtomicReference<Throwable> errRef = new AtomicReference<>();
    final AtomicReference<Object> resRef = new AtomicReference<>();

    try {
      wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
        @Override
        public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
          try {
            resRef.set(r);
            WifiConfiguration cfg = r.getWifiConfiguration();
            String ssid = preferredSsid;
            String pass = WifiSoftApHelper.DEFAULT_PASSWORD;
            if (cfg != null) {
              if (cfg.SSID != null) ssid = stripQuotes(cfg.SSID);
              if (cfg.preSharedKey != null) pass = stripQuotes(cfg.preSharedKey);
            }
            infoRef.set(new WifiSoftApHelper.ApInfo(ssid, pass, "local-only"));
            Log.i(TAG, "LocalOnlyHotspot started ssid=" + ssid);
          } catch (Throwable t) {
            errRef.set(t);
          } finally {
            latch.countDown();
          }
        }

        @Override
        public void onFailed(int reason) {
          errRef.set(new IllegalStateException("LocalOnlyHotspot failed reason=" + reason));
          latch.countDown();
        }
      }, new Handler(Looper.getMainLooper()));

      if (!latch.await(8, TimeUnit.SECONDS)) {
        Log.w(TAG, "LocalOnlyHotspot timeout");
        return null;
      }
      if (errRef.get() != null) {
        Log.w(TAG, "LocalOnlyHotspot: " + errRef.get().getMessage());
        return null;
      }
      reservation = resRef.get();
      return infoRef.get();
    } catch (Throwable t) {
      Log.w(TAG, "LocalOnlyHotspot exception: " + t.getMessage());
      return null;
    }
  }

  static void release() {
    try {
      Object r = reservation;
      reservation = null;
      if (r instanceof WifiManager.LocalOnlyHotspotReservation) {
        ((WifiManager.LocalOnlyHotspotReservation) r).close();
        Log.i(TAG, "LocalOnlyHotspot closed");
      }
    } catch (Throwable t) {
      Log.w(TAG, "release LocalOnly: " + t.getMessage());
    }
  }

  private static String stripQuotes(String s) {
    if (s == null) return "";
    String t = s.trim();
    if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
      return t.substring(1, t.length() - 1);
    }
    return t;
  }
}
