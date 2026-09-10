# Snapshot: MQTT + UDP ổn định (2026-05-26)

Trạng thái đã xác nhận: **hey mini / chào / TTS dài / không cần wake lại giữa câu**.

## Khôi phục nhanh (PowerShell)

Từ thư mục `speechFrameworkDemo`:

```powershell
$snap = "snapshots\mqtt-udp-stable-2026-05-26"
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
| `MqttProtocol.kt` | MQTT+UDP, RX tuần tự, defer goodbye, buffer SharedFlow |
| `Protocol.kt` | `incomingAudioFlow` extraBufferCapacity=64 |
| `OpusStreamPlayer.kt` | Jitter buffer TTS, interrupt/prepare |
| `XiaozhiMqttSessionManager.kt` | Wake, chào, echo chặn, keepalive, mid-TTS reopen |
| `XiaozhiSessionManager.kt` | Chọn MQTT / WebSocket |
| `DemoSpeech.kt` / `DemoRecognizer.java` | Wire mic, wake, transport |
| `build.gradle` | Paho MQTT deps |

## Điểm chính (đừng revert nhầm)

- Wake: `wake detect` + **listen ngay** (server cần để gửi Opus TTS); **uplink PCM chặn** đến sau ting.
- Chào: `prepareForIncomingTts`, log `[TTS] <<`, timeout 15s chỉ khi chưa có TTS start.
- Echo: `pcmBlockReason` + `isTtsPlaying` + cooldown sau ting (~2.8s chào).
- UDP RX: **một worker tuần tự** (tránh gap giả / rè do race).
- TTS dài: **Opus im lặng keepalive** khi chặn mic; **defer goodbye** + reopen sau buffer nếu server ngắt giữa câu.

## Snapshot cũ

- `snapshots/mqtt-udp-working-2026-05-26/` — bản đầu (chưa có fix chào/echo/keepalive).

## Git (nếu có repo)

```bash
git add speechFrameworkDemo/snapshots/mqtt-udp-stable-2026-05-26
git commit -m "Snapshot MQTT+UDP stable 2026-05-26"
```
