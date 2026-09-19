package com.ubtrobot.mini.speech.framework.demo.wificonfig;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bật SoftAP bằng nhiều cách (LocalOnly / setWifiApEnabled / startTethering).
 * App thường trên Mini thường bị SecurityException → caller fallback Settings.
 */
final class WifiSoftApHelper {
  private static final String TAG = "WifiProvision";

  static final String DEFAULT_PASSWORD = "12345678";

  /** Lỗi lần start gần nhất (để log / mắt). */
  static volatile String lastError = "";

  static final class ApInfo {
    final String ssid;
    final String password;
    final String mode;

    ApInfo(String ssid, String password, String mode) {
      this.ssid = ssid;
      this.password = password;
      this.mode = mode;
    }
  }

  private WifiSoftApHelper() {}

  static ApInfo start(Context context, String preferredSsid) {
    lastError = "";
    WifiManager wifi = (WifiManager) context.getApplicationContext()
        .getSystemService(Context.WIFI_SERVICE);
    if (wifi == null) {
      lastError = "WifiManager null";
      throw new IllegalStateException(lastError);
    }

    List<String> errors = new ArrayList<>();

    // Ưu tiên mạng MỞ (không mật khẩu) theo yêu cầu user.
    try {
      ApInfo open = startLegacyAp(wifi, preferredSsid, false);
      Log.i(TAG, "SoftAP OK mode=legacy-open ssid=" + open.ssid + " (no password)");
      return open;
    } catch (Throwable t) {
      errors.add("legacy-open:" + rootMsg(t));
      Log.w(TAG, "legacy-open fail", t);
    }

    try {
      ApInfo tether = tryStartTethering(context, preferredSsid, /*open*/ true);
      if (tether != null) {
        Log.i(TAG, "SoftAP OK mode=tethering-open ssid=" + tether.ssid);
        return tether;
      }
      errors.add("tethering-open=null");
    } catch (Throwable t) {
      errors.add("tethering-open:" + rootMsg(t));
      Log.w(TAG, "tethering-open fail", t);
    }

    try {
      ApInfo legacy = startLegacyAp(wifi, preferredSsid, true);
      Log.i(TAG, "SoftAP OK mode=legacy-wpa ssid=" + legacy.ssid + " pass=" + DEFAULT_PASSWORD);
      return legacy;
    } catch (Throwable t) {
      errors.add("legacy-wpa:" + rootMsg(t));
      Log.w(TAG, "legacy-wpa fail", t);
    }

    try {
      ApInfo tether = tryStartTethering(context, preferredSsid, /*open*/ false);
      if (tether != null) {
        Log.i(TAG, "SoftAP OK mode=tethering-wpa ssid=" + tether.ssid);
        return tether;
      }
      errors.add("tethering-wpa=null");
    } catch (Throwable t) {
      errors.add("tethering-wpa:" + rootMsg(t));
      Log.w(TAG, "tethering-wpa fail", t);
    }

    lastError = join(errors);
    Log.e(TAG, "ALL SoftAP methods failed: " + lastError);
    throw new IllegalStateException("SoftAP blocked by ROM: " + lastError);
  }

  static void stop(Context context) {
    WifiManager wifi = (WifiManager) context.getApplicationContext()
        .getSystemService(Context.WIFI_SERVICE);
    if (wifi == null) return;
    WifiLocalOnlyHotspot.release();
    tryStopTethering(context);
    try {
      Method setAp = wifi.getClass().getMethod(
          "setWifiApEnabled", WifiConfiguration.class, boolean.class);
      setAp.invoke(wifi, null, false);
      Log.i(TAG, "SoftAP stop setWifiApEnabled false");
    } catch (Throwable t) {
      Log.w(TAG, "SoftAP stop: " + t.getMessage());
    }
    try {
      if (!wifi.isWifiEnabled()) {
        wifi.setWifiEnabled(true);
      }
    } catch (Throwable t) {
      Log.w(TAG, "re-enable wifi: " + t.getMessage());
    }
  }

  static void releaseLocalOnly() {
    WifiLocalOnlyHotspot.release();
  }

