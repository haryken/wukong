# Ghi chú bản ổn định (test OK ~14:32 2026-05-26)

## Logcat filter hữu ích

- `XiaozhiMQTT` — wake, chào, STT/TTS, PCM block
- `MqttProtocol` / `MQTT_UDP` — connect, RX/TX, goodbye
- `OpusStreamPlayer` — `TTS_PCM in`
- `SpeechDebug` — mic uplink

## Luồng wake (hey mini / chạm đầu)

1. Ting (DemoSpeech) + `forceStopPlaybackForHeyMini`
2. MQTT connect → MCP init → client hello → server hello → UDP
3. `wake detect` + `listen` (downlink TTS)
4. Uplink PCM: **chặn** (`chờ TTS chào + ting`)
5. TTS stop → playback xong → ting → listen → mở PCM

## Đã xử lý

| Vấn đề | Cách xử lý |
|--------|------------|
| Tự nghe / echo STT | Chặn uplink khi TTS + cooldown sau ting |
| Mất audio chào đầu | `listen` + `prepareForIncomingTts`, không cắt timeout 6s giữa câu |
| Rè / gap UDP 1 frame | RX queue tuần tự |
| Cắt giữa câu + phải wake lại | Keepalive Opus im lặng, defer goodbye, auto reopen |
