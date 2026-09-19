# Luồng Wi‑Fi · QR Self-Control · Web `:8080` · MAC / Client-Id

Tài liệu **một chỗ** để làm mượt giao tiếp Xiaozhi: không đụng timing WebSocket, chỉ biết rõ từng luồng phụ (provision / QR / config / identity).

**Nguồn sự thật kèm theo**

| Doc | Nội dung |
|-----|----------|
| [`WS_SESSION_STABLE.md`](WS_SESSION_STABLE.md) | WebSocket = commit `2b23f8d` (cấm sửa timing nói) |
| [`DEVICE_IDENTITY.md`](DEVICE_IDENTITY.md) | Quy tắc Device-Id / Client-Id |
| [`WIFI_PROVISION.md`](WIFI_PROVISION.md) | SoftAP / portal Wi‑Fi chi tiết |

**Commit ổn định luồng nói:** `2b23f8d5bc7870f85a0a91abacd12a32be5de99e`

---

## Khóa phạm vi (yêu cầu sản phẩm)

| Phần | Khi “quay lại WS ổn định / gắn identity” |
|------|------------------------------------------|
| **Bắt Wi‑Fi SoftAP + portal `:8888`** | **Giữ 100% code hiện tại** — không rewrite, không “đơn giản hóa” |
| **Gõ đầu (double-tap) → QR Wi‑Fi** khi offline | **Giữ 100%** (`WifiProvisionController` + sticky / mắt) |
| **Cơ chế QR Wi‑Fi** vs **QR Self-Control `:8080`** | **Giữ 100%** — hai luồng QR tách biệt như đang chạy |
| **Web Self-Control `:8080` + HTML + API** | **Giữ 100% y chang hiện tại** (`self_control.html`, Store, HTTP server) |
| **WebSocket nói chuyện** | Pin timing về `2b23f8d` |
| **Đổi MAC + random Client-Id khi lưu cấu hình** | Móc mỏng qua `switchDeviceIdentity` (không đụng web/Wi‑Fi) |

→ Chỉ được đụng **session Xiaozhi / identity apply**. Wi‑Fi + web + hai loại QR = **đóng băng hành vi hiện tại**.

---

## 0. Nguyên tắc chung (để giao tiếp mượt)

1. **Luồng nói (WS)** giữ đúng `2b23f8d`: wake → listen → TTS → ting → PCM. Không abort/đóng WS mỗi hey mini.
2. **Self-Control / Wi‑Fi / QR** là lớp phụ: Wi‑Fi + web **không refactor**; chỉ **móc** identity vào WS qua API mỏng (`switchDeviceIdentity`, `recoverTalkAfterShowConfig`, `isAudioChannelOpened`).
3. **Đổi cấu hình học** (khóa / giọng / MAC) → **đổi Device-Id (MAC) + random Client-Id** rồi Apply. Chỉ đổi tên/unit → **không** đổi MAC, **không** cắt session.
4. Hiện QR Self-Control / SoftAP có thể làm **SSL abort WS** → sau show QR Self-Control phải `recoverTalkAfterShowConfig` (đã có; giữ).

---

## 1. Luồng bắt Wi‑Fi (SoftAP provision) — **đóng băng 100% hiện tại**

### Mục tiêu

Robot chưa có mạng nhà → user nối phone vào hotspot Mini → chọn SSID/pass → robot STA vào Wi‑Fi nhà → sau đó mới Xiaozhi / Self-Control `:8080`.

### Trigger (giữ nguyên hành vi đang ship)

- Boot: không có STA → `SpeechBootstrap` → `WifiProvisionController.start`
- Head **double-tap** khi **offline** → `DemoSpeech` / controller → hiện QR portal Wi‑Fi (không nhầm với QR `:8080`)
- SoftAP bị chặn → Settings + mắt `SET WIFI` (như hiện tại)

### Các bước (code hiện tại — không đổi thứ tự)

```text
1. WifiScanHelper: quét + cache SSID TRƯỚC khi bật SoftAP
   (SoftAP bật giữa ScanState → scan count=0 — đã verify)
2. WifiSoftApHelper / WifiLocalOnlyHotspot: SoftAP tên Mini-XXXX
3. WifiProvisionHttpServer: portal http://192.168.43.1:8888
4. assets/wifi_provision.html: list SSID từ cache / nhập tay → Lưu
5. Tắt SoftAP → WifiStationHelper nối STA
6. SoftAP bị ROM chặn → Settings + mắt "SET WIFI"
```

