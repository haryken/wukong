# Snapshot: MQTT + UDP ổn định (2026-05-27)

Trạng thái user xác nhận **đang ổn**: hey mini / TTS / ting nhanh sau câu / mic sau ting / UDP ít rè.

## Khôi phục nhanh (PowerShell)

Từ thư mục `speechFrameworkDemo`:

```powershell
$snap = "snapshots\mqtt-udp-stable-2026-05-27"
Get-ChildItem -Recurse $snap -Include "*.kt","*.java","*.gradle" | ForEach-Object {
  $rel = $_.FullName.Substring((Resolve-Path $snap).Path.Length + 1)
  $dest = Join-Path (Get-Location) $rel
  New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
  Copy-Item -Force $_.FullName $dest
}
```

Sau đó **Rebuild** APK trong Android Studio.

## File trong snapshot

| File | Vai trò |
|------|---------|
| `MqttProtocol.kt` | MQTT+UDP, gap→frame_loss (ESP32), RX tuần tự, defer goodbye |
| `Protocol.kt` | `incomingAudioFlow` buffer |
| `OpusStreamPlayer.kt` | Jitter TTS, drain ~280ms head-stable |
| `XiaozhiMqttSessionManager.kt` | Wake, ting nhanh, PCM guard, keepalive, mid-TTS reopen |
| `XiaozhiSessionManager.kt` | Chọn MQTT / WebSocket |
| `DemoSpeech.kt` / `DemoRecognizer.java` | Wire mic, wake, transport |
| `XiaozhiOta*.kt` / activation | OTA → MQTT config |
| `build.gradle` | Paho MQTT deps |

## Điểm chính (đừng revert nhầm)

- **Wake**: `listen` ngay sau wake; uplink PCM chặn đến sau ting.
- **UDP gap**: tối đa 8 frame `frame_loss` (Opus rỗng) → PCM im lặng 60ms @ 24kHz (không duplicate Opus).
- **Ting sau TTS**: `waitForPlaybackCompletion` drain ngắn (~280ms) + 80ms → **ting trước** restart mic; `TING_ECHO_GUARD_MS=280` cho uplink.
- **Mic sau ting**: `pcmAllowedRightAfterTingUntilMs` bỏ listen cooldown 1.5s; uplink ~280ms sau ting.
- **TTS dài / goodbye giữa câu**: defer goodbye + reopen sau buffer.

## Snapshot cũ

| Thư mục | Ghi chú |
|---------|---------|
| `mqtt-udp-stable-2026-05-26` | Trước fix ting nhanh + frame_loss ESP32 |
| `mqtt-udp-working-2026-05-26` | Bản đầu MQTT+UDP |

## Git (nếu có repo)

```bash
git add speechFrameworkDemo/snapshots/mqtt-udp-stable-2026-05-27
git commit -m "Snapshot MQTT+UDP stable 2026-05-27"
```
