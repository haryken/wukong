# PROMPT: Port Xiaozhi voice stack (A→Z) vào Android Java project

Bạn là senior Android engineer. Nhiệm vụ: **integrate đầy đủ Xiaozhi** vào project Java hiện tại.

**QUAN TRỌNG:** Toàn bộ URL, header, JSON và **code mẫu Java** nằm **trong file này**. Không cần (và thường không thể) mở repo reference khác. Copy/adapt code mẫu bên dưới vào package của project đích.

Phải làm: identity → OTA → mã 6 số → activate → WebSocket TTS/STT → MQTT+UDP (optional) → Opus → wake → MCP self-control.

Target: **Java** (+ OkHttp). Kotlin optional.

---

## 0) Endpoints

| Việc | URL |
|------|-----|
| OTA | `https://api.tenclass.net/xiaozhi/ota/` |
| Activate | `https://api.tenclass.net/xiaozhi/ota/activate` |
| WebSocket | `wss://api.tenclass.net/xiaozhi/v1/` |
| MQTT | lấy từ OTA field `mqtt` |

Gradle:

```gradle
implementation "com.squareup.okhttp3:okhttp:4.12.0"
// MQTT optional:
implementation "org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5"
```

Permissions: `INTERNET`, `RECORD_AUDIO`, `CAMERA` (vision), `ACCESS_NETWORK_STATE`.

---

## 1) Device identity — CODE MẪU

```java
package your.app.xiaozhi;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONObject;
import java.util.Random;
import java.util.UUID;

/** Device-Id = MAC; Client-Id = UUID cố định. */
public final class DeviceIdentityStore {
    private static final String TAG = "XiaozhiDeviceId";
    private static final String PREFS = "app_prefs";
    private static final String KEY = "device_id";

    public static final class Identity {
        public final String deviceId; // header Device-Id
        public final String clientId; // header Client-Id
        public Identity(String deviceId, String clientId) {
            this.deviceId = deviceId;
            this.clientId = clientId;
        }
    }

    public static Identity getOrCreate(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, null);
        if (raw != null) {
            try {
                JSONObject o = new JSONObject(raw);
                String mac = o.optString("mac_address", "");
                String uuid = o.optString("uuid", "");
                if (!mac.isEmpty() && !uuid.isEmpty()) {
                    return new Identity(mac, uuid);
                }
            } catch (Exception ignored) {}
        }
        Identity id = new Identity(randomMac(), UUID.randomUUID().toString());
        save(sp, id);
        Log.i(TAG, "new Device-Id=" + id.deviceId + " Client-Id=" + id.clientId);
        return id;
    }

    private static void save(SharedPreferences sp, Identity id) {
        try {
            JSONObject o = new JSONObject();
            o.put("mac_address", id.deviceId);
            o.put("uuid", id.clientId);
            sp.edit().putString(KEY, o.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "save failed", e);
        }
    }

    private static String randomMac() {
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02x", r.nextInt(256)));
        }
        return sb.toString();
    }
}
```

Mã 6 số fallback (chỉ khi OTA không trả `activation.code`):

```java
package your.app.xiaozhi;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

public final class ActivationCode {
    public static String generate6Digits(String deviceId, String clientId) {
        String input = deviceId.trim() + "|" + clientId.trim();
        CRC32 crc = new CRC32();
        crc.update(input.getBytes(StandardCharsets.UTF_8));
        int num = (int) (crc.getValue() % 1_000_000L);
        return String.format("%06d", num);
    }
}
```

---

## 2) Board JSON cho OTA — CODE MẪU

```java
package your.app.xiaozhi;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Random;

/** Body POST OTA — giả ESP32 board để server trả activation.challenge. */
public final class BoardJson {
    public static String build(String macAddress, String uuid) {
        try {
            Random r = new Random();
            JSONObject root = new JSONObject();
            root.put("version", 2);
            root.put("flash_size", 8388608);
            root.put("psram_size", 4194304);
            root.put("minimum_free_heap_size", 200000 + r.nextInt(100000));
            root.put("mac_address", macAddress);
            root.put("uuid", uuid);
            root.put("chip_model_name", "esp32s3");

            JSONObject chip = new JSONObject();
            chip.put("model", 3);
            chip.put("cores", 2);
            chip.put("revision", 1);
            chip.put("features", 5);
            root.put("chip_info", chip);

            JSONObject app = new JSONObject();
            app.put("name", "sensor-hub");
            app.put("version", "1.3.0");
            app.put("compile_time", "2025-02-28T12:34:56Z");
            app.put("idf_version", "5.1-beta");
            app.put("elf_sha256", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
            root.put("application", app);

            JSONArray parts = new JSONArray();
            parts.put(part("app", 1, 2, 65536, 2097152));
            parts.put(part("nvs", 1, 1, 32768, 65536));
            root.put("partition_table", parts);

            root.put("ota", new JSONObject().put("label", "ota_1"));

            JSONObject board = new JSONObject();
            board.put("name", "ESP32S3-DevKitM-1");
            board.put("revision", "v1.2");
            board.put("features", new JSONArray().put("WiFi").put("Bluetooth"));
            board.put("manufacturer", "Espressif");
            board.put("serial_number", "ESP32S3-" + (1000 + r.nextInt(9000)));
            root.put("board", board);

            return root.toString();
        } catch (Exception e) {
            // fallback tối thiểu
            try {
                return new JSONObject()
                    .put("device_id", macAddress)
                    .put("client_id", uuid)
                    .put("platform", "android-mini")
                    .toString();
            } catch (Exception e2) {
                return "{}";
            }
        }
    }

    private static JSONObject part(String label, int type, int subtype, int address, int size)
            throws Exception {
        return new JSONObject()
            .put("label", label)
            .put("type", type)
            .put("subtype", subtype)
            .put("address", address)
            .put("size", size);
    }
}
```