### File chính (không rewrite khi làm plan WS/identity)

| File | Vai trò |
|------|---------|
| `wificonfig/WifiProvisionController.kt` | Orchestrator: `enterProvisionNow` / `enterProvisionLocked` / `showProvisionQr` + **gõ đầu** |
| `WifiScanHelper` | Scan + cache SSID |
| `WifiSoftApHelper` / `WifiLocalOnlyHotspot` | Bật/tắt SoftAP |
| `WifiProvisionHttpServer` + `assets/wifi_provision.html` | Portal `:8888` |
| `WifiStationHelper` | Nối STA |
| `ActivationEyeDisplay.showWifiProvisionUrlAndQr` | QR portal Wi‑Fi trên mắt (~60s) |

### Hai cơ chế QR (giữ tách biệt 100%)

| | **QR bắt Wi‑Fi** (chưa STA) | **QR Self-Control** (đã có Wi‑Fi) |
|--|----------------------------|-----------------------------------|
| URL | `http://192.168.43.1:8888` | `http://<STA-IP>:8080` |
| Trigger gõ đầu | Offline → provision QR | Online + sticky show_config → dismiss / không mở SoftAP |
| Mục đích | Lấy SSID/pass | Mở web cấu hình học |
| Cache mắt | Provision URL/QR | `warmSelfControlEyeCache` / `sc_eye_*.png` |
| Server | `:8888` SoftAP | `:8080` LAN |

**Cấm** gộp hai QR thành một luồng hoặc đổi double-tap khi online thành SoftAP.

---

## 2. Luồng hiện QR Self-Control (mắt) — giữ cơ chế hiện tại

### Mục tiêu

User nói “mở cấu hình” / MCP `show_config_page` → mắt hiện QR tới `:8080`, **không đọc URL** bằng TTS, **không chết WS** lâu.

### Trigger

- MCP / voice → `DemoSpeech.selfControlShowConfigPage()`
- STT fallback: `tryLocalShowConfigFromStt` (server không gọi MCP)
- Head double-tap khi đang sticky QR Self-Control → dismiss (không vào SoftAP)

### Các bước

```text
1. Đảm bảo SelfControlHttpServer đang listen :8080
2. markQrShowingForHeadTap / sticky (để double-tap đóng QR)
3. ActivationEyeDisplay:
   - Ưu tiên cache PNG (warm lúc boot / đổi IP)
   - showSelfControlIpAndQr / showSelfControlUrlAndQr (~60s, vẽ 1 lần, không redraw loop)
4. Tool result / TTS: chỉ "Đã mở mã QR." — mute nếu server vẫn đọc http/:8080
5. recoverTalkAfterShowConfig(): nếu SSL abort → reopen WS vài lần (~12s)
```

### File chính

| File | Vai trò |
|------|---------|
| `DemoSpeech.selfControlShowConfigPage` | Entry |
| `ActivationEyeDisplay.java` | `warmSelfControlEyeCache`, `showSelfControl*`, flag sticky |
| `SpeechBootstrap` | Warm QR cache boot (URL từ IP STA + `:8080`) |
| `XiaozhiWebSocketSessionManager.recoverTalkAfterShowConfig` | Phục hồi WS sau QR |
| Mute TTS URL | Trong WS manager (chỉ khi câu TTS giống URL) |

### Warm cache (tránh encode chậm lúc show)

```text
Boot / Wi‑Fi IP đổi
  → warmSelfControlEyeCache("http://IP:8080")
  → ghi sc_eye_left.png / sc_eye_right.png + meta URL
Show QR
  → blit từ cache nếu URL khớp; chỉ encode lại khi IP/URL đổi
```

---

## 3. Luồng web Self-Control `:8080` — **y chang 100% hiện tại**

Không đổi UI HTML, không đổi contract API, không đổi rule pool MAC trên Store — trừ khi sau này bạn yêu cầu rõ.

### Mục tiêu

