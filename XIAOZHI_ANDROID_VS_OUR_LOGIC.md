# So sánh logic Xiaozhi_Android vs ubt_speech_demo (XiaozhiSessionManager)

## Nguồn tham chiếu
- **Xiaozhi_Android:** `Xiaozhi_Android-main/xiaozhi_android_source/app/.../ChatViewModel.kt`, `AudioRecorder.kt`, `OpusStreamPlayer.kt`, `WebsocketProtocol.kt`, `Protocol.kt`
- **Code mình:** `XiaozhiSessionManager.kt`, `DemoRecognizer.java`, `WebsocketProtocol.kt`

---

## 1. Khởi tạo kênh + listen

| Bước | Xiaozhi_Android | Code mình |
|------|-----------------|-----------|
| Mở WS | `protocol.openAudioChannel()` | `protocol.openAudioChannel()` (OpenChannelResult) |
| Hello | Client gửi hello, đợi server hello, lấy `session_id` | Giống |
| Sau khi mở | **Ngay lập tức** `protocol.sendStartListening(ListeningMode.AUTO_STOP)` | Reconnect: `sendStartListening(AUTO_STOP)`; hey mini: `sendStartListening(ALWAYS_ON)`; init qua `ensureChannelOpen` + gửi listen ở chỗ gọi |
| Recorder / PCM | `AudioRecorder.startRecording()` → Flow; **luôn** `opusFlow.collect { protocol.sendAudio(it) }` | `DemoRecognizer` → `sendPcmFrameFromJava`; **bị chặn khi isTtsPlaying** |

---

## 2. Gửi audio lên server (PCM → Opus → WS)

### Xiaozhi_Android (ChatViewModel)
```kotlin
opusFlow?.collect {
    it?.let { protocol.sendAudio(it) }
}
```
- **Không có** `if (deviceState != SPEAKING)`.
- **Luôn gửi** mọi frame: TTS đang phát vẫn gửi mic lên server.
- Recorder chạy liên tục, encode → send không phụ thuộc trạng thái TTS.

### Code mình (XiaozhiSessionManager.sendPcmFrameFromJava)
```kotlin
if (isTtsPlaying) {
    // timeout 120s mới cho gửi lại
    ...
    return  // ← KHÔNG GỬI GÌ trong suốt thời gian TTS
}
// mới gửi protocol.sendAudio(encoded)
```
- **Trong lúc TTS (phát nhạc dài): không gửi bất kỳ frame nào.**
- Server nhận **0 byte** từ client có thể vài phút → dễ rơi vào trạng thái idle / không xử lý STT khi client gửi lại sau.

**Kết luận:** Đây là khác biệt quan trọng nhất: Xiaozhi_Android **luôn stream audio**; mình **dừng stream** khi TTS.

---

## 3. TTS: start / sentence_start

### Xiaozhi_Android
- `"tts"` state `"start"`: set `deviceState = SPEAKING` (chỉ UI). **Không** tắt recorder, **không** chặn send audio.

### Code mình
- `"tts"` state `"start"` hoặc `"sentence_start"`: set `isTtsPlaying = true` → **mọi frame PCM bị drop** trong `sendPcmFrameFromJava`.

---

## 4. TTS: stop (sau khi phát xong / phát nhạc dài)

### Xiaozhi_Android
```kotlin
"stop" -> {
    schedule {
        if (deviceState == DeviceState.SPEAKING) {
            player?.waitForPlaybackCompletion()  // chờ phát hết buffer
            if (keepListening) {
                protocol.sendStartListening(ListeningMode.AUTO_STOP)
                deviceState = DeviceState.LISTENING
            }
        }
    }
}
```
- **Chỉ** `sendStartListening(AUTO_STOP)`.
- **Không** gọi `sendStopListening()`.
- **Không** đóng WS, **không** mở lại.
- Audio vẫn đang được gửi suốt (recorder không bao giờ dừng theo TTS).

### Code mình
- TTS `"stop"`: delay + `waitForPlaybackCompletion()` + delay 850ms → `isTtsPlaying = false`.
- Nếu TTS **dài** (≥ LONG_TTS_REOPEN_THRESHOLD_MS): **đóng WS + mở lại** + `sendStartListening(AUTO_STOP)`.
- Nếu TTS ngắn: **chỉ** `sendListenStartOnly("sau TTS", AUTO_STOP)`.
- **Không** gửi listen stop trước (đã bỏ theo ref trước).

---

## 5. Listen mode

### Xiaozhi_Android
- Sau khi mở kênh: `AUTO_STOP` (mode `"auto"`).
- Sau TTS stop: `AUTO_STOP`.
- User bấm start listening: `MANUAL`.
- `sendStopListening()` **chỉ** khi user chủ động stop (toggle LISTENING → IDLE), **không** dùng trong flow TTS.

