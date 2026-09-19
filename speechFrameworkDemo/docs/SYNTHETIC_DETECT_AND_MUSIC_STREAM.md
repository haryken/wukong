# `requestSyntheticDetect` + stream nhạc (spec đa ngôn ngữ)

Tài liệu này mô tả **logic chung** (không phụ thuộc Kotlin/Android), để port sang Python / C++ / Go / ESP32 / …  

Hai phần:
1. **Synthetic detect** — sau hết nhạc / fail, mở lại kênh Xiaozhi và “đánh thức” server bằng JSON.  
2. **Stream nhạc** — HTTP tới server nhạc (mặc định `https://youtube.kytuoi.com`), phát trên thiết bị.

---

# Phần A — `requestSyntheticDetect` / Synthetic Wake Detect

## A.1. Mục đích (non-tech)

Khi robot **đóng đường nói chuyện** để phát nhạc, hết bài (hoặc lỗi) nó cần **nối lại** và báo server kiểu: “mình vừa hết nhạc / nhạc lỗi”.  

Không phải người nói “hey mini”, mà **phần mềm giả lập** một lần phát hiện wake word với **chữ cố định**, để server trả lời / mở hội thoại lại.

## A.2. Hai lớp API trên Mini (Kotlin)

```
OttoMusicPlayer.notifyFinished() / notifySearchFailed()
    → delay 600–800 ms
    → DemoSpeech.requestSyntheticDetect(text)
        → XiaozhiSessionApi.sendSyntheticWakeDetect(text)
            → (WS) XiaozhiWebSocketSessionManager.sendSyntheticWakeDetect
            → (MQTT) XiaozhiMqttSessionManager.sendSyntheticWakeDetect
```

| Sự kiện | `text` gửi lên server |
|---------|------------------------|
| Phát hết bài | `"phát hết nhạc"` |
| Tìm/stream fail | `"nhạc thất bại"` |

**Không** dùng `"xin chào"` ở đây (boot/wake người dùng mới dùng `"xin chào"`).

## A.3. Logic thuật toán (pseudo — mọi ngôn ngữ)

```
function requestSyntheticDetect(text):
    text = trim(text)
    if text empty: return

    // 1) Local flags (client-only, không gửi server)
    musicOnlyMode = false
    stop local TTS playback if any
    suppressMicUplinkUntilServerSpeaks = true   // tránh echo
    treatAsJustWoke = true

    // 2) Đảm bảo kênh audio Xiaozhi đang mở
    if not isAudioChannelOpened():
        for attempt in 1..4:
            clear "await manual wake" flags
            result = openAudioChannel()          // xem A.4
            if result.ok:
                awaitMcpInitialize(optional timeout)
                break
            sleep(1500 * attempt ms)
        if still closed:
            // Fail: chờ người nói hey mini / chạm đầu
            markAwaitManualWake()
            return

    // 3) Gửi detect + listen lên server (JSON trên WS hoặc MQTT)
    sendWakeWordDetected(text)                 // A.5 JSON #1
    sleep(80 ms)
    sendStartListening(mode = "auto")          // A.5 JSON #2

    // 4) Local: bật lại mic UI / KWS
    notifyMicReady()
```

## A.4. `openAudioChannel()` — mở kênh (chung Xiaozhi)

Áp dụng **WebSocket** (Mini hiện dùng nhiều) hoặc **MQTT+UDP** — khái niệm giống nhau.

### WebSocket (tóm tắt)

1. Kết nối `wss://…` (URL từ OTA / config).  
2. Client gửi **hello** (JSON text frame).  
3. Đợi server **hello** → nhận `session_id` (và có thể capabilities/vision).  
4. Server có thể gửi MCP `initialize` → client trả `tools/list` / result (nếu có).  
5. Kênh = OPENED → được gửi `listen` + binary Opus.

Ví dụ client hello (tham khảo `WebsocketProtocol`):

```json
{
  "type": "hello",
  "version": 1,
  "transport": "websocket",
  "audio_params": {
    "format": "opus",
    "sample_rate": 16000,
    "channels": 1,
    "frame_duration": 60
  }
}
```

