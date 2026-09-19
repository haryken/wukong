package com.ubtrobot.mini.speech.framework.demo;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import com.iflytek.cloud.SpeechConstant;
import com.iflytek.cloud.SpeechUtility;
import com.iflytek.cloud.msc.util.log.DebugLog;
import com.ubtech.utilcode.utils.thread.ThreadPool;
import com.ubtrobot.master.log.InfrequentLoggerFactory;
import com.ubtrobot.mini.speech.framework.AbstractSpeechApplication;
import com.ubtrobot.mini.speech.framework.BuildConfig;
import com.ubtrobot.service.ServiceModules;
import com.ubtrobot.speech.SpeechService;
import com.ubtrobot.speech.SpeechSettings;
import com.ubtrobot.ulog.FwLoggerFactory2;
import com.ubtrobot.ulog.logger.android.AndroidLoggerFactory;

/**
 * Giống develop2222 / commit 2b23f8d: đăng ký {@link DemoSpeech} lên Master để đè speech stock.
 * Cần kèm MicrophoneArrayService thật (AAR) + {@code ubt-master-app=third_part_speechservice}
 * + tắt nguồn robot sau khi cài.
 */
public class SpeechApplication extends AbstractSpeechApplication {
  private static final String TAG_APP = "SpeechApplication";

  private static void tryInitUbtMiniSdkLikeOuterDemo(Context app) {
    try {
      Class<?> pathCl = Class.forName("com.ubtrobot.mini.properties.sdk.Path");
      Object root = pathCl.getField("DIR_MINI_FILES_SDCARD_ROOT").get(null);
      Class<?> propsCl = Class.forName("com.ubtrobot.mini.properties.sdk.PropertiesApi");
      Method setRoot = propsCl.getMethod("setRootPath", root.getClass());
      setRoot.invoke(null, root);
      Class<?> sdkInit = Class.forName("com.ubtrobot.mini.SDKInit");
      sdkInit.getMethod("initialize", Context.class).invoke(null, app);
      Log.i(TAG_APP, "UBT Mini SDKInit + PropertiesApi (mini-outer-sdk-demo) OK");
    } catch (ClassNotFoundException e) {
      Log.w(
          TAG_APP,
          "Chưa có outer SDK (com.ubtrobot.mini.SDKInit) — copy *.jar từ mini-outer-sdk-demo/app/libs vào speechFrameworkDemo/libs");
    } catch (Throwable t) {
      Log.w(TAG_APP, "SDKInit/PropertiesApi (optional): " + t.getMessage());
    }
  }

  private static void logSauronTakePicAvailability() {
    try {
      Class.forName("com.ubtechinc.sauron.api.TakePicApi");
      Log.i(TAG_APP, "Sauron TakePicApi: có trong APK — MCP self.camera.take_photo có thể chụp ROM.");
    } catch (ClassNotFoundException e) {
      Log.w(
          TAG_APP,
          "Sauron TakePicApi: KHÔNG có trong APK. Copy JAR/AAR chứa com.ubtechinc.sauron từ gói SDK UBT "
              + "(cùng bộ với mini-outer-sdk-demo) vào speechFrameworkDemo/libs/sauron/ rồi Rebuild "
              + "(xem libs/sauron/README.txt).");
    }
  }

  @Override public void onCreate() {
    super.onCreate();
    tryInitUbtMiniSdkLikeOuterDemo(this);
    logSauronTakePicAvailability();
    MiniRobotActionInvoker.initApplicationContext(this);
    XiaozhiMqttConfigStore.init(this);
    CameraShutterSound.preload();
    StringBuffer param = new StringBuffer();
    param.append("appid=" + getString(R.string.app_id));
    param.append(",");
    param.append(SpeechConstant.ENGINE_MODE + "=" + SpeechConstant.MODE_MSC);
    SpeechUtility.createUtility(this, param.toString());
    DebugLog.setLogLevel(BuildConfig.DEBUG ? DebugLog.LOG_LEVEL.none : DebugLog.LOG_LEVEL.none);
    FwLoggerFactory2.setup(
        BuildConfig.DEBUG ? new AndroidLoggerFactory() : new InfrequentLoggerFactory());

    SpeechBootstrap.startOnce(this);

    // Giống develop2222 / 2b23f8d — Master nhận DemoSpeech thật.
    ServiceModules.declare(SpeechSettings.class,
        (aClass, moduleCreatedNotifier) -> moduleCreatedNotifier.notifyModuleCreated(
            DemoSpeech.INSTANCE.createSpeechSettings()));

    ServiceModules.declare(SpeechService.class,
        (aClass, moduleCreatedNotifier) -> ThreadPool.runOnNonUIThread(() -> {
          while (DemoSpeech.INSTANCE.createSpeechService() == null) {
            SystemClock.sleep(5);
          }
          Log.d("Logic", "Speech Service create ok..");
          moduleCreatedNotifier.notifyModuleCreated(DemoSpeech.INSTANCE.createSpeechService());
        }));
  }
}
