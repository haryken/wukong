# Sửa lỗi DNS khi robot kết nối Xiaozhi

Tài liệu cho mọi robot / app Android dùng server Xiaozhi (`api.tenclass.net`), khi robot có mạng nhưng
**đánh thức xong (có ting) mà không nói chuyện được**, hoặc lúc được lúc không, do router/nhà mạng phân giải
tên miền dịch vụ Trung Quốc chậm hoặc sai.

---

## 1. Triệu chứng

- Robot ting, cười nhưng không trả lời; log có:
  ```
  WS: WebSocket connecting to wss://api.tenclass.net/xiaozhi/v1/
  WS: Waiting for server hello
  WS: Failed to receive server hello        ← sau đúng 10 s
  ```
- Khoảng cách giữa `WebSocket connecting` và dòng `[socket] connection api.tenclass.net/47.76.65.170:443`
  lên tới **~10–11 s** (bình thường < 1 s). Thời gian đó là robot đang **tra tên miền (DNS)**.
- Kết nối muộn vẫn thành công ngay sau khi app đã bỏ cuộc → lần gọi kế tiếp lại chạy bình thường
  (vì Android đã cache kết quả DNS) → lỗi "lúc được lúc không".
- OTA / kích hoạt thiết bị (`XiaozhiOtaClient`) cũng có thể lỗi `timeout` / `Unable to resolve host`.

## 2. Nguyên nhân

Trước khi kết nối, robot phải hỏi DNS "`api.tenclass.net` là IP nào?". Hay gặp 3 trường hợp:

| Trường hợp | Chuyện gì xảy ra |
|---|---|
| **Router phát DNS IPv6 không trả lời** (vd. `fd00:db80::1`, modem Viettel/VNPT/FPT bật IPv6) | Android hỏi DNS đầu tiên, chờ ~10 s mới chuyển sang DNS thứ hai → quá hạn 10 s chờ server hello |
| **DNS nhà mạng / router không phân giải được dịch vụ Trung Quốc**, hoặc trả IP sai, bị lọc | Tra lỗi hẳn hoặc kết nối vào IP sai |
| DNS chậm do mạng yếu | Thỉnh thoảng quá hạn |

Kiểm tra DNS robot đang dùng:
```bash
adb shell getprop net.dns1
adb shell getprop net.dns2
adb shell dumpsys connectivity | grep -i dns
```
Robot đã gặp lỗi: `net.dns1 = fd00:db80::1` (DNS IPv6 của router), `net.dns2 = 208.67.222.222`.

Kiểm tra từ máy tính cùng mạng (thay `192.168.1.1` bằng IP router):
```powershell
nslookup api.tenclass.net 192.168.1.1     # DNS router — chậm / lỗi = đúng bệnh
nslookup api.tenclass.net 223.5.5.5       # AliDNS — phải ra 47.76.65.170 ngay
```

## 3. Cách sửa trong app (đã làm – khuyến nghị)

File `src/main/java/com/ubtrobot/mini/speech/framework/demo/XiaozhiDns.kt` — một `okhttp3.Dns` riêng cho
kết nối Xiaozhi. **Không đổi cài đặt mạng của robot**, không ảnh hưởng app/tính năng khác, mang robot sang
mạng khác vẫn chạy.

Thứ tự tra IP:

1. **DNS hệ thống** của robot, tối đa **3 s**, lấy IPv4 trước (IPv6 trên các router này hay rớt).
   Mạng tốt thì dừng ở bước này, không khác gì trước.
2. Quá 3 s hoặc lỗi → hỏi **song song DNS-over-HTTPS** (DoH), ai trả trước dùng người đó, tối đa **4 s**:
   - AliDNS `https://223.5.5.5/resolve` — máy chủ Trung Quốc, phân giải dịch vụ Trung Quốc tốt nhất
   - Cloudflare `https://1.1.1.1/dns-query`
   - Google `https://8.8.8.8/resolve`

   DoH đi qua HTTPS cổng 443 như web thường → router/nhà mạng không chặn/sửa được như DNS cổng 53.
   URL dùng IP trực tiếp nên không cần DNS để gọi DoH.
