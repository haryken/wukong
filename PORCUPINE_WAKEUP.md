# Engine đánh thức: Sherpa-ONNX / Picovoice Porcupine

Robot có 2 engine wake word, chọn trên web Self-Control `http://<ip-robot>:8080` → **Cài đặt bot → 🎙️ Đánh thức**.
Đổi ngay khi bấm Lưu, không cần khởi động lại. Chạm đầu luôn đánh thức được, không phụ thuộc engine.

| Engine | Ưu | Nhược |
|---|---|---|
| **Sherpa-ONNX** (mặc định) | Offline, miễn phí, đổi từ đánh thức tự do (tiếng Anh) | Độ chính xác thấp hơn Porcupine một chút |
| **Porcupine 4.x** | Nhận "Hey Mini" rất tốt | Cần AccessKey + Internet lúc khởi động; gói free giới hạn số thiết bị |

## Sherpa-ONNX

- Model: `speechFrameworkDemo/src/main/assets/sherpa-kws/` (gigaspeech KWS, `bpe.model` là sentencepiece unigram).
- Từ đánh thức nhập trên web, mỗi dòng một cụm (tối đa 8 cụm, 4 từ/cụm, chỉ A-Z và `'`).
  App tự tách token (`SherpaKwsTokenizer`) — không cần sửa `keywords.txt`.
- Độ nhạy 0–100% ↔ threshold 0.20–0.04 (50% = 0.12).

## Porcupine

- Lấy AccessKey miễn phí ở https://console.picovoice.ai/ rồi dán vào web (lưu trong SharedPreferences của robot, web chỉ hiện bản che).
- Từ đánh thức: `assets/hey-mini_en_android_v4_0_0.ppn` (train trên Picovoice Console, cần thư viện `ai.picovoice:porcupine-android:4.x`).
- Dùng API low-level `Porcupine.process()` với PCM từ mic dùng chung (không dùng `PorcupineManager` vì nó tự mở mic, tranh với Xiaozhi).
- Lỗi key / hết quota → tự quay về Sherpa và hiện lý do trên web. Lỗi mạng / khởi động chậm → dùng tạm Sherpa, tự thử lại Porcupine mỗi 30 giây (tối đa 10 lần).

## Code

- `SwitchableWakeUpDetector` — detector đăng ký với `CompositeSpeechService`, chuyển PCM/suppress cho engine đang chạy.
- `SherpaOnnxWakeUpDetector`, `PorcupineWakeEngine` — 2 engine (interface `WakeEngine`).
- `WakeEngineSettings` — lưu cấu hình, `GET/POST /api/wake_engine`.