(Chi tiết field có thể khác theo bản server; quan trọng: **có `session_id` sau server hello** rồi mới gửi listen.)

### Đóng kênh khi bắt đầu phát nhạc

Khi MediaPlayer **start** thật:

```
enterMusicOnlyMode():
  musicOnlyMode = true
  interrupt TTS
  closeAudioChannel()     // đóng WS (hoặc UDP session)
  // Wake word local (KWS) vẫn chạy — không gửi PCM lên server
```

## A.5. JSON gửi lên server (bắt buộc để port)

Gửi bằng **text frame WebSocket** hoặc **MQTT publish** cùng schema Xiaozhi.  
Luôn kèm `session_id` lấy từ server hello.

### (1) Synthetic / thật — “wake detect”

Hàm protocol: `sendWakeWordDetected(text)`:

```json
{
  "session_id": "<id từ server hello>",
  "type": "listen",
  "state": "detect",
  "text": "phát hết nhạc"
}
```

hoặc `"text": "nhạc thất bại"`.

**Ý nghĩa:** giống thiết bị vừa phát hiện wake word với nội dung `text`. Server coi như user “đánh thức” bằng câu đó → LLM/TTS phản hồi (không cần audio Opus trước đó).

### (2) Bắt đầu nghe mic (sau detect ~80ms)

Hàm: `sendStartListening(AUTO_STOP)`:

```json
{
  "session_id": "<id>",
  "type": "listen",
  "state": "start",
  "mode": "auto"
}
```

| `mode` | Nghĩa |
|--------|--------|
| `"auto"` | AUTO_STOP — nói xong server tự kết thúc lượt nghe |
| `"realtime"` | ALWAYS_ON |
| `"manual"` | MANUAL |

**Lưu ý Mini:** resume thường **không** gửi `listen/state=stop` trước `start` (tránh server lỗi STT).

### (3) Sau đó — uplink audio (nếu user nói)

- Encode mic → **Opus** frames → gửi **binary** trên cùng WebSocket (hoặc UDP với MQTT).  
- Chỉ gửi sau khi đã `listen/start` + hết cooldown ngắn (tránh STT rác).

### (4) Abort (khi wake cắt TTS) — tham khảo

```json
{
  "session_id": "<id>",
  "type": "abort",
  "reason": "wake_word_detected"
}
```

Synthetic detect trên Mini **không bắt buộc** gửi abort trước; chủ yếu clear flag + mở kênh + detect + listen.

## A.6. Server nhận gì / trả gì (kỳ vọng)

1. Nhận `listen/detect` + `text` → xử lý như wake (có thể chạy prompt/tool tùy cấu hình agent).  
2. Có thể trả `stt` echo text, rồi `tts` / `mcp` / `llm`.  
3. Client phát TTS Opus xuống loa; khi `tts` state `stop` → listen lại như chat bình thường.

## A.7. Sơ đồ tuần tự

```
[Music ends / fail]
        │
        ▼
 exitMusicOnlyMode (local)
        │
        ▼
 channel closed? ──yes──► openAudioChannel (retry ≤4)
        │                         │
        │◄────────────────────────┘
        ▼
 JSON: listen + state=detect + text
        │
        ▼  (~80ms)
 JSON: listen + state=start + mode=auto
        │
        ▼
 Server TTS / chat lại
```

---

# Phần B — Stream nhạc về thiết bị

## B.1. Server nhạc (HTTP, độc lập Xiaozhi)

Base URL cấu hình được (Self-Control `:8080` → Cài đặt robot → Server nhạc).  
Mặc định: `https://youtube.kytuoi.com`

### B.1.1 Search

```
GET {base}/api/search?q={urlencoded_query}&limit=5
Accept: */*
```

Response JSON (tóm tắt thực tế dùng):

```json
{
  "success": true,
  "data": [
    { "id": "VIDEO_ID", "title": "Tên bài ..." }
  ]
}
```

Client chọn 1 phần tử (score theo từ khóa title) → lấy `id`.

### B.1.2 Stream MP3

```
GET {base}/api/stream/mp3?id={VIDEO_ID}&format=mp3
Accept: audio/mpeg,*/*
```