---

## 3) OTA + Activate — CODE MẪU

```java
package your.app.xiaozhi;

import android.util.Log;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

public final class XiaozhiOtaClient {
    private static final String TAG = "XiaozhiOtaClient";
    public static final String OTA_URL = "https://api.tenclass.net/xiaozhi/ota/";

    public enum ActivateResult { SUCCESS, PENDING, FAILED }

    private final String deviceId;
    private final String clientId;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    public JSONObject otaRoot;          // raw
    public String activationCode;       // 6 digits
    public String activationChallenge;
    public JSONObject mqttConfig;       // optional

    public XiaozhiOtaClient(String deviceId, String clientId) {
        this.deviceId = deviceId;
        this.clientId = clientId;
    }

    /** POST OTA — trả true nếu HTTP 200 + parse được body. */
    public boolean checkVersionBlocking(String boardJson) {
        MediaType json = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(boardJson, json);
        Request req = new Request.Builder()
                .url(OTA_URL)
                .addHeader("Device-Id", deviceId)
                .addHeader("Client-Id", clientId)
                .addHeader("Activation-Version", "1")
                .addHeader("Accept-Language", "Chinese")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                Log.e(TAG, "OTA HTTP " + resp.code());
                return false;
            }
            String text = resp.body().string();
            Log.i(TAG, "OTA response: " + text);
            otaRoot = new JSONObject(text);
            JSONObject act = otaRoot.optJSONObject("activation");
            if (act != null) {
                activationCode = act.optString("code", "");
                activationChallenge = act.optString("challenge", "");
            }
            mqttConfig = otaRoot.optJSONObject("mqtt");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "checkVersion failed", e);
            return false;
        }
    }

    /**
     * POST {ota}/activate body {}
     * 200 = OK, 202 = user chưa nhập mã trên xiaozhi.me, else FAILED.
     */
    public ActivateResult activateBlocking() {
        MediaType json = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create("{}", json);
        String url = OTA_URL.replaceAll("/$", "") + "/activate";
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Device-Id", deviceId)
                .addHeader("Client-Id", clientId)
                .addHeader("Activation-Version", "1")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
        try (Response resp = http.newCall(req).execute()) {
            int code = resp.code();
            Log.i(TAG, "activate HTTP " + code);
            if (code == 200) return ActivateResult.SUCCESS;
            if (code == 202) return ActivateResult.PENDING;
            return ActivateResult.FAILED;
        } catch (Exception e) {
            Log.e(TAG, "activate error", e);
            return ActivateResult.FAILED;
        }
    }

    /** Poll trên background thread đến khi SUCCESS hoặc hết lần. */
    public void pollActivateUntilOk(int maxTries) {
        for (int i = 1; i <= maxTries; i++) {
            ActivateResult r = activateBlocking();
            if (r == ActivateResult.SUCCESS) {
                Log.i(TAG, "activated OK at try " + i);
                return;
            }
            try {
                Thread.sleep(r == ActivateResult.PENDING ? 3000 : 8000);
            } catch (InterruptedException e) {
                return;
            }
        }
        Log.w(TAG, "activate poll exhausted — nhập mã " + activationCode + " trên xiaozhi.me");
    }
}
```

OTA `mqtt` object (nếu có):

```json
{
  "endpoint": "mqtt.xiaozhi.me",
  "client_id": "GID_xxx@@@mac@@@uuid",
  "username": "...",
  "password": "...",
  "publish_topic": "device-server",
  "subscribe_topic": "devices/p2p/aa_bb_cc_dd_ee_ff"
}
```

---

## 4) Control JSON helpers — CODE MẪU

