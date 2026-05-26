package com.ubtrobot.mini.speech.framework.demo;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gọi API robot Alpha Mini khi Xiaozhi gửi {@code type=mcp} hoặc {@code type=iot}:
 * <ul>
 *   <li><b>Tiến/lùi</b>: {@code SkillApi GO_AHEAD|BACK_UP}; fallback {@code keep_moving_forward} / {@code keep_going_backwards}.</li>
 *   <li><b>Quay trái/phải</b>: {@code SkillApi TURN_LEFT|TURN_RIGHT}; fallback {@code keep_turning_left} / {@code keep_turning_right}.</li>
 *   <li><b>Biểu cảm LLM</b> ({@code type:"llm", emotion}): {@link LlmEmotionSkillMapper} → {@code SkillApi.startSkill}.</li>
 *   <li>Skill khác: {@code SkillApi.startSkill} → {@code SkillsProxy} → {@code SkillHelper.startSkillByIntent}.</li>
 *   <li>{@code ActionApi.playAction}, {@code StandUpApi}, {@code TakePicApi}.</li>
 * </ul>
 * Reflection để build không phụ thuộc AAR — trên robot Mini có SDK hệ thống thì mới chạy được.
 */
public final class MiniRobotActionInvoker {
    private static final String TAG = "MiniRobotAction";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * <b>Chỉ để thử nghiệm:</b> MCP {@code walk_forward} (đi tới) gửi utterance {@code sit_down} qua SkillsProxy
     * — đúng ví dụ {@code startSkillByUtterance(Context, "sit_down", null)} trong {@code call-builtin-skills.txt}.
     * <p><b>Kết quả thử trên ROM demo:</b> cả {@code sit_down} vẫn {@code callPath=null} — không phải do sai tên
     * {@code keep_moving_forward}; toàn bộ speech builtin qua {@code SkillsProxy} + {@code robot:…sysmaster} không được route.</p>
     * <p><b>Đặt {@code true} chỉ khi cần thử lại;</b> mặc định {@code false}.</p>
     */
    private static final boolean TEST_WALK_FORWARD_USES_SIT_DOWN_ON_SKILLS_PROXY = false;

    /** Enum {@code SkillApi.SKILL_NAME} trên ROM Alpha Mini (đã xác nhận qua {@code loadAllSkills}). */
    private static final String SKILL_API_GO_AHEAD = "GO_AHEAD";
    private static final String SKILL_API_BACK_UP = "BACK_UP";
    private static final String SKILL_API_TURN_LEFT = "TURN_LEFT";
    private static final String SKILL_API_TURN_RIGHT = "TURN_RIGHT";
    private static final String SKILL_API_SHUT_DOWN = "SHUT_DOWN";
    /** ROM skill #37 — 弓步 (cung bộ / bow step). */
    private static final String SKILL_API_BOW_STEP = "BOW_STEP";
    /** ROM skill #2 — 太极 (thái cực / Tai Chi). */
    private static final String SKILL_API_TAIJI = "TAIJI";
    /** Motion ROM — 打功夫 (giống {@code ActionApiActivity}: {@code unsafeAction("013")}). */
    private static final String ACTION_API_KUNGFU_ID = RobotBuiltinActionCatalog.ACTION_013_KUNG_FU;

    /** Dùng cho {@link #trySkillsProxySpeechUtterance}; gán trong {@link SpeechApplication#onCreate()}. */
    private static volatile Context sAppContext;

    /** Endpoint vision từ server (MCP {@code initialize} hoặc JSON {@code hello}). */
    private static volatile String sVisionExplainUrl = "";
    private static volatile String sVisionBearerToken = "";
    /** Giống header WebSocket: Device-Id / Client-Id khi POST vision. */
    private static volatile String sWireDeviceId = "";
    private static volatile String sWireClientId = "";

    private static final int MAX_JPEG_READ_BYTES = 12 * 1024 * 1024;

    private MiniRobotActionInvoker() {}

    public static void initApplicationContext(Context applicationContext) {
        if (applicationContext != null) {
            sAppContext = applicationContext.getApplicationContext();
        }
    }

    /**
     * Gọi từ {@link XiaozhiSessionManager} — trùng giá trị header WebSocket (Device-Id / Client-Id).
     */
    public static void setXiaozhiWireIdentities(String deviceId, String clientId) {
        sWireDeviceId = deviceId != null ? deviceId : "";
        sWireClientId = clientId != null ? clientId : "";
    }

    /**
     * Khối {@code vision} từ MCP {@code initialize} params.capabilities (giống ESP32
     * {@code McpServer::ParseCapabilities}).
     */
    public static void updateVisionFromCapabilitiesJson(JSONObject vision) {
        if (vision == null) return;
        String u = vision.optString("url", "").trim();
        String t = vision.optString("token", "").trim();
        if (!u.isEmpty()) {
            sVisionExplainUrl = u;
            sVisionBearerToken = t;
            Log.i(TAG, "Vision: đã lưu url (len=" + u.length() + ") token=" + (t.isEmpty() ? "empty" : "set"));
        }
    }

    /**
     * Một số server gửi {@code vision} ngay trong {@code type=hello}.
     */
    public static void ingestVisionFromServerHello(JSONObject hello) {
        if (hello == null) return;
        JSONObject cap = hello.optJSONObject("capabilities");
        JSONObject v = cap != null ? cap.optJSONObject("vision") : null;
        if (v == null) {
            v = hello.optJSONObject("vision");
        }
        if (v != null) {
            updateVisionFromCapabilitiesJson(v);
        }
    }

    /**
     * Chụp {@link com.ubtechinc.sauron.api.TakePicApi} → đọc JPEG → POST vision (ESP32-style).
     *
     * @return {@code first} = HTTP 2xx và TakePic thành công; {@code second} = body JSON/text từ server hoặc thông báo lỗi.
     */
    public static Pair<Boolean, String> takePhotoAndExplainVision(String question) {
        String q = (question == null || question.trim().isEmpty())
                ? "Hãy mô tả ảnh chụp từ robot."
                : question.trim();
        String url = sVisionExplainUrl != null ? sVisionExplainUrl.trim() : "";
        if (url.isEmpty()) {
            String msg = "Chưa có vision.url (đợi MCP initialize hoặc hello từ server). Chỉ chụp ROM không đủ cho phân tích Xiaozhi.";
            Log.w(TAG, msg);
            return Pair.create(false, msg);
        }
        try {
            byte[] jpeg = captureJpegForVisionWithFallback();
            Log.i(TAG, "JPEG cho vision, len=" + jpeg.length);
            VisionExplainUploader.Result res = VisionExplainUploader.upload(
                    url,
                    sVisionBearerToken,
                    sWireDeviceId,
                    sWireClientId,
                    q,
                    jpeg);
            boolean ok = res.isHttp2xx();
            return Pair.create(ok, res.body.isEmpty() ? ("HTTP " + res.httpCode) : res.body);
        } catch (Throwable t) {
            Log.e(TAG, "takePhotoAndExplainVision: " + t.getMessage(), t);
            return Pair.create(false, t.getMessage() != null ? t.getMessage() : "unknown error");
        }
    }

    /**
     * Một số ROM không đăng ký {@code /api/camera/take_picture_Immediately} (404 Call NOT found) nhưng vẫn có
     * {@code takePicWithFaceDetect} — giống {@link com.ubtrobot.mini.sdkdemo.TakePicApiActivity}.
     */
    private static boolean shouldRetryTakePicWithFaceDetect(String failureMsg) {
        if (failureMsg == null || failureMsg.isEmpty()) {
            return false;
        }
        if (failureMsg.contains("take_picture_Immediately")) {
            return true;
        }
        return failureMsg.contains("404") && failureMsg.contains("Call NOT found");
    }

    /**
     * @param methodName {@code takePicImmediately} hoặc {@code takePicWithFaceDetect} (đều nhận ResponseListener)
     */
    private static String invokeTakePicMethodSync(String methodName) throws IOException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> successStr = new AtomicReference<>();
        final AtomicInteger failCode = new AtomicInteger(0);
        final AtomicReference<String> failMsg = new AtomicReference<>("");
        final AtomicReference<String> setupFailure = new AtomicReference<>();
        Runnable job = () -> {
            try {
                Class<?> c = Class.forName("com.ubtechinc.sauron.api.TakePicApi");
                Method get = c.getMethod("get");
                Object api = get.invoke(null);
                Class<?> listenerClass = Class.forName("com.ubtrobot.commons.ResponseListener");
                Object listener = Proxy.newProxyInstance(
                        listenerClass.getClassLoader(),
                        new Class<?>[]{listenerClass},
                        (proxy, method, args) -> {
                            if ("onResponseSuccess".equals(method.getName())) {
                                if (args != null && args.length > 0 && args[0] != null) {
                                    successStr.set(String.valueOf(args[0]));
                                }
                                latch.countDown();
                            } else if ("onFailure".equals(method.getName())) {
                                int code = -1;
                                if (args != null && args.length > 0 && args[0] instanceof Number) {
                                    code = ((Number) args[0]).intValue();
                                }
                                failCode.set(code);
                                failMsg.set(args != null && args.length > 1 ? String.valueOf(args[1]) : "");
                                latch.countDown();
                            }
                            return null;
                        });
                Method take = c.getMethod(methodName, listenerClass);
                take.invoke(api, listener);
            } catch (Throwable t) {
                Log.e(TAG, "TakePicApi." + methodName + " setup/callback", t);
                if (t instanceof ClassNotFoundException) {
                    setupFailure.set(
                            "APK không có class Sauron (TakePicApi). Copy từ gói SDK UBT / mini-outer-sdk-demo "
                                    + "các JAR hoặc AAR có package com.ubtechinc.sauron vào "
                                    + "speechFrameworkDemo/libs/sauron/ (xem libs/sauron/README.txt), Sync Gradle, build lại.");
                } else if (t instanceof NoSuchMethodException) {
                    setupFailure.set("TakePicApi không có method " + methodName
                            + " — cập nhật JAR Sauron hoặc ROM.");
                } else {
                    setupFailure.set(t.getClass().getSimpleName() + ": "
                            + (t.getMessage() != null ? t.getMessage() : "no message"));
                }
                latch.countDown();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            job.run();
        } else {
            MAIN.post(job);
        }
        try {
            if (!latch.await(45, TimeUnit.SECONDS)) {
                throw new IOException("TakePicApi." + methodName + ": timeout 45s (callback không về)");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("TakePicApi." + methodName + ": interrupted", ie);
        }
        if (setupFailure.get() != null) {
            throw new IOException(setupFailure.get());
        }
        if (failCode.get() != 0) {
            throw new IOException("TakePicApi." + methodName + " onFailure code=" + failCode.get()
                    + " msg=" + failMsg.get());
        }
        String out = successStr.get();
        if (out == null || out.trim().isEmpty()) {
            throw new IOException("TakePicApi." + methodName
                    + " onResponseSuccess nhưng chuỗi trống — kiểm tra ROM / log raw");
        }
        return out;
    }

    /** Master không đăng ký route camera (404 Call NOT found). */
    private static boolean isMasterCameraRouteMissing(String msg) {
        if (msg == null || msg.isEmpty()) {
            return false;
        }
        return msg.contains("404")
                && msg.contains("Call NOT found")
                && msg.contains("/api/camera/");
    }

    /**
     * TakePic/Master trước; nếu ROM không có Sauron camera hoặc APK thiếu class, thử
     * {@link DirectCamera2JpegCapture} (Camera2 + ImageReader).
     */
    private static boolean directCameraFallbackAllowed(IOException e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) {
            return false;
        }
        if (m.contains("APK không có class Sauron")) {
            return true;
        }
        if (m.contains("ROM không có dịch vụ camera Sauron")) {
            return true;
        }
        if (isMasterCameraRouteMissing(m)) {
            return true;
        }
        return m.contains("take_picture_Immediately")
                && m.contains("take_a_picture")
                && m.contains("404");
    }

