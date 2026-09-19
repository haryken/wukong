package info.dourok.voicebot.protocol

import android.util.Log
import com.ubtrobot.mini.speech.framework.demo.MiniRobotActionInvoker
import com.ubtrobot.mini.speech.framework.demo.XiaozhiMcpResponder
import info.dourok.voicebot.data.model.DeviceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebsocketProtocol(
    private var deviceInfo: DeviceInfo,
    private val url: String,
    private val accessToken: String
) : Protocol() {

    companion object {
        private const val TAG = "WS"
        private const val OPUS_FRAME_DURATION_MS = 60
    }

    private var isOpen: Boolean = false
    /** Chỉ true sau server hello — tránh gửi Opus trước khi session_id sẵn sàng (server không STT). */
    private var serverHelloReady: Boolean = false
    private var websocket: WebSocket? = null
    private var audioSendCount = 0L
    /** Lần đầu hey mini có thể chậm (DNS, TLS, mạng) – tăng timeout tránh connect timed out. */
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var helloReceived = CompletableDeferred<Boolean>()
    private val openMutex = Mutex()  // Tránh nhiều coroutine gọi openAudioChannel cùng lúc → crash

    init {
        sessionId = "your_session_id"
    }

    /** Self-Control Apply only. */
    fun updateIdentity(deviceId: String, clientId: String) {
        deviceInfo = info.dourok.voicebot.data.model.DummyDataGenerator.generate(deviceId, clientId)
        Log.i(TAG, "updateIdentity Device-Id=$deviceId Client-Id=$clientId")
    }

    override suspend fun start() {
        // no-op
    }

    override suspend fun sendAudio(data: ByteArray) {
        val ws = websocket
        if (ws == null) {
            Log.e(TAG, "sendAudio: WebSocket null – KHÔNG gửi được, cần mở kênh lại")
            return
        }
        ws.send(ByteString.of(*data))
        audioSendCount++
        if (audioSendCount <= 3 || audioSendCount % 200 == 0L) {
            Log.i(TAG, "WS gửi thật audio: ${data.size} bytes, tổng lần gửi=$audioSendCount – nếu thấy log này mà vẫn không có [STT] = server không trả lời")
        }
    }

    override suspend fun sendText(text: String) {
        Log.i(TAG, "Sending text: $text")
        websocket?.run {
            send(text)
        } ?: Log.e(TAG, "WebSocket is null")
    }

    override fun isAudioChannelOpened(): Boolean {
        return websocket != null && isOpen && serverHelloReady
    }

    override fun closeAudioChannel() {
        val ws = websocket
        websocket = null
        isOpen = false
        serverHelloReady = false
        audioSendCount = 0L
        try {
            ws?.close(1000, "Normal closure")
        } catch (e: Exception) {
            Log.w(TAG, "closeAudioChannel: " + e.message)
        }
    }

    override suspend fun openAudioChannel(): OpenChannelResult {
        return withContext(Dispatchers.IO) {
            openMutex.withLock {
                openAudioChannelInternal()
            }
        }
    }

    private suspend fun openAudioChannelInternal(): OpenChannelResult {
        // 0. Đã mở + server hello xong → return ngay (didOpen=false để caller không gửi listen trùng)
        if (websocket != null && isOpen && serverHelloReady) return OpenChannelResult(success = true, didOpen = false)

        // 1. Chỉ close khi WS còn nhưng đã đóng (ví dụ lỗi)
        if (websocket != null) {
            closeAudioChannel()
            delay(100)  // Đợi onClosed xử lý xong trước khi tạo mới
        }
        helloReceived = CompletableDeferred()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Protocol-Version", "1")
            .addHeader("Device-Id", deviceInfo.mac_address)
            .addHeader("Client-Id", deviceInfo.uuid)
            .build()
        Log.i(TAG, "WebSocket connecting to $url")

        websocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                Log.i(TAG, "WebSocket connected")
                scope.launch {
                    audioChannelStateFlow.emit(AudioState.OPENED)
                }

                val helloMessage = JSONObject().apply {
                    put("type", "hello")
                    put("version", 1)
                    put("transport", "websocket")
                    // Giống xiaozhi-esp32-main GetHelloMessage(): bật MCP server mới gửi type=mcp / tools/call (nhảy múa, self control).
                    put("features", JSONObject().apply {
                        put("mcp", true)
                    })
                    put("audio_params", JSONObject().apply {
                        put("format", "opus")
                        put("sample_rate", 16000)
                        put("channels", 1)
                        put("frame_duration", OPUS_FRAME_DURATION_MS)
                    })
                }
                Log.i(TAG, "WebSocket hello: $helloMessage")
                webSocket.send(helloMessage.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.i(TAG, "WebSocket message: $text")
                scope.launch {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    when (type) {
                        "hello" -> parseServerHello(json)
                        else -> incomingJsonFlow.emit(json)
                    }
                }
            }

            // TTS: server gửi binary (Opus) → đẩy vào channel để luồng phát decode và ra loa
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size() == 0) return
                try {
                    scope.launch {
                        incomingAudioFlow.emit(bytes.toByteArray())
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onMessage binary send failed", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Chỉ hủy nếu đúng WS hiện tại — WS cũ (timeout/retry) đóng muộn không được phá kênh mới.
                if (websocket !== webSocket) {
                    Log.d(TAG, "WebSocket closing (stale) ignored: $code: $reason")
                    return
                }
                isOpen = false
                serverHelloReady = false
                Log.i(TAG, "WebSocket closing: $code: $reason")
                super.onClosing(webSocket, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (websocket !== webSocket) {
                    Log.d(TAG, "WebSocket closed (stale) ignored: $code: $reason")
                    return
                }
                isOpen = false
                serverHelloReady = false
                Log.i(TAG, "WebSocket closed: $code: $reason")
                scope.launch {
                    audioChannelStateFlow.emit(AudioState.CLOSED)
                }
                websocket = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (websocket !== webSocket) {
                    Log.d(TAG, "WebSocket failure (stale) ignored: ${t.message}")
                    return
                }
                isOpen = false
                serverHelloReady = false
                t.printStackTrace()
                Log.e(TAG, "WebSocket error: ${t.message}")
                scope.launch {
                    networkErrorFlow.emit("Server not found")
                    audioChannelStateFlow.emit(AudioState.CLOSED)
                }
                websocket = null
            }
        })

        return try {
            withTimeout(10000) {
                Log.i(TAG, "Waiting for server hello")
                helloReceived.await()
                Log.i(TAG, "Server hello received")
                OpenChannelResult(success = true, didOpen = true)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Failed to receive server hello")
            networkErrorFlow.emit("Server timeout")
            closeAudioChannel()
            OpenChannelResult(success = false, didOpen = false)
        }
    }

    private fun parseServerHello(root: JSONObject) {
        val transport = root.optString("transport")
        if (transport != "websocket") {
            Log.e(TAG, "Unsupported transport: $transport")
            return
        }

        val audioParams = root.optJSONObject("audio_params")
        audioParams?.let {
            val sampleRate = it.optInt("sample_rate", -1)
            if (sampleRate != -1) {
                serverSampleRate = sampleRate
            }
        }
        sessionId = root.optString("session_id")
        serverHelloReady = true

        // Vision URL/token (một số server gửi trong hello; MCP initialize cũng có thể gửi lại).
        MiniRobotActionInvoker.ingestVisionFromServerHello(root)

        helloReceived.complete(true)
    }

    override fun dispose() {
        scope.cancel()
        closeAudioChannel()
        client.dispatcher().executorService().shutdown()
    }

    private var serverSampleRate: Int = -1
}

