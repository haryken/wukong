package com.ubtrobot.mini.speech.framework.demo.selfcontrol

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocketFactory

/**
 * Tunnel ra ngoài mạng: robot kết nối outbound WebSocket tới relay công khai.
 *
 * Relay: wss://your-vps/robot/&lt;deviceId&gt;
 * Phone: https://your-vps/p/&lt;deviceId&gt;
 *
 * Binary: [type][payload] — 1=JPEG, 2=PCM robot→phone, 3=PCM phone→robot
 */
object SelfControlTunnelBridge {
    private const val TAG = "SelfControlTunnel"
    private const val TYPE_JPEG: Byte = 1
    private const val TYPE_PCM_ROBOT: Byte = 2
    private const val TYPE_PCM_PHONE: Byte = 3

    @Volatile private var appContext: Context? = null
    private val pool = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private val wsRef = AtomicReference<TunnelWs?>(null)
    private var frameListener: ((ByteArray) -> Unit)? = null

    @Volatile var relayUrl: String = ""
        private set
    @Volatile var connected: Boolean = false
        private set
    @Volatile var lastError: String = ""
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        val saved = SelfControlStore.getTunnelRelayUrl()
        if (saved.isNotEmpty()) relayUrl = saved
    }

    fun statusJson(): JSONObject = JSONObject().apply {
        put("success", true)
        put("relay_url", relayUrl)
        put("connected", connected)
        put("running", running.get())
        put("last_error", lastError)
        put("lan_url", SelfControlHttpServer.configUrl())
        put("device_id", SelfControlStore.resolveDeviceId())
    }

    fun applyConfig(relay: String?, autoStart: Boolean): JSONObject {
        val url = relay?.trim().orEmpty()
        relayUrl = url
        SelfControlStore.setTunnelRelayUrl(url)
        if (url.isEmpty()) {
            stop()
            return statusJson().put("message", "Đã xóa tunnel")
        }
        if (autoStart) start()
        return statusJson().put("message", if (running.get()) "Tunnel đang chạy" else "Đã lưu URL")
    }

    fun start() {
        val url = relayUrl.trim()
        if (url.isEmpty()) {
            lastError = "Chưa cấu hình relay URL"
            return
        }
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "tunnel already running")
            return
        }
        pool.execute { runLoop(url) }
    }

    fun stop() {
        running.set(false)
        connected = false
        try {
            wsRef.getAndSet(null)?.close()
        } catch (_: Exception) {
        }
        frameListener?.let { SelfControlCameraMjpeg.removeFrameListener(it) }
        frameListener = null
    }

    fun forwardRobotPcm(pcm: ByteArray) {
        if (!connected || SelfControlIntercom.robotTxMuted) return
        sendTyped(TYPE_PCM_ROBOT, pcm)
    }

    private fun runLoop(baseUrl: String) {
        lastError = ""
        var backoff = 2_000L
        while (running.get()) {
            try {
                val deviceId = SelfControlStore.resolveDeviceId().ifBlank { "robot" }
                val wsUrl = normalizeRobotWsUrl(baseUrl, deviceId)
                Log.i(TAG, "connecting $wsUrl")
                val ws = TunnelWs.connect(wsUrl)
                wsRef.set(ws)
                connected = true
                backoff = 2_000L
                lastError = ""
                attachCamera()
                ws.sendText(
                    JSONObject()
                        .put("type", "hello")
                        .put("role", "robot")
                        .put("device_id", deviceId)
                        .put("robot_tx_muted", SelfControlIntercom.robotTxMuted)
                        .put("phone_tx_muted", SelfControlIntercom.phoneTxMuted)
                        .toString()
                )
                ws.readLoop(
                    onText = { handleRemoteText(it) },
                    onBinary = { handleRemoteBinary(it) }
                )
            } catch (e: Exception) {
                lastError = e.message ?: "tunnel error"
                Log.w(TAG, "tunnel: $lastError")
            } finally {
                connected = false
                wsRef.set(null)
                frameListener?.let { SelfControlCameraMjpeg.removeFrameListener(it) }
                frameListener = null
            }
            if (!running.get()) break
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                break
            }
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
        running.set(false)
        Log.i(TAG, "tunnel loop exit")
    }

    private fun attachCamera() {
        val listener: (ByteArray) -> Unit = { jpeg ->
            if (connected) sendTyped(TYPE_JPEG, jpeg)
        }
        frameListener = listener
        SelfControlCameraMjpeg.addFrameListener(listener)
    }

    private fun handleRemoteText(text: String) {
        try {
            val o = JSONObject(text)
            when (o.optString("type")) {
                "mute" -> SelfControlIntercom.setMutes(
                    if (o.has("robot_tx_muted")) o.optBoolean("robot_tx_muted") else null,
                    if (o.has("phone_tx_muted")) o.optBoolean("phone_tx_muted") else null
                )
                "play_skill" -> SelfControlRobotTryout.playSkill(
                    o.optString("name"),
                    o.optBoolean("music", true)
                )
                "play_express" -> SelfControlRobotTryout.playExpress(o.optString("name"))
                "stop" -> SelfControlRobotTryout.stopAll()
                "ping" -> wsRef.get()?.sendText("""{"type":"pong"}""")
            }
        } catch (e: Exception) {
            Log.d(TAG, "remote text: ${e.message}")
        }
    }

    private fun handleRemoteBinary(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0]) {
            TYPE_PCM_PHONE -> SelfControlIntercom.onPhonePcm(data.copyOfRange(1, data.size))
        }
    }

    private fun sendTyped(type: Byte, payload: ByteArray) {
        val ws = wsRef.get() ?: return
        try {
            val packet = ByteArray(1 + payload.size)
            packet[0] = type
            System.arraycopy(payload, 0, packet, 1, payload.size)
            ws.sendBinary(packet)
        } catch (_: Exception) {
        }
    }

    private fun normalizeRobotWsUrl(raw: String, deviceId: String): String {
        var u = raw.trim()
        if (u.startsWith("https://")) u = "wss://" + u.removePrefix("https://")
        if (u.startsWith("http://")) u = "ws://" + u.removePrefix("http://")
        if (!u.startsWith("ws://") && !u.startsWith("wss://")) u = "wss://$u"
        val id = deviceId.replace(":", "").lowercase()
        if (!u.contains("/robot/")) u = u.trimEnd('/') + "/robot/$id"
        return u
    }

    private class TunnelWs(
        private val sock: Socket,
        private val input: InputStream,
        private val output: OutputStream
    ) {
        private val open = AtomicBoolean(true)

        fun sendText(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))
        fun sendBinary(data: ByteArray) = sendFrame(0x2, data)

        fun close() {
            if (!open.compareAndSet(true, false)) return
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }

        fun readLoop(onText: (String) -> Unit, onBinary: (ByteArray) -> Unit) {
            try {
                while (open.get()) {
                    val frame = readFrame() ?: break
                    when (frame.first) {
                        0x1 -> onText(String(frame.second, Charsets.UTF_8))
                        0x2 -> onBinary(frame.second)
                        0x8 -> break
                        0x9 -> sendFrame(0xA, frame.second)
                    }
                }
            } finally {
                close()
            }
        }

        private fun sendFrame(opcode: Int, payload: ByteArray) {
            if (!open.get()) return
            val mask = ByteArray(4)
            java.security.SecureRandom().nextBytes(mask)
            val bout = ByteArrayOutputStream()
            bout.write(0x80 or (opcode and 0x0F))
            val len = payload.size
            when {
                len < 126 -> bout.write(0x80 or len)
                len <= 0xFFFF -> {
                    bout.write(0x80 or 126)
                    bout.write((len shr 8) and 0xFF)
                    bout.write(len and 0xFF)
                }
                else -> {
                    bout.write(0x80 or 127)
                    for (i in 7 downTo 0) bout.write(((len.toLong() shr (8 * i)) and 0xFF).toInt())
                }
            }
            bout.write(mask)
            for (i in payload.indices) {
                bout.write(payload[i].toInt() xor mask[i % 4].toInt())
            }
            synchronized(output) {
                output.write(bout.toByteArray())
                output.flush()
            }
        }

        private fun readFrame(): Pair<Int, ByteArray>? {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = input.read()
            if (b1 < 0) return null
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            when (len) {
                126L -> {
                    val hi = input.read(); val lo = input.read()
                    if (hi < 0 || lo < 0) return null
                    len = ((hi and 0xFF) shl 8 or (lo and 0xFF)).toLong()
                }
                127L -> {
                    var v = 0L
                    for (i in 0 until 8) {
                        val b = input.read(); if (b < 0) return null
                        v = (v shl 8) or (b and 0xFF).toLong()
                    }
                    len = v
                }
            }
            val mask = if (masked) {
                val m = ByteArray(4)
                readFully(m); m
            } else null
            val payload = ByteArray(len.toInt())
            if (len > 0) readFully(payload)
            if (mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }
            return opcode to payload
        }

        private fun readFully(buf: ByteArray) {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) throw IllegalStateException("eof")
                off += n
            }
        }

        companion object {
            fun connect(wsUrl: String): TunnelWs {
                val uri = URI(wsUrl)
                val ssl = uri.scheme.equals("wss", true)
                val port = when {
                    uri.port > 0 -> uri.port
                    ssl -> 443
                    else -> 80
                }
                val host = uri.host ?: throw IllegalArgumentException("no host")
                val path = buildString {
                    append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
                    if (!uri.rawQuery.isNullOrEmpty()) append('?').append(uri.rawQuery)
                }
                val sock = if (ssl) {
                    SSLSocketFactory.getDefault().createSocket(host, port)
                } else {
                    Socket(host, port)
                }
                sock.tcpNoDelay = true
                val key = Base64.encodeToString(
                    ByteArray(16).also { java.security.SecureRandom().nextBytes(it) },
                    Base64.NO_WRAP
                )
                val out = sock.getOutputStream()
                out.write(
                    ("GET $path HTTP/1.1\r\n" +
                        "Host: $host\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\n" +
                        "Sec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.US_ASCII)
                )
                out.flush()
                val input = sock.getInputStream()
                val headerBuf = ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b < 0) throw IllegalStateException("tunnel handshake eof")
                    headerBuf.write(b)
                    if (headerBuf.toString("US-ASCII").contains("\r\n\r\n")) break
                    if (headerBuf.size() > 16_000) throw IllegalStateException("handshake too large")
                }
                val headers = headerBuf.toString("US-ASCII")
                if (!headers.contains("101")) {
                    throw IllegalStateException(
                        "WS handshake failed: ${headers.lineSequence().firstOrNull()}"
                    )
                }
                return TunnelWs(sock, input, out)
            }
        }
    }
}