    private static byte[] captureJpegForVisionWithFallback() throws IOException {
        try {
            String raw = captureTakePicResultSync();
            Log.i(TAG, "TakePicApi callback raw=" + raw);
            byte[] jpeg = readJpegBytesFromTakePicString(raw);
            if (jpeg == null || jpeg.length == 0) {
                throw new IOException("Không đọc được JPEG từ kết quả TakePicApi: " + raw);
            }
            CameraShutterSound.playOnCaptureSuccess();
            return jpeg;
        } catch (IOException e) {
            if (!directCameraFallbackAllowed(e)) {
                throw e;
            }
            Log.w(TAG, "TakePic/Master không dùng được — thử Camera2 trực tiếp…");
            byte[] jpeg = DirectCamera2JpegCapture.captureOneJpegBlocking(sAppContext, 22_000);
            if (jpeg == null || jpeg.length == 0) {
                throw new IOException(
                        "Camera2 không chụp được JPEG (quyền CAMERA, camera busy, hoặc ROM khóa). "
                                + "Gốc TakePic: "
                                + e.getMessage());
            }
            Log.i(TAG, "Đã chụp JPEG qua Camera2 fallback, len=" + jpeg.length);
            CameraShutterSound.playOnCaptureSuccess();
            return jpeg;
        }
    }

    private static String captureTakePicResultSync() throws IOException {
        try {
            return invokeTakePicMethodSync("takePicImmediately");
        } catch (IOException e) {
            String m = e.getMessage() != null ? e.getMessage() : "";
            if (shouldRetryTakePicWithFaceDetect(m)) {
                Log.w(TAG, "takePicImmediately thất bại trên ROM (" + m + "), thử takePicWithFaceDetect…");
                try {
                    return invokeTakePicMethodSync("takePicWithFaceDetect");
                } catch (IOException e2) {
                    String m2 = e2.getMessage() != null ? e2.getMessage() : "";
                    if (isMasterCameraRouteMissing(m) && isMasterCameraRouteMissing(m2)) {
                        throw new IOException(
                                "ROM không có dịch vụ camera Sauron: Master trả 404 (Call NOT found) cho cả "
                                        + "take_picture_Immediately và take_a_picture. "
                                        + "Firmware/thiết bị này không expose camera qua TakePicApi — cần ROM Alpha Mini "
                                        + "đủ Sauron camera hoặc cập nhật từ UBT. "
                                        + "Chi tiết: [takePicImmediately] " + m + " | [takePicWithFaceDetect] " + m2);
                    }
                    throw new IOException(
                            "[takePicImmediately] " + m + " | [takePicWithFaceDetect] " + m2);
                }
            }
            throw e;
        }
    }

