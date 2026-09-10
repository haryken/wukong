# Luồng audio Xiaozhi (từ source Xiaozhi_Android-main)

## Phát âm chuẩn như Xiaozhi_Android-main (quan trọng)

- **Cách giống hệt Xiaozhi:** Dùng **native Opus** (libopus qua JNI → libapp.so). Project này đã có sẵn: `speechFrameworkDemo/src/main/cpp/` build libopus + JNI thành **libapp.so** (cùng cơ chế với Xiaozhi).
- **Build:** Chạy **không** dùng `-PskipNdk`:
  - Đúng: `./gradlew assembleDebug` hoặc build trong Android Studio (mặc định build NDK).
  - Sai: `./gradlew assembleDebug -PskipNdk=true` → không tạo libapp.so → app dùng Concentus (Java) → âm nhỏ, dễ rè.
- **Cần:** NDK đã cài (Android Studio hoặc `sdkmanager ndk`), CMake, mạng (lần đầu FetchContent tải opus). Nếu build NDK lỗi, kiểm tra NDK version trong `build.gradle` (ndkVersion "21.4.7075529").
- **Thư viện thay thế:** Không cần thư viện ngoài – native Opus đã nằm trong project (xiph/opus qua CMake). Giống Xiaozhi_Android-main (cùng libopus, cùng JNI). Nếu không build được NDK có thể copy **libapp.so** từ bản build thành công (hoặc từ Xiaozhi_Android) vào `src/main/jniLibs/arm64-v8a/`.

---

## Tổng quan

Xiaozhi_Android thực hiện: **thu âm mic → encode Opus → gửi WebSocket → server STT/TTS → nhận Opus → decode → phát loa**.

---

## 1. Thu âm (nhận audio từ micro)

**File:** `AudioRecorder.kt`

- Dùng **Android `AudioRecord`**: `MediaRecorder.AudioSource.MIC`, 16 kHz, mono, 16-bit PCM.
- `bufferSize = getMinBufferSize(...) * 2`.
- Frame: `frameBytes = (sampleRate * frameSizeMs / 1000) * channels * 2` (ví dụ 60 ms → 1920 bytes).
- Trong thread riêng: `audioRecord.read(buffer, 0, frameBytes)` → gửi từng frame vào `Channel<ByteArray>`.
- Trả về **Flow<ByteArray>** (PCM từng frame).
- Tùy chọn: AEC (AcousticEchoCanceler), NoiseSuppressor nếu device hỗ trợ.

**Cách dùng trong ChatViewModel:**

```kotlin
recorder = AudioRecorder(sampleRate = 16000, channels = 1, frameSizeMs = 60)
val audioFlow = recorder?.startRecording()
```

---

## 2. Gửi lên server (PCM → Opus → WebSocket)

**Encode:** `OpusEncoder.kt`

- Load `System.loadLibrary("app")` (libapp.so – Opus JNI).
- Init: `nativeInitEncoder(sampleRate, channels, 2048)` (VOIP).
- Mỗi frame PCM (đúng size, ví dụ 1920 bytes) → `nativeEncodeBytes(...)` → **ByteArray Opus**.
- Format gửi lên server: Opus 16 kHz, 1 channel, 60 ms/frame.

**WebSocket:** `WebsocketProtocol.kt`

- Kết nối: `OkHttpClient.newWebSocket(url)` với header `Authorization`, `Device-Id`, `Client-Id`, `Protocol-Version`.
- Sau khi connect: gửi **Hello** JSON (type, version, transport, audio_params: format opus, sample_rate 16000, channels 1, frame_duration 60).
- Gửi audio: `websocket.send(ByteString.of(*data))` (binary = Opus từng frame).

**Quan trọng:** Sau khi mở kênh audio, phải gửi **bắt đầu nghe** để server biết bắt đầu nhận giọng và chạy STT:

```kotlin
protocol.openAudioChannel()
protocol.sendStartListening(ListeningMode.AUTO_STOP)  // "listen" + state "start" + mode "auto"
```

**Cách dùng trong ChatViewModel:**

```kotlin
encoder = OpusEncoder(16000, 1, 60)
opusFlow = audioFlow?.map { encoder?.encode(it) }
opusFlow?.collect { it?.let { protocol.sendAudio(it) } }
```

---

## 3. Nhận từ server và phát âm thanh

**Nhận binary:** `WebsocketProtocol` – `onMessage(webSocket, bytes: ByteString)` → chỉ emit vào **`incomingAudioFlow`** (SharedFlow, giống Xiaozhi; không dùng Channel cho audio).

**Decode:** `OpusDecoder.kt`