```java
package your.app.xiaozhi;

import org.json.JSONObject;

public final class XiaozhiMessages {
    public static String listenStart(String sessionId, String mode) throws Exception {
        // mode: "auto" | "realtime" | "manual"
        return new JSONObject()
                .put("session_id", sessionId)
                .put("type", "listen")
                .put("state", "start")
                .put("mode", mode)
                .toString();
    }

    public static String listenStop(String sessionId) throws Exception {
        return new JSONObject()
                .put("session_id", sessionId)
                .put("type", "listen")
                .put("state", "stop")
                .toString();
    }

    public static String wakeDetect(String sessionId, String text) throws Exception {
        return new JSONObject()
                .put("session_id", sessionId)
                .put("type", "listen")
                .put("state", "detect")
                .put("text", text) // "hey mini"
                .toString();
    }

    public static String abort(String sessionId, boolean wakeWord) throws Exception {
        JSONObject o = new JSONObject()
                .put("session_id", sessionId)
                .put("type", "abort");
        if (wakeWord) o.put("reason", "wake_word_detected");
        return o.toString();
    }

    public static String goodbye(String sessionId) throws Exception {
        return new JSONObject()
                .put("session_id", sessionId)
                .put("type", "goodbye")
                .toString();
    }
}
```

Inbound types: `stt`, `tts` (`state`: start/sentence_start/end/stop), `mcp`, `llm`, `alert`, `goodbye`.

---

## 5) WebSocket protocol — CODE MẪU (Java + OkHttp)

```java
package your.app.xiaozhi;

import android.util.Log;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket Xiaozhi:
 * connect → hello(text) → server hello(session_id) → binary Opus 2 chiều.
 */
public final class WebsocketProtocol {
    private static final String TAG = "WS";
    public static final String WS_URL = "wss://api.tenclass.net/xiaozhi/v1/";
    private static final int FRAME_MS = 60;

    public interface Listener {
        void onJson(JSONObject json);
        void onAudioOpus(byte[] opus);
        void onChannelOpened();
        void onChannelClosed();
        void onError(String msg);
    }

    private final String deviceId;
    private final String clientId;
    private final String accessToken; // demo: ""
    private final Listener listener;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    private WebSocket webSocket;
    private final AtomicBoolean isOpen = new AtomicBoolean(false);
    private final AtomicBoolean serverHelloReady = new AtomicBoolean(false);
    private volatile String sessionId = "";
    private CountDownLatch helloLatch;

    public WebsocketProtocol(String deviceId, String clientId, String accessToken, Listener listener) {
        this.deviceId = deviceId;
        this.clientId = clientId;
        this.accessToken = accessToken == null ? "" : accessToken;
        this.listener = listener;
    }

    public String getSessionId() { return sessionId; }

    public boolean isAudioChannelOpened() {
        return webSocket != null && isOpen.get() && serverHelloReady.get();
    }

    /** Blocking: connect + chờ server hello tối đa 10s. */
    public boolean openAudioChannel() {
        if (isAudioChannelOpened()) return true;
        closeAudioChannel();

        helloLatch = new CountDownLatch(1);
        Request req = new Request.Builder()
                .url(WS_URL)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Protocol-Version", "1")
                .addHeader("Device-Id", deviceId)
                .addHeader("Client-Id", clientId)
                .build();

        Log.i(TAG, "connecting " + WS_URL);
        webSocket = client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                isOpen.set(true);
                Log.i(TAG, "connected — send hello");
                try {
                    JSONObject hello = new JSONObject();
                    hello.put("type", "hello");
                    hello.put("version", 1);
                    hello.put("transport", "websocket");
                    hello.put("features", new JSONObject().put("mcp", true)); // BẮT BUỘC cho self-control
                    JSONObject audio = new JSONObject();
                    audio.put("format", "opus");
                    audio.put("sample_rate", 16000);
                    audio.put("channels", 1);
                    audio.put("frame_duration", FRAME_MS);
                    hello.put("audio_params", audio);
                    ws.send(hello.toString());
                    Log.i(TAG, "hello: " + hello);
                } catch (Exception e) {
                    Log.e(TAG, "hello build failed", e);
                }
                if (listener != null) listener.onChannelOpened();
            }

            @Override public void onMessage(WebSocket ws, String text) {
                Log.i(TAG, "← text: " + text);
                try {
                    JSONObject json = new JSONObject(text);
                    if ("hello".equals(json.optString("type"))) {
                        parseServerHello(json);
                    } else if (listener != null) {
                        listener.onJson(json);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "json parse", e);
                }
            }

            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                if (bytes.size() == 0) return;
                if (listener != null) listener.onAudioOpus(bytes.toByteArray());
            }

            @Override public void onClosing(WebSocket ws, int code, String reason) {
                isOpen.set(false);
                serverHelloReady.set(false);
            }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                isOpen.set(false);
                serverHelloReady.set(false);
                if (webSocket == ws) webSocket = null;
                if (listener != null) listener.onChannelClosed();
            }

            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                isOpen.set(false);
                serverHelloReady.set(false);
                Log.e(TAG, "failure: " + t.getMessage(), t);
                if (webSocket == ws) webSocket = null;
                if (listener != null) listener.onError(t.getMessage());
                if (helloLatch != null) helloLatch.countDown();
            }
        });

        try {
            boolean ok = helloLatch.await(10, TimeUnit.SECONDS) && serverHelloReady.get();
            if (!ok) {
                Log.e(TAG, "server hello timeout");
                closeAudioChannel();
            }
            return ok;
        } catch (InterruptedException e) {
            closeAudioChannel();
            return false;
        }
    }

    private void parseServerHello(JSONObject root) {
        if (!"websocket".equals(root.optString("transport"))) {
            Log.e(TAG, "bad transport");
            return;
        }
        sessionId = root.optString("session_id", "");
        serverHelloReady.set(true);
        Log.i(TAG, "server hello session_id=" + sessionId);
        if (helloLatch != null) helloLatch.countDown();
    }

    public void sendText(String json) {
        WebSocket ws = webSocket;
        if (ws == null) {
            Log.e(TAG, "sendText: ws null");
            return;
        }
        Log.i(TAG, "→ " + json);
        ws.send(json);
    }

    /** Gửi 1 frame Opus (binary). Chỉ gọi sau server hello + listen start. */
    public void sendAudio(byte[] opus) {
        WebSocket ws = webSocket;
        if (ws == null) return;
        ws.send(ByteString.of(opus));
    }

    public void closeAudioChannel() {
        WebSocket ws = webSocket;
        webSocket = null;
        isOpen.set(false);
        serverHelloReady.set(false);
        if (ws != null) {
            try { ws.close(1000, "Normal closure"); } catch (Exception ignored) {}
        }
    }

    public void dispose() {
        closeAudioChannel();
        client.dispatcher().executorService().shutdown();
    }
}
```