    private static byte[] readJpegBytesFromTakePicString(String raw) throws IOException {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(s);
                if (o.has("path")) {
                    s = o.optString("path", "").trim();
                } else if (o.has("file")) {
                    s = o.optString("file", "").trim();
                } else if (o.has("url")) {
                    s = o.optString("url", "").trim();
                }
            } catch (Exception ignored) {
                // không phải JSON — xử lý như chuỗi thường
            }
        }
        if (s.startsWith("http://") || s.startsWith("https://")) {
            okhttp3.Request req = new okhttp3.Request.Builder().url(s).get().build();
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build();
            okhttp3.Response resp = client.newCall(req).execute();
            try {
                if (resp.body() == null) {
                    return null;
                }
                return readStreamFully(resp.body().byteStream(), MAX_JPEG_READ_BYTES);
            } finally {
                resp.close();
            }
        }
        if (s.startsWith("content://")) {
            Context ctx = sAppContext;
            if (ctx == null) {
                throw new IOException("content:// cần ApplicationContext — gọi initApplicationContext trước");
            }
            InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(s));
            if (in == null) {
                return null;
            }
            try {
                return readStreamFully(in, MAX_JPEG_READ_BYTES);
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) { }
            }
        }
        if (s.startsWith("file://")) {
            s = Uri.parse(s).getPath();
            if (s == null) {
                return null;
            }
        }
        File f = new File(s);
        if (!f.isFile() || !f.canRead()) {
            throw new IOException("Không đọc được file ảnh: " + s);
        }
        try (FileInputStream fis = new FileInputStream(f)) {
            return readStreamFully(fis, MAX_JPEG_READ_BYTES);
        }
    }

    private static byte[] readStreamFully(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) {
                throw new IOException("Ảnh vượt quá " + max + " bytes");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /**
     * Gọi {@code StandUpApi.standUp()} trên thread hiện tại (nên gọi từ worker — tránh chặn main / xung đột mic).
     */
    public static void performStandUpSync() {
        invokeStandUp();
    }

    /**
     * Trễ rồi gọi đứng trên background thread (không chặn main looper — tránh làm chậm feed PCM / Porcupine).
     *
     * @param delayMs độ trễ trước khi gửi lệnh (khuyến nghị 1500–3000 ms)
     */
    public static void requestInitialStandPose(long delayMs) {
        long d = Math.max(0L, delayMs);
        MAIN.postDelayed(() -> new Thread(() -> {
            try {
                Log.i(TAG, "Khởi động: StandUpApi.standUp() (worker thread, delay=" + d + "ms)");
                invokeStandUp();
            } catch (Throwable t) {
                Log.w(TAG, "requestInitialStandPose: " + t.getMessage());
            }
        }, "MiniInitialStand").start(), d);
    }

    public static void dispatchFromXiaozhiJson(JSONObject root) {
        if (root == null) return;
        String type = root.optString("type");
        try {
            if ("mcp".equals(type)) {
                Object payload = root.opt("payload");
                if (payload instanceof JSONObject) {
                    handleMcpPayload((JSONObject) payload);
                } else if (payload instanceof String) {
                    handleMcpPayload(new JSONObject((String) payload));
                }
            } else if ("iot".equals(type)) {
                JSONArray commands = root.optJSONArray("commands");
                if (commands != null) {
                    for (int i = 0; i < commands.length(); i++) {
                        JSONObject cmd = commands.optJSONObject(i);
                        if (cmd != null) {
                            handleIotCommand(cmd);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "dispatchFromXiaozhiJson: " + e.getMessage());
        }
    }

    private static void handleMcpPayload(JSONObject rpc) throws Exception {
        if (!"2.0".equals(rpc.optString("jsonrpc"))) return;
        String method = rpc.optString("method");
        if (method != null && method.startsWith("notifications")) return;
        if (!"tools/call".equals(method)) {
            Log.d(TAG, "MCP method bỏ qua: " + method);
            return;
        }
        JSONObject params = rpc.optJSONObject("params");
        if (params == null) return;
        String toolName = params.optString("name");
        JSONObject arguments = params.optJSONObject("arguments");
        noteMcpToolInvoked(toolName);
        Log.i(TAG, "MCP tools/call → name=\"" + toolName + "\" arguments=" + (arguments != null ? arguments.toString() : "null"));
        invokeToolByName(toolName, arguments);
    }

    private static void handleIotCommand(JSONObject cmd) {
        String name = cmd.optString("name", cmd.optString("command", ""));
        JSONObject args = cmd.optJSONObject("arguments");
        if (args == null) args = cmd.optJSONObject("params");
        invokeToolByName(name, args != null ? args : new JSONObject());
    }

    /**
     * Map MCP → Skill built-in UBT, motion resource ({@code ActionApi}), đứng/ngồi ({@code StandUpApi}), chụp ảnh.
     */
    private static void invokeToolByName(String toolName, JSONObject arguments) {
        if (toolName == null || toolName.isEmpty()) return;
        noteMcpToolInvoked(toolName);
        String n = toolName.toLowerCase(Locale.US);
        String argHints = extractArgumentHints(arguments);
        final String combined = (n + " " + argHints).trim();
        Log.i(TAG, "Xiaozhi → robot: tool=\"" + toolName + "\" combined=\"" + combined + "\"");

        MAIN.post(() -> {
            try {
                if (ExploreModeController.isActive() && !n.contains("explore")) {
                    ExploreModeController.stop();
                }
                String skillFromArgs = UbtechBuiltinSkillCatalog.resolveFromArguments(arguments);
                if (skillFromArgs != null) {
                    invokeStartSkillByIntent(skillFromArgs);
                    return;
                }
                String explicitId = RobotBuiltinActionCatalog.resolveExplicitOnly(arguments);
                if (!explicitId.isEmpty()) {
                    invokePlayAction(explicitId);
                    return;
                }
                if (matchesOttoWalkMcp(n, combined, arguments)) {
                    return;
                }
                if (matchesOttoTurnMcp(n, combined, arguments)) {
                    return;
                }
                if (matchesTaijiMcp(n, combined)) {
                    return;
                }
                if (matchesKungfuMcp(n, combined)) {
                    return;
                }
                if (matchesOttoDanceMcp(n, combined)) {
                    return;
                }
                if (matchesShutDownMcp(n, combined)) {
                    return;
                }
                if (matchesBowStepMcp(n, combined)) {
                    return;
                }
                String skillFromTool = UbtechBuiltinSkillCatalog.resolveFromToolName(n);
                if (skillFromTool != null) {
                    invokeStartSkillByIntent(skillFromTool);
                    return;
                }
                if (n.contains("otto") && n.contains("explore")) {
                    suppressLlmEmotionForRobotAction(SUPPRESS_LLM_EMOTION_FOR_ACTION_MS);
                    ExploreModeController.start();
                    return;
                }
                if (n.contains("otto") && n.contains("stop")) {
                    ExploreModeController.stop();
                    invokeStopAction();
                    return;
                }
                if (n.contains("take_photo") || n.contains("camera.take")) {
                    invokeTakePic();
                    return;
                }
                if (matchesStandUpIntent(n)) {
                    invokeStandUp();
                    return;
                }
                if (matchesSitDownIntent(n)) {
                    invokeSitDown();
                    return;
                }
                if (n.contains("squat")) {
                    invokeSquatDown();
                    return;
                }
                if (n.contains("otto.stop") || (n.contains("otto") && n.endsWith(".stop"))) {
                    ExploreModeController.stop();
                    invokeStopAction();
                    return;
                }
                if (isOttoMotionPlayActionTool(n)) {
                    String actionId = RobotBuiltinActionCatalog.resolvePlayActionId(n, arguments, true);
                    invokePlayAction(actionId.isEmpty() ? RobotBuiltinActionCatalog.W_STAND_0001 : actionId);
                    return;
                }
                Log.w(TAG, "Tool không khớp điều khiển robot: \"" + toolName + "\" args=" + (arguments != null ? arguments.toString() : "{}"));
            } catch (Throwable t) {
                Log.w(TAG, "invokeToolByName: " + t.getMessage());
            }
        });
    }

    /**
     * Đi tiến / lùi: firmware Otto dùng {@code self.otto.walk_forward} + {@code direction} (1=tiến, -1=lùi);
     * phải xử lý trước {@code resolveFromToolName} để không map cứng {@code Keep_moving_forward}.
     */
    private static boolean matchesOttoWalkMcp(String n, String combined, JSONObject arguments) {
        if (n == null) n = "";
        if (n.contains("walk_backward") || n.contains("walk_back") || n.contains("go_back")) {
            invokeBuiltinMoveSkill(false);
            return true;
        }
        if (n.contains("walk_forward") || n.contains("go_forward")) {
            int dir = arguments != null ? arguments.optInt("direction", 1) : 1;
            invokeBuiltinMoveSkill(dir >= 0);
            return true;
        }
        return false;
    }

    /**
     * Đi tiến / lùi: {@code SkillApi.startSkill(GO_AHEAD|BACK_UP)} (giống {@code SkillApiActivity} demo),
     * rồi fallback {@code SkillsProxy} + utterance {@code keep_moving_forward} / {@code keep_going_backwards}.
     */
    private static void invokeBuiltinMoveSkill(boolean forward) {
        suppressLlmEmotionForRobotAction(SUPPRESS_LLM_EMOTION_FOR_ACTION_MS);
        if (trySkillApiStartBuiltinMove(forward)) {
            Log.i(TAG, "Đi tiến/lùi: SkillApi.startSkill("
                    + (forward ? SKILL_API_GO_AHEAD : SKILL_API_BACK_UP) + ")");
            return;
        }
        invokeLinearBuiltinMoveSkillsProxyOnly(forward);
    }

    /** {@code SkillApi.get().startSkill(SKILL_NAME, listener)} — reflection, không cần compile-time enum. */
    private static boolean trySkillApiStartBuiltinMove(boolean forward) {
        return trySkillApiStartByIntentName(forward ? SKILL_API_GO_AHEAD : SKILL_API_BACK_UP);
    }

    /**
     * Quay trái / phải: {@code SkillApi.startSkill(TURN_LEFT|TURN_RIGHT)}, fallback SkillsProxy.
     */
    private static void invokeBuiltinTurnSkill(boolean left) {
        suppressLlmEmotionForRobotAction(SUPPRESS_LLM_EMOTION_FOR_ACTION_MS);
        if (trySkillApiStartBuiltinTurn(left)) {
            Log.i(TAG, "Quay: SkillApi.startSkill("
                    + (left ? SKILL_API_TURN_LEFT : SKILL_API_TURN_RIGHT) + ")");
            return;
        }
        invokeBuiltinTurnSkillsProxyOnly(left);
    }

    private static boolean trySkillApiStartBuiltinTurn(boolean left) {
        return trySkillApiStartByIntentName(left ? SKILL_API_TURN_LEFT : SKILL_API_TURN_RIGHT);
    }

    private static void invokeBuiltinTurnSkillsProxyOnly(boolean left) {
        String u = left ? "keep_turning_left" : "keep_turning_right";
        if (trySkillsProxySpeechUtterance(u, true)) {
            Log.i(TAG, "Quay (fallback SkillsProxy): utterance=\"" + u + "\"");
        } else {
            Log.w(TAG, "Quay: SkillApi + SkillsProxy thất bại, utterance=\"" + u + "\"");
        }
    }

    /**
     * MCP / STT quay: {@code self.otto.turn_left|turn_right} + {@code direction} (1=trái, -1=phải).
     */
    /**
     * MCP {@code self.otto.dance} hoặc ý nhảy/múa — {@link SkillApi} điệu ngẫu nhiên trên ROM (không playAction).
     */
    private static boolean matchesOttoDanceMcp(String n, String combined) {
        if (n == null) {
            n = "";
        }
        if (n.contains("otto.dance") || n.endsWith(".dance")) {
            invokeRandomRomDanceSkill();
            return true;
        }
        return false;
    }

    /**
     * Giống {@link #invokeBuiltinMoveSkill} / {@code GO_AHEAD}: một lần {@code SkillApi.startSkill(enum)}
     * — chọn ngẫu nhiên một điệu trong pool ROM, không gọi stop giữa chừng (ROM chạy hết chu kỳ ~7s).
     */
    private static void invokeRandomRomDanceSkill() {
        extendLlmEmotionSuppress(SUPPRESS_LLM_EMOTION_FOR_DANCE_MS, "dance");
        String pick = AlphaMiniRomDanceSkills.pickRandomSkillName();
        if (trySkillApiStartByIntentName(pick)) {
            Log.i(TAG, "Nhảy múa SkillApi.startSkill(" + pick + ") — 1 điệu/ngẫu nhiên, pool="
                    + AlphaMiniRomDanceSkills.count() + " (chạy hết chu kỳ ROM)");
            return;
        }
        if (trySkillApiStartByIntentName("DANCING")) {
            Log.w(TAG, "Nhảy múa SkillApi.startSkill(DANCING) fallback");
            return;
        }
        Log.w(TAG, "Nhảy múa: SkillApi.startSkill thất bại cho \"" + pick + "\" (giống GO_AHEAD cần ROM + SDK)");
    }

    /** MCP {@code self.shut_down} — ROM skill #81 {@code SHUT_DOWN} (giống {@code GO_AHEAD}). */
    private static boolean matchesShutDownMcp(String n, String combined) {
        if (n != null && (n.contains("shut_down") || n.contains("shutdown"))) {
            invokeShutDownSkill();
            return true;
        }
        return false;
    }

    private static void invokeShutDownSkill() {
        suppressLlmEmotionForRobotAction(SUPPRESS_LLM_EMOTION_FOR_ACTION_MS);
        if (ExploreModeController.isActive()) {
            ExploreModeController.stop();
        }
        if (trySkillApiStartByIntentName(SKILL_API_SHUT_DOWN)) {
            Log.i(TAG, "Tắt máy: SkillApi.startSkill(SHUT_DOWN)");
            return;
        }
        invokeStartSkillByIntent("turn_off_robot");
        Log.w(TAG, "Tắt máy: SkillApi SHUT_DOWN thất bại — fallback turn_off_robot");
    }

    /** MCP {@code self.otto.taiji} — ROM {@code TAIJI}; chỉ khi {@code tools/call} đúng tên tool (giống {@code self.otto.walk_forward}). */
    private static boolean matchesTaijiMcp(String n, String combined) {
        if (n == null) {
            n = "";
        }
        if (n.contains("otto.taiji") || n.endsWith(".taiji")) {
            invokeTaijiSkill("MCP " + n);
            return true;
        }
        return false;
    }

    private static void invokeTaijiSkill(String reason) {
        long now = System.currentTimeMillis();
        if (lastTaijiSkillStartMs > 0 && now - lastTaijiSkillStartMs < TAIJI_RESTART_COOLDOWN_MS) {
            Log.i(TAG, "Thái cực: bỏ qua startSkill lần 2 — đang/vừa TAIJI "
                    + ((now - lastTaijiSkillStartMs) / 1000) + "s trước (" + reason + ")");
            extendLlmEmotionSuppress(SUPPRESS_LLM_EMOTION_FOR_TAIJI_MS, "TAIJI");
            return;
        }
        lastTaijiSkillStartMs = now;
        extendLlmEmotionSuppress(SUPPRESS_LLM_EMOTION_FOR_TAIJI_MS, "TAIJI");
        if (trySkillApiStartByIntentName(SKILL_API_TAIJI)) {
            Log.i(TAG, "Thái cực: SkillApi.startSkill(TAIJI) — " + reason + ", emotion LLM tắt ~"
                    + (SUPPRESS_LLM_EMOTION_FOR_TAIJI_MS / 1000) + "s");
            return;
        }
        invokeStartSkillByIntent("taiji");
        Log.w(TAG, "Thái cực: SkillApi TAIJI thất bại — thử utterance taiji");
    }

    /** MCP {@code self.otto.kungfu} — ActionApi.playAction {@code "013"} (功夫), không phải SkillApi. */
    private static boolean matchesKungfuMcp(String n, String combined) {
        if (n == null) {
            n = "";
        }
        if (n.contains("otto.kungfu") || n.endsWith(".kungfu")) {
            invokeKungfuSkill("MCP " + n);
            return true;
        }
        return false;
    }

    private static void invokeKungfuSkill(String reason) {
        long now = System.currentTimeMillis();
        if (lastKungfuSkillStartMs > 0 && now - lastKungfuSkillStartMs < KUNGFU_RESTART_COOLDOWN_MS) {
            Log.i(TAG, "Kungfu: bỏ qua playAction lần 2 — đang/vừa 013 "
                    + ((now - lastKungfuSkillStartMs) / 1000) + "s trước (" + reason + ")");
            extendLlmEmotionSuppress(SUPPRESS_LLM_EMOTION_FOR_KUNGFU_MS, "kungfu");
            return;
        }
        lastKungfuSkillStartMs = now;
        extendLlmEmotionSuppress(SUPPRESS_LLM_EMOTION_FOR_KUNGFU_MS, "kungfu");
        Log.i(TAG, "Kungfu: ActionApi.playAction(\"" + ACTION_API_KUNGFU_ID + "\") — " + reason
                + ", emotion LLM tắt ~" + (SUPPRESS_LLM_EMOTION_FOR_KUNGFU_MS / 1000) + "s");
        invokePlayAction(ACTION_API_KUNGFU_ID);
    }

    /** MCP {@code self.otto.bow_step} — ROM {@code BOW_STEP} (弓步 / cung bộ). */
    private static boolean matchesBowStepMcp(String n, String combined) {
        if (n != null && (n.contains("bow_step") || n.contains("bowstep"))) {
            invokeBowStepSkill();
            return true;
        }
        return false;
    }

    private static void invokeBowStepSkill() {
        suppressLlmEmotionForRobotAction(SUPPRESS_LLM_EMOTION_FOR_ACTION_MS);
        if (trySkillApiStartByIntentName(SKILL_API_BOW_STEP)) {
            Log.i(TAG, "Cung bộ: SkillApi.startSkill(BOW_STEP)");
            return;
        }
        invokeStartSkillByIntent("bow_step");
        Log.w(TAG, "Cung bộ: SkillApi BOW_STEP thất bại — fallback utterance bow_step");
    }

    private static boolean matchesOttoTurnMcp(String n, String combined, JSONObject arguments) {
        if (n == null) n = "";
        if (n.contains("turn_right")) {
            invokeBuiltinTurnSkill(false);
            return true;
        }
        if (n.contains("turn_left")) {
            int dir = arguments != null ? arguments.optInt("direction", 1) : 1;
            invokeBuiltinTurnSkill(dir >= 0);
            return true;
        }
        return false;
    }

    /** Motion Otto (jump, swing, …) — chỉ tên tool MCP, không đọc chữ trong args. */
    private static boolean isOttoMotionPlayActionTool(String n) {
        if (n == null || !n.contains("otto.")) {
            return false;
        }
        if (n.contains("walk") || n.contains("turn") || n.contains("dance") || n.contains("taiji")
                || n.contains("kungfu") || n.contains("explore") || n.contains("stop") || n.contains("sit")
                || n.contains("stand") || n.contains("bow") || n.contains("shut")) {
            return false;
        }
        return n.contains("jump") || n.contains("swing") || n.contains("moonwalk")
                || n.contains("bend") || n.contains("shake_leg") || n.contains("updown")
                || n.contains("hands_up") || n.contains("hands_down") || n.contains("hand_wave")
                || n.contains("motion") || n.contains("play_action")
                || n.contains("emoticon") || n.contains("emotion") || n.contains("expression");
    }

    private static void invokeLinearBuiltinMoveSkillsProxyOnly(boolean forward) {
        String u;
        if (forward && TEST_WALK_FORWARD_USES_SIT_DOWN_ON_SKILLS_PROXY) {
            u = "sit_down";
            Log.w(TAG, "[TEST walk_forward→sit_down] SkillsProxy utterance=\"" + u
                    + "\" (call-builtin-skills) — robot có thể NGỒI; tắt TEST_WALK_FORWARD_USES_SIT_DOWN_ON_SKILLS_PROXY sau khi xong");
        } else {
            u = forward ? "keep_moving_forward" : "keep_going_backwards";
        }
        if (trySkillsProxySpeechUtterance(u, true)) {
            Log.i(TAG, "Đi tiến/lùi (chỉ SkillsProxy / call-builtin-skills): utterance=\"" + u + "\"");
        } else {
            Log.w(TAG, "Đi tiến/lùi: SkillsProxy không gọi được (Context/Master/Intent), utterance=\"" + u + "\"");
        }
    }

    /** Ngồi: {@code self.otto.sit} / {@code self.otto.sit_down} → StandUpApi. */
    private static boolean matchesSitDownIntent(String n) {
        if (n == null) n = "";
        return n.contains("sitdown") || n.contains("sit_down") || n.endsWith(".sit");
    }

    /** Đứng: {@code self.otto.stand_up}. */
    private static boolean matchesStandUpIntent(String n) {
        if (n == null) n = "";
        return n.contains("standup") || n.contains("stand_up");
    }

    /** Gộp chuỗi trong arguments — server đôi khi để ý định ở đây chứ không nằm trong name. */
    private static String extractArgumentHints(JSONObject arguments) {
        if (arguments == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] keys = {"query", "text", "utterance", "instruction", "prompt", "user_message", "message", "speech", "transcript",
                "direction", "steps", "speed"};
        for (String k : keys) {
            String v = arguments.optString(k, "").trim();
            if (!v.isEmpty()) sb.append(' ').append(v);
        }
        if (arguments.has("direction")) {
            sb.append(" direction=").append(arguments.optInt("direction", 0));
        }
        return sb.toString().toLowerCase(Locale.US);
    }

    private static volatile long lastLlmEmotionSkillMs;
    private static volatile String lastLlmEmotionApplied = "";
    /**
     * Đến thời điểm này: không gọi {@link #applyLlmEmotionFromServer} (tránh skill emotion chen ngang TAIJI/dance).
     */
    private static volatile long suppressLlmEmotionUntilMs;
    private static volatile String suppressLlmEmotionReason = "";

    /** MCP / STT điều khiển thường. */
    private static final long SUPPRESS_LLM_EMOTION_FOR_ACTION_MS = 12_000L;
    /** TAIJI / múa thái cực trên ROM thường &gt;12s — LLM vẫn gửi emotion khi TTS. */
    private static final long SUPPRESS_LLM_EMOTION_FOR_TAIJI_MS = 90_000L;
    /** Không gọi lại {@code startSkill(TAIJI)} trong khoảng này — tránh MCP lần 2 cắt bài đang múa. */
    private static final long TAIJI_RESTART_COOLDOWN_MS = 90_000L;
    private static volatile long lastTaijiSkillStartMs;
    /** Kungfu / exercise ROM thường &gt;12s — giống TAIJI. */
    private static final long SUPPRESS_LLM_EMOTION_FOR_KUNGFU_MS = 90_000L;
    private static final long KUNGFU_RESTART_COOLDOWN_MS = 90_000L;
    private static volatile long lastKungfuSkillStartMs;
    /** Một điệu dance ~10s (đôi khi 15s) + nhiều gói emotion LLM khi trả lời. */
    private static final long SUPPRESS_LLM_EMOTION_FOR_DANCE_MS = 25_000L;
    /** Chặn PCM lên Xiaozhi trong lúc múa + TTS chồng (ROM ~10s, đôi khi 15s). */
    private static final long SUPPRESS_PCM_FOR_DANCE_MS = 20_000L;

    /**
     * Gia hạn chặn emotion (lấy max — không để lệnh 12s ghi đè 90s của TAIJI).
     */
    private static void extendLlmEmotionSuppress(long durationMs, String reason) {
        if (durationMs <= 0) return;
        long until = System.currentTimeMillis() + durationMs;
        if (until > suppressLlmEmotionUntilMs) {
            suppressLlmEmotionUntilMs = until;
            suppressLlmEmotionReason = reason != null ? reason : "";
            Log.i(TAG, "Chặn map emotion LLM ~" + (durationMs / 1000) + "s (" + suppressLlmEmotionReason + ")");
        }
    }

    public static boolean isLlmEmotionSuppressed() {
        return System.currentTimeMillis() < suppressLlmEmotionUntilMs;
    }

    /** Tắt map emotion LLM (mọi self-control / skill robot). */
    public static void suppressLlmEmotionForRobotAction(long durationMs) {
        extendLlmEmotionSuppress(durationMs, "robot action");
    }

    /** @deprecated dùng {@link #suppressLlmEmotionForRobotAction} */
    public static void suppressLlmEmotionForMotion(long durationMs) {
        suppressLlmEmotionForRobotAction(durationMs);
    }

    /**
     * MCP {@code tools/call}: self.otto.*, camera, motion… → chặn emotion trước khi xử lý (tránh đụng skill).
     */
    public static void noteMcpToolInvoked(String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return;
        }
        String n = toolName.toLowerCase(Locale.US);
        if (n.contains("otto.taiji") || n.endsWith(".taiji")) {
            extendLlmEmotionSuppress(SUPPRESS_LLM_EMOTION_FOR_TAIJI_MS, "MCP " + toolName);
            XiaozhiSessionManager.noteRobotSkillPcmSuppress(SUPPRESS_LLM_EMOTION_FOR_TAIJI_MS, toolName);
            return;
        }
        if (n.contains("otto.kungfu") || n.endsWith(".kungfu")) {
            extendLlmEmotionSuppress(SUPPRESS_LLM_EMOTION_FOR_KUNGFU_MS, "MCP " + toolName);
            XiaozhiSessionManager.noteRobotSkillPcmSuppress(SUPPRESS_LLM_EMOTION_FOR_KUNGFU_MS, toolName);
            return;
        }
        if (n.contains("dance") && !n.contains("dance_")) {
            extendLlmEmotionSuppress(SUPPRESS_LLM_EMOTION_FOR_DANCE_MS, "MCP " + toolName);
            XiaozhiSessionManager.noteRobotSkillPcmSuppress(SUPPRESS_PCM_FOR_DANCE_MS, toolName);
            return;
        }
        if (!shouldSuppressEmotionForMcpTool(n)) {
            return;
        }
        suppressLlmEmotionForRobotAction(SUPPRESS_LLM_EMOTION_FOR_ACTION_MS);
        Log.i(TAG, "MCP tool \"" + toolName + "\" → tắt emotion LLM " + (SUPPRESS_LLM_EMOTION_FOR_ACTION_MS / 1000) + "s");
    }

    private static boolean shouldSuppressEmotionForMcpTool(String toolNameLower) {
        if (toolNameLower == null || toolNameLower.isEmpty()) {
            return false;
        }
        return toolNameLower.contains("self.otto.")
                || toolNameLower.contains(".otto.")
                || toolNameLower.contains("take_photo")
                || toolNameLower.contains("takepic")
                || toolNameLower.contains("camera")
                || toolNameLower.contains("shut_down")
                || toolNameLower.contains("shutdown");
    }

    /** @deprecated Dừng explore chỉ qua MCP {@code self.otto.stop}. */
    @SuppressWarnings("unused")
    public static void maybeStopExploreFromStt(String sttText) {
        // no-op
    }

    /** STT không kích skill — chỉ {@link #noteMcpToolInvoked} khi có {@code tools/call}. */
    public static void noteUserRobotActionIntentFromStt(String sttText) {
        // no-op
    }

    /** Giữ tên cũ — gọi {@link #noteUserRobotActionIntentFromStt}. */
    public static void noteUserMotionIntentFromStt(String sttText) {
        noteUserRobotActionIntentFromStt(sttText);
    }

    /**
     * Server Xiaozhi: {@code {"type":"llm","emotion":"happy",...}} — map emotion → skill ROM (81 enum),
     * gọi {@code SkillApi.startSkill} để robot vừa nói (TTS) vừa làm động tác.
     */
    public static void applyLlmEmotionFromServer(String emotion) {
        if (emotion == null || emotion.isEmpty()) {
            return;
        }
        if (System.currentTimeMillis() < suppressLlmEmotionUntilMs) {
            long leftSec = (suppressLlmEmotionUntilMs - System.currentTimeMillis() + 999) / 1000;
            Log.i(TAG, "LLM emotion \"" + emotion + "\" bỏ qua — còn ~" + leftSec + "s ("
                    + suppressLlmEmotionReason + ")");
            return;
        }
        String skillName = LlmEmotionSkillMapper.resolveSkillName(emotion);
        long now = System.currentTimeMillis();
        if (skillName.equals(lastLlmEmotionApplied) && now - lastLlmEmotionSkillMs < 2500L) {
            Log.d(TAG, "LLM emotion: bỏ qua trùng \"" + emotion + "\" → " + skillName + " (cooldown 2.5s)");
            return;
        }
        lastLlmEmotionApplied = skillName;
        lastLlmEmotionSkillMs = now;
        final String emotionIn = emotion;
        MAIN.post(() -> {
            Log.i(TAG, "LLM emotion \"" + emotionIn + "\" → SkillApi." + skillName);
            if (trySkillApiStartByIntentName(skillName)) {
                return;
            }
            Log.w(TAG, "SkillApi." + skillName + " thất bại, thử invokeStartSkillByIntent");
            invokeStartSkillByIntent(skillName);
        });
    }

    /** Đã tắt: skill chỉ qua MCP {@code tools/call}, không khớp chữ TTS/STT. */
    @SuppressWarnings("unused")
    public static void maybeRunMotionFromAssistantTts(String assistantText) {
        // no-op
    }

    /** @deprecated Chỉ MCP — không khớp keyword STT. */
    @SuppressWarnings("unused")
    public static void maybeRunDanceFromUserSttMotionIntent(String sttText) {
        // no-op
    }

    /** @deprecated Chỉ MCP — không khớp keyword STT. */
    @SuppressWarnings("unused")
    public static void maybeRunWalkFromUserSttMotionIntent(String sttText) {
        // no-op
    }

    /** @deprecated Chỉ MCP — không khớp keyword STT. */
    @SuppressWarnings("unused")
    public static void maybeRunWalkFromUserSttTypoForward(String sttText) {
        // no-op
    }

    /** TakePic thành công → tiếng chụp để user biết đã chụp xong. */
    private static Object takePicResponseListener() {
        try {
            Class<?> listenerClass = Class.forName("com.ubtrobot.commons.ResponseListener");
            return Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if ("onResponseSuccess".equals(method.getName())) {
                            Log.i(TAG, "TakePicApi: onResponseSuccess");
                            CameraShutterSound.playOnCaptureSuccess();
                        } else if ("onFailure".equals(method.getName())) {
                            int code = args != null && args.length > 0 && args[0] instanceof Integer
                                    ? (Integer) args[0] : -1;
                            String msg = args != null && args.length > 1 ? String.valueOf(args[1]) : "";
                            Log.w(TAG, "TakePicApi: onFailure code=" + code + " msg=" + msg);
                        }
                        return null;
                    });
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Object responseListenerProxy(String tag) {
        try {
            Class<?> listenerClass = Class.forName("com.ubtrobot.commons.ResponseListener");
            return Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if ("onResponseSuccess".equals(method.getName())) {
                            Log.i(TAG, tag + ": onResponseSuccess");
                        } else if ("onFailure".equals(method.getName())) {
                            int code = args != null && args.length > 0 && args[0] instanceof Integer
                                    ? (Integer) args[0] : -1;
                            String msg = args != null && args.length > 1 ? String.valueOf(args[1]) : "";
                            Log.w(TAG, tag + ": onFailure code=" + code + " msg=" + msg);
                        }
                        return null;
                    });
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * ROM Alpha Mini: {@code SkillHelper.startSkillByIntent(String, com.google.protobuf.Any, …)} —
     * tham số giữa là protobuf {@code Any}, không phải {@link android.os.Bundle}.
     */
    private static Object protobufAnyDefaultInstance() {
        try {
            Class<?> any = Class.forName("com.google.protobuf.Any");
            Method def = any.getMethod("getDefaultInstance");
            return def.invoke(null);
        } catch (Throwable t) {
            Log.w(TAG, "protobuf Any.getDefaultInstance: " + t.getMessage());
            return null;
        }
    }

    private static boolean isProtobufAnyParameter(Class<?> paramType) {
        return paramType != null && "com.google.protobuf.Any".equals(paramType.getName());
    }

    /** Overload thứ hai trên ROM: {@code (String, Any, ResponseCallback)}. */
    private static Object responseCallbackProxy(String tag) {
        try {
            Class<?> cbClass = Class.forName("com.ubtrobot.transport.message.ResponseCallback");
            return Proxy.newProxyInstance(
                    cbClass.getClassLoader(),
                    new Class<?>[]{cbClass},
                    (proxy, method, args) -> {
                        Log.d(TAG, tag + ": " + method.getName());
                        return null;
                    });
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static void invokeTakePic() {
        try {
            Class<?> c = Class.forName("com.ubtechinc.sauron.api.TakePicApi");
            Method get = c.getMethod("get");
            Object api = get.invoke(null);
            Object listener = takePicResponseListener();
            if (listener == null) {
                Log.w(TAG, "ResponseListener không có trên classpath");
                return;
            }
            Class<?> listenerClass = Class.forName("com.ubtrobot.commons.ResponseListener");
            Method take = c.getMethod("takePicImmediately", listenerClass);
            take.invoke(api, listener);
            Log.i(TAG, "Đã gọi TakePicApi.takePicImmediately()");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "TakePicApi không có trên classpath (cần Sauron SDK trên robot)");
        } catch (Throwable t) {
            Log.w(TAG, "TakePicApi: " + t.getMessage());
        }
    }

    private static void invokeStandUp() {
        invokeStandUpSdk("standUp");
    }

    private static void invokeSitDown() {
        invokeStandUpSdk("sitdown");
    }

    private static void invokeSquatDown() {
        invokeStandUpSdk("squatdown");
    }

    private static void invokeStandUpSdk(String methodName) {
        suppressLlmEmotionForRobotAction(SUPPRESS_LLM_EMOTION_FOR_ACTION_MS);
        try {
            Class<?> c = Class.forName("ubtechinc.com.standupsdk.StandUpApi");
            Method get = c.getMethod("get");
            Object api = get.invoke(null);
            Class<?> cbClass = Class.forName("com.ubtrobot.transport.message.ResponseCallback");
            Object cb = Proxy.newProxyInstance(
                    cbClass.getClassLoader(),
                    new Class<?>[]{cbClass},
                    (proxy, method, args) -> null);
            Method m = c.getMethod(methodName, cbClass);
            m.invoke(api, cb);
            Log.i(TAG, "Đã gọi StandUpApi." + methodName + "()");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "StandUpApi không có trên classpath");
        } catch (Throwable t) {
            Log.w(TAG, "StandUpApi: " + t.getMessage());
        }
    }

    private static void invokePlayAction(String preferredActionId) {
        suppressLlmEmotionForRobotAction(SUPPRESS_LLM_EMOTION_FOR_ACTION_MS);
        String[] chain = buildPlayActionFallbackChain(preferredActionId);
        invokePlayActionChain(chain, 0);
    }

    /** Thử lần lượt các id motion (ROM thiếu file → 10005). */
    private static String[] buildPlayActionFallbackChain(String primary) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (primary != null) {
            String p = primary.trim();
            if (!p.isEmpty()) set.add(p);
        }
        set.add("dance_0002");
        set.add("dance_0002en");
        set.add("013");
        set.add("010");
        set.add("015");
        return set.toArray(new String[0]);
    }

    private static void invokePlayActionChain(String[] ids, int index) {
        if (ids == null || index >= ids.length) {
            Log.w(TAG, "playAction: hết fallback, không còn id motion");
            return;
        }
        final String actionId = ids[index];
        try {
            Class<?> c = Class.forName("com.ubtrobot.action.ActionApi");
            Method get = c.getMethod("get");
            Object api = get.invoke(null);
            Class<?> listenerClass = Class.forName("com.ubtrobot.commons.ResponseListener");
            Object listener = playActionChainListener(ids, index, actionId);
            if (listener == null) {
                Log.w(TAG, "ResponseListener không có trên classpath");
                return;
            }
            Method play = c.getMethod("playAction", String.class, listenerClass);
            play.invoke(api, actionId, listener);
            Log.i(TAG, "Đã gọi ActionApi.playAction(\"" + actionId + "\") [" + (index + 1) + "/" + ids.length + "]");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "ActionApi không có trên classpath (cần motion SDK trên robot)");
        } catch (Throwable t) {
            Log.w(TAG, "ActionApi.playAction: " + t.getMessage());
        }
    }

    private static Object playActionChainListener(String[] ids, int index, String actionId) {
        try {
            Class<?> listenerClass = Class.forName("com.ubtrobot.commons.ResponseListener");
            final int nextIndex = index + 1;
            return Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if ("onResponseSuccess".equals(method.getName())) {
                            Log.i(TAG, "ActionApi.playAction(\"" + actionId + "\"): onResponseSuccess");
                        } else if ("onFailure".equals(method.getName())) {
                            int code = args != null && args.length > 0 && args[0] instanceof Integer
                                    ? (Integer) args[0] : -1;
                            String msg = args != null && args.length > 1 ? String.valueOf(args[1]) : "";
                            Log.w(TAG, "ActionApi.playAction(\"" + actionId + "\"): onFailure code=" + code + " msg=" + msg
                                    + (nextIndex < ids.length ? " → thử id tiếp theo" : ""));
                            if (nextIndex < ids.length) {
                                MAIN.post(() -> invokePlayActionChain(ids, nextIndex));
                            }
                        }
                        return null;
                    });
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** Gọi từ {@link ExploreModeController} — mỗi lần chỉ một skill tiến/lùi. */
    static void exploreApplyMove(boolean forward) {
        suppressLlmEmotionForRobotAction(SUPPRESS_LLM_EMOTION_FOR_ACTION_MS);
        invokeBuiltinMoveSkill(forward);
    }

    /** Gọi từ {@link ExploreModeController} — mỗi lần chỉ một skill quay. */
    static void exploreApplyTurn(boolean left) {
        suppressLlmEmotionForRobotAction(SUPPRESS_LLM_EMOTION_FOR_ACTION_MS);
        invokeBuiltinTurnSkill(left);
    }

    /** Giữa hai bước explore: cắt GO_AHEAD/BACK_UP/TURN sớm (tránh ROM chạy ~7s). */
    static void exploreApplyStopLocomotion() {
        invokeStopBuiltinLocomotionSkills();
        invokeStopAction();
    }

    /** Kết thúc explore: dừng skill di chuyển + motion. */
    static void exploreApplyStopAll() {
        invokeStopBuiltinLocomotionSkills();
        invokeStopAction();
    }

    /** Dừng GO_AHEAD / BACK_UP / TURN_* — bắt buộc giữa các bước explore (ROM ~7s nếu không stop). */
    private static void invokeStopBuiltinLocomotionSkills() {
        boolean any = trySkillApiStopViaReflectionNoArg();
        for (String name : new String[]{
                SKILL_API_GO_AHEAD, SKILL_API_BACK_UP, SKILL_API_TURN_LEFT, SKILL_API_TURN_RIGHT
        }) {
            if (trySkillApiStopByIntentName(name)) {
                any = true;
            }
        }
        if (any) {
            Log.i(TAG, "Đã gọi SkillApi stop skill di chuyển");
        }
    }

    private static boolean trySkillApiStopByIntentName(String intentName) {
        try {
            Class<?> skillApiClass = Class.forName("com.ubtechinc.skill.SkillApi");
            Object api = skillApiClass.getMethod("get").invoke(null);
            Method loadAll = skillApiClass.getMethod("loadAllSkills");
            java.util.List<?> list = (java.util.List<?>) loadAll.invoke(api);
            if (list == null || list.isEmpty()) return false;
            Object sample = list.get(0);
            if (!(sample instanceof Enum)) return false;
            @SuppressWarnings("rawtypes")
            Class enumClass = sample.getClass();
            Object constant = findSkillApiEnumByIntent(enumClass, intentName);
            if (constant == null) return false;
            Class<?> listenerClass = Class.forName("com.ubtrobot.commons.ResponseListener");
            Object listener = responseListenerProxy("SkillApi.stopSkill");
            for (Method m : skillApiClass.getMethods()) {
                if (!"stopSkill".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0].isAssignableFrom(enumClass)) {
                    m.invoke(api, constant);
                    return true;
                }
                if (p.length == 2 && p[0].isAssignableFrom(enumClass)
                        && listener != null && p[1].isAssignableFrom(listenerClass)) {
                    m.invoke(api, constant, listener);
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "trySkillApiStopByIntentName(" + intentName + "): " + t.getMessage());
        }
        return false;
    }

    private static boolean trySkillApiStopViaReflectionNoArg() {
        try {
            Class<?> skillApiClass = Class.forName("com.ubtechinc.skill.SkillApi");
            Object api = skillApiClass.getMethod("get").invoke(null);
            for (Method m : skillApiClass.getMethods()) {
                if (!"stopSkill".equals(m.getName()) || m.getParameterCount() != 0) continue;
                m.invoke(api);
                Log.i(TAG, "SkillApi.stopSkill() không tham số");
                return true;
            }
        } catch (Throwable t) {
            Log.d(TAG, "trySkillApiStopViaReflectionNoArg: " + t.getMessage());
        }
        return false;
    }

    private static void invokeStopAction() {
        try {
            Class<?> c = Class.forName("com.ubtrobot.action.ActionApi");
            Method get = c.getMethod("get");
            Object api = get.invoke(null);
            Class<?> listenerClass = Class.forName("com.ubtrobot.commons.ResponseListener");
            Object listener = responseListenerProxy("ActionApi.stopAction");
            if (listener == null) {
                Log.w(TAG, "ResponseListener không có trên classpath");
                return;
            }
            Method stop = c.getMethod("stopAction", listenerClass);
            stop.invoke(api, listener);
            Log.i(TAG, "Đã gọi ActionApi.stopAction()");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "ActionApi không có trên classpath");
        } catch (Throwable t) {
            Log.w(TAG, "stopAction: " + t.getMessage());
        }
    }

    /**
     * Tài liệu UBT: {@code SkillApi.get().startSkill(SKILL_NAME, listener)} — enum thường trùng tên intent (vd. {@code Keep_moving_forward}).
     * Nếu không khớp enum trên ROM, fallback {@code SkillHelper.startSkillByIntent}.
     */
    private static boolean trySkillApiStartByIntentName(String intentName) {
        if (intentName == null || intentName.isEmpty()) return false;
        try {
            Class<?> skillApiClass = Class.forName("com.ubtechinc.skill.SkillApi");
            Method get = skillApiClass.getMethod("get");
            Object api = get.invoke(null);
            Method loadAll = skillApiClass.getMethod("loadAllSkills");
            java.util.List<?> list = (java.util.List<?>) loadAll.invoke(api);
            if (list == null || list.isEmpty()) return false;
            Object sample = list.get(0);
            if (!(sample instanceof Enum)) return false;
            @SuppressWarnings("rawtypes")
            Class enumClass = sample.getClass();
            Object constant = findSkillApiEnumByIntent(enumClass, intentName);
            if (constant == null) return false;
            Class<?> listenerClass = Class.forName("com.ubtrobot.commons.ResponseListener");
            Object listener = responseListenerProxy("SkillApi.startSkill");
            if (listener == null) return false;
            Method start = skillApiClass.getMethod("startSkill", enumClass, listenerClass);
            start.invoke(api, constant, listener);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "SkillApi.startSkill(\"" + intentName + "\"): " + t.getMessage());
            return false;
        }
    }

    private static Object findSkillApiEnumByIntent(Class<?> enumClass, String intentName) {
        String want = normSkillEnumKey(intentName);
        for (Object ec : enumClass.getEnumConstants()) {
            if (!(ec instanceof Enum)) continue;
            Enum<?> e = (Enum<?>) ec;
            if (normSkillEnumKey(e.name()).equals(want)) return ec;
        }
        return null;
    }

    private static String normSkillEnumKey(String s) {
        if (s == null) return "";
        return s.trim().replace('-', '_').toUpperCase(Locale.US);
    }

    /**
     * Quét overload {@code startSkillByIntent}: public + declared (non-public), static hoặc instance {@code SkillHelper.get()}.
     */
    private static boolean invokeSkillHelperStartByIntentBestEffort(
            Class<?> sh, String utterance, Object listener) {
        for (Method m : collectStartSkillByIntentMethods(sh)) {
            if (tryInvokeStartSkillByIntentMethod(sh, m, utterance, listener)) {
                return true;
            }
        }
        return false;
    }

    private static List<Method> collectStartSkillByIntentMethods(Class<?> sh) {
        Map<String, Method> bySig = new LinkedHashMap<>();
        for (Method m : sh.getMethods()) {
            if ("startSkillByIntent".equals(m.getName())) {
                bySig.put(Arrays.toString(m.getParameterTypes()), m);
            }
        }
        for (Method m : sh.getDeclaredMethods()) {
            if ("startSkillByIntent".equals(m.getName())) {
                bySig.putIfAbsent(Arrays.toString(m.getParameterTypes()), m);
            }
        }
        return new ArrayList<>(bySig.values());
    }

    private static Object skillHelperInstance(Class<?> sh) {
        try {
            Method get = sh.getMethod("get");
            return get.invoke(null);
        } catch (Throwable t) {
            Log.d(TAG, "SkillHelper.get(): " + t.getMessage());
            return null;
        }
    }

    private static boolean tryInvokeStartSkillByIntentMethod(
            Class<?> sh, Method m, String utterance, Object listener) {
        if (!"startSkillByIntent".equals(m.getName())) return false;
        try {
            if (!Modifier.isPublic(m.getModifiers())) {
                m.setAccessible(true);
            }
        } catch (Throwable ignored) {
        }
        Class<?>[] p = m.getParameterTypes();
        boolean isStatic = Modifier.isStatic(m.getModifiers());
        Object receiver = isStatic ? null : skillHelperInstance(sh);
        if (!isStatic && receiver == null) {
            return false;
        }
        try {
            if (p.length == 2 && p[0] == String.class && p[1].isAssignableFrom(listener.getClass())) {
                m.invoke(receiver, utterance, listener);
                Log.i(TAG, "SkillHelper.startSkillByIntent OK overload " + Arrays.toString(p)
                        + " static=" + isStatic);
                return true;
            }
            if (p.length == 3 && p[0] == String.class && p[2].isAssignableFrom(listener.getClass())) {
                if (Bundle.class.isAssignableFrom(p[1])) {
                    m.invoke(receiver, utterance, null, listener);
                    Log.i(TAG, "SkillHelper.startSkillByIntent OK overload " + Arrays.toString(p)
                            + " static=" + isStatic);
                    return true;
                }
            }
            if (p.length == 3 && p[0] == String.class && isProtobufAnyParameter(p[1])) {
                /* Doc UBT: startSkillByIntent("exercise", null, callback) — thử null trước (giống Bundle=null), rồi Any rỗng. */
                if (p[2].isAssignableFrom(listener.getClass())) {
                    if (tryInvokeStartSkillByIntentAnyArg(receiver, m, utterance, null, listener, isStatic, p)) {
                        return true;
                    }
                    Object anyDef = protobufAnyDefaultInstance();
                    if (anyDef != null
                            && tryInvokeStartSkillByIntentAnyArg(receiver, m, utterance, anyDef, listener, isStatic, p)) {
                        return true;
                    }
                }
                try {
                    Class<?> cbClass = Class.forName("com.ubtrobot.transport.message.ResponseCallback");
                    if (p[2].isAssignableFrom(cbClass)) {
                        Object cb = responseCallbackProxy("SkillHelper.startSkillByIntent");
                        if (cb != null) {
                            if (tryInvokeStartSkillByIntentAnyArg(receiver, m, utterance, null, cb, isStatic, p)) {
                                return true;
                            }
                            Object anyDef = protobufAnyDefaultInstance();
                            if (anyDef != null
                                    && tryInvokeStartSkillByIntentAnyArg(receiver, m, utterance, anyDef, cb, isStatic, p)) {
                                return true;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "startSkillByIntent thử " + Arrays.toString(p) + " static=" + isStatic + " → " + t.getMessage());
        }
        return false;
    }

    /** Gọi overload (String, Any, Listener|Callback); {@code anyArg} có thể {@code null} theo doc UBT. */
    private static boolean tryInvokeStartSkillByIntentAnyArg(
            Object receiver,
            Method m,
            String utterance,
            Object anyArg,
            Object listenerOrCallback,
            boolean isStatic,
            Class<?>[] p) {
        try {
            m.invoke(receiver, utterance, anyArg, listenerOrCallback);
            Log.i(TAG, "SkillHelper.startSkillByIntent OK overload " + Arrays.toString(p)
                    + " static=" + isStatic + " anyArg=" + (anyArg == null ? "null" : "Any"));
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "startSkillByIntent Any middle="
                    + (anyArg == null ? "null" : "instance") + " → " + t.getMessage());
            return false;
        }
    }

    private static void logSkillHelperStartByIntentSignatures(Class<?> sh) {
        StringBuilder sb = new StringBuilder();
        for (Method m : collectStartSkillByIntentMethods(sh)) {
            int mod = m.getModifiers();
            if (sb.length() > 0) sb.append(" | ");
            sb.append(Modifier.toString(mod)).append(' ');
            sb.append(Arrays.toString(m.getParameterTypes()));
        }
        if (sb.length() == 0) {
            Log.w(TAG, "SkillHelper: không có method startSkillByIntent (public hay declared)");
        } else {
            Log.w(TAG, "SkillHelper.startSkillByIntent trên ROM: " + sb);
        }
    }

    private static Context resolveAppContext() {
        Context c = sAppContext;
        if (c != null) return c;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return ((Context) app).getApplicationContext();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isBuiltinLinearMoveIntentKey(String utterance) {
        if (utterance == null || utterance.isEmpty()) return false;
        String lower = utterance.toLowerCase(Locale.US);
        if ("keep_moving_forward".equals(lower) || "keep_going_backwards".equals(lower)
                || "go_ahead".equals(lower) || "back_up".equals(lower)) {
            return true;
        }
        String canon = UbtechBuiltinSkillCatalog.toCanonicalIfKnown(utterance);
        return "Keep_moving_forward".equals(canon) || "Keep_going_backwards".equals(canon)
                || SKILL_API_GO_AHEAD.equals(canon) || SKILL_API_BACK_UP.equals(canon);
    }

    private static boolean isBackwardMoveUtterance(String utterance) {
        if (utterance == null || utterance.isEmpty()) return false;
        String lower = utterance.toLowerCase(Locale.US);
        if ("keep_going_backwards".equals(lower) || "back_up".equals(lower)) {
            return true;
        }
        String canon = UbtechBuiltinSkillCatalog.toCanonicalIfKnown(utterance);
        return "Keep_going_backwards".equals(canon) || SKILL_API_BACK_UP.equals(canon);
    }

    private static boolean isBuiltinTurnIntentKey(String utterance) {
        if (utterance == null || utterance.isEmpty()) return false;
        String lower = utterance.toLowerCase(Locale.US);
        if ("keep_turning_left".equals(lower) || "keep_turning_right".equals(lower)
                || "turn_left".equals(lower) || "turn_right".equals(lower)) {
            return true;
        }
        String canon = UbtechBuiltinSkillCatalog.toCanonicalIfKnown(utterance);
        return "Keep_turning_left".equals(canon) || "Keep_turning_right".equals(canon)
                || SKILL_API_TURN_LEFT.equals(canon) || SKILL_API_TURN_RIGHT.equals(canon);
    }

    private static boolean isRightTurnUtterance(String utterance) {
        if (utterance == null || utterance.isEmpty()) return false;
        String lower = utterance.toLowerCase(Locale.US);
        if ("keep_turning_right".equals(lower) || "turn_right".equals(lower)) {
            return true;
        }
        String canon = UbtechBuiltinSkillCatalog.toCanonicalIfKnown(utterance);
        return "Keep_turning_right".equals(canon) || SKILL_API_TURN_RIGHT.equals(canon);
    }

    /**
     * Alpha Mini: skill đi/lùi thường đăng ký trên process/sysmaster, không phải app demo —
     * thử các khóa {@code Master.get().getOrCreateInteractor(...)} theo thứ tự.
     */
    private static List<String> robotInteractorKeysForSpeech(Context ctx, boolean linearMove) {
        List<String> keys = new ArrayList<>();
        String pkg = ctx.getPackageName();
        if (linearMove) {
            keys.add("robot:com.ubtrobot.mini.sysmaster");
            keys.add("robot:com.ubtechinc.mini.sysmaster");
        }
        keys.add("robot:" + pkg);
        LinkedHashSet<String> dedupe = new LinkedHashSet<>(keys);
        return new ArrayList<>(dedupe);
    }

    /**
     * Luồng tài liệu UBT ({@code call-builtin-skills.txt}): {@code Master} → {@code SkillsProxy}
     * + {@code SkillIntent(CATEGORY_SPEECH)} + {@code setSpeechUtterance}. Trên ROM Alpha Mini,
     * {@code SkillHelper.startSkillByIntent(..., Any.EMPTY, ...)} trả 404 {@code callPath=null};
     * proxy này thường điền đúng đường RPC nội bộ.
     *
     * @param linearMove nếu true: thử {@code robot:…sysmaster} trước {@code robot:} + package app (skill đi thường gắn sysmaster).
     */
    private static boolean trySkillsProxySpeechUtterance(String utterance, boolean linearMove) {
        if (utterance == null || utterance.isEmpty()) return false;
        Context ctx = resolveAppContext();
        if (ctx == null) {
            Log.d(TAG, "SkillsProxy: chưa có Context (initApplicationContext hoặc ActivityThread)");
            return false;
        }
        for (String interactorKey : robotInteractorKeysForSpeech(ctx, linearMove)) {
            if (trySkillsProxySpeechUtteranceForInteractor(utterance, interactorKey)) {
                return true;
            }
        }
        return false;
    }

    private static boolean trySkillsProxySpeechUtteranceForInteractor(String utterance, String interactorKey) {
        try {
            Class<?> masterClass = Class.forName("com.ubtrobot.master.Master");
            Object master = masterClass.getMethod("get").invoke(null);
            Method getOrCreate = masterClass.getMethod("getOrCreateInteractor", String.class);
            Object interactor = getOrCreate.invoke(master, interactorKey);
            if (interactor == null) {
                Log.d(TAG, "SkillsProxy: interactor null cho \"" + interactorKey + "\"");
                return false;
            }

            Object skillsProxy = invokeNoArgFactory(interactor, "createSkillsProxy");
            if (skillsProxy == null) {
                skillsProxy = invokeNoArgFactory(interactor, "createRobotSkillsProxy");
            }
            if (skillsProxy == null) {
                Log.d(TAG, "SkillsProxy: không có proxy cho \"" + interactorKey + "\"");
                return false;
            }

            Object skillIntent = null;
            Class<?> skillIntentClass = null;
            /* ROM demo: chỉ có com.ubtrobot.master.skill.SkillIntent — thử trước để tránh log ClassNotFoundException ồn ào. */
            String[] intentClassNames = {
                    "com.ubtrobot.master.skill.SkillIntent",
                    "com.ubtechinc.skill.SkillIntent",
                    "com.ubtrobot.skill.SkillIntent",
            };
            for (String cn : intentClassNames) {
                try {
                    Class<?> sic = Class.forName(cn);
                    Object inst = newSkillIntentSpeechCategory(sic);
                    if (inst != null) {
                        skillIntentClass = sic;
                        skillIntent = inst;
                        break;
                    }
                } catch (ClassNotFoundException e) {
                    Log.d(TAG, "SkillsProxy: không có class " + cn);
                } catch (Throwable t) {
                    Log.d(TAG, "SkillsProxy: bỏ qua " + cn + " → " + t);
                }
            }
            if (skillIntent == null || skillIntentClass == null) {
                Log.w(TAG, "SkillsProxy: không tạo được SkillIntent (interactor=\"" + interactorKey + "\")");
                return false;
            }
            Method setUtter = skillIntentClass.getMethod("setSpeechUtterance", String.class);
            setUtter.invoke(skillIntent, utterance);

            Object cb = responseCallbackProxyDetailed("SkillsProxy", utterance, interactorKey);
            if (cb == null) return false;

            Method callMethod = findSkillsProxyCallMethod(skillsProxy.getClass(), skillIntentClass, cb);
            if (callMethod == null) {
                Log.w(TAG, "SkillsProxy: không tìm thấy call(SkillIntent, ?, ResponseCallback)");
                return false;
            }
            callMethod.invoke(skillsProxy, skillIntent, null, cb);
            Log.i(TAG, "SkillsProxy.call(interactor=\"" + interactorKey + "\", utterance=\"" + utterance + "\")");
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "SkillsProxy interactor=\"" + interactorKey + "\": " + t.getMessage());
            return false;
        }
    }

    private static Object invokeNoArgFactory(Object receiver, String methodName) {
        try {
            Method m = receiver.getClass().getMethod(methodName);
            return m.invoke(receiver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object newSkillIntentSpeechCategory(Class<?> skillIntentClass) throws Exception {
        Object category = null;
        for (Field f : skillIntentClass.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!"CATEGORY_SPEECH".equals(f.getName())) continue;
            category = f.get(null);
            break;
        }
        if (category == null) {
            throw new NoSuchFieldException("CATEGORY_SPEECH");
        }
        Constructor<?>[] ctors = skillIntentClass.getDeclaredConstructors();
        for (Constructor<?> ctor : ctors) {
            if (ctor.getParameterTypes().length != 1) continue;
            try {
                if (!Modifier.isPublic(ctor.getModifiers())) {
                    ctor.setAccessible(true);
                }
            } catch (Throwable ignored) {
            }
            Class<?>[] pt = ctor.getParameterTypes();
            Class<?> p0 = pt[0];
            Object instance = newSkillIntentWithCategoryArg(ctor, p0, category);
            if (instance != null) {
                return instance;
            }
        }
        throw new NoSuchMethodException("SkillIntent(category) không khớp " + category.getClass().getName());
    }

    /**
     * Một số ROM: {@code CATEGORY_SPEECH} là {@link Integer} nhưng constructor nhận {@code int} —
     * {@code isAssignableFrom(Integer.class)} với {@code int.class} là false nên phải unbox.
     */
    private static Object newSkillIntentWithCategoryArg(
            Constructor<?> ctor, Class<?> param0, Object category) {
        try {
            if (param0 == int.class && category instanceof Number) {
                return ctor.newInstance(((Number) category).intValue());
            }
            if (param0 == long.class && category instanceof Number) {
                return ctor.newInstance(((Number) category).longValue());
            }
            if (param0.isAssignableFrom(category.getClass())) {
                return ctor.newInstance(category);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method findSkillsProxyCallMethod(
            Class<?> proxyClass, Class<?> skillIntentClass, Object responseCallback) {
        for (Method cm : proxyClass.getMethods()) {
            if (!"call".equals(cm.getName())) continue;
            Class<?>[] pt = cm.getParameterTypes();
            if (pt.length != 3) continue;
            if (!pt[0].isAssignableFrom(skillIntentClass)) continue;
            if (responseCallback != null && !pt[2].isInstance(responseCallback)) continue;
            return cm;
        }
        return null;
    }

    private static Object responseCallbackProxyDetailed(String tag, String utterance, String interactorKey) {
        try {
            Class<?> cbClass = Class.forName("com.ubtrobot.transport.message.ResponseCallback");
            return Proxy.newProxyInstance(
                    cbClass.getClassLoader(),
                    new Class<?>[]{cbClass},
                    (proxy, method, args) -> {
                        String mn = method.getName();
                        if ("onFailure".equals(mn)) {
                            Log.w(TAG, tag + " onFailure interactor=\"" + interactorKey + "\" utterance=\""
                                    + utterance + "\" args="
                                    + (args == null ? "null" : Arrays.toString(args)));
                        } else {
                            Log.d(TAG, tag + ": " + mn + " interactor=\"" + interactorKey + "\" utterance=\""
                                    + utterance + "\"");
                        }
                        return null;
                    });
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Gọi built-in skill theo utterance: tiến/lùi/quay ưu tiên {@code SkillApi};
     * còn lại {@code SkillApi} → {@code SkillsProxy} → {@code SkillHelper}.
     */
    private static void invokeStartSkillByIntent(String utterance) {
        suppressLlmEmotionForRobotAction(SUPPRESS_LLM_EMOTION_FOR_ACTION_MS);
        if (isBuiltinLinearMoveIntentKey(utterance)) {
            boolean forward = !isBackwardMoveUtterance(utterance);
            if (trySkillApiStartBuiltinMove(forward)) {
                Log.i(TAG, "Builtin tiến/lùi: SkillApi.startSkill("
                        + (forward ? SKILL_API_GO_AHEAD : SKILL_API_BACK_UP) + ")");
                return;
            }
            String u = speechUtteranceForLinearMoveSkillsProxy(utterance);
            if (trySkillsProxySpeechUtterance(u, true)) {
                Log.i(TAG, "Builtin tiến/lùi (fallback SkillsProxy): utterance=\"" + u + "\"");
            } else {
                Log.w(TAG, "Builtin tiến/lùi: SkillApi + SkillsProxy thất bại, utterance=\"" + u + "\"");
            }
            return;
        }
        if (isBuiltinTurnIntentKey(utterance)) {
            boolean left = !isRightTurnUtterance(utterance);
            if (trySkillApiStartBuiltinTurn(left)) {
                Log.i(TAG, "Builtin quay: SkillApi.startSkill("
                        + (left ? SKILL_API_TURN_LEFT : SKILL_API_TURN_RIGHT) + ")");
                return;
            }
            String u = speechUtteranceForTurnSkillsProxy(utterance);
            if (trySkillsProxySpeechUtterance(u, true)) {
                Log.i(TAG, "Builtin quay (fallback SkillsProxy): utterance=\"" + u + "\"");
            } else {
                Log.w(TAG, "Builtin quay: SkillApi + SkillsProxy thất bại, utterance=\"" + u + "\"");
            }
            return;
        }
        if (trySkillApiStartByIntentName(utterance)) {
            Log.i(TAG, "SkillApi.startSkill đã gọi cho intent \"" + utterance + "\"");
            return;
        }
        String speechUtterance = utterance;
        if (trySkillsProxySpeechUtterance(speechUtterance, false)) {
            Log.i(TAG, "SkillsProxy đã gửi speech utterance=\"" + speechUtterance + "\"");
            return;
        }
        try {
            Class<?> sh = Class.forName("com.ubtechinc.skill.SkillHelper");
            Object listener = responseListenerProxy("SkillHelper");
            if (listener == null) {
                Log.w(TAG, "ResponseListener không có — không gọi SkillHelper");
                return;
            }
            String skillHelperUtterance = UbtechBuiltinSkillCatalog.toCanonicalIfKnown(utterance);
            if (!invokeSkillHelperStartByIntentBestEffort(sh, skillHelperUtterance, listener)) {
                logSkillHelperStartByIntentSignatures(sh);
                throw new NoSuchMethodException("startSkillByIntent: không có overload invoke được");
            }
            Log.i(TAG, "SkillHelper.startSkillByIntent(\"" + skillHelperUtterance + "\")");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "SkillHelper / Skill SDK không có trên classpath");
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "SkillHelper.startSkillByIntent không tìm thấy: " + e.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, "startSkillByIntent: " + t.getMessage());
        }
    }

    /** Chuẩn hóa về {@code keep_*} cho SkillsProxy fallback (doc dùng {@code Keep_*}). */
    private static String speechUtteranceForLinearMoveSkillsProxy(String utterance) {
        if (utterance == null || utterance.isEmpty()) return "keep_moving_forward";
        if (isBackwardMoveUtterance(utterance)) {
            return "keep_going_backwards";
        }
        String lower = utterance.toLowerCase(Locale.US);
        if ("keep_moving_forward".equals(lower) || "keep_going_backwards".equals(lower)) {
            return lower;
        }
        String canon = UbtechBuiltinSkillCatalog.toCanonicalIfKnown(utterance);
        if ("Keep_going_backwards".equals(canon) || SKILL_API_BACK_UP.equals(canon)) {
            return "keep_going_backwards";
        }
        return "keep_moving_forward";
    }

    /** Chuẩn hóa về {@code keep_turning_*} cho SkillsProxy fallback. */
    private static String speechUtteranceForTurnSkillsProxy(String utterance) {
        if (utterance == null || utterance.isEmpty()) return "keep_turning_left";
        if (isRightTurnUtterance(utterance)) {
            return "keep_turning_right";
        }
        String lower = utterance.toLowerCase(Locale.US);
        if ("keep_turning_left".equals(lower) || "keep_turning_right".equals(lower)) {
            return lower;
        }
        String canon = UbtechBuiltinSkillCatalog.toCanonicalIfKnown(utterance);
        if ("Keep_turning_right".equals(canon) || SKILL_API_TURN_RIGHT.equals(canon)) {
            return "keep_turning_right";
        }
        return "keep_turning_left";
    }
}
