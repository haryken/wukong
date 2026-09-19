# Device-Id / Client-Id (RẤT QUAN TRỌNG)

Xiaozhi nhận robot qua header WebSocket / OTA:

| Header | Nghĩa trong app |
|--------|------------------|
| **Device-Id** | MAC khóa Self-Control (preset khóa học / custom / daily pool) |
| **Client-Id** | UUID phiên — **phải random**, không tái sử dụng lâu |

## Quy tắc bắt buộc

1. **Mở robot / mỗi lần tạo session Xiaozhi** → **random Client-Id mới** (giữ Device-Id = MAC khóa hiện tại).  
   Mục đích: tránh server gắn cứng / timeout / chặn theo UUID cũ.
2. **Đổi cấu hình học** (Self-Control `:8080`, MCP `set_course`, …) khi MAC/khóa đổi →  
   - **Device-Id** = MAC mới theo khóa  
   - **Client-Id** = **UUID random mới**  
   rồi ApplyDeviceIdentity (đóng WS → mở lại với identity mới).

**Không** giữ Client-Id cố định qua nhiều lần boot hoặc qua lần đổi khóa.

---

## Code hiện tại đang làm gì

### Store

`XiaozhiDeviceIdentityStore.kt`

- `getOrCreate()` — đọc MAC + **tái sử dụng** Client-Id đã lưu nếu còn (chỉ tạo UUID nếu chưa có).
- `rotateClientId()` — **UUID mới**, Device-Id = `SelfControlStore.resolveDeviceId()` (MAC khóa).
- `updateDeviceIdSnapshot(mac)` — cập nhật MAC sau Apply, **giữ** Client-Id vừa rotate.

### Boot / mở session

`DemoSpeech.createXiaozhiSessionManager()`:

```text
rotateClientId(appContext)  →  Device-Id (MAC) + Client-Id random mới
→ XiaozhiSessionManager.create(..., deviceId, clientId, ...)
→ header WS Device-Id / Client-Id
```

→ **Mỗi lần tạo session khi mở robot: Client-Id đã random.** Đúng quy tắc (1).

### Đổi cấu hình học

1. `SelfControlStore.applyPostConfig` / `set_course` → đổi preset / MAC khóa.  
2. Nếu `identityChanged` → `DemoSpeech.applyDeviceIdentityFromSelfControl()`:
   - `rotateClientId()` → **Client-Id random mới**
   - `updateDeviceIdSnapshot(deviceId)` → **Device-Id = MAC mới**
   - WS: `switchDeviceIdentity(deviceId, clientId)` (hoặc dispose + `createXiaozhiSessionManager` — create cũng `rotateClientId` lần nữa)

→ **Đổi khóa: cả MAC và Client-Id đều đổi.** Đúng quy tắc (2).

Skip khi **cùng Device-Id** và kênh đang mở (tránh cắt TTS khi save config không đổi MAC) — lúc đó **không** rotate Client-Id.

### OTA

`runXiaozhiOtaAndShowActivation()` dùng `obtainXiaozhiIds()` = `getOrCreate()` (không rotate tại chỗ).  
Session voice vẫn random Client-Id ở `createXiaozhiSessionManager`. OTA nên chạy sau khi prefs đã có Client-Id mới từ rotate session, hoặc chấp nhận OTA dùng UUID đã lưu gần nhất.

### MQTT

`SelfControlMqttIdentityPatch.patchStoreToDeviceId` — chỉnh clientId/username MQTT theo MAC mới khi Apply trên MQTT.

---

## Device-Id từ Self-Control (`:8080`)

| idx | Khóa | Device-Id |
|-----|------|-----------|
| 0 | Tự cấu hình | custom_mac hoặc MAC chip |
| 1..5 | Explorers…TOEIC | **random** trong pool theo `*_voice` (0/1) → `custom_mac` |
| 6 | Tự nhập MAC | custom_mac bắt buộc |
| 7 | Giao tiếp hằng ngày | **random** 20 MAC mỗi boot / đổi khóa |

Random MAC khi: **boot** (khóa pool), **đổi khóa pool**, **đổi giọng**. Không random khi chỉ sửa tên/unit.

**Client-Id** vẫn random khi tạo session / Apply đổi identity (mục trên).

---

## CẤM / khi sửa code

- Đừng bỏ `rotateClientId()` khỏi `createXiaozhiSessionManager` hoặc ApplyDeviceIdentity.
- Đừng persist một Client-Id “mãi mãi” rồi dùng lại mọi boot.
- Đổi khóa mà chỉ đổi MAC, **không** random Client-Id → dễ timeout / session cũ dính server.

## Log kỳ vọng

- Boot / tạo session: `Rotated Client-Id=… Device-Id=…`
- Đổi cấu hình: `ApplyDeviceIdentity Device-Id=… Client-Id=…`