---

## 6) Session flow (wake / TTS / STT) — CODE MẪU

```java
package your.app.xiaozhi;

import android.util.Log;
import org.json.JSONObject;

/**
 * Pseudo session — gắn Opus encoder/decoder + AudioTrack của project bạn.
 * Mic: 16kHz mono s16le, frame 60ms = 1920 bytes PCM → Opus → sendAudio.
 * TTS: Opus binary → decode 24kHz → AudioTrack.
 */
public final class XiaozhiSession {
    private static final String TAG = "XiaozhiWS";

    private final WebsocketProtocol protocol;
    // private final OpusEncoder encoder;
    // private final OpusDecoder decoder;
    // private final AudioTrackPlayer player;

    private volatile boolean listening;
    private volatile boolean ttsPlaying;
    private volatile boolean suppressMic; // greeting / dance

    public XiaozhiSession(WebsocketProtocol protocol) {
        this.protocol = protocol;
    }

    public void bootstrap() throws Exception {
        if (!protocol.openAudioChannel()) {
            throw new IllegalStateException("openAudioChannel failed");
        }
        // Đợi MCP initialize nếu server gửi (xem McpHandler) — timeout 5s vẫn OK
        protocol.sendText(XiaozhiMessages.listenStart(protocol.getSessionId(), "auto"));
        listening = true;
        suppressMic = false;
    }

    /** Gọi từ AudioRecord callback mỗi 60ms. */
    public void onPcmFrame(byte[] pcm1920) {
        if (!protocol.isAudioChannelOpened()) return;
        if (!listening || suppressMic || ttsPlaying) return; // hoặc cho phép uplink khi TTS nếu có AEC
        // byte[] opus = encoder.encode(pcm1920);
        // protocol.sendAudio(opus);
    }

    public void onServerJson(JSONObject json) throws Exception {
        String type = json.optString("type");
        if ("stt".equals(type)) {
            Log.i(TAG, "[STT] " + json.optString("text"));
        } else if ("tts".equals(type)) {
            String state = json.optString("state");
            if ("start".equals(state) || "sentence_start".equals(state)) {
                ttsPlaying = true;
            } else if ("stop".equals(state) || "end".equals(state)) {
                ttsPlaying = false;
                // player.waitForPlaybackCompletion(timeout);
                // playTing();
                protocol.sendText(XiaozhiMessages.listenStart(protocol.getSessionId(), "auto"));
                listening = true;
                suppressMic = false;
            }
        } else if ("mcp".equals(type)) {
            McpHandler.handle(json, protocol);
        }
    }

    public void onServerOpus(byte[] opus) {
        // short[] pcm = decoder.decode(opus);
        // player.write(pcm);
    }

    /** hey mini / nút wake */
    public void onWake() throws Exception {
        // player.interrupt();
        protocol.sendText(XiaozhiMessages.abort(protocol.getSessionId(), true));
        protocol.closeAudioChannel();
        Thread.sleep(100);
        if (!protocol.openAudioChannel()) return;
        suppressMic = true; // chờ greeting
        protocol.sendText(XiaozhiMessages.wakeDetect(protocol.getSessionId(), "hey mini"));
        protocol.sendText(XiaozhiMessages.listenStart(protocol.getSessionId(), "auto"));
        listening = true;
    }
}
```