- Load libapp.so, `nativeInitDecoder(sampleRate, channels)`.
- Mỗi gói Opus từ server → `nativeDecodeBytes(...)` → **PCM ByteArray**.
- Trong Xiaozhi_Android, TTS server trả 24 kHz → decoder/player dùng 24000.

**Phát:** `OpusStreamPlayer.kt`

- **AudioTrack** (MODE_STREAM): 16/24 kHz, mono, 16-bit.
- `start(Flow<ByteArray?>)`: collect PCM từ flow → `audioTrack.write(it, 0, it.size)`.
- Có thể `waitForPlaybackCompletion()` để đợi phát xong (theo trạng thái TTS JSON).

**Cách dùng trong ChatViewModel:**

```kotlin
player = OpusStreamPlayer(24000, 1, 60)   // TTS từ server thường 24k
decoder = OpusDecoder(24000, 1, 60)
player?.start(protocol.incomingAudioFlow.map { decoder?.decode(it) })
```

**JSON từ server:** `incomingJsonFlow` nhận các type: `tts` (state start/stop, sentence_start), `stt` (text user), `llm` (emotion), `iot` (commands). Dùng để cập nhật UI và điều khiển trạng thái (LISTENING / SPEAKING).

---

## 4. So sánh với demo ubt_speech_demo (XiaozhiSessionManager)

| Bước              | Xiaozhi_Android        | Demo hiện tại                          |
|-------------------|-------------------------|----------------------------------------|
| Thu âm            | AudioRecorder (Flow)    | DemoRecognizer: AudioRecord + WeiNa   |
| Encode            | OpusEncoder (libapp.so) | OpusEncoder (cùng lib)                 |
| Gửi               | sendAudio(opus)         | sendAudio(opus)                       |
| **sendStartListening** | Có (sau openAudioChannel) | Đã thêm (sau open trong XiaozhiSessionManager) |
| Nhận binary       | incomingAudioFlow       | incomingAudioFlow (đã bỏ Channel)    |
| Decode / phát     | OpusDecoder → OpusStreamPlayer | Giống (init có decode + player) |
| Sample rate TTS   | 24k                     | 16k (có thể đổi 24k nếu server trả 24k) |

**Đã sửa trong demo:** Sau khi `openAudioChannel()` thành công, đã gọi `protocol.sendStartListening(ListeningMode.AUTO_STOP)` trong XiaozhiSessionManager.

---

## 5. Thứ tự khuyến nghị (giống Xiaozhi_Android)

1. `protocol.start()` (nếu cần).
2. `protocol.openAudioChannel()` → đợi server Hello.
3. **`protocol.sendStartListening(ListeningMode.AUTO_STOP)`**
4. Khởi động pipeline nhận: **`incomingAudioFlow`** → map decode → player.start(flow) (đúng luồng phát audio Xiaozhi).
5. Khởi động pipeline gửi: recorder (hoặc AudioRecord) → encode → `sendAudio()`.

Như vậy server vừa nhận audio vừa xử lý STT và trả TTS; client vừa gửi mic vừa phát âm thanh trả lời.

---

## 6. Bật WebSocket + Xiaozhi trong demo (cần libapp.so)

- **OpusEncoder / OpusDecoder** đều gọi `System.loadLibrary("app")` → cần **libapp.so** (Opus JNI) trong APK.
- Nếu **không có** libapp.so: `createXiaozhiSessionManager()` bắt `UnsatisfiedLinkError` và trả về **null** → không mở WebSocket, không gửi PCM lên server, không có TTS.
- Log khi đó: lúc init có `XiaozhiSessionManager: NULL (libapp.so missing)`; lúc thu âm có (một lần) `PCM captured but XiaozhiSessionManager is null (no libapp.so) - not sending to server...`.

**Cách có libapp.so (đã thêm sẵn trong project):**

- Module native trong `speechFrameworkDemo/src/main/cpp/`: CMake tải Opus (FetchContent từ xiph/opus v1.4), build JNI encoder/decoder thành **libapp.so**. Build app **bình thường** (không dùng `-PskipNdk`) bằng Android Studio hoặc `./gradlew assembleDebug` → có libapp.so trong APK. Cần **NDK + CMake + mạng** lần build đầu. Nếu không build được NDK, copy **libapp.so** từ bản build thành công (hoặc từ Xiaozhi_Android) vào `speechFrameworkDemo/src/main/jniLibs/arm64-v8a/`.

Sau khi có libapp.so trong APK, init sẽ log `XiaozhiSessionManager: OK (WebSocket+Opus)`, và khi đánh thức + nói sẽ thấy log WebSocket (tag WS / XiaozhiSessionManager) và có thể nghe TTS trả lời.
