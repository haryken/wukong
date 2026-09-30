/**
 * Self-Control public relay — cho phép xem cam / đàm thoại / điều khiển robot ngoài LAN.
 *
 * Chạy trên VPS có IP/domain công khai:
 *   npm install ws
 *   node server.js
 *   # hoặc PORT=8787 node server.js
 *
 * Robot (tab Live): dán URL gốc, ví dụ https://relay.example.com:8787
 * Phone ngoài mạng: mở https://relay.example.com:8787/p/<deviceId>
 *   deviceId = MAC không dấu ':' (vd. aabbccddeeff)
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT || 8787);
const robots = new Map(); // id -> ws
const phones = new Map(); // id -> Set<ws>

function normId(id) {
  return String(id || '').replace(/:/g, '').toLowerCase();
}

const server = http.createServer((req, res) => {
  const u = new URL(req.url, `http://${req.headers.host}`);
  if (u.pathname === '/' || u.pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      ok: true,
      robots: [...robots.keys()],
      hint: 'Phone: /p/<deviceId>  Robot WS: /robot/<deviceId>  Phone WS: /phone/<deviceId>'
    }));
    return;
  }
  const m = u.pathname.match(/^\/p\/([a-zA-Z0-9_-]+)/);
  if (m) {
    const htmlPath = path.join(__dirname, 'phone.html');
    let html = fs.readFileSync(htmlPath, 'utf8');
    html = html.replace(/__DEVICE_ID__/g, m[1]);
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(html);
    return;
  }
  res.writeHead(404);
  res.end('Not found');
});

const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const u = new URL(req.url, `http://${req.headers.host}`);
  const robotMatch = u.pathname.match(/^\/robot\/([a-zA-Z0-9_-]+)/);
  const phoneMatch = u.pathname.match(/^\/phone\/([a-zA-Z0-9_-]+)/);
  if (!robotMatch && !phoneMatch) {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    if (robotMatch) attachRobot(normId(robotMatch[1]), ws);
    else attachPhone(normId(phoneMatch[1]), ws);
  });
});

function attachRobot(id, ws) {
  const prev = robots.get(id);
  if (prev && prev !== ws) try { prev.close(); } catch (_) {}
  robots.set(id, ws);
  console.log('[robot] online', id);
  ws.on('message', (data, isBinary) => {
    const set = phones.get(id);
    if (!set) return;
    for (const p of set) {
      if (p.readyState === 1) p.send(data, { binary: isBinary });
    }
  });
  ws.on('close', () => {
    if (robots.get(id) === ws) robots.delete(id);
    console.log('[robot] offline', id);
  });
}

function attachPhone(id, ws) {
  if (!phones.has(id)) phones.set(id, new Set());
  phones.get(id).add(ws);
  console.log('[phone] join', id, 'count=', phones.get(id).size);
  ws.on('message', (data, isBinary) => {
    const robot = robots.get(id);
    if (robot && robot.readyState === 1) robot.send(data, { binary: isBinary });
  });
  ws.on('close', () => {
    const set = phones.get(id);
    if (set) {
      set.delete(ws);
      if (set.size === 0) phones.delete(id);
    }
    console.log('[phone] leave', id);
  });
}

server.listen(PORT, () => {
  console.log(`Self-Control relay http://0.0.0.0:${PORT}`);
  console.log(`Robot:  wss://HOST:${PORT}/robot/<deviceId>`);
  console.log(`Phone:  https://HOST:${PORT}/p/<deviceId>`);
});