3. Vẫn lỗi → dùng **IP đã kết nối thành công lần trước** (lưu trong SharedPreferences
   `xiaozhi_dns_cache`, còn sau khi khởi động lại app).
4. Hết cách → báo lỗi `UnknownHostException` như bình thường.

Trường hợp xấu nhất tra IP mất ~7 s (3 + 4), vẫn trong hạn 10 s chờ server hello; thường chỉ ~3,5 s khi
DNS router chết.

Đang áp dụng cho:

| Chỗ dùng | File |
|---|---|
| WebSocket `wss://api.tenclass.net/xiaozhi/v1/` | `info/dourok/voicebot/protocol/WebsocketProtocol.kt` (`.dns(XiaozhiDns)`) |
| OTA / kích hoạt / kiểm tra liên kết | `XiaozhiOtaClient.kt` (`.dns(XiaozhiDns)`) |
| UDP audio (chế độ MQTT) | `MqttProtocol.kt` → `UdpClient` (`XiaozhiDns.lookup(server).first()`) |
| Khởi tạo cache | `DemoSpeech.init()` → `XiaozhiDns.init(appContext)` |

Chưa áp dụng: kết nối TCP tới **MQTT broker** (thư viện Paho tự tra DNS). Nếu robot dùng MQTT và lỗi ở
bước kết nối broker, dùng cách ở mục 4.

### Chuyển qua lại DNS mới / DNS như cũ (web :8080)

Trang `http://<IP robot>:8080` → Cấu hình robot → **🌐 DNS Xiaozhi**:

| Chế độ | Cách làm |
|---|---|
| DNS mới (`smart`, mặc định) | DNS router tối đa 3 s → DoH song song → IP đã nhớ (như trên) |
| DNS như cũ (`system`) | Chỉ `Dns.SYSTEM` của Android, không giới hạn thời gian — y như trước khi có `XiaozhiDns` |

- Lưu vào SharedPreferences `xiaozhi_dns_settings` (giữ qua khởi động lại). Áp dụng từ lần phân giải kế tiếp,
  kết nối đang mở giữ nguyên → đánh thức lại / chạm đầu để thử.
- Nút **Kiểm tra** phân giải thử `api.tenclass.net` bằng chế độ đang lưu, hiện IP, nguồn (DNS hệ thống / DoH /
  IP đã nhớ) và thời gian — dùng để so sánh hai chế độ trên cùng một mạng.
- API: `GET /api/xiaozhi_dns`, `POST /api/xiaozhi_dns` body `{"mode":"smart"|"system"}`,
  `POST /api/xiaozhi_dns/test`.

### Đọc log

```bash
adb logcat -s XiaozhiDns WS
```

| Log | Ý nghĩa |
|---|---|
| (không có dòng `XiaozhiDns`) | DNS hệ thống trả nhanh — mạng bình thường |
| `api.tenclass.net: DNS hệ thống quá 3000ms → thử DoH` | DNS router chậm/chết — đúng bệnh ở mục 2 |
| `api.tenclass.net → 47.76.65.170 qua DoH AliDNS (3400ms)` | Đã vượt qua bằng DoH |
| `... DNS hệ thống + DoH đều lỗi → dùng IP đã nhớ [...]` | Mạng chặn cả DoH, dùng IP cũ |
| `XiaozhiDns: không phân giải được ...` | Mất mạng hẳn hoặc chưa từng kết nối được lần nào |
| `chế độ DNS: system` / `đổi chế độ DNS → system` | Đang dùng DNS như cũ |
| `... qua DNS hệ thống, chế độ cũ (11000ms)` | Chế độ cũ mà DNS router chậm — nên chuyển về DNS mới |

### Mang sang robot / app khác

1. Copy `XiaozhiDns.kt` (đổi `package` cho khớp). Cần OkHttp 3.x (`okhttp3.Dns`).
2. Gọi `XiaozhiDns.init(context)` một lần lúc khởi động (không gọi thì vẫn chạy, chỉ không nhớ IP qua lần
   khởi động lại).
3. Mọi `OkHttpClient` kết nối Xiaozhi thêm `.dns(XiaozhiDns)`. Chỗ nào dùng `InetAddress.getByName(host)`
   cho server Xiaozhi thì đổi thành `XiaozhiDns.lookup(host).first()` (gọi ngoài main thread).

