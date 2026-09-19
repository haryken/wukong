package com.ubtrobot.mini.speech.framework.demo.wificonfig;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.List;

/** Kiểm tra / nối Wi‑Fi station (mạng nhà). */
final class WifiStationHelper {
  private static final String TAG = "WifiProvision";

  private WifiStationHelper() {}

  /** Đã gắn vào một AP (SSID hợp lệ), không cần internet. */
  @SuppressWarnings("deprecation")
  static boolean isAssociated(Context context) {
    try {
      WifiManager wifi = (WifiManager) context.getApplicationContext()
          .getSystemService(Context.WIFI_SERVICE);
      if (wifi == null || !wifi.isWifiEnabled()) return false;
      // SoftAP bật: một số ROM vẫn báo wifi enabled nhưng không phải STA.
      if (isApEnabled(wifi)) return false;
      WifiInfo info = wifi.getConnectionInfo();
      if (info == null || info.getNetworkId() == -1) return false;
      String ssid = info.getSSID();
      if (ssid == null) return false;
      String s = ssid.trim();
      if (s.isEmpty() || "<unknown ssid>".equalsIgnoreCase(s) || "0x".equalsIgnoreCase(s)) {
        return false;
      }
      ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext()
          .getSystemService(Context.CONNECTIVITY_SERVICE);
      if (cm != null) {
        NetworkInfo ni = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        if (ni != null && ni.isConnected()) return true;
      }
      // Fallback: có networkId + SSID
      return !s.equals("\"\"");
    } catch (Throwable t) {
      Log.w(TAG, "isAssociated: " + t.getMessage());
      return false;
    }
  }

  static boolean isApEnabled(WifiManager wifi) {
    try {
      int state = MethodCompat.getWifiApState(wifi);
      // WIFI_AP_STATE_ENABLED = 13; ENABLING = 12 trên nhiều ROM
      return state == 13 || state == 12;
    } catch (Throwable ignored) {
      return false;
    }
  }

  @SuppressWarnings("deprecation")
  static boolean connect(Context context, String ssid, String password) {
    WifiManager wifi = (WifiManager) context.getApplicationContext()
        .getSystemService(Context.WIFI_SERVICE);
    if (wifi == null) return false;
    try {
      WifiSoftApHelper.releaseLocalOnly();
      WifiSoftApHelper.stop(context);
      Thread.sleep(500);
      if (!wifi.isWifiEnabled()) {
        wifi.setWifiEnabled(true);
        Thread.sleep(800);
      }

      String quotedSsid = "\"" + ssid + "\"";
      // Xóa config trùng SSID cũ nếu có
      List<WifiConfiguration> configured = wifi.getConfiguredNetworks();
      if (configured != null) {
        for (WifiConfiguration c : configured) {
          if (c.SSID != null && c.SSID.equals(quotedSsid)) {
            wifi.removeNetwork(c.networkId);
          }
        }
      }

      WifiConfiguration conf = new WifiConfiguration();
      conf.SSID = quotedSsid;
      if (password == null || password.isEmpty()) {
        conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
      } else {
        conf.preSharedKey = "\"" + password + "\"";
        conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
      }
      conf.status = WifiConfiguration.Status.ENABLED;

      int netId = wifi.addNetwork(conf);
      if (netId == -1) {
        Log.e(TAG, "addNetwork failed for " + ssid);
        return false;
      }
      wifi.disconnect();
      boolean en = wifi.enableNetwork(netId, true);
      boolean re = wifi.reconnect();
      Log.i(TAG, "connect ssid=" + ssid + " netId=" + netId + " enable=" + en + " reconnect=" + re);
      return en;
    } catch (Throwable t) {
      Log.e(TAG, "connect: " + t.getMessage(), t);
      return false;
    }
  }

  /** Reflection getWifiApState. */
  private static final class MethodCompat {
    static int getWifiApState(WifiManager wifi) throws Exception {
      return (Integer) wifi.getClass().getMethod("getWifiApState").invoke(wifi);
    }
  }
}
