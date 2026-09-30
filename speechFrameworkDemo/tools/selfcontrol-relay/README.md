# Self-Control Relay (ngoài mạng)

Robot nằm sau NAT → kết nối **outbound** tới relay. Phone mở trang relay để xem cam + đàm thoại.

## Chạy trên VPS

```bash
cd tools/selfcontrol-relay
npm install
PORT=8787 node server.js
```

Nên đặt HTTPS (nginx + Let’s Encrypt) rồi proxy WebSocket tới `:8787`.

## Robot

Tab **Live** → Tunnel:

- URL: `https://your-domain.com` (hoặc `wss://your-domain.com`)
- Bấm **Lưu & bật tunnel**

`deviceId` = MAC không dấu `:` (xem status trên UI).

## Phone ngoài mạng

Mở: `https://your-domain.com/p/<deviceId>`

Ví dụ MAC `aa:bb:cc:dd:ee:ff` → `/p/aabbccddeeff`

## LAN (không cần relay)

Cùng Wi‑Fi: mở `http://<ip-robot>:8080` → tab **Live**.