Audio constants:

| | |
|--|--|
| Mic | 16000 Hz mono s16le |
| WS frame | 60 ms → 1920 bytes PCM |
| MQTT frame | thường 20–60 ms (khớp hello) |
| TTS decode | 24000 Hz |
| Codec | Opus |

---

## 7) MCP self-control — CODE MẪU

```java
package your.app.xiaozhi;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Server gửi type=mcp (JSON-RPC trong payload).
 * Client PHẢI trả initialize / tools/list / tools/call.
 * Hello phải có features.mcp=true.
 */
public final class McpHandler {
    private static final String TAG = "XiaozhiMcp";

    public static void handle(JSONObject root, WebsocketProtocol protocol) throws Exception {
        String sessionId = root.optString("session_id", protocol.getSessionId());
        Object payloadRaw = root.opt("payload");
        JSONObject rpc;
        if (payloadRaw instanceof JSONObject) {
            rpc = (JSONObject) payloadRaw;
        } else if (payloadRaw instanceof String) {
            rpc = new JSONObject((String) payloadRaw);
        } else {
            Log.w(TAG, "no payload");
            return;
        }
        if (!"2.0".equals(rpc.optString("jsonrpc"))) return;
        String method = rpc.optString("method");
        if (method.startsWith("notifications")) return;
        if (!(rpc.opt("id") instanceof Number)) return;
        int id = ((Number) rpc.get("id")).intValue();

        switch (method) {
            case "initialize": {
                // optional: params.capabilities.vision {url, token}
                JSONObject result = new JSONObject();
                result.put("protocolVersion", "2024-11-05");
                result.put("capabilities", new JSONObject().put("tools", new JSONObject()));
                result.put("serverInfo", new JSONObject()
                        .put("name", "alpha-mini")
                        .put("version", "1.0"));
                sendResult(protocol, sessionId, id, result);
                break;
            }
            case "tools/list": {
                JSONObject result = new JSONObject().put("tools", buildTools());
                sendResult(protocol, sessionId, id, result);
                break;
            }
            case "tools/call": {
                JSONObject params = rpc.optJSONObject("params");
                String name = params != null ? params.optString("name", "") : "";
                Log.i(TAG, "tools/call " + name);
                // TODO: map name → robot action thật của project bạn
                // self.otto.sit / stand_up / dance / walk_forward / turn_left / ...
                // self.camera.take_photo / self.shut_down / self.otto.stop
                boolean ok = dispatchTool(name, params);
                JSONObject toolResult = new JSONObject();
                JSONArray content = new JSONArray();
                content.put(new JSONObject().put("type", "text").put("text", ok ? "ok" : "fail"));
                toolResult.put("content", content);
                toolResult.put("isError", !ok);
                sendResult(protocol, sessionId, id, toolResult);
                break;
            }
            default:
                sendError(protocol, sessionId, id, "Method not implemented: " + method);
        }
    }

    private static JSONArray buildTools() throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(tool("self.camera.take_photo", "Chụp ảnh + hỏi vision"));
        tools.put(tool("self.shut_down", "Tắt máy"));
        tools.put(tool("self.otto.walk_forward", "Đi tới"));
        tools.put(tool("self.otto.walk_backward", "Đi lùi"));
        tools.put(tool("self.otto.turn_left", "Xoay trái"));
        tools.put(tool("self.otto.turn_right", "Xoay phải"));
        tools.put(tool("self.otto.jump", "Nhảy"));
        tools.put(tool("self.otto.dance", "Nhảy múa"));
        tools.put(tool("self.otto.taiji", "Thái cực"));
        tools.put(tool("self.otto.kungfu", "Công phu"));
        tools.put(tool("self.otto.sit", "Ngồi"));
        tools.put(tool("self.otto.sit_down", "Ngồi"));
        tools.put(tool("self.otto.stand_up", "Đứng"));
        tools.put(tool("self.otto.stop", "Dừng"));
        tools.put(tool("self.otto.hand_wave", "Vẫy tay"));
        tools.put(tool("self.otto.explore", "Khám phá"));
        // Thêm đủ tool — list ngắn → LLM bảo "không có tool"
        return tools;
    }

    private static JSONObject tool(String name, String desc) throws Exception {
        return new JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("inputSchema", new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()));
    }

    private static boolean dispatchTool(String name, JSONObject params) {
        // Map sang API robot của bạn. Dance: suppress mic ~20s.
        Log.i(TAG, "dispatch " + name);
        return true;
    }

    private static void sendResult(WebsocketProtocol p, String sid, int id, JSONObject result)
            throws Exception {
        JSONObject rpc = new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result);
        JSONObject outer = new JSONObject()
                .put("type", "mcp")
                .put("session_id", sid)
                .put("payload", rpc);
        p.sendText(outer.toString());
    }

    private static void sendError(WebsocketProtocol p, String sid, int id, String msg)
            throws Exception {
        JSONObject rpc = new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("error", new JSONObject().put("message", msg));
        JSONObject outer = new JSONObject()
                .put("type", "mcp")
                .put("session_id", sid)
                .put("payload", rpc);
        p.sendText(outer.toString());
    }
}
```

