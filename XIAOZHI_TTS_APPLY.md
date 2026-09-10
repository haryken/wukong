# Xiaozhi_Android-main: thư viện và cách phát TTS "lớn và rõ" – áp dụng sang ubt_speech_demo

## 1. Thư viện Xiaozhi dùng cho TTS (phát audio từ server)

| Thành phần | Thư viện / API | Ghi chú |
|------------|----------------|---------|
| **Decode Opus** | **libopus (C)** qua JNI → `libapp.so` | `opus_decoder.cpp`: `opus_decoder_create`, `opus_decode()`, không có gain/volume trong code. |
| **Phát ra loa** | **Android AudioTrack** | Không dùng ExoPlayer/MediaPlayer. Chỉ `AudioTrack` + `write(pcm, 0, size)`. |
| **Luồng** | Kotlin Flow | `incomingAudioFlow.map { decoder.decode(it) }` → `player.start(pcmFlow)` → `collect { audioTrack.write(it) }`. |

Trong Xiaozhi **không có**:
- Không chỉnh volume/gain trong decoder hay player.
- Không dùng StreamType/ContentType khác (chỉ USAGE_MEDIA, CONTENT_TYPE_MUSIC).
- Âm "lớn và rõ" chủ yếu do **decode bằng native Opus** (chất lượng đầy đủ) và **AudioTrack** phát đúng format (24 kHz, mono, 16-bit).

---

## 2. Code cụ thể trong Xiaozhi_Android-main

**OpusDecoder.kt**
- `System.loadLibrary("app")` → libapp.so (build từ opus_decoder.cpp).
- `nativeInitDecoder(sampleRate, channels)` → opus_decoder_create.
- `decode(opusData)` → nativeDecodeBytes → opus_decode(), trả về PCM ByteArray.
- Sample rate TTS: **24000**, channels: **1**.

**opus_decoder.cpp**
- `#include <opus/opus.h>`, opus_decoder_create, opus_decode, opus_decoder_destroy.
- Trả về số mẫu * 2 (byte). Không gain.

**OpusStreamPlayer.kt**
- `bufferSize = AudioTrack.getMinBufferSize(...) * 2`.
- `AudioAttributes`: USAGE_MEDIA, CONTENT_TYPE_MUSIC.
- `AudioFormat`: sampleRate (24000), CHANNEL_OUT_MONO, ENCODING_PCM_16BIT.
- `MODE_STREAM`, `play()` rồi `pcmFlow.collect { audioTrack.write(it, 0, it.size) }`.
- Không setVolume (mặc định 1.0).

**ChatViewModel.kt**
- `player?.start(protocol.incomingAudioFlow.map { decoder?.decode(it) })`.

---

## 3. Đã áp dụng sang ubt_speech_demo

| Mục | Xiaozhi | ubt_speech_demo |
|-----|---------|------------------|
| Decode Opus | libapp.so (opus_decoder.cpp) | Cùng: opus_decoder_jni.cpp, OpusDecoder.kt, libapp.so (build NDK). |
| Fallback khi không có NDK | Không có | Concentus (Java) + applyPcmGain (ví dụ 3f) để bù âm nhỏ. |
| OpusStreamPlayer | buffer*2, USAGE_MEDIA, CONTENT_TYPE_MUSIC, write() | Đã copy giống (cùng tham số). |
| TTS sample rate | 24000 | XiaozhiSessionManager.TTS_SAMPLE_RATE = 24000. |
| Luồng TTS | binary → Flow → map(decode) → player.start → write | binary → Channel → flow { for decode } → player.start → write (tương đương, phù hợp Coroutines 1.3). |

Để âm giống Xiaozhi ("lớn và rõ"):
- **Build bắt buộc có libapp.so**: chạy build **không** dùng `-PskipNdk` (ví dụ `./gradlew assembleDebug`).
- Khi có libapp.so: dùng **NativeOpusDecoderAdapter** (pcmGain = 1f), không gain thêm.
- Khi không có libapp.so: dùng Concentus + **PCM_GAIN_CONCENTUS** (ví dụ 3f) trong XiaozhiSessionManager.

---

## 5. Build ra libapp.so bằng Android Studio

1. **Cài NDK và CMake (nếu chưa có)**  
   - Vào **File → Settings** (Windows/Linux) hoặc **Android Studio → Preferences** (macOS).  
   - **Languages & Frameworks → Android SDK** → tab **SDK Tools**.  
   - Bật **NDK (Side by side)** và **CMake**, chọn version CMake ≥ 3.18.1.  
   - Trong project đang dùng `ndkVersion "21.4.7075529"` → có thể cài NDK 21.x hoặc để Android Studio cài đúng version.  
   - Apply → OK.