Phone cùng LAN mở trang cấu hình: tên robot, khóa học (MAC pool), giọng, unit, custom MAC.

### Trigger

- `DemoSpeech.init` → `SelfControlHttpServer.start`
- User mở `http://<robot-ip>:8080` (hoặc quét QR mắt Self-Control)

### API (giữ nguyên)

| Method | Path | Việc |
|--------|------|------|
| GET | `/` | Serve `assets/self_control.html` |
| GET | `/api/config` | `SelfControlStore.buildGetConfigJson` |
| POST | `/api/config` | `SelfControlStore.applyPostConfig` → nếu `identity_changed` → callback Apply |

### Callback identity (móc vào WS — phần được phép chỉnh khi pin `2b23f8d`)

```text
SelfControlHttpServer.setOnIdentityChanged {
  DemoSpeech.applyDeviceIdentityFromSelfControl()
}
```

### File — đóng băng hành vi web

| File | Vai trò |
|------|---------|
| `selfcontrol/SelfControlHttpServer.kt` | NanoHTTPD `:8080` |
| `assets/self_control.html` | UI **y chang hiện tại** |
| `selfcontrol/SelfControlStore.kt` | Prefs + resolve MAC + applyPostConfig |
| `selfcontrol/SelfControlPresets.kt` | Pool MAC theo khóa / giọng / daily |

---

## 4. MAC (Device-Id) · Client-Id · khi nào đổi

| Header Xiaozhi | Trong app |
|----------------|-----------|
| **Device-Id** | MAC khóa (`SelfControlStore.resolveDeviceId()`) |
| **Client-Id** | UUID — **random** khi tạo session / Apply đổi identity |

| idx | Khóa | Device-Id |
|-----|------|-----------|
| 0 | Tự cấu hình | custom_mac hoặc MAC chip; clear leftover pool khi chọn idx=0 |
| 1..5 | Explorers…TOEIC | Random pool theo voice → `custom_mac` |
| 6 | Tự nhập MAC | `custom_mac` bắt buộc |
| 7 | Giao tiếp hằng ngày | Random pool 20 MAC |

**Đổi MAC:** boot pool / đổi khóa / đổi giọng / custom MAC mới. **Không** khi chỉ tên/unit.  
**Random Client-Id:** tạo session; Apply khi MAC đổi. **Không** khi save cùng Device-Id + kênh mở.

Chi tiết: [`DEVICE_IDENTITY.md`](DEVICE_IDENTITY.md).

---

## 5. Lưu cấu hình → Apply MAC + Client (móc vào WS)

Web/Store **giữ như hiện tại**; chỉ đảm bảo Apply gọi đúng vào WS `2b23f8d`:

```text
POST /api/config  ← web không đổi
  → applyPostConfig (store hiện tại)
  → identityChanged?
       false → không cắt WS
       true  → applyDeviceIdentityFromSelfControl
                 rotateClientId + MAC mới
                 → switchDeviceIdentity (close → updateIdentity → reopen)
```

**Được phép trong WS:** `isAudioChannelOpened` / `switchDeviceIdentity` / `recoverTalkAfterShowConfig` / `updateIdentity`.  
**Cấm:** đụng SoftAP, `wifi_provision.html`, `self_control.html`, delay ting/listen/PCM.

---

## 6. Sơ đồ tổng

```text
Boot
 ├─ WifiProvision (nếu chưa STA)     [đóng băng 100%]
 ├─ warmSelfControlEyeCache
 ├─ SelfControlHttpServer :8080      [web đóng băng 100%]
 └─ WS 2b23f8d + rotateClientId

Gõ đầu offline  → QR :8888 (provision)
Gõ đầu + sticky QR :8080 → dismiss Self-Control QR
Nói mở cấu hình → QR :8080 + recover WS

Lưu web :8080 → nếu đổi khóa/MAC → Apply identity
```

---

## 7. Checklist

- [ ] SoftAP + gõ đầu QR provision = hành vi hiện tại
- [ ] QR `:8888` vs `:8080` tách đúng offline/online
- [ ] `self_control.html` + `/api/config` = y chang hiện tại
- [ ] WS timing = `2b23f8d`
- [ ] Save tên/unit → không cắt session; đổi khóa → MAC + Client mới