### Code mình
- Sau TTS (ngắn/dài), reconnect, refresh: `AUTO_STOP`.
- Hey mini: `ALWAYS_ON` (`"realtime"`).
- Đã bỏ listen stop khi refresh / sau TTS.

---

## 6. Protocol / WebSocket

- Cả hai: hello (version, transport, audio_params), session_id từ server, send text (JSON), send binary (Opus).
- Format listen: `session_id`, `type: "listen"`, `state: "start"` / `"stop"`, `mode: "auto"` / `"realtime"` / `"manual"` – giống nhau.

---

## 7. Tại sao TTS dài trên Xiaozhi_Android vẫn STT lại bình thường?

1. **Audio luôn được gửi:** Recorder → encode → sendAudio chạy **liên tục**, kể cả khi đang SPEAKING. Server luôn nhận stream mic → pipeline STT không bị “ngắt quãng”.
2. **Sau TTS chỉ cần listen start:** Server chỉ cần một lần `sendStartListening(AUTO_STOP)` là chuyển lại chế độ nghe; không cần reconnect hay listen stop.
3. **Không có “khoảng trống” vài phút không gửi gì:** Trên code mình, trong lúc TTS dài **không gửi frame nào** → server có thể coi kênh listen idle / timeout và sau đó không xử lý đúng dù có gửi listen start + audio lại.

---

## 8. Đề xuất sửa (giống Xiaozhi_Android)

- **Gửi audio liên tục:** Không chặn gửi PCM khi `isTtsPlaying` (bỏ hoặc nới block trong `sendPcmFrameFromJava`).
- Xiaozhi_Android dùng AEC (AcousticEchoCanceler) trên AudioRecorder; code mình cũng dùng AEC (DemoRecognizer / AudioRecord cùng session với TTS). Echo có thể được xử lý phần lớn bởi AEC.
- Nếu vẫn echo: có thể chỉ drop vài frame **đầu** sau TTS start (vd 200–500 ms) thay vì chặn toàn bộ TTS.

Như vậy luồng sẽ giống Xiaozhi_Android: **luôn stream mic lên server**, sau TTS chỉ cần **một lần send listen start** là STT hoạt động lại kể cả sau TTS/nhạc dài.

---

## 9. Tại sao "hey mini" mãi không nhận?

Luồng hey mini: **AudioRecord (directRecord)** → `sendFrameToXiaozhi(frame)` → vừa `wakeWordPcmFeeder.feedPcmFrame(frame)` (Porcupine) vừa `xiaozhiSessionManager.sendPcmFrameFromJava(frame)` (server). Cùng một luồng PCM.

Nguyên nhân có thể:

1. **Framework gọi `stop()` lên wake detector khi recognition bắt đầu**  
   Nhiều framework (AbstractWakeUpDetector / CompositeSpeechService) tắt wake detector khi bắt đầu nhận diện để tránh trigger giữa chừng. Nếu sau đó **không có TTS** (vd: server không trả lời, không phát câu nào), callback `onTtsStoppedRestartMic` / `resumeXiaozhiMicAfterTts()` không bao giờ chạy → **không ai gọi `wakeUpDetectorRef?.start()` lại** → Porcupine ở trạng thái stopped → nói "hey mini" mãi không nhận.

2. **Chỉ start lại Porcupine khi "TTS stop"**  
   Hiện tại ta gọi `wakeUpDetectorRef?.start()` trong:
   - `onTtsStarted` (khi TTS bắt đầu),
   - `resumeXiaozhiMicAfterTts()` (khi TTS stop / reconnect).  
   Nếu user chưa từng nghe TTS (mở app → nói → không có phản hồi), framework có thể đã stop detector khi bắt đầu recognition → không có sự kiện TTS stop → detector không được start lại.

3. **Đã xử lý trong code**  
   - Thêm callback **`onReconnectOrRefreshListen`** trong `XiaozhiSessionManager`: gọi khi **auto reconnect** thành công và khi **refresh listen** (gửi PCM lâu không thấy STT).
   - Trong `DemoSpeech`, truyền `onReconnectOrRefreshListen = { wakeUpDetectorRef?.start() }` để mỗi lần reconnect hoặc refresh listen đều **start lại Porcupine**.  
   → Dù framework đã gọi stop() khi recognition bắt đầu, sau vài chục giây không STT (refresh) hoặc khi reconnect, hey mini lại có cơ hội hoạt động.

4. **Các khả năng khác (nếu vẫn không nhận)**  
   - Log có xuất **"[WakeWord] PCM tới nhưng Porcupine chưa start hoặc null"** → Porcupine chưa start hoặc init lỗi (key, file .ppn).  
   - `setWakeWordPcmFeeder` chưa được gọi hoặc gọi sau khi đã có PCM → feeder = null, PCM không vào Porcupine.  
   - `startMicForWakeWord()` / `startDirectAudioRecord()` chưa chạy → không có PCM từ mic (kiểm tra log "onRecord (AudioRecord)").
