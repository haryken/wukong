# Picovoice Porcupine Wake-Up (thay thế WeiNa/Iflytek)

## Tổng quan

Wake-up hiện tại (WeiNa/Iflytek) đã được thay bằng **Picovoice Porcupine** (offline) vì không có license. WebSocket Xiaozhi chỉ được mở khi:

1. **Porcupine** phát hiện wake word **"Hey Mini"** (custom .ppn)
2. **Chạm đầu** (head touch) – nếu robot có SysEventApi

## Cấu hình

### 1. Picovoice AccessKey

- Đăng ký miễn phí tại: https://console.picovoice.ai/
- Lấy AccessKey rồi cập nhật trong `speechFrameworkDemo/src/main/res/values/strings.xml`:

```xml
<string name="picovoice_access_key">YOUR_PICOVOICE_ACCESS_KEY</string>
```

### 2. Chạm đầu (Head Touch)

Nếu robot có **SysEventApi** (ví dụ từ behavior-lib hoặc sysevent AAR), chạm đầu sẽ tự động kích hoạt wake-up. Nếu không có, log sẽ in: `SysEventApi not available, head-touch disabled`.

## Custom wake word "Hey Mini"

Đang dùng file **hey-mini_en_android_v4_0_0.ppn** (đã train trên Picovoice Console).

**Bạn cần copy file** `hey-mini_en_android_v4_0_0.ppn` vào:
```
speechFrameworkDemo/src/main/assets/hey-mini_en_android_v4_0_0.ppn
```

Nếu muốn dùng built-in khác (PORCUPINE, ALEXA, v.v.), sửa `PorcupineWakeUpDetector.kt`: dùng `setKeyword(BuiltInKeyword.XXX)` thay cho `setKeywordPath(...)`.

## Lưu ý

- **DingDangManager.load()**: Vẫn cần thành công để init TencentVadRecorder, ResourceLoader (VAD path). Nếu không có license WeiNa, có thể phải chỉnh sửa DingDangManager hoặc cung cấp đường dẫn VAD thủ công.
- **Tương thích CompositeSpeechService**: Nếu `setWakeUpDetector()` báo lỗi kiểu, framework có thể yêu cầu detector kế thừa một interface riêng; khi đó cần dùng adapter phù hợp với AAR của speech framework.