  @SuppressWarnings("deprecation")
  private static ApInfo startLegacyAp(WifiManager wifi, String preferredSsid, boolean withPass)
      throws Exception {
    try {
      wifi.setWifiEnabled(false);
      Thread.sleep(500);
    } catch (Throwable ignored) {
    }

    WifiConfiguration conf = new WifiConfiguration();
    conf.SSID = preferredSsid;
    conf.allowedKeyManagement.clear();
    conf.allowedAuthAlgorithms.clear();
    conf.allowedGroupCiphers.clear();
    conf.allowedPairwiseCiphers.clear();
    conf.allowedProtocols.clear();
    if (withPass) {
      conf.preSharedKey = DEFAULT_PASSWORD;
      conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
      conf.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
    } else {
      // Open AP – không mật khẩu
      conf.preSharedKey = "";
      conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
      conf.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
      conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
      conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
      conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
      conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
    }

    Method setAp = wifi.getClass().getMethod(
        "setWifiApEnabled", WifiConfiguration.class, boolean.class);
    Object ok = setAp.invoke(wifi, conf, true);
    Log.i(TAG, "setWifiApEnabled(" + preferredSsid + ", pass=" + withPass + ") => " + ok);
    if (ok instanceof Boolean && !(Boolean) ok) {
      conf.SSID = "\"" + preferredSsid + "\"";
      if (withPass) {
        conf.preSharedKey = "\"" + DEFAULT_PASSWORD + "\"";
      }
      ok = setAp.invoke(wifi, conf, true);
      Log.i(TAG, "setWifiApEnabled retry quoted => " + ok);
      if (ok instanceof Boolean && !(Boolean) ok) {
        throw new IllegalStateException("setWifiApEnabled returned false");
      }
    }
    // Đợi AP lên (ENABLING=12 / ENABLED=13); tránh fail sớm khi state còn DISABLED=11.
    Method getSt = wifi.getClass().getMethod("getWifiApState");
    int st = -1;
    for (int i = 0; i < 20; i++) {
      st = (Integer) getSt.invoke(wifi);
      if (st == 13) break;
      Thread.sleep(250);
    }
    Log.i(TAG, "getWifiApState=" + st + " (13=ENABLED)");
    if (st != 13 && st != 12) {
      throw new IllegalStateException("AP state not enabled: " + st);
    }
    return new ApInfo(preferredSsid, withPass ? DEFAULT_PASSWORD : "", withPass ? "legacy-wpa" : "legacy-open");
  }

  /**
   * ConnectivityManager.startTethering — OnStartTetheringCallback là abstract class,
   * không Proxy được → dùng ResultReceiver overload.
   */
  private static ApInfo tryStartTethering(Context context, String preferredSsid, boolean open)
      throws Exception {
    ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext()
        .getSystemService(Context.CONNECTIVITY_SERVICE);
    if (cm == null) return null;

    try {
      WifiManager wifi = (WifiManager) context.getApplicationContext()
          .getSystemService(Context.WIFI_SERVICE);
      if (wifi != null) {
        Method setConfig = wifi.getClass().getMethod("setWifiApConfiguration", WifiConfiguration.class);
        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = preferredSsid;
        conf.allowedKeyManagement.clear();
        if (open) {
          conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
          conf.preSharedKey = null;
        } else {
          conf.preSharedKey = DEFAULT_PASSWORD;
          conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        }
        setConfig.invoke(wifi, conf);
        Log.i(TAG, "setWifiApConfiguration OK open=" + open);
      }
    } catch (Throwable t) {
      Log.w(TAG, "setWifiApConfiguration: " + t.getMessage());
    }

    ApInfo info = tryStartTetheringResultReceiver(cm, preferredSsid);
    if (info == null) return null;
    return new ApInfo(preferredSsid, open ? "" : DEFAULT_PASSWORD, open ? "tethering-open" : "tethering-wpa");
  }

  private static ApInfo tryStartTetheringResultReceiver(ConnectivityManager cm, String preferredSsid)
      throws Exception {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicBoolean ok = new AtomicBoolean(false);
    ResultReceiver rr = new ResultReceiver(new Handler(Looper.getMainLooper())) {
      @Override
      protected void onReceiveResult(int resultCode, android.os.Bundle resultData) {
        Log.i(TAG, "tether ResultReceiver code=" + resultCode);
        ok.set(resultCode == 0);
        latch.countDown();
      }
    };
    Method start = null;
    for (Method m : cm.getClass().getDeclaredMethods()) {
      if ("startTethering".equals(m.getName()) && m.getParameterTypes().length >= 3) {
        start = m;
        break;
      }
    }
    if (start == null) return null;
    start.setAccessible(true);
    Class<?>[] pts = start.getParameterTypes();
    if (pts.length == 3 && pts[2] == ResultReceiver.class) {
      start.invoke(cm, 0, false, rr);
    } else if (pts.length >= 4) {
      start.invoke(cm, 0, false, rr, false);
    } else {
      return null;
    }
    latch.await(6, TimeUnit.SECONDS);
    if (!ok.get()) return null;
    return new ApInfo(preferredSsid, DEFAULT_PASSWORD, "tethering-rr");
  }

  private static void tryStopTethering(Context context) {
    try {
      ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext()
          .getSystemService(Context.CONNECTIVITY_SERVICE);
      if (cm == null) return;
      Method stop = cm.getClass().getDeclaredMethod("stopTethering", int.class);
      stop.setAccessible(true);
      stop.invoke(cm, 0);
      Log.i(TAG, "stopTethering WIFI");
    } catch (Throwable t) {
      Log.w(TAG, "stopTethering: " + t.getMessage());
    }
  }

  private static String rootMsg(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null && c.getCause() != c) c = c.getCause();
    String m = c.getMessage();
    if (m == null || m.isEmpty()) m = c.getClass().getSimpleName();
    if (m.contains("SecurityException") || c instanceof SecurityException) {
      return "SecurityException(no permission)";
    }
    return m;
  }

  private static String join(List<String> parts) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.size(); i++) {
      if (i > 0) sb.append(" | ");
      sb.append(parts.get(i));
    }
    return sb.toString();
  }
}
