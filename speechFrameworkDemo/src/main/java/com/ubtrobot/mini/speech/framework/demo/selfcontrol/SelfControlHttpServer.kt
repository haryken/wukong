package com.ubtrobot.mini.speech.framework.demo.selfcontrol

import android.content.Context
import android.util.Log
import com.ubtrobot.mini.speech.framework.demo.WakeEngineSettings
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP Self-Control LAN :8080 — GET /, GET/POST /api/config (Otto parity).
 */
object SelfControlHttpServer {
    private const val TAG = "SelfControlHttp"
    const val PORT = 8080
    /** Cap POST body (bytes). Self-Control JSON is small; avoid OOM on bad clients. */
    private const val MAX_POST_BODY_BYTES = 1_048_576

    @Volatile private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var onIdentityChanged: (() -> Unit)? = null
    @Volatile private var appContext: Context? = null
    private val identityApplyLock = Any()
    @Volatile private var identityApplyScheduled = false
    /** POST mới trong lúc đang apply → chạy lại 1 lần với config mới nhất. */
    @Volatile private var identityApplyPending = false

    fun setOnIdentityChanged(cb: (() -> Unit)?) {
        onIdentityChanged = cb
    }

    /** Defer ApplyDeviceIdentity so HTTP can finish first; coalesce rapid POSTs. */
    private fun scheduleIdentityChanged() {
        synchronized(identityApplyLock) {
            if (identityApplyScheduled) {
                identityApplyPending = true
                Log.i(TAG, "identity apply in flight – mark pending for latest config")
                return
            }
            identityApplyScheduled = true
            identityApplyPending = false
        }
        pool.execute {
            try {
                while (true) {
                    try {
                        Thread.sleep(800)
                        onIdentityChanged?.invoke()
                    } catch (e: Exception) {
                        Log.w(TAG, "onIdentityChanged: ${e.message}")
                    }
                    val again = synchronized(identityApplyLock) {
                        if (identityApplyPending) {
                            identityApplyPending = false
                            true
                        } else {
                            identityApplyScheduled = false
                            false
                        }
                    }
                    if (!again) break
                    Log.i(TAG, "identity apply pending – run again with latest config")
                }
            } catch (e: Exception) {
                Log.w(TAG, "scheduleIdentityChanged: ${e.message}")
                synchronized(identityApplyLock) {
                    identityApplyScheduled = false
                    identityApplyPending = false
                }
            }
        }
    }

    /** Chỉ process chính bind :8080 — process phụ (:speech) bind sẽ luôn EADDRINUSE. */
    private fun isMainProcess(context: Context): Boolean {
        val name = try {
            java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n')
        } catch (_: Exception) {
            return true
        }
        return name.isEmpty() || name == context.packageName
    }

