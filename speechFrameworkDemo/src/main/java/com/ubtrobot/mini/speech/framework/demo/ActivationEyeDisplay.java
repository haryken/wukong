package com.ubtrobot.mini.speech.framework.demo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.ubtrobot.commons.Priority;
import com.ubtrobot.express.ExpressApi;
import com.ubtrobot.express.listeners.AnimationListener;
import com.ubtrobot.eyescreen.EyeScreenApi;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hiện IP / mã trên mắt robot.
 * <p>
 * Mắt trái / phải theo {@link EyeScreenApi#drawBitmap} {@code screenIndex}:
 * {@link #EYE_LEFT}=0, {@link #EYE_RIGHT}=1 (SDK: 0..2; 2 = cả hai / panel rộng nếu ROM hỗ trợ).
 * IP luôn <b>1 dòng</b>, chữ nhỏ để đủ chỗ.
 */
public class ActivationEyeDisplay {

  private static final String TAG = "ActivationEyeDisplay";
  /**
   * Buffer bitmap EyeScreenApi: 240×240 (vuông).
   * Màn mắt vật lý hình tròn → chỉ vùng trong đường kính ~240 px nhìn được;
   * hình vuông nội tiếp ≈ 240/√2 ≈ 170 px — QR/chữ phải nằm trong đó.
   */
  private static final int EYE_W = 240;
  private static final int EYE_H = 240;
  /** Cạnh vuông an toàn trong mắt tròn (margin thêm ~8%). */
  private static final int CIRCLE_SAFE_SIDE = 156;

  /** Mắt trái (screenIndex 0). */
  public static final int EYE_LEFT = 0;
  /** Mắt phải (screenIndex 1). */
  public static final int EYE_RIGHT = 1;
  /** Một số ROM: 2 = cả hai mắt / buffer chung. */
  public static final int EYE_BOTH = 2;

  private static final long IP_HOLD_MS = 10_000L;
  private static final long CODE_HOLD_MS = 8_000L;
  /** URL + QR trên mắt (wifi provision / Self-Control): ~1 phút. */
  private static final long SELF_CONTROL_HOLD_MS = 60_000L;
  private static final long WIFI_STATUS_HOLD_MS = 5_000L;
  private static final long PAUSE_AFTER_DIGIT_MS = 400L;

  /** Tăng khi có nội dung mắt mới → hủy sleep QR / hold cũ. */
  private static final AtomicInteger eyeEpoch = new AtomicInteger(0);

  private static final String[] RESTORE_EYE_EXPRESS = {
      "wakeup", "normal", "default", "idle", "w_basic_001", "emo_001", "codemao1"
  };

  /** Express mắt cười giống lúc wake / sẵn sàng nghe. */
  private static final String[] LISTEN_READY_SMILE = {
      "wakeup", "w_basic_001", "emo_001", "normal"
  };

  /** IP 1 dòng — chữ rất nhỏ để không xuống dòng / tràn. */
  private static final float IP_MAX_TEXT_SIZE = 11f;
  private static final float IP_MIN_TEXT_SIZE = 7f;
  private static final float CODE_MAX_TEXT_SIZE = 22f;
  private static final float CODE_MIN_TEXT_SIZE = 12f;

  public interface CodeDisplayListener {
    void onCodeReceived(String code);
  }

  private static volatile CodeDisplayListener uiListener;
  private static final AtomicBoolean playing = new AtomicBoolean(false);
  /** Đang hiện QR (SoftAP / Self-Control) trên mắt — double-tap để tắt. */
  private static final AtomicBoolean qrShowing = new AtomicBoolean(false);
  /**
   * Sticky: voice/MCP cũng bật — tránh race clear qrShowing khiến double-tap tưởng “chưa mở” rồi mở lại (chớp).
   */
  private static final AtomicBoolean stickyConfigEyes = new AtomicBoolean(false);

  public static boolean isQrShowing() {
    return qrShowing.get() || stickyConfigEyes.get();
  }

  /** Gọi ngay khi mở QR (đầu / giọng) — trước khi vẽ xong. */
  public static void markQrShowingForHeadTap() {
    qrShowing.set(true);
    stickyConfigEyes.set(true);
  }

  /** Force hạ cờ QR (khi kẹt sau double-tap lỗi) để chạm đầu wake lại được. */
  public static void clearQrShowingFlag(String reason) {
    stickyConfigEyes.set(false);
    if (qrShowing.getAndSet(false)) {
      Log.w(TAG, "clearQrShowingFlag: " + reason);
    }
  }

  /** Tắt QR/URL — restore mắt cười ngay, không để màn hình đen lâu. */
  public static void dismissQrEyes() {
    stickyConfigEyes.set(false);
    qrShowing.set(false);
    eyeEpoch.incrementAndGet();
    playing.set(false);
    // Chạy nền: HeadEvent thường trên main — không được block restore 10s+.
    new Thread(() -> {
      try {
        restoreNormalEyesFast();
        Log.i(TAG, "dismissQrEyes: restore mắt thường OK");
      } catch (Exception e) {
        Log.w(TAG, "dismissQrEyes: " + e.getMessage());
        try {
          showWakeupSmileEyes();
        } catch (Exception ignored) {
        }
      }
    }, "DismissQrEyes").start();
  }

  public static void setCodeDisplayListener(CodeDisplayListener listener) {
    uiListener = listener;
  }

  private ActivationEyeDisplay() {
  }

  /**
   * Mắt cười như vừa đánh thức — gọi cùng tiếng ting khi mở mic lại sau TTS.
   */
  public static void showWakeupSmileEyes() {
    if (qrShowing.get()) {
      Log.i(TAG, "showWakeupSmileEyes skipped – QR đang hiện");
      return;
    }
    new Thread(() -> {
      if (qrShowing.get()) {
        Log.i(TAG, "showWakeupSmileEyes aborted – QR đang hiện");
        return;
      }
      ExpressApi api;
      try {
        api = ExpressApi.get();
      } catch (Throwable t) {
        Log.w(TAG, "ExpressApi: " + t.getMessage());
        return;
      }
      for (String name : LISTEN_READY_SMILE) {
        try {
          CountDownLatch done = new CountDownLatch(1);
          api.doExpress(name, 1, Priority.HIGH, new AnimationListener() {
            @Override public void onAnimationStart() {
            }

            @Override public void onAnimationEnd(int i) {
              done.countDown();
            }

            @Override public void onAnimationRepeat(int loopNumber) {
            }
          });
          if (done.await(1500, TimeUnit.MILLISECONDS)) {
            Log.i(TAG, "Listen-ready smile OK express=" + name);
            return;
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (Throwable t) {
          Log.d(TAG, "Listen-ready smile miss " + name + ": " + t.getMessage());
        }
      }
    }, "ListenReadySmile").start();
  }

  /**
   * Chỉ cập nhật TextView UI — không vẽ lên mắt (ESP32 ApplyDeviceIdentity cũng không hiện activation UI).
   */
  public static void showCodeOnUiOnly(String code) {
    if (code == null || code.length() == 0) {
      Log.w(TAG, "Activation code is empty");
      return;
    }
    notifyUi(code);
  }

  public static void showCode(String code) {
    if (code == null || code.length() == 0) {
      Log.w(TAG, "Activation code is empty");
      return;
    }
    notifyUi(code);
    if (!playing.compareAndSet(false, true)) {
      return;
    }
    try {
      showBitmapOnBothEyes(renderSingleLineBitmap(code, CODE_MAX_TEXT_SIZE, CODE_MIN_TEXT_SIZE), CODE_HOLD_MS);
      restoreNormalEyes();
    } finally {
      playing.set(false);
    }
  }

  /** Gõ đầu 2 lần: IP 1 dòng nhỏ trên mắt trái + phải, 10s rồi restore. */
  public static void showRobotIpOnEyes() {
    String ip = readWifiIpv4();
    if (ip == null || ip.isEmpty()) {
      Log.w(TAG, "Không lấy được IP Wi‑Fi");
      return;
    }
    Log.i(TAG, "Head double-tap → IP 1 dòng mắt L/R 10s: " + ip);
    notifyUi(ip);

    if (!playing.compareAndSet(false, true)) {
      Log.w(TAG, "Đang hiện mắt — bỏ lần này");
      return;
    }
    try {
      Bitmap bmp = renderSingleLineBitmap(ip, IP_MAX_TEXT_SIZE, IP_MIN_TEXT_SIZE);
      boolean ok = showBitmapOnBothEyes(bmp, IP_HOLD_MS);
      if (!ok) {
        Log.w(TAG, "drawBitmap fail — fallback digit_*");
        playDigitsSequentiallyUnlocked(ip);
      }
      restoreNormalEyes();
      Log.i(TAG, "IP hết 10s — restore mắt");
    } catch (Exception e) {
      Log.e(TAG, "showRobotIpOnEyes: " + e.getMessage(), e);
      restoreNormalEyes();
    } finally {
      playing.set(false);
    }
  }

  /**
   * Có Wi‑Fi: mắt trái = IP+URL, mắt phải = QR.
   * Vẽ 1 lần rồi giữ — không redraw mỗi vài giây (tránh chớp).
   * Double-tap → {@link #dismissQrEyes} tăng epoch → thoát hold.
   */
  public static void showSelfControlIpAndQr(String ip, String portalUrl) {
    String url = portalUrl == null ? "" : portalUrl.trim();
    if (url.isEmpty()) {
      String fallbackIp = (ip == null || ip.isEmpty()) ? "127.0.0.1" : ip.trim();
      url = "http://" + fallbackIp + ":8080";
    }
    String top = (ip == null || ip.isEmpty()) ? readWifiIpv4() : ip.trim();
    if (top == null) top = "";
    final int epoch = eyeEpoch.incrementAndGet();
    Log.i(TAG, "Self-Control mắt: LEFT=IP+URL RIGHT=QR 60s → ip=" + top + " url=" + url);
    notifyUi(top + "\n" + url);
    qrShowing.set(true);
    stickyConfigEyes.set(true);
    playing.set(true);
    Bitmap left = null;
    Bitmap right = null;
    try {
      ensureSelfControlEyeCache(url);
      synchronized (QR_CACHE_LOCK) {
        if (cachedScLeft != null && !cachedScLeft.isRecycled()) {
          left = cachedScLeft.copy(Bitmap.Config.ARGB_8888, false);
        }
        if (cachedScRight != null && !cachedScRight.isRecycled()) {
          right = cachedScRight.copy(Bitmap.Config.ARGB_8888, false);
        }
      }
      if (left == null) left = renderIpAboveUrlBitmap(top, url);
      if (right == null) right = renderQrBitmap(url);

      EyeScreenApi api = EyeScreenApi.get();
      try {
        ExpressApi.get().stopExpress();
      } catch (Exception ignored) {
      }
      if (eyeEpoch.get() != epoch) return;

      drawOneEye(api, left, EYE_LEFT, "LEFT-SC");
      boolean rightOk = drawOneEye(api, right, EYE_RIGHT, "RIGHT-QR");
      if (!rightOk) {
        drawOneEye(api, right, EYE_BOTH, "BOTH-QR");
      }
      Log.i(TAG, "Self-Control put cached bitmaps once – hold " + (SELF_CONTROL_HOLD_MS / 1000) + "s (no redraw)");

      sleepWhileEpoch(epoch, SELF_CONTROL_HOLD_MS);
      if (eyeEpoch.get() == epoch) {
        stickyConfigEyes.set(false);
        qrShowing.set(false);
        restoreNormalEyesFast();
        Log.i(TAG, "Self-Control IP+QR hết hold — restore mắt");
      }
    } catch (Exception e) {
      Log.e(TAG, "showSelfControlIpAndQr: " + e.getMessage(), e);
      stickyConfigEyes.set(false);
      qrShowing.set(false);
      if (eyeEpoch.get() == epoch) restoreNormalEyesFast();
    } finally {
      if (left != null && !left.isRecycled()) left.recycle();
      if (right != null && !right.isRecycled()) right.recycle();
      playing.set(false);
      if (eyeEpoch.get() == epoch) {
        stickyConfigEyes.set(false);
        qrShowing.set(false);
      }
    }
  }

  /**
   * SoftAP provision: mắt trái = tên hotspot + URL portal, mắt phải = QR.
   * Giữ {@value #SELF_CONTROL_HOLD_MS} ms.
   */
  public static void showWifiProvisionUrlAndQr(String apSsid, String portalUrl) {
    if (portalUrl == null || portalUrl.trim().isEmpty()) {
      Log.w(TAG, "showWifiProvisionUrlAndQr: url rỗng");
      return;
    }
    final String u = portalUrl.trim();
    final String ssid = apSsid == null ? "" : apSsid.trim();
    final int epoch = eyeEpoch.incrementAndGet();
    Log.i(TAG, "Provision mắt: LEFT=SSID+URL RIGHT=QR 60s → ssid=" + ssid + " url=" + u);
    notifyUi((ssid.isEmpty() ? "" : ssid + "\n") + u);
    qrShowing.set(true);
    playing.set(true);
    Bitmap left = null;
    Bitmap right = null;
    try {
      EyeScreenApi api = EyeScreenApi.get();
      left = renderProvisionUrlBitmap(ssid, u);
      drawOneEye(api, left, EYE_LEFT, "LEFT-PROVISION");
      drawOneEye(api, left, EYE_BOTH, "BOTH-PROV-TMP");
      if (eyeEpoch.get() != epoch) return;
      right = renderQrBitmap(u);
      if (eyeEpoch.get() != epoch) return;
      boolean rightOk = drawOneEye(api, right, EYE_RIGHT, "RIGHT-QR");
      if (!rightOk) {
        drawOneEye(api, right, EYE_BOTH, "BOTH-QR");
      }
      sleepWhileEpoch(epoch, SELF_CONTROL_HOLD_MS);
      if (eyeEpoch.get() == epoch) {
        restoreNormalEyesFast();
        Log.i(TAG, "Provision URL+QR hết 60s — restore mắt");
      }
    } catch (Exception e) {
      Log.e(TAG, "showWifiProvisionUrlAndQr: " + e.getMessage(), e);
      if (eyeEpoch.get() == epoch) restoreNormalEyesFast();
    } finally {
      if (left != null && !left.isRecycled()) left.recycle();
      if (right != null && !right.isRecycled()) right.recycle();
      playing.set(false);
      if (eyeEpoch.get() == epoch) {
        qrShowing.set(false);
      }
    }
  }

  /**
   * Self-Control {@code show_config_page}: mắt trái IP + URL :8080, mắt phải QR.
   */
  public static void showSelfControlUrlAndQr(String url) {
    String ip = readWifiIpv4();
    if (ip == null) ip = "";
    showSelfControlIpAndQr(ip, url);
  }

  /** Portal đã gửi SSID/pass → force logo bắt Wi‑Fi (nhấp nháy) đến khi OK/FAIL. */
  public static void showWifiConnecting(String ssid) {
    String label = (ssid == null || ssid.isEmpty()) ? "WIFI..." : ssid;
    if (label.length() > 12) label = label.substring(0, 11) + "…";
    notifyUi("Bắt Wi‑Fi: " + label);
    qrShowing.set(false);
    final int epoch = eyeEpoch.incrementAndGet();
    playing.set(true);
    Log.i(TAG, "showWifiConnecting blink epoch=" + epoch + " ssid=" + label);
    new Thread(() -> {
      Bitmap on = null;
      Bitmap off = null;
      try {
        on = renderWifiStatusBitmap(/*ok*/ null, "BẮT WIFI");
        off = Bitmap.createBitmap(EYE_W, EYE_H, Bitmap.Config.ARGB_8888);
        new Canvas(off).drawColor(Color.BLACK);
        boolean lit = true;
        while (eyeEpoch.get() == epoch) {
          showBitmapOnBothEyesNoSleep(lit ? on : off);
          lit = !lit;
          sleepWhileEpoch(epoch, 380);
        }
      } catch (Exception e) {
        Log.e(TAG, "showWifiConnecting blink: " + e.getMessage(), e);
      } finally {
        if (on != null && !on.isRecycled()) on.recycle();
        if (off != null && !off.isRecycled()) off.recycle();
      }
    }, "WifiConnectingBlink").start();
  }

  /** Nối Wi‑Fi nhà thành công — icon Wi‑Fi + tick xanh. */
  public static void showWifiOk() {
    notifyUi("Wi‑Fi OK");
    forceShowStatusBitmap(renderWifiStatusBitmap(Boolean.TRUE, "OK"), WIFI_STATUS_HOLD_MS);
  }

  /** Sai mật khẩu / không nối được — icon Wi‑Fi + X đỏ. */
  public static void showWifiFail() {
    notifyUi("Wi‑Fi FAIL");
    forceShowStatusBitmap(renderWifiStatusBitmap(Boolean.FALSE, "FAIL"), WIFI_STATUS_HOLD_MS);
  }

  private static void forceShowStatusBitmap(Bitmap bmp, long holdMs) {
    qrShowing.set(false); // Wi‑Fi status đè QR
    final int epoch = eyeEpoch.incrementAndGet();
    playing.set(true);
    try {
      showBitmapOnBothEyesNoSleep(bmp);
      if (holdMs > 0) {
        sleepWhileEpoch(epoch, holdMs);
        if (eyeEpoch.get() == epoch) {
          restoreNormalEyes();
        }
      }
      // holdMs==0: giữ frame đến khi có status tiếp theo
    } catch (Exception e) {
      Log.e(TAG, "forceShowStatusBitmap: " + e.getMessage(), e);
    } finally {
      if (bmp != null && !bmp.isRecycled()) bmp.recycle();
      if (holdMs > 0 && eyeEpoch.get() == epoch) {
        playing.set(false);
      }
    }
  }

  private static void sleepWhileEpoch(int epoch, long holdMs) {
    long end = System.currentTimeMillis() + holdMs;
    while (System.currentTimeMillis() < end) {
      if (eyeEpoch.get() != epoch) return;
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static void showBitmapOnBothEyesNoSleep(Bitmap bmp) {
    EyeScreenApi api = EyeScreenApi.get();
    drawOneEye(api, bmp, EYE_LEFT, "LEFT");
    drawOneEye(api, bmp, EYE_RIGHT, "RIGHT");
    drawOneEye(api, bmp, EYE_BOTH, "BOTH");
  }

  /**
   * @param ok null = đang bắt; true = tick xanh; false = X đỏ
   */
  private static Bitmap renderWifiStatusBitmap(Boolean ok, String subtitle) {
    Bitmap bmp = Bitmap.createBitmap(EYE_W, EYE_H, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bmp);
    canvas.drawColor(Color.BLACK);

    float cx = EYE_W / 2f;
    float cy = EYE_H / 2f - 8f;
    Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    arc.setStyle(Paint.Style.STROKE);
    arc.setStrokeCap(Paint.Cap.ROUND);
    arc.setColor(Color.WHITE);
    // 3 vòng cung Wi‑Fi
    float[] radii = {22f, 38f, 54f};
    float[] strokes = {5f, 5f, 5f};
    for (int i = 0; i < radii.length; i++) {
      arc.setStrokeWidth(strokes[i]);
      float r = radii[i];
      android.graphics.RectF oval = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
      canvas.drawArc(oval, 225f, 90f, false, arc);
    }
    // chấm gốc
    Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    dot.setColor(Color.WHITE);
    dot.setStyle(Paint.Style.FILL);
    canvas.drawCircle(cx, cy + 8f, 6f, dot);

    // Badge góc phải: tick / X / ...
    float bx = cx + 48f;
    float by = cy + 28f;
    Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    badge.setStyle(Paint.Style.FILL);
    if (ok == null) {
      badge.setColor(Color.rgb(255, 180, 40));
      canvas.drawCircle(bx, by, 22f, badge);
      Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
      t.setColor(Color.BLACK);
      t.setTextAlign(Paint.Align.CENTER);
      t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
      t.setTextSize(28f);
      canvas.drawText("…", bx, by + 10f, t);
    } else if (ok) {
      badge.setColor(Color.rgb(40, 190, 80));
      canvas.drawCircle(bx, by, 22f, badge);
      Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
      tick.setColor(Color.WHITE);
      tick.setStyle(Paint.Style.STROKE);
      tick.setStrokeWidth(5f);
      tick.setStrokeCap(Paint.Cap.ROUND);
      tick.setStrokeJoin(Paint.Join.ROUND);
      canvas.drawLine(bx - 10f, by, bx - 2f, by + 9f, tick);
      canvas.drawLine(bx - 2f, by + 9f, bx + 12f, by - 10f, tick);
    } else {
      badge.setColor(Color.rgb(220, 50, 50));
      canvas.drawCircle(bx, by, 22f, badge);
      Paint x = new Paint(Paint.ANTI_ALIAS_FLAG);
      x.setColor(Color.WHITE);
      x.setStyle(Paint.Style.STROKE);
      x.setStrokeWidth(5f);
      x.setStrokeCap(Paint.Cap.ROUND);
      canvas.drawLine(bx - 9f, by - 9f, bx + 9f, by + 9f, x);
      canvas.drawLine(bx + 9f, by - 9f, bx - 9f, by + 9f, x);
    }

    if (subtitle != null && !subtitle.isEmpty()) {
      Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
      text.setColor(Color.WHITE);
      text.setTextAlign(Paint.Align.CENTER);
      text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
      text.setTextSize(18f);
      canvas.drawText(subtitle, cx, EYE_H - 28f, text);
    }
    return bmp;
  }

  /** Mắt trái: dòng IP nổi trên, URL portal (tách dòng) phía dưới. */
  private static Bitmap renderIpAboveUrlBitmap(String ip, String url) {
    Bitmap bmp = Bitmap.createBitmap(EYE_W, EYE_H, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bmp);
    canvas.drawColor(Color.BLACK);

    java.util.ArrayList<String> lines = new java.util.ArrayList<>();
    if (ip != null && !ip.isEmpty()) {
      lines.add(ip);
    }
    for (String ul : splitUrlLines(url)) {
      lines.add(ul);
    }
    String[] arr = lines.toArray(new String[0]);

    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    paint.setTextAlign(Paint.Align.CENTER);

    float size = 13f;
    float maxW = CIRCLE_SAFE_SIDE;
    while (size >= 7f) {
      paint.setTextSize(size);
      boolean fit = true;
      for (String line : arr) {
        if (paint.measureText(line) > maxW) {
          fit = false;
          break;
        }
      }
      if (fit) break;
      size -= 0.5f;
    }
    paint.setTextSize(size);
    Paint.FontMetrics fm = paint.getFontMetrics();
    float lineH = (fm.descent - fm.ascent) + 2.5f;
    float blockH = lineH * arr.length;
    float y0 = EYE_H / 2f - blockH / 2f - fm.ascent;
    boolean hasIp = ip != null && !ip.isEmpty();
    for (int i = 0; i < arr.length; i++) {
      if (i == 0 && hasIp) {
        paint.setColor(Color.rgb(120, 255, 160)); // IP trên
      } else {
        paint.setColor(Color.WHITE); // URL dưới
      }
      canvas.drawText(arr[i], EYE_W / 2f, y0 + i * lineH, paint);
    }
    return bmp;
  }

  /** Mắt trái SoftAP: dòng 1 = tên Wi‑Fi robot, các dòng sau = URL portal. */
  private static Bitmap renderProvisionUrlBitmap(String apSsid, String url) {
    Bitmap bmp = Bitmap.createBitmap(EYE_W, EYE_H, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bmp);
    canvas.drawColor(Color.BLACK);

    java.util.ArrayList<String> lines = new java.util.ArrayList<>();
    if (apSsid != null && !apSsid.isEmpty()) {
      lines.add(apSsid);
    }
    String[] urlLines = splitUrlLines(url);
    for (String ul : urlLines) {
      lines.add(ul);
    }
    String[] arr = lines.toArray(new String[0]);

    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor(Color.WHITE);
    paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    paint.setTextAlign(Paint.Align.CENTER);

    float size = 13f;
    float maxW = CIRCLE_SAFE_SIDE;
    while (size >= 7f) {
      paint.setTextSize(size);
      boolean fit = true;
      for (String line : arr) {
        if (paint.measureText(line) > maxW) {
          fit = false;
          break;
        }
      }
      if (fit) break;
      size -= 0.5f;
    }
    paint.setTextSize(size);
    Paint.FontMetrics fm = paint.getFontMetrics();
    float lineH = (fm.descent - fm.ascent) + 2.5f;
    float blockH = lineH * arr.length;
    float y0 = EYE_H / 2f - blockH / 2f - fm.ascent;
    for (int i = 0; i < arr.length; i++) {
      // Dòng SSID nổi hơn một chút
      if (i == 0 && apSsid != null && !apSsid.isEmpty()) {
        paint.setColor(Color.rgb(120, 200, 255));
      } else {
        paint.setColor(Color.WHITE);
      }
      canvas.drawText(arr[i], EYE_W / 2f, y0 + i * lineH, paint);
    }
    return bmp;
  }

  /** URL nhiều dòng — chỉ trong vùng tròn nhìn được. */
  private static Bitmap renderWrappedUrlBitmap(String url) {
    return renderProvisionUrlBitmap("", url);
  }

  private static String[] splitUrlLines(String url) {
    // http://192.168.x.x:8080 → 3 dòng dễ đọc trên mắt 240px
    String u = url;
    String scheme = "";
    if (u.startsWith("http://")) {
      scheme = "http://";
      u = u.substring(7);
    } else if (u.startsWith("https://")) {
      scheme = "https://";
      u = u.substring(8);
    }
    int colon = u.lastIndexOf(':');
    if (colon > 0 && colon < u.length() - 1) {
      String host = u.substring(0, colon);
      String port = u.substring(colon);
      if (!scheme.isEmpty()) {
        return new String[] {scheme, host, port};
      }
      return new String[] {host, port};
    }
    if (!scheme.isEmpty()) {
      return new String[] {scheme, u};
    }
    return new String[] {url};
  }

  private static final Object QR_CACHE_LOCK = new Object();
  /** Cache QR đơn (SoftAP portal hoặc URL lẻ). */
  private static volatile String cachedQrContent = null;
  private static volatile Bitmap cachedQrBitmap = null;
  /** Cache cặp mắt Self-Control :8080 — encode 1 lần / mỗi lần đổi IP. */
  private static volatile String cachedScUrl = null;
  private static volatile Bitmap cachedScLeft = null;
  private static volatile Bitmap cachedScRight = null;
  private static final String SC_META = "sc_eye_cache_url.txt";
  private static final String SC_LEFT_FILE = "sc_eye_left.png";
  private static final String SC_RIGHT_FILE = "sc_eye_right.png";
  private static volatile Context appCtx;

  public static void bindAppContext(Context context) {
    if (context != null) {
      appCtx = context.getApplicationContext();
    }
  }

  /**
   * Boot: chờ có IP LAN rồi encode sẵn LEFT+RIGHT vào RAM (+ file).
   * Gọi sớm — trước khi Xiaozhi WS sẵn sàng.
   */
  public static void startBootSelfControlEyeWarm(Context context) {
    bindAppContext(context);
    new Thread(
            () -> {
              for (int i = 0; i < 90; i++) {
                try {
                  String ip = readWifiIpv4();
                  if (ip != null && !ip.isEmpty() && !ip.equals("127.0.0.1") && !ip.equals("0.0.0.0")) {
                    String url = "http://" + ip + ":8080";
                    warmSelfControlEyeCache(url);
                    Log.i(TAG, "boot Self-Control eye warm OK url=" + url);
                    return; 
                  }
                } catch (Exception e) {
                  Log.d(TAG, "boot warm wait: " + e.getMessage());
                }
                try {
                  Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
              Log.w(TAG, "boot Self-Control eye warm: hết chờ IP");
            },
            "BootWarmScEyes")
        .start();
  }

  private static boolean isUsableLanUrl(String url) {
    if (url == null) return false;
    String u = url.trim();
    return !u.isEmpty()
        && !u.contains("127.0.0.1")
        && !u.contains("0.0.0.0")
        && u.startsWith("http");
  }

  /**
   * Encode sẵn cặp mắt Self-Control (không hiện). Chỉ encode lại khi URL/IP đổi.
   */
  public static void warmSelfControlEyeCache(String url) {
    if (!isUsableLanUrl(url)) return;
    final String u = url.trim();
    synchronized (QR_CACHE_LOCK) {
      if (u.equals(cachedScUrl)
          && cachedScLeft != null
          && !cachedScLeft.isRecycled()
          && cachedScRight != null
          && !cachedScRight.isRecycled()) {
        Log.d(TAG, "warmSelfControlEyeCache HIT (đã có ảnh)");
        return;
      }
    }
    new Thread(
            () -> {
              try {
                ensureSelfControlEyeCache(u);
                Log.i(TAG, "warmSelfControlEyeCache OK url=" + u);
              } catch (Exception e) {
                Log.w(TAG, "warmSelfControlEyeCache: " + e.getMessage());
              }
            },
            "WarmScEyes")
        .start();
  }

  /** Đồng bộ: đảm bảo cache LEFT+RIGHT cho url (encode hoặc load file). */
  private static void ensureSelfControlEyeCache(String url) throws Exception {
    synchronized (QR_CACHE_LOCK) {
      if (url.equals(cachedScUrl)
          && cachedScLeft != null
          && !cachedScLeft.isRecycled()
          && cachedScRight != null
          && !cachedScRight.isRecycled()) {
        return;
      }
      // Thử load file nếu URL trùng lần trước
      if (tryLoadSelfControlEyeFiles(url)) {
        return;
      }
      String ip = readWifiIpv4();
      if (ip == null || ip.isEmpty()) {
        ip = extractHostFromUrl(url);
      }
      Bitmap left = renderIpAboveUrlBitmap(ip, url);
      Bitmap right = encodeQrBitmap(url);
      recycleSilent(cachedScLeft);
      recycleSilent(cachedScRight);
      cachedScLeft = left;
      cachedScRight = right;
      cachedScUrl = url;
      // Đồng bộ cache QR đơn
      recycleSilent(cachedQrBitmap);
      cachedQrBitmap = right.copy(Bitmap.Config.ARGB_8888, false);
      cachedQrContent = url;
      persistSelfControlEyeFiles(url, left, right);
      Log.i(TAG, "Self-Control eye cache ENCODE + save file url=" + url);
    }
  }

  private static String extractHostFromUrl(String url) {
    try {
      String u = url.replace("http://", "").replace("https://", "");
      int slash = u.indexOf('/');
      if (slash >= 0) u = u.substring(0, slash);
      int colon = u.lastIndexOf(':');
      if (colon > 0) u = u.substring(0, colon);
      return u;
    } catch (Exception e) {
      return "";
    }
  }

  private static boolean tryLoadSelfControlEyeFiles(String url) {
    Context ctx = appCtx;
    if (ctx == null) return false;
    try {
      java.io.File dir = ctx.getFilesDir();
      java.io.File meta = new java.io.File(dir, SC_META);
      java.io.File leftF = new java.io.File(dir, SC_LEFT_FILE);
      java.io.File rightF = new java.io.File(dir, SC_RIGHT_FILE);
      if (!meta.isFile() || !leftF.isFile() || !rightF.isFile()) return false;
      StringBuilder sb = new StringBuilder();
      try (java.io.BufferedReader br =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(
                  new java.io.FileInputStream(meta), java.nio.charset.StandardCharsets.UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
      }
      if (!url.equals(sb.toString().trim())) return false;
      Bitmap left = android.graphics.BitmapFactory.decodeFile(leftF.getAbsolutePath());
      Bitmap right = android.graphics.BitmapFactory.decodeFile(rightF.getAbsolutePath());
      if (left == null || right == null) return false;
      recycleSilent(cachedScLeft);
      recycleSilent(cachedScRight);
      cachedScLeft = left;
      cachedScRight = right;
      cachedScUrl = url;
      Log.i(TAG, "Self-Control eye cache LOAD file HIT url=" + url);
      return true;
    } catch (Exception e) {
      Log.d(TAG, "load eye files: " + e.getMessage());
      return false;
    }
  }

  private static void persistSelfControlEyeFiles(String url, Bitmap left, Bitmap right) {
    Context ctx = appCtx;
    if (ctx == null || left == null || right == null) return;
    try {
      java.io.File dir = ctx.getFilesDir();
      try (java.io.FileOutputStream fos = new java.io.FileOutputStream(new java.io.File(dir, SC_LEFT_FILE))) {
        left.compress(Bitmap.CompressFormat.PNG, 100, fos);
      }
      try (java.io.FileOutputStream fos = new java.io.FileOutputStream(new java.io.File(dir, SC_RIGHT_FILE))) {
        right.compress(Bitmap.CompressFormat.PNG, 100, fos);
      }
      try (java.io.FileOutputStream fos = new java.io.FileOutputStream(new java.io.File(dir, SC_META))) {
        fos.write(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      Log.i(TAG, "Self-Control eye cache saved PNG + meta");
    } catch (Exception e) {
      Log.w(TAG, "persist eye files: " + e.getMessage());
    }
  }

  private static void recycleSilent(Bitmap b) {
    if (b != null && !b.isRecycled()) {
      try {
        b.recycle();
      } catch (Exception ignored) {
      }
    }
  }

  /**
   * Encode sẵn QR vào cache (không hiện mắt). SoftAP / URL lẻ.
   * Self-Control :8080 nên dùng {@link #warmSelfControlEyeCache}.
   */
  public static void warmQrCache(String content) {
    if (content == null || content.trim().isEmpty()) return;
    final String c = content.trim();
    if (c.contains(":8080")) {
      warmSelfControlEyeCache(c);
      return;
    }
    synchronized (QR_CACHE_LOCK) {
      if (c.equals(cachedQrContent)
          && cachedQrBitmap != null
          && !cachedQrBitmap.isRecycled()) {
        return;
      }
    }
    new Thread(
            () -> {
              try {
                Bitmap discard = renderQrBitmap(c);
                if (discard != null && !discard.isRecycled()) discard.recycle();
                Log.i(TAG, "warmQrCache OK");
              } catch (Exception e) {
                Log.w(TAG, "warmQrCache: " + e.getMessage());
              }
            },
            "WarmQrCache")
        .start();
  }

  /**
   * QR vừa mắt tròn: buffer 240×240 vuông nhưng mắt vật lý tròn.
   * Cache theo nội dung URL — chỉ encode lại khi IP/URL đổi.
   */
  private static Bitmap renderQrBitmap(String content) throws Exception {
    if (content == null) content = "";
    synchronized (QR_CACHE_LOCK) {
      if (content.equals(cachedQrContent)
          && cachedQrBitmap != null
          && !cachedQrBitmap.isRecycled()) {
        Log.i(TAG, "QR cache HIT (không encode lại)");
        return cachedQrBitmap.copy(Bitmap.Config.ARGB_8888, false);
      }
      if (content.equals(cachedScUrl)
          && cachedScRight != null
          && !cachedScRight.isRecycled()) {
        Log.i(TAG, "QR cache HIT from Self-Control right");
        return cachedScRight.copy(Bitmap.Config.ARGB_8888, false);
      }
      if (cachedQrBitmap != null && !cachedQrBitmap.isRecycled()) {
        cachedQrBitmap.recycle();
      }
      cachedQrBitmap = encodeQrBitmap(content);
      cachedQrContent = content;
      Log.i(TAG, "QR cache MISS encode mới content=" + content);
      return cachedQrBitmap.copy(Bitmap.Config.ARGB_8888, false);
    }
  }

  private static Bitmap encodeQrBitmap(String content) throws Exception {
    Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
    hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
    hints.put(EncodeHintType.MARGIN, 1);
    int encodeDim = CIRCLE_SAFE_SIDE;
    BitMatrix matrix =
        new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, encodeDim, encodeDim, hints);
    int mw = matrix.getWidth();
    int mh = matrix.getHeight();
    Bitmap qr = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888);
    for (int y = 0; y < mh; y++) {
      for (int x = 0; x < mw; x++) {
        qr.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
      }
    }

    Bitmap out = Bitmap.createBitmap(EYE_W, EYE_H, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(out);
    canvas.drawColor(Color.BLACK);

    float cx = EYE_W / 2f;
    float cy = EYE_H / 2f;
    float discR = CIRCLE_SAFE_SIDE / 2f + 8f;
    Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    disc.setColor(Color.WHITE);
    disc.setStyle(Paint.Style.FILL);
    canvas.drawCircle(cx, cy, discR, disc);

    int qrSide = CIRCLE_SAFE_SIDE;
    Bitmap scaled = Bitmap.createScaledBitmap(qr, qrSide, qrSide, false);
    canvas.drawBitmap(scaled, cx - qrSide / 2f, cy - qrSide / 2f, null);

    if (scaled != qr) scaled.recycle();
    qr.recycle();
    Log.i(TAG, "QR encode xong: buffer=240 safeSide=" + CIRCLE_SAFE_SIDE + " discR=" + discR);
    return out;
  }

  /** Một dòng chữ giữa mắt, tự giảm size đến khi vừa ngang. */
  private static Bitmap renderSingleLineBitmap(String text, float maxSize, float minSize) {
    Bitmap bmp = Bitmap.createBitmap(EYE_W, EYE_H, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bmp);
    canvas.drawColor(Color.BLACK);

    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor(Color.WHITE);
    paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    paint.setTextAlign(Paint.Align.CENTER);

    float maxW = EYE_W - 20f;
    float size = fitSingleLine(paint, text, maxW, maxSize, minSize);
    paint.setTextSize(size);
    Paint.FontMetrics fm = paint.getFontMetrics();
    float y = EYE_H / 2f - (fm.ascent + fm.descent) / 2f;
    canvas.drawText(text, EYE_W / 2f, y, paint);
    Log.i(TAG, "single-line bitmap size=" + size + " measure=" + paint.measureText(text) + " text=" + text);
    return bmp;
  }

  private static float fitSingleLine(
      Paint paint, String text, float maxWidth, float maxSize, float minSize) {
    float size = maxSize;
    while (size >= minSize) {
      paint.setTextSize(size);
      if (paint.measureText(text) <= maxWidth) {
        return size;
      }
      size -= 0.5f;
    }
    return minSize;
  }

  /**
   * Vẽ cùng nội dung lên mắt trái (0) và mắt phải (1).
   * Có xác định L/R: {@link #EYE_LEFT}/{@link #EYE_RIGHT}.
   */
  private static boolean showBitmapOnBothEyes(Bitmap bmp, long holdMs) {
    EyeScreenApi api = EyeScreenApi.get();
    boolean leftOk = drawOneEye(api, bmp, EYE_LEFT, "LEFT");
    boolean rightOk = drawOneEye(api, bmp, EYE_RIGHT, "RIGHT");
    // Một số firmware cần index 2 mới hiện cả hai
    boolean bothOk = drawOneEye(api, bmp, EYE_BOTH, "BOTH");
    boolean any = leftOk || rightOk || bothOk;
    if (!any) {
      return false;
    }
    try {
      Thread.sleep(holdMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return true;
  }

  private static boolean drawOneEye(EyeScreenApi api, Bitmap bmp, int screenIndex, String label) {
    try {
      api.drawBitmap(bmp, screenIndex);
      Log.i(TAG, "drawBitmap OK eye=" + label + " screenIndex=" + screenIndex);
      return true;
    } catch (Exception e) {
      Log.w(TAG, "drawBitmap FAIL eye=" + label + " screenIndex=" + screenIndex + ": " + e.getMessage());
      return false;
    }
  }

  private static void restoreNormalEyes() {
    if (qrShowing.get()) {
      Log.i(TAG, "restoreNormalEyes skipped – QR đang hiện");
      return;
    }
    try {
      ExpressApi.get().stopExpress();
    } catch (Exception e) {
      Log.d(TAG, "stopExpress: " + e.getMessage());
    }
    Bitmap black = Bitmap.createBitmap(EYE_W, EYE_H, Bitmap.Config.ARGB_8888);
    new Canvas(black).drawColor(Color.BLACK);
    EyeScreenApi eyeApi = EyeScreenApi.get();
    for (int idx : new int[] {EYE_LEFT, EYE_RIGHT, EYE_BOTH}) {
      try {
        eyeApi.drawBitmap(black, idx);
      } catch (Exception ignored) {
      }
    }
    black.recycle();

    ExpressApi api = ExpressApi.get();
    for (String name : RESTORE_EYE_EXPRESS) {
      try {
        CountDownLatch done = new CountDownLatch(1);
        api.doExpress(name, 1, Priority.HIGH, new AnimationListener() {
          @Override public void onAnimationStart() {
          }

          @Override public void onAnimationEnd(int i) {
            done.countDown();
          }

          @Override public void onAnimationRepeat(int loopNumber) {
          }
        });
        if (done.await(2, TimeUnit.SECONDS)) {
          Log.i(TAG, "Restore mắt OK express=" + name);
          return;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        Log.d(TAG, "Restore miss " + name + ": " + e.getMessage());
      }
    }
    tryPowerOnEyes();
  }

  /** Restore nhanh khi tắt QR: không vẽ đen, chỉ express wakeup (timeout ngắn). */
  private static void restoreNormalEyesFast() {
    if (qrShowing.get()) {
      Log.i(TAG, "restoreNormalEyesFast skipped – QR đang hiện");
      return;
    }
    try {
      ExpressApi.get().stopExpress();
    } catch (Exception ignored) {
    }
    tryPowerOnEyes();
    ExpressApi api = ExpressApi.get();
    for (String name : LISTEN_READY_SMILE) {
      try {
        CountDownLatch done = new CountDownLatch(1);
        api.doExpress(name, 1, Priority.HIGH, new AnimationListener() {
          @Override public void onAnimationStart() {
          }

          @Override public void onAnimationEnd(int i) {
            done.countDown();
          }

          @Override public void onAnimationRepeat(int loopNumber) {
          }
        });
        if (done.await(400, TimeUnit.MILLISECONDS)) {
          Log.i(TAG, "RestoreFast OK express=" + name);
          return;
        }
        // Timeout: express có thể vẫn đang chạy — coi như OK, không thử hết list (tránh chậm)
        Log.i(TAG, "RestoreFast fire express=" + name + " (no wait)");
        return;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        Log.d(TAG, "RestoreFast miss " + name + ": " + e.getMessage());
      }
    }
  }

  private static void tryPowerOnEyes() {
    try {
      Class<?> powerApi = Class.forName("com.ubtrobot.power.PowerApi");
      Object api = powerApi.getMethod("get").invoke(null);
      Boolean on = (Boolean) powerApi.getMethod("isEyesPowerOn").invoke(api);
      if (on == null || !on) {
        powerApi.getMethod("powerOnEyes").invoke(api);
        Log.i(TAG, "PowerApi.powerOnEyes()");
      }
    } catch (Exception e) {
      Log.d(TAG, "PowerApi: " + e.getMessage());
    }
  }

  private static void notifyUi(String text) {
    CodeDisplayListener listener = uiListener;
    if (listener != null) {
      listener.onCodeReceived(text);
    }
  }

  private static void playDigitsSequentiallyUnlocked(String text) {
    ExpressApi api = ExpressApi.get();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        continue;
      }
      String name = "digit_" + c;
      CountDownLatch done = new CountDownLatch(1);
      try {
        api.doExpress(name, 1, Priority.HIGH, new AnimationListener() {
          @Override public void onAnimationStart() {
          }

          @Override public void onAnimationEnd(int i) {
            done.countDown();
          }

          @Override public void onAnimationRepeat(int loopNumber) {
          }
        });
      } catch (Exception e) {
        done.countDown();
      }
      try {
        done.await(3, TimeUnit.SECONDS);
        Thread.sleep(PAUSE_AFTER_DIGIT_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  public static String readWifiIpv4() {
    try {
      Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
      if (ifaces == null) {
        return null;
      }
      String fallback = null;
      for (NetworkInterface nif : Collections.list(ifaces)) {
        if (!nif.isUp() || nif.isLoopback()) {
          continue;
        }
        String name = nif.getName() != null ? nif.getName().toLowerCase() : "";
        for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
          if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) {
            continue;
          }
          String host = addr.getHostAddress();
          if (host == null || host.isEmpty()) {
            continue;
          }
          if (name.contains("wlan") || name.contains("wifi")) {
            return host;
          }
          if (fallback == null) {
            fallback = host;
          }
        }
      }
      return fallback;
    } catch (Exception e) {
      Log.w(TAG, "readWifiIpv4: " + e.getMessage());
      return null;
    }
  }
}
