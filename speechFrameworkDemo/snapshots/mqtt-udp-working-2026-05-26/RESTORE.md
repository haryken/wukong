# Snapshot: MQTT + UDP hoạt động (2026-05-26)

Trạng thái đã xác nhận: **hey mini / nói chuyện qua MQTT + UDP ổn**.

## Cách revert (copy file)

Từ thư mục `speechFrameworkDemo`, chạy PowerShell:

```powershell
$snap = "snapshots\mqtt-udp-working-2026-05-26"
Get-ChildItem -Recurse $snap -Filter "*.kt","*.java","*.gradle" | ForEach-Object {
  $rel = $_.FullName.Substring((Resolve-Path $snap).Path.Length + 1)
  $dest = Join-Path (Get-Location) $rel
  New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
  Copy-Item -Force $_.FullName $dest
}
```

Hoặc copy thủ công từng file trong snapshot → đúng đường dẫn tương ứng dưới `speechFrameworkDemo/`.

## File trong snapshot

| File | Vai trò |
|------|---------|
| `MqttProtocol.kt` | MQTT TLS 8883, subscribe, MCP→hello→UDP, QoS 0 |
| `XiaozhiMcpResponder.kt` | Trả MCP initialize ngay (session rỗng OK) |
| `XiaozhiMqttSessionManager.kt` | Wake: connect → MCP → hello → listen |
| `XiaozhiMqttConfig.kt` | OTA + derive subscribe topic |
| `XiaozhiSessionManager.kt` | Chọn transport MQTT/WS |
| `DemoSpeech.kt` / `MainActivity.java` | Wire transport |
| `build.gradle` | Paho 1.1.1 + mqttv3 1.2.5 |

## Git tag (nếu đã init repo)

```bash
git checkout mqtt-udp-working-2026-05-26
# hoặc chỉ xem diff:
git diff mqtt-udp-working-2026-05-26 -- speechFrameworkDemo/
```

## Điểm chính của bản này (đừng revert nhầm)

- MCP `initialize` trả **ngay** sau SUBACK (giống ESP32), không defer sau server hello.
- `hello` dùng **QoS 0**, không chờ PUBACK.
- `ssl://mqtt.xiaozhi.me:8883`, subscribe `devices/p2p/{mac}`.
- Wake: teardown broker **chỉ khi** chưa connected; `openAudioChannel` drain MCP rồi mới hello.