2. **Không dùng -PskipNdk**  
   - Build **bình thường** (không truyền `-PskipNdk`). Khi đó `skipNdkBuild = false` → CMake chạy và tạo `libapp.so`.  
   - Nếu bạn từng build với **Run → Edit Configurations** và thêm VM options / script với `-PskipNdk=true` thì **xóa** hoặc **tắt** để không dùng.

3. **Build trong Android Studio**  
   - **Build → Make Project** (Ctrl+F9), hoặc  
   - **Build → Build Bundle(s) / APK(s) → Build APK(s)**.  
   - Lần đầu CMake sẽ tải Opus (FetchContent từ GitHub), có thể mất vài phút.

4. **Vị trí libapp.so sau khi build**  
   - Thư mục dạng:  
     `speechFrameworkDemo/build/intermediates/cmake/debug/obj/arm64-v8a/libapp.so`  
   - APK sẽ tự đóng gói `libapp.so` vào `lib/arm64-v8a/` khi build APK.

5. **Lỗi thường gặp**  
   - **"NDK not installed"**: cài NDK trong SDK Tools (bước 1).  
   - **"NDK is missing a platforms directory"**: Project đã nâng **AGP 4.2.2** + **Gradle 6.7.1** để tương thích NDK mới (r23+ không còn thư mục `platforms`). Cách sửa nếu vẫn báo:
     - **Không set** `ndk.dir` trong local.properties (để Gradle dùng `ndkVersion` trong build.gradle và tìm trong `sdk/ndk/<version>`).
     - Cài **NDK (Side by side)** trong SDK Tools → Apply. Nếu bạn chỉ cài bản mặc định (ví dụ 29.x), mở **speechFrameworkDemo/build.gradle** và đổi `ndkVersion "21.4.7075529"` thành đúng tên thư mục trong `.../AppData/Local/Android/Sdk/ndk/` (ví dụ `"29.0.142068"`).
     - Xóa biến môi trường **ANDROID_NDK_HOME** nếu nó trỏ tới `ndk-bundle`.
     - Sync + Build lại.
   - **"CMake not found" / version thấp**: cài CMake và chọn version ≥ 3.18.1.  
   - **FetchContent opus lỗi**: kiểm tra mạng (clone https://github.com/xiph/opus), thử Build lại.  
   - **Build thành công nhưng app vẫn báo thiếu lib**: kiểm tra không chạy với `-PskipNdk=true` và build lại **Build APK** (không chỉ Make Project).

6. **Kiểm tra đã tạo libapp.so chưa**  
   - Mở **Project** (chế độ Project), vào:  
     `speechFrameworkDemo/build/intermediates/cmake/debug/obj/arm64-v8a/`  
   - Nếu có file **libapp.so** → build native thành công.  
   - Nếu **không có** thư mục `cmake` trong `build/intermediates/` → NDK/CMake chưa chạy (sửa NDK như mục 5).

7. **Build xong vẫn không thấy libapp.so (task CMake không chạy)**  
   Trong log build không có dòng `externalNativeBuildDebug` hay `cmakeBuildDebug` → Gradle **không chạy** CMake, nên không tạo libapp.so. Thường do **ndkVersion trong build.gradle không trùng với NDK đã cài**.

   **Cách sửa:**
   1. Mở thư mục **`C:\Users\LEGION\AppData\Local\Android\Sdk\ndk\`** (đổi USER nếu cần).  
   2. Xem bên trong có thư mục nào (vd `21.4.7075529`, `25.1.8937393`, `29.0.142068` …).  
   3. Mở **speechFrameworkDemo/build.gradle**, tìm `ndkVersion "21.4.7075529"` và **đổi** thành đúng tên thư mục bạn thấy (vd `ndkVersion "29.0.142068"`).  
   4. (Tùy chọn) Trong **local.properties** thêm (đổi đường dẫn cho đúng):  
      `ndk.dir=C\:\\Users\\LEGION\\AppData\\Local\\Android\\Sdk\\ndk\\29.0.142068`  
   5. **Build → Clean Project**, sau đó **Build → Rebuild Project** (hoặc **Build APK(s)**).  
   6. Nếu vẫn không có libapp.so: mở **terminal** trong project, chạy:  
      `gradlew clean :speechFrameworkDemo:externalNativeBuildDebug`  
      Xem log báo lỗi gì (vd NDK not found, CMake error) và xử lý theo đúng lỗi đó.

---

## 4. Tùy chọn: set volume AudioTrack = 1.0

Xiaozhi không gọi setVolume; mặc định AudioTrack là 1.0. Trên một số thiết bị có thể bị giảm; có thể gọi `setStereoVolume(1.0f, 1.0f)` hoặc `setVolume(1.0f)` (API 26+) sau `play()` để đảm bảo phát full volume. Đã thêm trong OpusStreamPlayer (nếu API đủ).
