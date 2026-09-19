package com.ubtrobot.mini.speech.framework.demo.wificonfig;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Quét SSID quanh robot.
 * SoftAP tắt STA → phải quét xong + cache <b>trước</b> khi bật SoftAP.
 * Khi SoftAP đang bật, startScan thường không ra kết quả mới.
 */
final class WifiScanHelper {
  private static final String TAG = "WifiProvision";

  static final class Ap {
    final String ssid;
    final int rssi;
    final boolean secure;

    Ap(String ssid, int rssi, boolean secure) {
      this.ssid = ssid;
      this.rssi = rssi;
      this.secure = secure;
    }
  }

  private static final Object LOCK = new Object();
  private static volatile List<Ap> cached = Collections.emptyList();

  private WifiScanHelper() {}

  static List<Ap> cached() {
    synchronized (LOCK) {
      return new ArrayList<>(cached);
    }
  }

  /** Quét STA trước SoftAP — đợi broadcast kết quả, retry. */
  @SuppressWarnings("deprecation")
  static List<Ap> scanBeforeSoftAp(Context context) {
    Context app = context.getApplicationContext();
    logLocation(app);
    WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
    if (wifi == null) return cached();
    try {
      // Đảm bảo SoftAP tắt để STA quét được
      try {
        if (WifiStationHelper.isApEnabled(wifi)) {
          WifiSoftApHelper.stop(app);
          Thread.sleep(600);
        }
      } catch (Throwable ignored) {
      }
      if (!wifi.isWifiEnabled()) {
        wifi.setWifiEnabled(true);
      }
      waitWifiEnabled(wifi, 6000);
      List<Ap> best = Collections.emptyList();
      for (int attempt = 1; attempt <= 3; attempt++) {
        List<Ap> list = scanOnceBlocking(app, wifi, 10_000);
        Log.i(TAG, "pre-SoftAP scan attempt=" + attempt + " count=" + list.size());
        if (list.size() > best.size()) best = list;
        if (!list.isEmpty()) break;
        Thread.sleep(700);
      }
      store(best);
      Log.i(TAG, "pre-SoftAP scan final count=" + best.size());
      return best;
    } catch (Throwable t) {
      Log.w(TAG, "scanBeforeSoftAp: " + t.getMessage(), t);
      return cached();
    }
  }

  /**
   * Portal Quét lại khi SoftAP đang bật: thử startScan (thường rỗng),
   * trả về cache đã lấy trước SoftAP.
   */
  @SuppressWarnings("deprecation")
  static List<Ap> refreshWhileSoftAp(Context context) {
    Context app = context.getApplicationContext();
    WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
    if (wifi == null) return cached();
    try {
      if (WifiStationHelper.isApEnabled(wifi)) {
        Log.i(TAG, "SoftAP đang bật – trả cache (STA scan thường không chạy). cache="
            + cached().size());
        return cached();
      }
      List<Ap> list = scanOnceBlocking(app, wifi, 8_000);
      if (!list.isEmpty()) store(list);
      Log.i(TAG, "refresh scan count=" + list.size());
      return cached();
    } catch (Throwable t) {
      Log.w(TAG, "refreshWhileSoftAp: " + t.getMessage());
      return cached();
    }
  }

  static String toJson(List<Ap> list) {
    StringBuilder sb = new StringBuilder(256);
    sb.append("{\"aps\":[");
    for (int i = 0; i < list.size(); i++) {
      Ap a = list.get(i);
      if (i > 0) sb.append(',');
      sb.append("{\"ssid\":\"").append(jsonEscape(a.ssid)).append("\",");
      sb.append("\"rssi\":").append(a.rssi).append(',');
      sb.append("\"secure\":").append(a.secure).append('}');
    }
    sb.append("]}");
    return sb.toString();
  }

  @SuppressWarnings("deprecation")
  private static List<Ap> scanOnceBlocking(Context app, WifiManager wifi, long timeoutMs)
      throws InterruptedException {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicBoolean got = new AtomicBoolean(false);
    BroadcastReceiver rx = new BroadcastReceiver() {
      @Override public void onReceive(Context context, Intent intent) {
        got.set(true);
        latch.countDown();
      }
    };
    try {
      app.registerReceiver(rx, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
      boolean started = wifi.startScan();
      Log.i(TAG, "startScan=" + started + " wifiOn=" + wifi.isWifiEnabled());
      if (!started) {
        Thread.sleep(2000);
      } else {
        boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        Log.i(TAG, "scan broadcast ok=" + ok + " got=" + got.get());
        // Thêm chút cho driver đẩy kết quả
        Thread.sleep(300);
      }
      List<ScanResult> raw = wifi.getScanResults();
      Log.i(TAG, "getScanResults raw=" + (raw == null ? -1 : raw.size()));
      return parse(raw);
    } finally {
      try {
        app.unregisterReceiver(rx);
      } catch (Throwable ignored) {
      }
    }
  }

  @SuppressWarnings("deprecation")
  private static void waitWifiEnabled(WifiManager wifi, long timeoutMs)
      throws InterruptedException {
    long end = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < end) {
      if (wifi.isWifiEnabled()) {
        Thread.sleep(600);
        return;
      }
      Thread.sleep(200);
    }
    Log.w(TAG, "waitWifiEnabled timeout wifiOn=" + wifi.isWifiEnabled());
  }

  private static List<Ap> parse(List<ScanResult> raw) {
    Map<String, Ap> best = new LinkedHashMap<>();
    if (raw != null) {
      for (ScanResult r : raw) {
        if (r == null || r.SSID == null) continue;
        String ssid = r.SSID.trim();
        if (ssid.isEmpty()) continue;
        boolean secure = r.capabilities != null
            && (r.capabilities.contains("WPA")
            || r.capabilities.contains("WEP")
            || r.capabilities.contains("PSK")
            || r.capabilities.contains("EAP"));
        Ap prev = best.get(ssid);
        if (prev == null || r.level > prev.rssi) {
          best.put(ssid, new Ap(ssid, r.level, secure));
        }
      }
    }
    List<Ap> out = new ArrayList<>(best.values());
    Collections.sort(out, new Comparator<Ap>() {
      @Override public int compare(Ap a, Ap b) {
        return Integer.compare(b.rssi, a.rssi);
      }
    });
    return out;
  }

  private static void store(List<Ap> list) {
    synchronized (LOCK) {
      cached = Collections.unmodifiableList(new ArrayList<>(list));
    }
  }

  private static void logLocation(Context ctx) {
    boolean ok = true;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      ok = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
          == PackageManager.PERMISSION_GRANTED
          || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
          == PackageManager.PERMISSION_GRANTED;
    }
    Log.i(TAG, "location permission=" + ok);
  }

  private static String jsonEscape(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r");
  }
}
