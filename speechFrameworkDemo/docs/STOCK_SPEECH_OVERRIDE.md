# Đè speech stock (third-party) — **KHÔNG ĐỤNG**

**Trạng thái:** đã xác nhận đè được trên Alpha Mini (2026-09-12).  
**Tham chiếu hoạt động:** commit `2b23f8d` / tree `ubt_speech_demo-develop2222` (cùng pattern).

Khi sửa Xiaozhi / SoftAP / mắt / ANR / mic: **không thay** các mục dưới đây. Lần trước stub/strip `MicrophoneArrayService` → Master giữ `com.ubtechinc.alphamini.speech` → mất đè.

---

## Cơ chế (không cần root)

1. Manifest: `ubt-master-app` = `third_part_speechservice`
2. Sau **cài / đổi APK**: **tắt nguồn robot rồi bật lại** (bắt buộc để Master cắt mic stock)
3. `MicrophoneArrayService` **thật từ AAR** `speechFramework-oversea-release`, process `:speech`
4. `ServiceModules.declare(SpeechService / SpeechSettings)` → `DemoSpeech` (CompositeSpeechService thật)

Stock package có thể vẫn còn process; quan trọng là **mic-array + SpeechService Master** thuộc app này.

---

## File / chỗ được phép giữ nguyên

| Chỗ | Phải như thế nào |
|-----|------------------|
| `AndroidManifest.xml` | `<meta-data android:name="ubt-master-app" android:value="third_part_speechservice"/>` |
| `AndroidManifest.xml` | `<service android:name="…MicrophoneArrayService" android:process=":speech" />` — **không** stub, **không** `tools:node="replace"` tùy tiện |
| `build.gradle` | `implementation(name: 'speechFramework-oversea-release', ext: 'aar')` — **AAR nguyên**, có class MicArray |
| `build.gradle` | `implementation(name: 'sal-speech-1.0.0', ext: 'aar')` (kéo `SpeechSystemService`) |
| `SpeechApplication.java` | `SpeechBootstrap.startOnce` rồi `ServiceModules.declare(… DemoSpeech …)` chờ `createSpeechService() != null` |
| `SpeechBootstrap.java` | `startService(DemoMasterService)` + `startService(MicrophoneArrayService)` |
| `DemoSpeech.createSpeechService()` | Trả `speechServiceStub` sau khi build `CompositeSpeechService` |

---

## CẤM (đã làm hỏng đè một lần)

- **Strip / patch** `MicrophoneArrayService*.class` khỏi `speechFramework-oversea-release.aar`
- Thêm class app `MicrophoneArrayService` stub (no-op) đè AAR
- Đăng ký `StockSpeechOverride` / CompositeSpeechService **no-op** thay `DemoSpeech` lên `ServiceModules`
- Bỏ `android:process=":speech"` khỏi MicArray “cho tiện / tránh ANR”
- Bỏ `startService(MicrophoneArrayService)` trong bootstrap
- Đổi / xóa `ubt-master-app=third_part_speechservice`
- Giả định “chỉ cần declare SpeechService” mà không có MicArray AAR thật

Trade-off đã biết: MicArray AAR (WeiNa/MNPC) có thể ANR/`-10205` lúc boot. **Không** giải bằng stub/strip AAR — phải xử lý timing/ANR **không** phá identity Master mic-array.

---

## Kiểm tra sau cài + power cycle

Log / process kỳ vọng:

- `Logic: Speech Service create ok..`
- Process `com.ubtrobot.mini.speech.framework.demo:speech` (MicArray)
- Master **không** còn route mic chính qua stock kiểu `/isMicArrayStart` → `tvs_extra_api_service` trên `alphamini.speech` như khi mất đè

Nếu stock lại “lên tiếng” / tranh mic: xem lại diff các file bảng trên trước mọi thay đổi khác.
