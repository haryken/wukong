# WebSocket session = commit `2b23f8d` (y chang)

**Nguồn sự thật:** các file dưới đây checkout từ  
`2b23f8d5bc7870f85a0a91abacd12a32be5de99e`.

**Luồng Wi‑Fi / QR / web `:8080` / MAC:** xem [`FLOWS_WIFI_QR_SELFCONTROL_IDENTITY.md`](FLOWS_WIFI_QR_SELFCONTROL_IDENTITY.md) — SoftAP + gõ đầu QR + web **đóng băng 100% hiện tại**; chỉ móc identity vào WS.

| File | Ghi chú |
|------|---------|
| `XiaozhiWebSocketSessionManager.kt` | Luồng nói: wake / listen / TTS→ting→PCM / gate / đóng idle |
| `WebsocketProtocol.kt` | OkHttp timeout 25/30/15, open/close/hello như 2b23f8d |
| `OpusStreamPlayer.kt` | Playback TTS / AEC session |

## Giống hệt 2b23f8d (cấm sửa nếu không copy lại từ commit)

- Bootstrap openAudioChannel + MCP + listen init  
- Wake / hey mini / chạm đầu: **WS mở** → `interruptPlayback` + `sendWakeWordDetected` + `listen` (ngắt TTS/turn cũ, nói mới, **không** đóng WS). **WS đóng** → `performFullWakeReconnect`  
- `reopenChannelAndListen("hey mini")`: kênh mở → wake detect + listen (không đóng); kênh đóng → open  
- Sau TTS: playback → ting → listen → cooldown PCM  
- Gate PCM: chào / skill / listen cooldown / chưa listen  
- Không STT lâu → listen refresh → reopen  
- Skill: `noteRobotSkillPcmSuppress` (flag trên companion WS)

## Chỉ khác 2b23f8d (Self-Control / phụ)

| Thêm | Mục đích |
|------|----------|
| `isAudioChannelOpened()` | ApplyDeviceIdentity chờ kênh |
| `switchDeviceIdentity` | Đổi MAC: close → `updateIdentity` → reopen+listen |
| `recoverTalkAfterShowConfig` | WS đứt sau hiện QR → tự mở lại |
| Mute TTS URL | Không đọc `http`/`:8080` khi show_config |
| STT fallback show_config / shift_unit | Khi server không gọi MCP |
| `WebsocketProtocol.updateIdentity` + emit CLOSED onFailure | Self-Control + phát hiện kênh chết |

**Identity (MAC + Client-Id random):** xem [`DEVICE_IDENTITY.md`](DEVICE_IDENTITY.md) — **bắt buộc** random Client-Id mỗi boot/session và mỗi lần đổi cấu hình học.

**Không đụng:** `wificonfig/*`, `wifi_provision.html`, `self_control.html`, SoftAP gõ đầu, rule Store pool (giữ hành vi hiện tại).

## CẤM

- Abort + đóng WS trên mọi hey mini (không có trong 2b23f8d)  
- Smile / SafeWakeTing / ChatViewModel “simplify” trong session manager  
- Đổi timeout OkHttp / thứ tự ting–listen / bỏ gate PCM  
- Refactor SoftAP / gộp QR `:8888` với `:8080`  
