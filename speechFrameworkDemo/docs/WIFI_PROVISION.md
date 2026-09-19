# Cấu hình Wi‑Fi qua hotspot (Alpha Mini)

## Luồng

1. Robot không có Wi‑Fi STA → quét danh sách SSID quanh đây (cache).
2. Bật SoftAP `Mini-XXXX` (ưu tiên mở; fallback pass `12345678`).
3. Portal **`http://192.168.43.1:8888`** — chọn Wi‑Fi trong list / nhập tay → mật khẩu → Lưu.
4. Robot tắt hotspot, nối mạng nhà.
5. SoftAP bị chặn → Settings + mắt `SET WIFI`.

QR/URL trên mắt giữ ~60 giây. Mở portal bằng quét QR hoặc gõ URL (ROM không cho captive portal tự mở).

## Quét Wi‑Fi (đã verify log)

`startScan=true` nhưng `scan count=0` vì SoftAP **tắt STA giữa lúc đang ScanState** → kết quả bị hủy.

→ App quét **xong + cache trước** SoftAP; portal đọc cache. “Quét lại” khi SoftAP bật thường không ra mạng mới.
