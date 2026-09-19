package com.ubtrobot.mini.speech.framework.demo.stockoverride;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import com.ubtrobot.mini.speech.framework.SpeechSettingStub;
import com.ubtrobot.speech.CompositeSpeechService;
import com.ubtrobot.speech.SpeechService;

/**
 * Facade độc lập: đăng ký SpeechService/SpeechSettings với Master để <b>đè speech hệ thống</b>.
 * <p>
 * Không import / không gọi Xiaozhi, DemoRecognizer, Sherpa.
 * Điều kiện UBT (doc): {@code meta-data ubt-master-app = third_part_speechservice}
 * + <b>tắt nguồn robot rồi bật lại</b> sau khi cài APK.
 */
public final class StockSpeechOverride {
  public static final String TAG = "StockSpeechOverride";
  public static final String META_NAME = "ubt-master-app";
  public static final String META_VALUE = "third_part_speechservice";

  private static final Object LOCK = new Object();
  private static volatile StockSpeechOverride instance;

  private final SpeechSettingStub settings;
  private final CompositeSpeechService speechService;

  private StockSpeechOverride(Context appContext) {
    Context app = appContext.getApplicationContext();
    settings = new SpeechSettingStub(app);
    speechService = new CompositeSpeechService.Builder()
        .setWakeUpDetector(new ClaimWakeUpDetector())
        .setRecognizer(new ClaimRecognizer())
        .setSynthesizer(new ClaimSynthesizer())
        .setUnderstander(new ClaimUnderstander())
        .build();
    Log.i(TAG, "CompositeSpeechService claim sẵn sàng (no-op modules)");
  }

  public static StockSpeechOverride get(Context context) {
    if (instance == null) {
      synchronized (LOCK) {
        if (instance == null) {
          instance = new StockSpeechOverride(context.getApplicationContext());
        }
      }
    }
    return instance;
  }

  public SpeechSettingStub getSpeechSettings() {
    return settings;
  }

  public SpeechService getSpeechService() {
    return speechService;
  }

  /**
   * Kiểm tra APK đã cài có đúng mark doc UBT hay không.
   * @return null nếu OK, hoặc chuỗi lỗi để log.
   */
  public static String verifyThirdPartyMark(Context context) {
    try {
      ApplicationInfo ai = context.getPackageManager()
          .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
      Bundle meta = ai.metaData;
      if (meta == null) {
        return "Thiếu meta-data trong APK";
      }
      String v = meta.getString(META_NAME);
      if (!META_VALUE.equals(v)) {
        return "ubt-master-app=\"" + v + "\" (cần \"" + META_VALUE + "\")";
      }
      return null;
    } catch (Throwable t) {
      return "verify meta thất bại: " + t.getMessage();
    }
  }

  public static void logClaimStatus(Context context) {
    String err = verifyThirdPartyMark(context);
    if (err == null) {
      Log.i(TAG, "Mark OK: " + META_NAME + "=" + META_VALUE
          + " — sau cài/đổi APK phải TẮT NGUỒN robot rồi bật lại để cắt mic speech stock");
    } else {
      Log.e(TAG, "Mark SAI: " + err);
    }
  }
}
