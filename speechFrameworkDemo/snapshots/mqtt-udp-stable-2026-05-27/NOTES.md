# MQTT+UDP stable — 2026-05-27

## Luồng wake → chào → nói tiếp

1. **hey mini** / chạm đầu → `openFreshMqttSession` → MCP → hello → UDP → `listen`
2. Uplink PCM **chặn** (`chờ TTS chào + ting`); downlink Opus TTS vẫn phát
3. `tts stop` → chờ buffer drain → **ting** → `listen` → uplink sau ~280ms (`TING_ECHO_GUARD_MS`)

## Timing sau TTS (MQTT)

| Hằng số | Giá trị |
|---------|---------|
| `MIC_DELAY_MS_AFTER_TTS` | 60ms |
| `waitForPlaybackCompletion` head-stable | ~280ms |
| `DELAY_AFTER_PLAYBACK_BEFORE_MIC_MS` | 80ms |
| `PRE_TING_MIC_WARMUP_MS` | 80ms (chào/skill) |
| `TING_ECHO_GUARD_MS` | 280ms |
| `LISTEN_COOLDOWN_MS` | 400ms (bỏ qua sau ting 60s) |

Log kiểm tra: `[TTS] playback drain xong, ting sau XXXms từ tts stop` → `[TTS] ting ngay (...)`

## UDP RX gap (khớp ESP32)

- `MqttProtocol`: `UDP_GAP_FILL_MAX = 8`, payload rỗng = `frame_loss`
- `XiaozhiMqttSessionManager`: `TTS_FRAME_LOSS_SILENCE_PCM` = 2880 bytes @ 24kHz mono 60ms

## Logcat filter gợi ý

- `XiaozhiMqtt` — session, ting, PCM block
- `MqttProtocol` — MQTT/UDP, gap, goodbye
- `OpusStreamPlayer` — TTS drain

## Khác biệt so với snapshot 2026-05-26

- Ting sớm hơn (ting trước mic restart; drain 280ms thay vì ~900ms+400ms)
- UDP frame_loss + silence PCM (giảm rè khi miss 1 frame)
- Mic uplink sau ting ~280ms (không còn settle 2.8s chào)