    fun start(context: Context) {
        if (!isMainProcess(context)) return
        SelfControlStore.init(context)
        WakeEngineSettings.init(context)
        appContext = context.applicationContext
        SelfControlCameraMjpeg.init(context)
        SelfControlIntercom.init(context)
        SelfControlTunnelBridge.init(context)
        SelfControlQuickShare.init(context)
        SelfControlGameSpeak.init(context)
        if (SelfControlStore.getTunnelRelayUrl().isNotEmpty()) {
            SelfControlTunnelBridge.start()
        }
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "already running on :$PORT")
            return
        }
        pool.execute {
            try {
                val ss = ServerSocket(PORT)
                serverSocket = ss
                Log.i(TAG, "Self-Control HTTP listening 0.0.0.0:$PORT url=${configUrl()}")
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
            }
        }
    }

    fun stop() {
        running.set(false)
        SelfControlTunnelBridge.stop()
        SelfControlQuickShare.stop()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    fun configUrl(): String {
        val ip = lanIpv4() ?: "127.0.0.1"
        return "http://$ip:$PORT"
    }

    fun lanIpv4(): String? {
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name?.lowercase() ?: ""
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (name.contains("wlan") || name.contains("wifi") || name.contains("ap")) {
                            return host
                        }
                    }
                }
            }
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun handleClient(sock: Socket) {
        try {
            sock.soTimeout = 15_000
            val input = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            var contentLength = 0
            var upgrade = ""
            var wsKey = ""
            var line: String?
            while (true) {
                line = input.readLine() ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(':').trim().toIntOrNull() ?: 0
                } else if (lower.startsWith("upgrade:")) {
                    upgrade = lower.substringAfter(':').trim()
                } else if (lower.startsWith("sec-websocket-key:")) {
                    wsKey = line.substringAfter(':').trim()
                }
            }

            // WebSocket intercom (đàm thoại 2 chiều)
            if (upgrade.contains("websocket") && wsKey.isNotEmpty() &&
                (path == "/ws/intercom" || path == "/intercom")
            ) {
                sock.soTimeout = 0
                SelfControlIntercom.handleWebSocket(sock, wsKey)
                return
            }

            // MJPEG realtime — giữ socket lâu
            if (method == "GET" && (path == "/cam/mjpeg" || path == "/video")) {
                sock.soTimeout = 0
                SelfControlCameraMjpeg.writeMjpeg(sock)
                return
            }
            if (method == "GET" && (path == "/cam/snapshot.jpg" || path == "/cam/snapshot")) {
                SelfControlCameraMjpeg.writeSnapshotResponse(sock.getOutputStream())
                return
            }

            val body = if (contentLength > 0) {
                if (contentLength > MAX_POST_BODY_BYTES) {
                    writeResponse(
                        sock.getOutputStream(),
                        400,
                        "application/json; charset=utf-8",
                        """{"success":false,"error":"body too large"}"""
                    )
                    return
                }
                // Content-Length is bytes; Self-Control JSON is ASCII-safe so char read is OK.
                val buf = CharArray(contentLength)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            when {
                method == "OPTIONS" -> writeResponse(sock.getOutputStream(), 204, "text/plain", "")
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    val html = loadHtml()
                    writeResponse(sock.getOutputStream(), 200, "text/html; charset=utf-8", html)
                }
                method == "GET" && path == "/self_control.css" -> {
                    writeAsset(sock, "self_control.css", "text/css; charset=utf-8")
                }
                method == "GET" && path == "/self_control_i18n.js" -> {
                    writeAsset(sock, "self_control_i18n.js", "application/javascript; charset=utf-8")
                }
                method == "GET" && path == "/games" -> {
                    writeResponse(
                        sock.getOutputStream(), 200, "text/html; charset=utf-8",
                        """<meta http-equiv="refresh" content="0;url=/games/">"""
                    )
                }
                method == "GET" && path.startsWith("/games/") -> {
                    val rel = path.removePrefix("/").let { if (it.endsWith("/")) it + "index.html" else it }
                    if (rel.contains("..")) {
                        writeResponse(sock.getOutputStream(), 404, "text/plain", "Not Found")
                    } else {
                        writeAsset(sock, rel, assetContentType(rel))
                    }
                }
                method == "POST" && path == "/api/game_speak" -> {
                    val req = try {
                        JSONObject(body.ifBlank { "{}" })
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    val resp = SelfControlGameSpeak.speak(req.optString("text"), req.optString("lang"))
                    writeResponse(
                        sock.getOutputStream(),
                        if (resp.optBoolean("success")) 200 else 400,
                        "application/json; charset=utf-8",
                        resp.toString()
                    )
                }
                method == "POST" && path == "/api/game_context" -> {
                    val req = try {
                        JSONObject(body.ifBlank { "{}" })
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    SelfControlGameContext.update(
                        req.optString("game"),
                        req.optString("exitId"),
                        req.optString("summary")
                    )
                    writeResponse(sock.getOutputStream(), 200, "application/json; charset=utf-8", """{"success":true}""")
                }
                method == "GET" && path == "/api/wake_engine" -> {
                    writeResponse(
                        sock.getOutputStream(), 200, "application/json; charset=utf-8",
                        WakeEngineSettings.statusJson().toString()
                    )
                }
                method == "POST" && path == "/api/wake_engine" -> {
                    val resp = try {
                        WakeEngineSettings.applyPostJson(JSONObject(body.ifBlank { "{}" }))
                    } catch (e: Exception) {
                        JSONObject().put("success", false).put("error", e.message ?: "lỗi")
                    }
                    writeResponse(
                        sock.getOutputStream(),
                        if (resp.optBoolean("success")) 200 else 400,
                        "application/json; charset=utf-8",
                        resp.toString()
                    )
                }
                method == "GET" && path == "/api/config" -> {
                    val json = SelfControlStore.buildGetConfigJson().toString()
                    writeResponse(sock.getOutputStream(), 200, "application/json; charset=utf-8", json)
                }
                method == "POST" && path == "/api/config" -> {
                    val result = try {
                        SelfControlStore.applyPostConfig(JSONObject(body.ifBlank { "{}" }))
                    } catch (e: Exception) {
                        SelfControlStore.SaveResult(false, false, e.message)
                    }
                    val resp = JSONObject().apply {
                        put("success", result.success)
                        if (result.error != null) put("error", result.error)
                        put("identity_changed", result.identityChanged)
                        put("device_id", SelfControlStore.resolveDeviceId())
                    }
                    // Respond first — never block HTTP on dispose/recreate WS session
                    writeResponse(
                        sock.getOutputStream(),
                        if (result.success) 200 else 400,
                        "application/json; charset=utf-8",
                        resp.toString()
                    )
                    if (result.success && result.identityChanged) {
                        scheduleIdentityChanged()
                    }
                }
                method == "POST" && path == "/api/dismiss_qr" -> {
                    val msg = try {
                        com.ubtrobot.mini.speech.framework.demo.DemoSpeech.selfControlHideConfigPage()
                    } catch (e: Exception) {
                        Log.w(TAG, "dismiss_qr: ${e.message}")
                        "Không tắt được mã QR."
                    }
                    val resp = JSONObject().apply {
                        put("success", true)
                        put("message", msg)
                    }
                    writeResponse(sock.getOutputStream(), 200, "application/json; charset=utf-8", resp.toString())
                }
                method == "GET" && path == "/api/pair_status" -> {
                    val ctx = appContext
                    val resp = if (ctx == null) {
                        JSONObject().put("success", false).put("error", "server chưa sẵn sàng")
                    } else {
                        SelfControlPairing.check(ctx)
                    }
                    writeResponse(sock.getOutputStream(), 200, "application/json; charset=utf-8", resp.toString())
                }
                method == "GET" && path == "/api/actions" -> {
                    writeResponse(
                        sock.getOutputStream(), 200, "application/json; charset=utf-8",
                        SelfControlRobotTryout.listActions().toString()
                    )
                }
                method == "GET" && path == "/api/expresses" -> {
                    writeResponse(
                        sock.getOutputStream(), 200, "application/json; charset=utf-8",
                        SelfControlRobotTryout.listExpresses().toString()
                    )
                }
                method == "POST" && path == "/api/play_skill" -> {
                    val req = try {
                        JSONObject(body.ifBlank { "{}" })
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    val resp = SelfControlRobotTryout.playSkill(
                        req.optString("name", ""),
                        req.optBoolean("music", true)
                    )
                    writeResponse(
                        sock.getOutputStream(),
                        if (resp.optBoolean("success")) 200 else 400,
                        "application/json; charset=utf-8",
                        resp.toString()
                    )
                }
                method == "POST" && path == "/api/play_express" -> {
                    val name = try {
                        JSONObject(body.ifBlank { "{}" }).optString("name", "")
                    } catch (_: Exception) {
                        ""
                    }
                    val resp = SelfControlRobotTryout.playExpress(name)
                    writeResponse(
                        sock.getOutputStream(),
                        if (resp.optBoolean("success")) 200 else 400,
                        "application/json; charset=utf-8",
                        resp.toString()
                    )
                }
                method == "POST" && path == "/api/stop_tryout" -> {
                    val resp = SelfControlRobotTryout.stopAll()
                    writeResponse(
                        sock.getOutputStream(), 200, "application/json; charset=utf-8",
                        resp.toString()
                    )
                }
                method == "GET" && path == "/api/share_link" -> {
                    writeResponse(
                        sock.getOutputStream(), 200, "application/json; charset=utf-8",
                        SelfControlQuickShare.statusJson().toString()
                    )
                }
                method == "POST" && path == "/api/share_link" -> {
                    sock.soTimeout = 90_000
                    val o = try {
                        JSONObject(body.ifBlank { "{}" })
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    val resp = when (o.optString("action", "create")) {
                        "stop" -> {
                            SelfControlQuickShare.stop()
                            SelfControlQuickShare.statusJson().put("message", "Đã tắt link")
                        }
                        else -> SelfControlQuickShare.createLink()
                    }
                    writeResponse(
                        sock.getOutputStream(),
                        if (resp.optBoolean("success", true)) 200 else 500,
                        "application/json; charset=utf-8",
                        resp.toString()
                    )
                }
                method == "GET" && path == "/api/live_status" -> {
                    val resp = JSONObject().apply {
                        put("success", true)
                        put("intercom", SelfControlIntercom.statusJson())
                        put("tunnel", SelfControlTunnelBridge.statusJson())
                        put("share", SelfControlQuickShare.statusJson())
                        put("lan_url", configUrl())
                        put("ws_intercom", "ws://${lanIpv4() ?: "127.0.0.1"}:$PORT/ws/intercom")
                        put("cam_mjpeg", "${configUrl()}/cam/mjpeg")
                    }
                    writeResponse(sock.getOutputStream(), 200, "application/json; charset=utf-8", resp.toString())
                }
                method == "POST" && path == "/api/intercom_mute" -> {
                    val o = try {
                        JSONObject(body.ifBlank { "{}" })
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    SelfControlIntercom.setMutes(
                        if (o.has("robot_tx_muted")) o.optBoolean("robot_tx_muted") else null,
                        if (o.has("phone_tx_muted")) o.optBoolean("phone_tx_muted") else null
                    )
                    writeResponse(
                        sock.getOutputStream(), 200, "application/json; charset=utf-8",
                        SelfControlIntercom.statusJson().toString()
                    )
                }
                method == "GET" && path == "/api/tunnel" -> {
                    writeResponse(
                        sock.getOutputStream(), 200, "application/json; charset=utf-8",
                        SelfControlTunnelBridge.statusJson().toString()
                    )
                }
                method == "POST" && path == "/api/tunnel" -> {
                    val o = try {
                        JSONObject(body.ifBlank { "{}" })
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    val action = o.optString("action", "save")
                    val resp = when (action) {
                        "start" -> {
                            if (o.has("relay_url")) {
                                SelfControlTunnelBridge.applyConfig(o.optString("relay_url"), false)
                            }
                            SelfControlTunnelBridge.start()
                            SelfControlTunnelBridge.statusJson()
                        }
                        "stop" -> {
                            SelfControlTunnelBridge.stop()
                            SelfControlTunnelBridge.statusJson().put("message", "Đã dừng tunnel")
                        }
                        else -> SelfControlTunnelBridge.applyConfig(
                            o.optString("relay_url", SelfControlStore.getTunnelRelayUrl()),
                            o.optBoolean("auto_start", true)
                        )
                    }
                    writeResponse(
                        sock.getOutputStream(), 200, "application/json; charset=utf-8",
                        resp.toString()
                    )
                }
                else -> writeResponse(sock.getOutputStream(), 404, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "client: ${e.message}")
        } finally {
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun loadHtml(): String {
        val ctx = appContext ?: return "<html><body>Self-Control</body></html>"
        return try {
            ctx.assets.open("self_control.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "load self_control.html: ${e.message}")
            "<html><body>Missing self_control.html</body></html>"
        }
    }

    private fun assetContentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    private fun writeAsset(sock: Socket, assetName: String, contentType: String) {
        val ctx = appContext
        if (ctx == null) {
            writeResponse(sock.getOutputStream(), 404, "text/plain", "No context")
            return
        }
        try {
            val bytes = ctx.assets.open(assetName).use { it.readBytes() }
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)
            val out = sock.getOutputStream()
            out.write(headers)
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "asset $assetName: ${e.message}")
            writeResponse(sock.getOutputStream(), 404, "text/plain", "Not Found")
        }
    }

    private fun writeResponse(out: OutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charset.forName("UTF-8"))
        val status = when (code) {
            200 -> "200 OK"
            204 -> "204 No Content"
            400 -> "400 Bad Request"
            else -> "404 Not Found"
        }
        val headers = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        out.write(headers)
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }
}
