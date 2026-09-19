# Cơ chế phát nhạc trên Alpha Mini (hiện tại)

Server nhạc mặc định: **`https://youtube.kytuoi.com`**  
(đổi được trên trang Self-Control `http://<IP-robot>:8080` → tab **Cài đặt robot** → **Server nhạc**)

API dùng trên server:
- `GET /api/search?q=...&limit=5` — tìm bài
- `GET /api/stream/mp3?id=...&format=mp3` — stream MP3

---

## Phần 1 — Giải thích không kỹ thuật

Robot làm **hai việc tách nhau**:

1. **Nói chuyện** với “bộ não” trên mạng (qua đường nối WebSocket).  
2. **Nghe nhạc** trên loa (tải/stream từ **server nhạc**).

Khi bạn bảo phát một bài:

1. Robot nhận lệnh, **vẫn nói chuyện bình thường** trong lúc đang **đi tìm bài** trên server nhạc.  
2. Chỉ khi **nhạc bắt đầu vang lên thật** → tạm **cúp đường nói chuyện** (đóng WebSocket) để loa chỉ dành cho nhạc, không bị chồng tiếng.  
3. Khi **hết bài**, hoặc **tìm/phát lỗi**, hoặc bạn nói **hey mini** để dừng → robot **tự nối lại** đường nói chuyện và sẵn sàng nghe bạn.

Bạn gần như không cần làm gì sau khi hết nhạc. Nếu lần tự mở lại không được, cứ nói **hey mini** hoặc **chạm đầu**.

**Server nhạc** giống “kho YouTube dạng API”: robot hỏi “bài gì?”, server trả ID bài + luồng MP3. Đổi địa chỉ server trên web `:8080` nếu bạn có server khác cùng kiểu API.

---

## Phần 2 — Vừa tech vừa dễ hiểu

### Tổng quan theo giai đoạn

| Giai đoạn | Người dùng thấy | Bên trong (tech) |
|-----------|-----------------|------------------|
| Bảo phát nhạc | Robot trả lời, vẫn nghe bạn | MCP `self.otto.music.play` → `OttoMusicPlayer`; **WS vẫn mở** |
| Đang tìm / chuẩn bị | Vẫn chat được | `GET {music_server}/api/search`; chọn bài (score title) |
| Stream sẵn sàng | (chưa chắc đã nghe) | Proxy local `http://127.0.0.1:port/stream.mp3` ← upstream kytuoi (hoặc server bạn cấu hình) |
| **Nhạc phát thật** | Có tiếng nhạc | `enterMusicOnlyMode()` → **đóng WS**, chặn PCM lên server |
| Hết bài | Robot nói lại được | `notifyFinished` → detect `"phát hết nhạc"` → **mở lại WS** |
| Fail tìm/stream | Robot báo / nói lại | `notifySearchFailed` → detect `"nhạc thất bại"` → **mở lại WS** |
| Hey mini lúc đang nhạc | Dừng nhạc, chat lại | `forceStop` + wake mở kênh |

### Server nhạc

- Config: `SelfControlStore.music_server_url` (SharedPreferences).  
- Mặc định: `https://youtube.kytuoi.com`.  
- `OttoMusicPlayer.apiHost()` đọc config mỗi lần search/stream.  
- Web `:8080` → tab **Cài đặt robot** → khối **Server nhạc** → Lưu.

### Cách phát trên Mini (vì sao không pipe FD)

ROM Mini lỗi `MediaPlayer.setDataSource(pipe FD)` (`offset error`).  
Cách hiện tại:

1. **Ưu tiên:** OkHttp kéo MP3 từ server nhạc → `ServerSocket` local → MediaPlayer mở `http://127.0.0.1:.../stream.mp3`.  
2. **Fallback:** tải file cache (giới hạn dung lượng) rồi phát local.

### Khi nào đóng / mở WebSocket

**Đóng WS** — chỉ khi MediaPlayer đã `start()` (phát thật):

- `DemoSpeech.enterMusicOnlyMode()`  
- Abort TTS, `protocol.closeAudioChannel()`, `musicOnlyMode = true`  
- Wake word (hey mini) local vẫn chạy; không gửi mic lên server.

**Mở lại WS** — sau hết nhạc / fail:

1. `exitMusicOnlyMode()` (clear flag).  
2. `requestSyntheticDetect("phát hết nhạc" | "nhạc thất bại")`.  
3. `sendSyntheticWakeDetect`:  
   - Nếu kênh đóng → `openAudioChannel()` tối đa **4 lần** (delay tăng dần).  
   - Chờ MCP initialize nếu cần.  
   - `sendWakeWordDetected(text)` (không dùng “xin chào”).  
   - `sendListen` → mic sẵn sàng.  
4. Nếu 4 lần fail → chờ **hey mini / chạm đầu**.

**Hey mini** lúc đang nhạc: dừng player + clear music-only + mở/listen như wake thường.

### File chính

- `OttoMusicPlayer.kt` — search, proxy/stream, finish/fail detect  
- `XiaozhiWebSocketSessionManager.kt` — `enterMusicOnlyMode`, `sendSyntheticWakeDetect`  
- `XiaozhiMcpResponder.kt` — tools `music.play` / `music.stop`  
- `SelfControlStore.kt` + `self_control.html` — đổi server nhạc trên `:8080`

---

## Một câu tóm

**Tìm nhạc chạy riêng trên server (mặc định youtube.kytuoi.com, đổi được ở :8080); vẫn nói chuyện đến khi nhạc phát thật thì đóng WS; hết/lỗi thì tự mở WS lại bằng detect giả, hoặc hey mini.**

Chi tiết JSON `listen/detect` + cách stream (để port ngôn ngữ khác): xem [`SYNTHETIC_DETECT_AND_MUSIC_STREAM.md`](./SYNTHETIC_DETECT_AND_MUSIC_STREAM.md).