Vision upload (khi `self.camera.take_photo`):

```java
// Multipart POST visionUrl
// Headers: Device-Id, Client-Id, optional Authorization: Bearer {token}
// Form: question=<string>, file=camera.jpg (JPEG bytes)
```

---

## 8) MQTT + UDP — CODE MẪU (cốt lõi)

```java
package your.app.xiaozhi;

import android.util.Log;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocketFactory;

/**
 * Control = MQTT publish/subscribe.
 * Audio = UDP AES-CTR (nonce 16 || ciphertext).
 * start() connect 1 lần; wake chỉ close/open UDP, KHÔNG disconnect MQTT.
 */
public final class MqttUdpProtocol {
    private static final String TAG = "MQTT";

    private final JSONObject mqttCfg; // từ OTA
    private final String deviceMac;   // để derive subscribe nếu thiếu
    private MqttAsyncClient client;
    private String publishTopic;
    private String subscribeTopic;
    private volatile String sessionId = "";
    private final AtomicBoolean brokerReady = new AtomicBoolean(false);

    private DatagramSocket udpSocket;
    private InetAddress udpServer;
    private int udpPort;
    private SecretKeySpec aesKey;
    private byte[] aesNonce = new byte[16];
    private long localSeq = 0;
    private long remoteSeq = 0;
    private CountDownLatch helloLatch;
    private final AtomicBoolean udpOpen = new AtomicBoolean(false);

    public interface Listener {
        void onJson(JSONObject json);
        void onAudioOpus(byte[] opus);
    }
    private Listener listener;

    public MqttUdpProtocol(JSONObject mqttCfg, String deviceMac, Listener listener) {
        this.mqttCfg = mqttCfg;
        this.deviceMac = deviceMac;
        this.listener = listener;
    }

    public static String resolveEndpoint(String raw) {
        String t = raw.trim();
        if (t.startsWith("mqtts://")) return "ssl://" + t.substring(8);
        if (t.startsWith("ssl://") || t.startsWith("tcp://")) return t;
        // xiaozhi.me → TLS 8883
        if (t.contains("xiaozhi.me") || t.contains("tenclass.net")) {
            return "ssl://" + t + (t.contains(":") ? "" : ":8883");
        }
        return "tcp://" + t + (t.contains(":") ? "" : ":1883");
    }

    public void start() throws Exception {
        String endpoint = resolveEndpoint(mqttCfg.getString("endpoint"));
        String clientId = mqttCfg.getString("client_id");
        publishTopic = mqttCfg.optString("publish_topic", "device-server");
        subscribeTopic = mqttCfg.optString("subscribe_topic", "");
        if (subscribeTopic.isEmpty() || "null".equalsIgnoreCase(subscribeTopic)) {
            String mac = deviceMac.replace(':', '_').replace('-', '_').toLowerCase();
            subscribeTopic = "devices/p2p/" + mac;
        }

        client = new MqttAsyncClient(endpoint, clientId, new MemoryPersistence());
        client.setCallback(new MqttCallbackExtended() {
            @Override public void connectComplete(boolean reconnect, String serverURI) {
                Log.i(TAG, "connectComplete reconnect=" + reconnect);
                try {
                    client.subscribe(subscribeTopic, 1).waitForCompletion(8000);
                    brokerReady.set(true);
                    Log.i(TAG, "subscribed " + subscribeTopic);
                } catch (Exception e) {
                    Log.e(TAG, "subscribe fail", e);
                }
            }
            @Override public void connectionLost(Throwable cause) {
                brokerReady.set(false);
                Log.w(TAG, "connectionLost: " + (cause != null ? cause.getMessage() : "?"));
            }
            @Override public void messageArrived(String topic, MqttMessage message) {
                try {
                    JSONObject json = new JSONObject(new String(message.getPayload()));
                    String type = json.optString("type");
                    if ("hello".equals(type)) {
                        parseServerHello(json);
                    } else if ("goodbye".equals(type)) {
                        closeUdpOnly(false);
                    } else if (listener != null) {
                        listener.onJson(json);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "messageArrived", e);
                }
            }
            @Override public void deliveryComplete(IMqttDeliveryToken token) {}
        });

        MqttConnectOptions opt = new MqttConnectOptions();
        opt.setAutomaticReconnect(true);
        opt.setCleanSession(true);
        opt.setKeepAliveInterval(90);
        opt.setUserName(mqttCfg.optString("username"));
        opt.setPassword(mqttCfg.optString("password").toCharArray());
        if (endpoint.startsWith("ssl://")) {
            opt.setSocketFactory(SSLSocketFactory.getDefault());
        }
        client.connect(opt).waitForCompletion(15_000);
        Log.i(TAG, "MQTT connected " + endpoint);
    }

    public boolean openAudioChannel() throws Exception {
        if (client == null || !client.isConnected()) start();
        // đợi subscribe
        for (int i = 0; i < 50 && !brokerReady.get(); i++) Thread.sleep(100);
        if (!brokerReady.get()) return false;

        sessionId = "";
        localSeq = 0;
        remoteSeq = 0;
        helloLatch = new CountDownLatch(1);

        JSONObject hello = new JSONObject();
        hello.put("type", "hello");
        hello.put("version", 3);
        hello.put("transport", "udp");
        hello.put("features", new JSONObject().put("mcp", true));
        JSONObject audio = new JSONObject();
        audio.put("format", "opus");
        audio.put("sample_rate", 16000);
        audio.put("channels", 1);
        audio.put("frame_duration", 20); // ESP32 thường 20; có thể 60 nếu server yêu cầu
        hello.put("audio_params", audio);

        publish(hello.toString());
        boolean ok = helloLatch.await(10, TimeUnit.SECONDS) && udpOpen.get();
        if (!ok) Log.e(TAG, "UDP server hello timeout");
        return ok;
    }

    private void parseServerHello(JSONObject json) throws Exception {
        if (!"udp".equals(json.optString("transport"))) return;
        sessionId = json.optString("session_id");
        JSONObject udp = json.getJSONObject("udp");
        String server = udp.getString("server");
        udpPort = udp.getInt("port");
        aesKey = new SecretKeySpec(hex(udp.getString("key")), "AES");
        aesNonce = hex(udp.getString("nonce"));

        closeUdpOnly(false);
        udpServer = InetAddress.getByName(server);
        udpSocket = new DatagramSocket();
        udpOpen.set(true);
        new Thread(this::udpRecvLoop, "udp-rx").start();
        Log.i(TAG, "UDP ready " + server + ":" + udpPort + " session=" + sessionId);
        if (helloLatch != null) helloLatch.countDown();
    }

    public void sendAudio(byte[] opus) {
        if (!udpOpen.get() || udpSocket == null) return;
        try {
            byte[] nonce = aesNonce.clone();
            int size = opus.length;
            nonce[2] = (byte) ((size >> 8) & 0xff);
            nonce[3] = (byte) (size & 0xff);
            int seq = (int) (++localSeq);
            nonce[12] = (byte) ((seq >> 24) & 0xff);
            nonce[13] = (byte) ((seq >> 16) & 0xff);
            nonce[14] = (byte) ((seq >> 8) & 0xff);
            nonce[15] = (byte) (seq & 0xff);

            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(nonce));
            byte[] enc = c.doFinal(opus);
            byte[] packet = new byte[nonce.length + enc.length];
            System.arraycopy(nonce, 0, packet, 0, nonce.length);
            System.arraycopy(enc, 0, packet, nonce.length, enc.length);
            udpSocket.send(new DatagramPacket(packet, packet.length, udpServer, udpPort));
        } catch (Exception e) {
            Log.e(TAG, "UDP send", e);
        }
    }

    private void udpRecvLoop() {
        byte[] buf = new byte[65535];
        while (udpOpen.get() && udpSocket != null) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                udpSocket.receive(pkt);
                byte[] data = new byte[pkt.getLength()];
                System.arraycopy(pkt.getData(), 0, data, 0, pkt.getLength());
                handleUdp(data);
            } catch (Exception e) {
                if (udpOpen.get()) Log.e(TAG, "UDP recv", e);
            }
        }
    }

    private void handleUdp(byte[] data) throws Exception {
        if (data.length < 16 || (data[0] & 0xff) != 1) return;
        long seq = ((data[12] & 0xffL) << 24) | ((data[13] & 0xffL) << 16)
                | ((data[14] & 0xffL) << 8) | (data[15] & 0xffL);
        if (seq < remoteSeq) return;
        // gap: log + optional silence fill max 8
        if (remoteSeq != 0 && seq > remoteSeq + 1) {
            Log.w(TAG, "Audio UDP gap: got seq " + seq + ", expected " + (remoteSeq + 1));
        }
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        byte[] iv = new byte[16];
        System.arraycopy(data, 0, iv, 0, 16);
        c.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(iv));
        byte[] opus = c.doFinal(data, 16, data.length - 16);
        remoteSeq = seq;
        if (listener != null) listener.onAudioOpus(opus);
    }

    public void publish(String text) throws Exception {
        if (client == null || !client.isConnected()) return;
        client.publish(publishTopic, text.getBytes(), 0, false);
        Log.i(TAG, "MQTT → " + text);
    }

    public void closeUdpOnly(boolean sendGoodbye) {
        udpOpen.set(false);
        if (udpSocket != null) {
            try { udpSocket.close(); } catch (Exception ignored) {}
            udpSocket = null;
        }
        if (sendGoodbye && sessionId != null && !sessionId.isEmpty()) {
            try { publish(XiaozhiMessages.goodbye(sessionId)); } catch (Exception ignored) {}
        }
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
```

