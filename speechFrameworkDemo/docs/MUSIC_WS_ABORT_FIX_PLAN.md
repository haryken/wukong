# Nhạc `play_music` đứt giữa bài — phân tích & phương án fix

**Trạng thái:** chưa implement — chỉ ghi nhận từ log 2026-09-11 (~16:50).  
**Ghi chú:** đè speech stock = `DemoSpeech` + MicArray AAR (`docs/STOCK_SPEECH_OVERRIDE.md`) — **không** dùng `StockSpeechOverride` no-op.

---

## Triệu chứng

Đang phát nhạc (ví dụ `《安眠奏》`) thì dừng ngang → phải chạm đầu / hey mini mới nói lại được.

## Timeline log (rút gọn)

| Thời điểm | Sự kiện |
|-----------|---------|
| `16:50:03` | Server: `% play_music...` → `《安眠奏》` |
| Trước đó | Mic: `sending to server (Xiaozhi)` — vẫn gửi Opus uplink |
| `16:50:06` | `SSLException: Write error … Software caused connection abort` (lúc **ghi** frame lên WS) |
| Ngay sau | `WebSocket CLOSED – chờ wake` |
| Mic | `mic local only` = **kênh đã đóng** (không phải cờ “chờ TTS chào”) |
| `16:50:18` | Head-tap → `[Wake] openAudioChannel` → listen OK |

**Kết luận:** nhạc dừng vì **WebSocket/SSL chết**, không phải vì hết bài hay `tts/stop`.

---

## Nguyên nhân (xếp theo khả năng)

1. **Uplink mic + downlink nhạc cùng một WebSocket**  
   Sau khi simplify theo ChatViewModel: *kênh mở → luôn gửi mic*. Lúc `play_music` vẫn spam `sendAudio` trong khi nhận Opus dài → áp lực ghi SSL trên robot → abort.

2. **Sau `CLOSED` không tự reopen giữa bài**  
   Session manager hiện tại: đóng → chờ wake. Không còn nhánh “đứt giữa TTS/nhạc → chờ buffer → reopen”.

3. **Phụ**  
   `AudioTrack` underrun khi chuyển câu; skill `HAND_KISS` trùng lúc play — ít khả năng tự cắt SSL.

---

## Phương án fix (chưa chọn / chưa code)

### A — Nhẹ (khuyến nghị ưu tiên)

- Tạm **không gửi mic** khi đang `speaking` / nhận TTS (gồm nhạc). Không dùng cờ sticky kiểu “chờ chào + ting”.
- Nếu `CLOSED` khi đang `speaking` → **tự reopen + listen** sau playback (hoặc timeout), không bắt buộc wake nếu vẫn muốn tiếp tục nghe.
- Ít lệch ChatViewModel, khớp trực tiếp log.

### B — Trung bình

- Nhận diện `play_music` / TTS dài → mute uplink + timeout chờ playback dài hơn (vài phút).
- Vẫn auto-reopen nếu SSL đứt giữa chừng.
- Ổn định nhạc hơn A; hơi lệch nguyên bản.

### C — Nặng

- Audio dài qua **MQTT + UDP**; WS chỉ control.
- Ít đứt SSL khi stream dài; đổi transport / phụ thuộc OTA.

### Không khuyến nghị

- Quay lại đống cờ sticky `suppressServerPcmUntilFirstGreetingDone` / `sessionClosedAwaitWake`… — dễ kẹt mic mãi như trước.

---

## Việc cần làm khi implement (A)

1. `XiaozhiWebSocketSessionManager` (và MQTT nếu dùng):  
   - `sendPcmFrameFromJava`: bỏ qua khi `speaking == true`.  
   - `collectChannelState` / `onFailure` CLOSED: nếu vừa `speaking` → schedule reopen (không sticky “await wake” vô hạn).
2. Giữ `WAIT_PLAYBACK_TIMEOUT_MS` đủ cho lời thoại; với nhạc có thể bump tạm khi thấy `play_music` trong text TTS (chỉ nếu chọn B).
3. Kiểm tra log sau fix:
   - Trong lúc nhạc: **không** spam `WS gửi thật audio` / `[GỬI AUDIO]`.
   - Nếu SSL vẫn abort: phải thấy auto `[Wake] openAudioChannel` hoặc reopen không cần head-tap.
   - Hết bài / `tts/stop`: listen + ting như hiện tại.

---

## Liên quan code hiện tại

- `XiaozhiWebSocketSessionManager.kt` — simplified ChatViewModel flow.
- `WebsocketProtocol.kt` — `readTimeout(0)`, `writeTimeout(0)`, `pingInterval(20s)`, `onFailure` → `CLOSED`.
- Đè speech stock: xem `docs/STOCK_SPEECH_OVERRIDE.md` (DemoSpeech + MicArray AAR) — **không** liên quan cắt nhạc.

---

## Ghi chú vận hành

- Sau cài APK có `ubt-master-app=third_part_speechservice`: **tắt nguồn robot rồi bật lại**.
- Log `mic local only (Xiaozhi chặn PCM)` khi WS đóng chỉ nghĩa “không gửi được” — đừng nhầm với cờ greeting cũ.