## 4. Cách sửa ngoài app (không cần sửa code)

Dùng khi robot chạy app cũ chưa có `XiaozhiDns`, hoặc cần sửa cho cả MQTT broker.

### 4a. Sửa trên router (tốt nhất nếu có quyền vào router)

- Vào trang quản trị router (thường `192.168.1.1` / `192.168.0.1` / `192.168.100.1`).
- **DHCP / LAN → DNS**: đặt DNS 1 = `223.5.5.5` (AliDNS), DNS 2 = `8.8.8.8` hoặc `1.1.1.1`.
- **IPv6**: nếu không cần, tắt IPv6 trên LAN, hoặc tắt phát DNS IPv6 (RDNSS / DHCPv6 DNS). Đây là
  nguyên nhân chính của lỗi chờ 10 s.
- Khởi động lại WiFi trên robot (tắt/bật WiFi hoặc khởi động lại robot) để nhận DNS mới.

Ảnh hưởng: mọi thiết bị trong mạng dùng DNS mới — thường nhanh hơn, không mất tính năng gì. Các tên
miền nội bộ kiểu `router.lan` có thể không còn phân giải (ít khi cần).

### 4b. Sửa trên từng robot (không vào được router)

Android chỉ cho sửa DNS khi đặt **IP tĩnh** cho mạng WiFi đó:

1. Mở cài đặt WiFi trên robot (qua Vysor/scrcpy):
   ```bash
   adb shell am start -a android.settings.WIFI_SETTINGS
   ```
2. Nhấn giữ mạng WiFi đang dùng → **Sửa mạng** → **Tùy chọn nâng cao** → **Cài đặt IP: Tĩnh**.
3. Điền:
   - Địa chỉ IP: IP robot đang có (vd. `192.168.100.103`) — nên đặt giữ chỗ (DHCP reservation) trên router
     để không trùng IP máy khác
   - Cổng (gateway): IP router
   - Độ dài tiền tố: `24`
   - DNS 1: `223.5.5.5`, DNS 2: `8.8.8.8`
4. Lưu, kiểm tra lại bằng `adb shell getprop net.dns1`.

Lưu ý:
- Cài đặt này **chỉ cho đúng mạng WiFi đó**. Mang robot sang mạng khác phải cài lại (hoặc để DHCP).
- Nếu mạng khác cùng tên WiFi nhưng dải IP khác, IP tĩnh cũ sẽ làm **robot mất mạng**.
- Một số mạng công ty/trường học **chặn DNS ngoài** (`8.8.8.8`, `223.5.5.5` cổng 53) → DNS tĩnh làm robot
  không tra được gì. Khi đó quay lại DHCP và dùng cách sửa trong app (DoH qua 443 thường vẫn đi được).

## 5. Sửa DNS KHÔNG giải quyết được

| Vấn đề | Dấu hiệu log | Cách xử lý |
|---|---|---|
| Nhà mạng chặn thẳng IP server (47.76.65.170) | DNS ra IP nhanh nhưng `connect timed out` / không có `[socket] ... connected` | VPN / proxy / server Xiaozhi tự host |
| WiFi rớt giữa chừng | `Software caused connection abort` khi đang nói chuyện | Cải thiện sóng WiFi, tắt IPv6 router; app cần tự kết nối lại |
| Server Xiaozhi sập / quá tải | Kết nối được nhưng không có server hello, lỗi ở mọi mạng | Chờ server |
| Robot quá tải CPU | TLS/handshake chậm, TTS rè | Xem phần giảm tải CPU |

## 6. Kiểm tra nhanh sau khi sửa

```bash
adb logcat -c
# Gọi robot (hey mini / chạm đầu), rồi:
adb logcat -d -v time | grep -E "XiaozhiDns|WebSocket connecting|\[socket\].*tenclass|Server hello|Failed to receive"
```
Đạt: `WebSocket connecting` → `Server hello received` dưới ~2 s (mạng tốt) hoặc dưới ~5 s kèm dòng
`qua DoH ...` (DNS router chết), không còn `Failed to receive server hello`.