---

## 9) Bootstrap Application — CODE MẪU

```java
package your.app.xiaozhi;

import android.app.Application;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import org.json.JSONObject;

public class App extends Application {
    private static final String TAG = "XiaozhiBoot";

    private WebsocketProtocol ws;
    private XiaozhiSession session;
    private XiaozhiOtaClient ota;

    @Override public void onCreate() {
        super.onCreate();
        HandlerThread t = new HandlerThread("xiaozhi");
        t.start();
        new Handler(t.getLooper()).post(this::boot);
    }

    private void boot() {
        DeviceIdentityStore.Identity id = DeviceIdentityStore.getOrCreate(this);
        Log.i(TAG, "Device-Id=" + id.deviceId + " Client-Id=" + id.clientId);

        // 1) OTA + mã 6 số
        ota = new XiaozhiOtaClient(id.deviceId, id.clientId);
        String board = BoardJson.build(id.deviceId, id.clientId);
        if (ota.checkVersionBlocking(board)) {
            String code = ota.activationCode;
            if (code == null || code.isEmpty()) {
                code = ActivationCode.generate6Digits(id.deviceId, id.clientId);
            }
            Log.i(TAG, "ACTIVATION CODE = " + code + " — nhập trên xiaozhi.me (MAC=" + id.deviceId + ")");
            // TODO: hiện code lên UI
            ota.pollActivateUntilOk(20);
        }

        // 2) WebSocket session
        ws = new WebsocketProtocol(id.deviceId, id.clientId, "", new WebsocketProtocol.Listener() {
            @Override public void onJson(JSONObject json) {
                try { session.onServerJson(json); } catch (Exception e) { Log.e(TAG, "json", e); }
            }
            @Override public void onAudioOpus(byte[] opus) {
                session.onServerOpus(opus);
            }
            @Override public void onChannelOpened() {}
            @Override public void onChannelClosed() {}
            @Override public void onError(String msg) { Log.e(TAG, msg); }
        });
        session = new XiaozhiSession(ws);
        try {
            session.bootstrap();
        } catch (Exception e) {
            Log.e(TAG, "bootstrap failed", e);
        }

        // 3) TODO: start AudioRecord 16k/60ms → session.onPcmFrame
        // 4) TODO: wake word → session.onWake()
        // 5) Optional MQTT: if (ota.mqttConfig != null) new MqttUdpProtocol(...)
    }
}
```

