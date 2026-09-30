package com.ubtrobot.mini.speech.framework.demo.selfcontrol

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal RFC6455 WebSocket (text + binary) over an accepted TCP socket.
 */
class SelfControlWebSocket private constructor(
    private val sock: Socket,
    private val input: InputStream,
    private val output: OutputStream
) {
    private val open = AtomicBoolean(true)

    fun isOpen(): Boolean = open.get() && !sock.isClosed

    @Synchronized
    fun sendText(text: String) {
        sendFrame(0x1, text.toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun sendBinary(data: ByteArray) {
        sendFrame(0x2, data)
    }

    @Synchronized
    fun close() {
        if (!open.compareAndSet(true, false)) return
        try {
            sendFrame(0x8, ByteArray(0))
        } catch (_: Exception) {
        }
        try {
            sock.close()
        } catch (_: Exception) {
        }
    }

    fun readLoop(onText: (String) -> Unit, onBinary: (ByteArray) -> Unit) {
        try {
            while (isOpen()) {
                val frame = readFrame() ?: break
                when (frame.opcode) {
                    0x1 -> onText(String(frame.payload, Charsets.UTF_8))
                    0x2 -> onBinary(frame.payload)
                    0x8 -> break
                    0x9 -> sendFrame(0xA, frame.payload) // ping → pong
                    0xA -> {} // pong
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "ws read end: ${e.message}")
        } finally {
            close()
        }
    }

    private data class Frame(val opcode: Int, val payload: ByteArray)

    private fun readFrame(): Frame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        when (len) {
            126L -> {
                val b2 = input.read()
                val b3 = input.read()
                if (b2 < 0 || b3 < 0) return null
                len = ((b2 and 0xFF) shl 8 or (b3 and 0xFF)).toLong()
            }
            127L -> {
                var v = 0L
                for (i in 0 until 8) {
                    val b = input.read()
                    if (b < 0) return null
                    v = (v shl 8) or (b and 0xFF).toLong()
                }
                len = v
            }
        }
        if (len > MAX_PAYLOAD) throw IllegalStateException("ws payload too large: $len")
        val mask = if (masked) {
            val m = ByteArray(4)
            readFully(m)
            m
        } else null
        val payload = ByteArray(len.toInt())
        if (len > 0) readFully(payload)
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return Frame(opcode, payload)
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IllegalStateException("eof")
            off += n
        }
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        if (!open.get()) return
        val out = ByteArrayOutputStream()
        out.write(0x80 or (opcode and 0x0F))
        val len = payload.size
        when {
            len < 126 -> out.write(len)
            len <= 0xFFFF -> {
                out.write(126)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(127)
                for (i in 7 downTo 0) {
                    out.write(((len.toLong() shr (8 * i)) and 0xFF).toInt())
                }
            }
        }
        out.write(payload)
        val bytes = out.toByteArray()
        output.write(bytes)
        output.flush()
    }

    companion object {
        private const val TAG = "SelfControlWS"
        private const val MAX_PAYLOAD = 2_000_000
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        fun accept(sock: Socket, secKey: String): SelfControlWebSocket {
            val accept = sha1Base64(secKey + GUID)
            val resp = (
                "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            val out = sock.getOutputStream()
            out.write(resp)
            out.flush()
            return SelfControlWebSocket(sock, sock.getInputStream(), out)
        }

        private fun sha1Base64(s: String): String {
            val md = MessageDigest.getInstance("SHA-1")
            val dig = md.digest(s.toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(dig, Base64.NO_WRAP)
        }
    }
}
