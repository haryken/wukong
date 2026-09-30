# Engine đánh thức: Sherpa-ONNX (Anh / Việt) / Picovoice Porcupine

Robot có 3 engine wake word, chọn trên web Self-Control `http://<ip-robot>:8080` → **Cài đặt bot → 🎙️ Đánh thức**.
Đổi ngay khi bấm Lưu, không cần khởi động lại. Chạm đầu luôn đánh thức được, không phụ thuộc engine.

| Engine | Ưu | Nhược |
|---|---|---|
| **Sherpa tiếng Anh** (mặc định) | Offline, miễn phí, nhẹ (14 MB), đổi từ đánh thức tự do | Chỉ tiếng Anh; độ chính xác thấp hơn Porcupine một chút |
| **Sherpa tiếng Việt** | Offline, miễn phí, từ đánh thức tiếng Việt có dấu ("mini ơi") | Model 51 MB, tốn CPU ~3 lần bản Anh; giấy phép phi thương mại |
| **Porcupine 4.x** | Nhận "Hey Mini" rất tốt | Cần AccessKey + Internet lúc khởi động; gói free giới hạn số thiết bị |

## Sherpa-ONNX

- Mỗi ngôn ngữ có danh sách từ đánh thức và độ nhạy riêng (tối đa 8 cụm, 4 từ/cụm).
  App tự tách token (`SherpaKwsTokenizer`, Viterbi trên `bpe.model` sentencepiece unigram) — không cần sửa `keywords.txt`.
- Độ nhạy 0–100% ↔ threshold 0.20–0.04 (50% = 0.12), ghi theo từng cụm (`tokens #0.120 @LABEL`) trong stream
  → đổi cụm / độ nhạy chỉ tạo stream mới, không nạp lại model.
- `keywords.txt` chỉ chứa 1 cụm giữ chỗ vô nghĩa (`#0.99 @RESERVED`): cụm trùng với dòng trong file sẽ bị threshold của file đè.
- Chỉ model của engine đang chạy được nạp; đổi engine thì giải phóng model cũ.

### Tiếng Anh — `assets/sherpa-kws/`

- `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (k2-fsa, Apache-2.0). Cụm chỉ gồm A-Z và `'`.

### Tiếng Việt — `assets/sherpa-kws-vi/`

- Model ASR streaming [hynt/Zipformer-30M-RNNT-Streaming-6000h](https://huggingface.co/hynt/Zipformer-30M-RNNT-Streaming-6000h),
  file `*-chunk-16-left-128.fp16.onnx` giữ nguyên; `tokens.txt` = `config.json` của repo gốc.
  KeywordSpotter của sherpa chạy được với model ASR streaming zipformer2 bất kỳ (cùng định dạng với model KWS).
- **Giấy phép CC BY-NC-ND 4.0**: chỉ dùng phi thương mại, không phân phối bản đã chỉnh sửa (không tự convert int8 rồi đóng APK).
- Cụm gõ tiếng Việt có dấu (NFC, viết hoa khi lưu), vd. `mini ơi` → `▁MI NI ▁ƠI`, `chào mini` → `▁CHÀO ▁MI NI`.
- Thử trên PC (Google TTS): bắt đúng "mini ơi" / "này mini" / "chào mini", không kích hoạt nhầm với "minh ơi…" và câu thường;
  RTF ~0.08–0.10 (1 luồng, PC) so với ~0.03 của bản Anh.
- Trên Alpha Mini nạp model mất ~60 giây. Trong lúc nạp, Sherpa tiếng Anh vẫn nghe (cả lúc khởi động robot),
  nạp xong tự chuyển sang tiếng Việt. Nạp lỗi → giữ Sherpa tiếng Anh và hiện lý do trên web.
- Chạy 1 luồng / ưu tiên background thì không theo kịp mic khi CPU bận (hàng đợi dồn ~27 s → wake trễ),
  nên model tiếng Việt chạy 2 luồng, ưu tiên thường. Mọi engine Sherpa bỏ PCM cũ khi tồn quá ~1 s
  (`staleSkip` trong log `PCM alive`).

## Porcupine

- Lấy AccessKey miễn phí ở https://console.picovoice.ai/ rồi dán vào web (lưu trong SharedPreferences của robot, web chỉ hiện bản che).
- Từ đánh thức: `assets/hey-mini_en_android_v4_0_0.ppn` (train trên Picovoice Console, cần thư viện `ai.picovoice:porcupine-android:4.x`).
- Dùng API low-level `Porcupine.process()` với PCM từ mic dùng chung (không dùng `PorcupineManager` vì nó tự mở mic, tranh với Xiaozhi).
- Lỗi key / hết quota → tự quay về Sherpa tiếng Anh và hiện lý do trên web. Lỗi mạng / khởi động chậm → dùng tạm Sherpa, tự thử lại Porcupine mỗi 30 giây (tối đa 10 lần).

## Code

- `SwitchableWakeUpDetector` — detector đăng ký với `CompositeSpeechService`, chuyển PCM/suppress cho engine đang chạy.
- `SherpaOnnxWakeUpDetector` (1 instance / `SherpaKwsModel`: `ENGLISH`, `VIETNAMESE`), `PorcupineWakeEngine` — interface `WakeEngine`.
- `WakeEngineSettings` — lưu cấu hình, `GET/POST /api/wake_engine`
  (`engine` = `sherpa` | `sherpa_vi` | `porcupine`; `sherpa_keywords`, `sherpa_vi_keywords`, `*_sensitivity`).