---

## 10) Checklist nghiệm thu

- [ ] Log OTA 200 + `activation.code`
- [ ] Nhập mã trên xiaozhi.me → `/activate` HTTP 200
- [ ] WS connect + hello + `session_id`
- [ ] MCP `initialize` + `tools/list` được trả
- [ ] Nói mic → có JSON `stt`
- [ ] Có `tts` + binary Opus → nghe tiếng
- [ ] Wake cắt TTS, chào lại, mở mic
- [ ] `tools/call` chạy action
- [ ] (Optional) MQTT subscribe → UDP hello → TTS UDP
- [ ] Không gửi Opus trước server hello / trước listen
- [ ] Một MQTT `client_id` duy nhất

Log tags: `XiaozhiOtaClient`, `WS`, `XiaozhiWS`, `MQTT`, `XiaozhiMcp`, `XiaozhiBoot`

---

## 11) Cách agent làm việc

1. Tạo package `your.app.xiaozhi` (đổi tên cho khớp project).
2. Copy các class mẫu trong file này, sửa package + gắn mic/AudioTrack/Opus thật.
3. Làm **WebSocket trước** đến khi STT/TTS chạy.
4. Rồi OTA UI, MCP tools map robot, sau cùng MQTT nếu cần.
5. Mỗi bước: code thật + build được — **không chỉ giải thích**.

Bắt đầu ngay: tạo `DeviceIdentityStore`, `XiaozhiOtaClient`, `WebsocketProtocol`, `XiaozhiMessages`, wire vào `Application`.
