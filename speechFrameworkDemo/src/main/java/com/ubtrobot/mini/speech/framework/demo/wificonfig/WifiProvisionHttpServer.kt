package com.ubtrobot.mini.speech.framework.demo.wificonfig

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Portal cấu hình Wi‑Fi khi SoftAP:
 * [http://192.168.43.1:8888] — danh sách SSID + form mật khẩu.
 */
object WifiProvisionHttpServer {
    private const val TAG = "WifiProvision"
    const val PORT = 8888
    private const val PREFS = "wifi_provision"
    private const val KEY_LAST_FAIL_SSID = "last_fail_ssid"

    @Volatile private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var appContext: Context? = null
    @Volatile private var onSubmit: ((ssid: String, pass: String) -> Unit)? = null
    @Volatile private var apSsid: String = ""
    @Volatile private var apPass: String = ""

    /** SSID lần nối nhà thất bại — prefs + RAM; xóa khi user bấm Lưu. */
    fun lastFailSsid(): String {
        val mem = lastFailSsidMem
        if (mem.isNotEmpty()) return mem
        val ctx = appContext ?: return ""
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_FAIL_SSID, "")?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    @Volatile private var lastFailSsidMem: String = ""

    fun noteConnectFail(ssid: String) {
        val s = ssid.trim()
        if (s.isEmpty()) return
        lastFailSsidMem = s
        try {
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()?.putString(KEY_LAST_FAIL_SSID, s)?.commit()
        } catch (e: Exception) {
            Log.w(TAG, "noteConnectFail prefs: ${e.message}")
        }
        Log.i(TAG, "noteConnectFail ssid=$s (đã lưu prefs)")
    }

    fun clearConnectFail() {
        lastFailSsidMem = ""
        try {
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()?.remove(KEY_LAST_FAIL_SSID)?.commit()
        } catch (e: Exception) {
            Log.w(TAG, "clearConnectFail prefs: ${e.message}")
        }
        Log.i(TAG, "clearConnectFail")
    }

    fun start(
        context: Context,
        apSsid: String,
        apPass: String,
        onSubmit: (ssid: String, pass: String) -> Unit
    ) {
        appContext = context.applicationContext
        this.apSsid = apSsid
        this.apPass = apPass
        this.onSubmit = onSubmit
        // Nạp lại fail SSID từ prefs (sau SoftAP restart)
        try {
            val fromPrefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_FAIL_SSID, "")?.trim().orEmpty()
            if (fromPrefs.isNotEmpty()) lastFailSsidMem = fromPrefs
        } catch (_: Exception) {
        }
        Log.i(TAG, "HTTP start lastFailSsid=${lastFailSsid().ifEmpty { "(none)" }}")
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "HTTP already on :$PORT")
            return
        }
        val ready = CountDownLatch(1)
        pool.execute {
            try {
                val ss = ServerSocket(PORT)
                serverSocket = ss
                Log.i(TAG, "Provision HTTP 0.0.0.0:$PORT url=${portalUrl()}")
                ready.countDown()
                while (running.get()) {
                    try {
                        val sock = ss.accept()
                        pool.execute { handleClient(sock) }
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "accept: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "bind :$PORT failed: ${e.message}", e)
                running.set(false)
                ready.countDown()
            }
        }
        try {
            ready.await(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        onSubmit = null
    }

    fun portalUrl(): String {
        val ip = apIpv4() ?: "192.168.43.1"
        return "http://$ip:$PORT"
    }

    fun apIpv4(): String? {
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.43.") || host.startsWith("192.168.42.")
                            || host.startsWith("192.168.137.")
                        ) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun handleClient(sock: Socket) {
        try {
            sock.soTimeout = 20_000
            val input = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.US)
            val path = parts[1].substringBefore('?').lowercase(Locale.US)
            var contentLength = 0
            var contentType = ""
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase(Locale.US)
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                if (lower.startsWith("content-type:")) {
                    contentType = lower.substringAfter(':').trim()
                }
            }
            val body = if (contentLength > 0 && contentLength < 64_000) {
                val buf = CharArray(contentLength)
                var off = 0
                while (off < contentLength) {
                    val n = input.read(buf, off, contentLength - off)
                    if (n < 0) break
                    off += n
                }
                String(buf, 0, off)
            } else ""

            val out = sock.getOutputStream()
            when {
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    write(out, 200, "text/html; charset=utf-8", htmlPage())
                }
                method == "GET" && path == "/scan" -> {
                    val ctx = appContext
                    val list = if (ctx != null) {
                        WifiScanHelper.refreshWhileSoftAp(ctx)
                    } else {
                        WifiScanHelper.cached()
                    }
                    write(out, 200, "application/json; charset=utf-8", WifiScanHelper.toJson(list))
                }
                method == "POST" && path == "/save" -> {
                    val form = parseForm(body)
                    val ssid = form["ssid"]?.trim().orEmpty()
                    val pass = form["password"] ?: form["pass"] ?: ""
                    if (ssid.isEmpty()) {
                        write(
                            out, 400, "application/json; charset=utf-8",
                            """{"ok":false,"error":"SSID trống"}"""
                        )
                    } else {
                        // User bấm Lưu → xóa data sai mk cũ; nếu fail lại sẽ ghi mới.
                        clearConnectFail()
                        write(
                            out, 200, "application/json; charset=utf-8",
                            """{"ok":true,"message":"Gửi thành công — robot đang tắt hotspot và nối \"${escapeJson(ssid)}\". Nếu bắt Wi‑Fi thất bại, hãy nối lại Wi‑Fi Mini của robot rồi mở lại trang này để nhập lại mật khẩu."}"""
                        )
                        pool.execute {
                            try {
                                onSubmit?.invoke(ssid, pass)
                            } catch (e: Exception) {
                                Log.e(TAG, "onSubmit: ${e.message}", e)
                            }
                        }
                    }
                }
                method == "GET" && path == "/status" -> {
                    val fail = lastFailSsid()
                    write(
                        out, 200, "application/json; charset=utf-8",
                        """{"apSsid":"${escapeJson(apSsid)}","portal":"${escapeJson(portalUrl())}","lastFailSsid":"${escapeJson(fail)}"}"""
                    )
                }
                else -> write(out, 404, "text/plain; charset=utf-8", "not found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleClient: ${e.message}")
        } finally {
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun htmlPage(): String {
        val ctx = appContext
        val asset = try {
            ctx?.assets?.open("wifi_provision.html")?.bufferedReader()?.readText()
        } catch (_: Exception) {
            null
        }
        val passHint = if (apPass.isBlank()) {
            "(<b>không mật khẩu</b>)."
        } else {
            "(mật khẩu hotspot: <code>${escape(apPass)}</code>)."
        }
        if (!asset.isNullOrBlank()) {
            val fail = lastFailSsid()
            return asset
                .replace("{{AP_SSID}}", escape(apSsid))
                .replace("{{AP_PASS}}", escape(apPass))
                .replace("{{AP_PASS_HINT}}", passHint)
                .replace("{{PORTAL}}", escape(portalUrl()))
                .replace("{{LAST_FAIL_SSID}}", escape(fail))
        }
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
            <title>Cấu hình Wi‑Fi</title></head>
            <body style="font-family:sans-serif;padding:20px">
            <h2>Cấu hình Wi‑Fi</h2>
            <p>Hotspot <b>${escape(apSsid)}</b> $passHint</p>
            <form method="POST" action="/save">
              <p>SSID<br/><input name="ssid" required/></p>
              <p>Mật khẩu<br/><input name="password" type="password"/></p>
              <p><button type="submit">Lưu</button></p>
            </form>
            </body></html>
        """.trimIndent()
    }

    private fun parseForm(body: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        if (body.isEmpty()) return map
        for (pair in body.split('&')) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            map[urlDecode(pair.substring(0, i))] = urlDecode(pair.substring(i + 1))
        }
        return map
    }

    private fun urlDecode(s: String): String =
        try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun write(out: java.io.OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"
            400 -> "400 Bad Request"
            else -> "404 Not Found"
        }
        val header =
            "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}