- Body: **bytes MP3** (thường `Transfer-Encoding: chunked`, `Content-Length: -1`).  
- Client **không** bắt buộc tải hết file trước khi phát (stream).

## B.2. Kiến trúc phát trên Mini (vì sao có proxy local)

```
[Music server HTTPS]
        │  OkHttp GET stream MP3
        ▼
[Local ServerSocket 127.0.0.1:random]
        │  HTTP/1.1 200 + Content-Type: audio/mpeg + body chunk
        ▼
[MediaPlayer] setDataSource("http://127.0.0.1:port/stream.mp3")
```

**Lý do:** `MediaPlayer.setDataSource(pipe FD)` trên ROM Mini lỗi `offset error`.  
HTTP loopback ổn định hơn.

### Pseudo pump

```
server = listen(127.0.0.1, 0)
url_local = "http://127.0.0.1:" + port + "/stream.mp3"

thread:
  client = server.accept()
  read_and_discard HTTP request from MediaPlayer
  write "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nConnection: close\r\n\r\n"
  upstream = HTTP_GET(music_server_stream_url)
  while chunk = upstream.read():
    client.write(chunk)

main:
  MediaPlayer.prepareAsync(url_local)
  onPrepared → start() → enterMusicOnlyMode()  // đóng Xiaozhi WS
  onCompletion → notifyFinished → requestSyntheticDetect("phát hết nhạc")
  onError / search fail → requestSyntheticDetect("nhạc thất bại")
```

### Fallback

Nếu proxy/prepare fail → tải file cache (giới hạn dung lượng) → `MediaPlayer` phát path local.

## B.3. Quan hệ với Xiaozhi WS

| Thời điểm | Xiaozhi WS | Nhạc |
|-----------|------------|------|
| MCP `music.play` / đang search | **Mở** — vẫn chat | HTTP search nền |
| `MediaPlayer.start` | **Đóng** (`enterMusicOnlyMode`) | Đang stream |
| Hết / fail | **Mở lại** + detect JSON (Phần A) | Stop player |

Nhạc **không** đi qua WebSocket Xiaozhi; chỉ HTTP tới server nhạc + loa local.

## B.4. MCP kích hoạt (Xiaozhi → robot)

Server gọi tool (JSON-RPC trong message `type: mcp`):

```json
{
  "name": "self.otto.music.play",
  "arguments": { "query": "Phép Màu" }
}
```

Robot trả tool result `status=starting`, rồi chạy search/stream nền.

Dừng:

```json
{ "name": "self.otto.music.stop", "arguments": {} }
```

---

# Phần C — Checklist port ngôn ngữ khác

### Synthetic detect

- [ ] Biết mở/đóng audio channel Xiaozhi (WS hello ↔ session_id).  
- [ ] Gửi đúng 2 JSON: `listen/detect` + `listen/start`.  
- [ ] Dùng text `"phát hết nhạc"` / `"nhạc thất bại"` (hoặc cấu hình tương đương trên agent).  
- [ ] Retry open channel nếu fail.  
- [ ] Không gửi Opus trước `listen/start`.

### Stream nhạc

- [ ] `GET /api/search` + parse `data[].id`.  
- [ ] `GET /api/stream/mp3?id=…` đọc body MP3.  
- [ ] Player nhận stream (URL / file / pipe tùy nền tảng).  
- [ ] On start → đóng kênh chat; on end/fail → synthetic detect.

### Cấu hình

- [ ] Base URL server nhạc (mặc định `https://youtube.kytuoi.com`).

---

# Tham chiếu code Mini

| Thành phần | File |
|------------|------|
| `requestSyntheticDetect` | `DemoSpeech.kt` |
| Logic mở kênh + JSON detect | `XiaozhiWebSocketSessionManager.sendSyntheticWakeDetect` |
| JSON listen/detect/start | `info/dourok/voicebot/protocol/Protocol.kt` |
| open WS + hello | `WebsocketProtocol.kt` |
| Search/stream/proxy | `OttoMusicPlayer.kt` |
| Đổi music server | `SelfControlStore` + web `:8080` |
