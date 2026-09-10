package info.dourok.voicebot.protocol

import android.content.Context
import android.util.Log
import com.ubtrobot.mini.speech.framework.demo.MiniRobotActionInvoker
import com.ubtrobot.mini.speech.framework.demo.XiaozhiMcpResponder
import com.ubtrobot.mini.speech.framework.demo.XiaozhiMqttConfig
import com.ubtrobot.mini.speech.framework.demo.deriveMqttSubscribeTopic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONObject
import java.net.DatagramPacket
import java.util.concurrent.ConcurrentLinkedQueue
import java.net.DatagramSocket
import java.net.InetAddress
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MQTT + UDP — giống Xiaozhi_Android ChatViewModel:
 * start() → connect + subscribe một lần; openAudioChannel() → hello → server hello → UDP.
 */
class MqttProtocol(
    private val context: Context,
    private val mqttConfig: XiaozhiMqttConfig
) : Protocol() {

    /** Trả true để không đóng UDP khi server gửi goodbye (TTS đang phát / chào). */
    @Volatile
    var shouldDeferGoodbye: () -> Boolean = { false }

    companion object {
        private const val TAG = "MqttProtocol"
        /** Khớp xiaozhi-esp32-main2 OPUS_FRAME_DURATION_MS / mqtt-udp.md */
        private const val OPUS_FRAME_DURATION_MS = 60

        fun resolveMqttEndpoint(raw: String): String {
            val t = raw.trim()
            if (t.startsWith("ssl://") || t.startsWith("tcp://") || t.startsWith("mqtts://")) {
                if (t.startsWith("mqtts://")) return "ssl://" + t.removePrefix("mqtts://")
                return t
            }
            val host = t.removePrefix("tcp://").removePrefix("ssl://")
            val useTls = host.contains("xiaozhi.me", ignoreCase = true)
                || host.contains("aliyuncs.com", ignoreCase = true)
                || host.contains("tenclass.net", ignoreCase = true)
            return if (useTls) "ssl://$host:8883" else "tcp://$host:1883"
        }

        fun buildMqttServerUri(rawEndpoint: String): String = resolveMqttEndpoint(rawEndpoint)

        private fun resolveSubscribeTopic(cfg: XiaozhiMqttConfig): String {
            val raw = cfg.subscribeTopic.trim()
            if (raw.isNotEmpty() && !raw.equals("null", ignoreCase = true)) return raw
            return deriveMqttSubscribeTopic(cfg.clientId)
        }

        /** Giảm log Paho (NativeCrypto/ssl vẫn do hệ thống – chỉ ít hơn khi ít reconnect). */
        init {
            try {
                java.util.logging.Logger.getLogger("org.eclipse.paho.client.mqttv3").level =
                    java.util.logging.Level.OFF
            } catch (_: Exception) {
            }
        }

        /** Tối thiểu giữa hai lần TCP/TLS connect – tránh spam NativeCrypto trong logcat. */
        private const val BROKER_RECONNECT_MIN_MS = 4_000L

        /** Log UDP frame giống mqtt_protocol.cc (type/len/ts/seq + gap) – filter logcat: MQTT_UDP */
        private const val UDP_LOG_FIRST_N = 8
        private const val UDP_LOG_EVERY_N = 40L
        private const val UDP_GAP_LOG_INTERVAL_MS = 500L
        /** Hàng đợi RX – xử lý tuần tự theo thứ tự socket (tránh race scope.launch → gap giả + rè). */
        private const val UDP_RX_CHANNEL_CAPACITY = 256

        private fun readUint16Be(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

        private fun readUint32Be(data: ByteArray, offset: Int): Long =
            ((data[offset].toLong() and 0xff) shl 24) or
                ((data[offset + 1].toLong() and 0xff) shl 16) or
                ((data[offset + 2].toLong() and 0xff) shl 8) or
                (data[offset + 3].toLong() and 0xff)

        /** Header 16 byte: type|flags|payload_len|ssrc|timestamp|sequence (mqtt-udp.md / ESP32). */
        fun formatUdpAudioHeader(data: ByteArray, maxBytes: Int = 16): String {
            if (data.size < 16) return "short_pkt=${data.size}"
            val type = data[0].toInt() and 0xff
            val flags = data[1].toInt() and 0xff
            val payloadLen = readUint16Be(data, 2)
            val ssrc = readUint32Be(data, 4)
            val ts = readUint32Be(data, 8)
            val seq = readUint32Be(data, 12)
            val hex = data.take(maxBytes.coerceAtMost(data.size)).joinToString("") {
                "%02x".format(it)
            }
            return "type=0x%02x flags=0x%02x payload_len=%d ssrc=%d ts=%d seq=%d hdr16=%s".format(
                type, flags, payloadLen, ssrc, ts, seq, hex,
            )
        }
    }

    @Volatile
    private var udpTxCount = 0L
    @Volatile
    private var udpRxCount = 0L
    @Volatile
    private var lastUdpGapLogMs = 0L
    @Volatile
    private var lastUdpRxDropLogMs = 0L
    private val udpRxChannel = Channel<ByteArray>(UDP_RX_CHANNEL_CAPACITY)
    private var udpRxWorkerJob: Job? = null

    private fun shouldLogUdpFrame(count: Long): Boolean =
        count <= UDP_LOG_FIRST_N || count % UDP_LOG_EVERY_N == 0L

    private fun logUdpTx(header: ByteArray, opusLen: Int, encryptedTotal: Int) {
        val n = ++udpTxCount
        if (!shouldLogUdpFrame(n)) return
        Log.i(TAG, "MQTT_UDP TX #$n ${formatUdpAudioHeader(header)} opus=$opusLen udp_pkt=$encryptedTotal")
    }

    private fun logUdpRx(header: ByteArray, decryptedLen: Int, note: String = "") {
        val n = ++udpRxCount
        if (!shouldLogUdpFrame(n) && note.isEmpty()) return
        val suffix = if (note.isEmpty()) "" else " $note"
        Log.i(TAG, "MQTT_UDP RX #$n ${formatUdpAudioHeader(header)} opus=$decryptedLen$suffix")
    }

    private val connectMutex = Mutex()
    /** Tuần tự hóa dispatch JSON (tránh race với openAudioChannel). */
    private val inboundDispatchMutex = Mutex()
    private val publishLock = Any()
    /** Chỉ một openAudioChannel/hello tại một thời điểm (bootstrap + hey mini không đè helloDeferred). */
    private val openChannelMutex = Mutex()
    @Volatile
    private var lastBrokerConnectAttemptMs = 0L
    @Volatile
    private var lastConnectionLostLogMs = 0L
    @Volatile
    private var connectInFlight = false
    @Volatile
    private var suppressConnectionLostUntilMs = 0L
    private var brokerReconnectJob: Job? = null
    private var mqttClient: MqttAndroidClient? = null
    private var udpClient: UdpClient? = null
    private val channelMutex = Any()

    private var endpoint: String = resolveMqttEndpoint(mqttConfig.endpoint)
    private var clientId: String = mqttConfig.clientId
    private var username: String = mqttConfig.username
    private var password: String = mqttConfig.password
    private var publishTopic: String = mqttConfig.publishTopic
    private var subscribeTopic: String = resolveSubscribeTopic(mqttConfig)
    @Volatile
    private var subscribed = false
    /** Chỉ xử lý/publish sau SUBACK — tránh race subscribe + MCP publish (Paho không thread-safe). */
    @Volatile
    private var brokerReady = false
    private val pendingInbound = ConcurrentLinkedQueue<Pair<String, String>>()

    private lateinit var aesKey: SecretKeySpec
    private var aesNonce: ByteArray = ByteArray(16)
    private var localSequence: Long = 0
    private var remoteSequence: Long = 0
    private var udpTimestampBaseMs: Long = 0L

    private var helloDeferred: CompletableDeferred<Boolean>? = null
    @Volatile
    private var awaitingServerHello = false
    @Volatile
    private var lastHelloJson: String? = null
    /** Tránh gửi client hello hai lần (sau MCP + openAudioChannel). */
    @Volatile
    private var clientHelloPublishStarted = false

    /** Gọi trước start/openAudioChannel lần đầu — server gửi MCP initialize ngay sau SUBACK. */
    fun prepareBootstrapHandshake() {
        awaitingServerHello = true
        clientHelloPublishStarted = false
        lastHelloJson = buildClientHelloJson()
        if (helloDeferred == null || helloDeferred!!.isCompleted) {
            helloDeferred = CompletableDeferred()
        }
    }

    /** Gửi client hello một lần — sau khi đã trả MCP initialize (ESP32: initialize trước hello). */
    private suspend fun sendClientHelloOnce() {
        if (clientHelloPublishStarted) return
        if (isAudioChannelOpened()) return
        if (mqttClient?.isConnected != true || !brokerReady) {
            Log.w(TAG, "sendClientHelloOnce: broker chưa sẵn sàng")
            return
        }
        if (!awaitingServerHello) {
            awaitingServerHello = true
            lastHelloJson = buildClientHelloJson()
            if (helloDeferred == null || helloDeferred!!.isCompleted) {
                helloDeferred = CompletableDeferred()
            }
            Log.i(TAG, "sendClientHelloOnce: bật awaitingServerHello (chưa prepareBootstrapHandshake)")
        }
        val json = lastHelloJson ?: buildClientHelloJson().also { lastHelloJson = it }
        clientHelloPublishStarted = true
        if (helloDeferred == null || helloDeferred!!.isCompleted) {
            helloDeferred = CompletableDeferred()
        }
        Log.i(TAG, "MQTT → hello $json")
        // ESP32 Publish() mặc định QoS 0 — không block chờ PUBACK (tránh miss server hello).
        publishText(json, qos = 0, waitForPubAck = false)
    }

    /** Sau MCP initialize: drain tools/list (nếu server gửi) trước client hello. */
    private suspend fun awaitMcpBootstrapSettled(timeoutMs: Long = 2_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            drainPendingInbound()
            if (mqttClient?.isConnected != true || !brokerReady) break
            delay(80)
        }
    }

    private fun isBootstrapMcpPayload(payload: String): Boolean {
        if (isAudioChannelOpened()) return false
        return try {
            JSONObject(payload).optString("type") == "mcp"
        } catch (_: Exception) {
            false
        }
    }

    /** MCP bootstrap publish không được chạy trong inboundDispatchMutex (sendText block → không nhận server hello). */
    private suspend fun dispatchInboundOrMcpBootstrap(payload: String) {
        if (isBootstrapMcpPayload(payload)) {
            val json = JSONObject(payload)
            XiaozhiMcpResponder.handleIncomingMcp(json, this)
            return
        }
        if (!isAudioChannelOpened()) {
            try {
                Log.i(TAG, "MQTT bootstrap ← type=${JSONObject(payload).optString("type")} len=${payload.length}")
            } catch (_: Exception) {
            }
        }
        inboundDispatchMutex.withLock {
            dispatchInboundPayload(payload)
        }
    }

    override suspend fun start() {
        ensureBrokerConnected()
    }

    fun isBrokerConnected(): Boolean = mqttClient?.isConnected == true && brokerReady && subscribed

    /**
     * Đóng UDP + ngắt MQTT — gọi trước mỗi lần wake (luồng mới, không reuse broker cũ).
     * Không auto-reconnect nền; lần connect tiếp theo do hey mini / openAudioChannel chủ động gọi.
     */
    suspend fun teardownForNewWake() {
        brokerReconnectJob?.cancel()
        brokerReconnectJob = null
        suppressConnectionLostUntilMs = System.currentTimeMillis() + 15_000L
        awaitingServerHello = false
        clientHelloPublishStarted = false
        helloDeferred?.cancel()
        helloDeferred = null
        sessionId = ""
        synchronized(channelMutex) {
            stopUdpRxWorker()
            udpClient?.close()
            udpClient = null
        }
        connectMutex.withLock {
            brokerReady = false
            pendingInbound.clear()
            subscribed = false
            val c = mqttClient
            mqttClient = null
            try {
                c?.disconnect()
            } catch (_: Exception) {
            }
            delay(350)
        }
        XiaozhiMcpResponder.resetInitializeHandshake()
        Log.i(TAG, "MQTT teardownForNewWake – idle, chờ connect mới (wake)")
    }

    /** Connect + subscribe một lần (sau teardownForNewWake hoặc lần đầu). */
    suspend fun ensureBrokerConnected(): Boolean {
        val ready = connectMutex.withLock {
            if (mqttClient?.isConnected == true && brokerReady && subscribed) {
                return@withLock true
            }
            if (mqttClient?.isConnected == true && !brokerReady) {
                ensureSubscribed()
                return@withLock brokerReady && subscribed
            }
            connectBrokerLocked()
            mqttClient?.isConnected == true && brokerReady
        }
        if (ready) drainPendingInbound()
        return ready
    }

    /** Hey mini / openAudioChannel: chờ broker ổn định (subscribe xong) trước khi gửi hello. */
    suspend fun awaitBrokerReadyAndConnected(timeoutMs: Long = 12_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (mqttClient?.isConnected == true && brokerReady && subscribed) {
                drainPendingInbound()
                return true
            }
            val progressed = connectMutex.withLock {
                if (mqttClient?.isConnected != true) {
                    connectBrokerLocked()
                } else if (!brokerReady) {
                    ensureSubscribed()
                }
                mqttClient?.isConnected == true && brokerReady && subscribed
            }
            if (progressed) {
                drainPendingInbound()
                return true
            }
            delay(400)
        }
        return false
    }

    private fun disconnectQuietly() {
        brokerReady = false
        pendingInbound.clear()
        subscribed = false
        val c = mqttClient
        mqttClient = null
        try {
            c?.disconnect()
        } catch (_: Exception) {
        }
    }

    private fun ensureMqttCallback(client: MqttAndroidClient) {
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.i(TAG, "MQTT connectComplete reconnect=$reconnect")
            }

            override fun connectionLost(cause: Throwable?) {
                val now = System.currentTimeMillis()
                if (now < suppressConnectionLostUntilMs) {
                    Log.d(TAG, "MQTT connectionLost suppressed (wake/open channel): ${cause?.message}")
                    return
                }
                brokerReady = false
                pendingInbound.clear()
                subscribed = false
                if (now - lastConnectionLostLogMs >= 5_000) {
                    lastConnectionLostLogMs = now
                    Log.w(
                        TAG,
                        "MQTT connectionLost: ${cause?.message} – reconnect sau ${BROKER_RECONNECT_MIN_MS}ms",
                    )
                }
                clientHelloPublishStarted = false
                helloDeferred?.complete(false)
                XiaozhiMcpResponder.resetInitializeHandshake()
                Log.i(TAG, "MQTT connectionLost – không auto-reconnect (nói hey mini để mở luồng mới)")
            }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    val payload = String(message.payload)
                    scope.launch {
                        if (!brokerReady) {
                            inboundDispatchMutex.withLock {
                                if (!brokerReady) {
                                    pendingInbound.add(topic to payload)
                                    Log.d(TAG, "MQTT queue (chờ subscribe): $topic len=${payload.length}")
                                }
                            }
                            return@launch
                        }
                        Log.d(TAG, "MQTT ← $topic len=${payload.length}")
                        dispatchInboundOrMcpBootstrap(payload)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
    }

    private suspend fun connectBrokerLocked() {
        if (endpoint.isEmpty()) {
            Log.e(TAG, "MQTT endpoint empty")
            return
        }

        if (mqttClient?.isConnected == true) {
            ensureSubscribed()
            return
        }
        brokerReady = false

        if (connectInFlight) {
            repeat(25) {
                delay(200)
                if (mqttClient?.isConnected == true) {
                    ensureSubscribed()
                    return
                }
            }
            return
        }

        val now = System.currentTimeMillis()
        if (mqttClient != null && now - lastBrokerConnectAttemptMs < BROKER_RECONNECT_MIN_MS) {
            val wait = BROKER_RECONNECT_MIN_MS - (now - lastBrokerConnectAttemptMs)
            Log.d(TAG, "MQTT connect throttle ${wait}ms")
            delay(wait.coerceAtLeast(200L))
        }

        connectInFlight = true
        lastBrokerConnectAttemptMs = now
        clientHelloPublishStarted = false
        XiaozhiMcpResponder.resetInitializeHandshake()
        try {
            val existing = mqttClient
            if (existing != null && !existing.isConnected) {
                try {
                    existing.disconnect()
                } catch (_: Exception) {
                }
                subscribed = false
                delay(400)
            }

            if (mqttClient == null) {
                Log.i(
                    TAG,
                    "MQTT connect uri=$endpoint clientId=$clientId publish=$publishTopic subscribe=$subscribeTopic",
                )
                mqttClient = MqttAndroidClient(context.applicationContext, endpoint, clientId)
                ensureMqttCallback(mqttClient!!)
            }

            val options = MqttConnectOptions().apply {
                keepAliveInterval = 240
                // Tắt auto-reconnect Paho: tránh connect() song song → broker đá → SSL log liên tục.
                isAutomaticReconnect = false
                isCleanSession = true
                userName = username
                password = this@MqttProtocol.password.toCharArray()
            }

            mqttClient?.connect(options)?.waitForCompletion(30_000)
            Log.i(TAG, "MQTT connected")
            delay(300)
            ensureSubscribed()
        } catch (e: MqttException) {
            Log.e(TAG, "MQTT connect failed: ${e.message} – thử lại khi wake/openAudioChannel")
            brokerReady = false
            subscribed = false
        } finally {
            connectInFlight = false
        }
    }

    private suspend fun ensureSubscribed() {
        if (subscribed && brokerReady) return
        if (subscribeTopic.isEmpty() || mqttClient?.isConnected != true) return
        for (attempt in 0..1) {
            try {
                mqttClient?.subscribe(subscribeTopic, 0)?.waitForCompletion(15_000)
                subscribed = true
                brokerReady = true
                Log.i(TAG, "MQTT subscribed: $subscribeTopic")
                drainPendingInbound()
                return
            } catch (e: Exception) {
                brokerReady = false
                subscribed = false
                Log.e(TAG, "MQTT subscribe failed (attempt ${attempt + 1}): ${e.message}")
                if (attempt == 0 && mqttClient?.isConnected == true) {
                    delay(400)
                } else {
                    Log.e(TAG, "MQTT subscribe failed – thử lại khi wake/openAudioChannel")
                    return
                }
            }
        }
    }

    private suspend fun dispatchInboundPayload(payload: String) {
        try {
            val json = JSONObject(payload)
            when (json.optString("type")) {
                "hello" -> parseServerHello(json)
                "goodbye" -> {
                    val sid = json.optString("session_id")
                    Log.i(TAG, "MQTT ← goodbye session_id=$sid current=$sessionId")
                    if (sid.isNotEmpty() && sid != sessionId) return
                    if (shouldDeferGoodbye()) {
                        Log.w(TAG, "MQTT goodbye deferred – TTS/greeting đang phát (giữ buffer + chờ reopen)")
                        return
                    }
                    closeAudioChannel()
                }
                "mcp" -> incomingJsonFlow.emit(json)
                else -> incomingJsonFlow.emit(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse: ${e.message}")
        }
    }

    private suspend fun drainPendingInbound() {
        while (true) {
            val item = pendingInbound.poll() ?: break
            Log.d(TAG, "MQTT drain queued: ${item.first} len=${item.second.length}")
            dispatchInboundOrMcpBootstrap(item.second)
        }
    }

    override suspend fun sendAudio(data: ByteArray) {
        synchronized(channelMutex) {
            if (udpClient == null) {
                if (udpTxCount == 0L || udpTxCount % 200 == 0L) {
                    Log.w(TAG, "MQTT_UDP TX skip: udpClient=null (kênh chưa mở)")
                }
                return
            }
            // Định dạng khớp mqtt_protocol.cc SendAudio: htons(len), htonl(ts), htonl(seq)
            val nonce = aesNonce.copyOf().apply {
                val size = data.size
                this[2] = (size shr 8).toByte()
                this[3] = size.toByte()
                val ts = ((System.currentTimeMillis() - udpTimestampBaseMs) and 0xFFFFFFFFL).toInt()
                this[8] = (ts shr 24).toByte()
                this[9] = (ts shr 16).toByte()
                this[10] = (ts shr 8).toByte()
                this[11] = ts.toByte()
                val seq = (++localSequence).toInt()
                this[12] = (seq shr 24).toByte()
                this[13] = (seq shr 16).toByte()
                this[14] = (seq shr 8).toByte()
                this[15] = seq.toByte()
            }
            val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, aesKey, IvParameterSpec(nonce))
            }
            val encrypted = cipher.doFinal(data)
            val packet = nonce + encrypted
            logUdpTx(nonce, data.size, packet.size)
            udpClient?.send(packet)
        }
    }

    private fun buildClientHelloJson(): String {
        val helloMessage = JSONObject().apply {
            put("type", "hello")
            put("version", 3)
            put("transport", "udp")
            put("features", JSONObject().apply { put("mcp", true) })
            put("audio_params", JSONObject().apply {
                put("format", "opus")
                put("sample_rate", 16000)
                put("channels", 1)
                put("frame_duration", OPUS_FRAME_DURATION_MS)
            })
        }
        return helloMessage.toString()
    }

    override suspend fun openAudioChannel(): OpenChannelResult = openChannelMutex.withLock {
        withContext(Dispatchers.IO) {
        if (udpClient != null) {
            return@withContext OpenChannelResult(success = true, didOpen = false)
        }
        if (!awaitBrokerReadyAndConnected(15_000)) {
            Log.e(TAG, "openAudioChannel: broker chưa subscribe / mất kết nối")
            return@withContext OpenChannelResult(success = false, didOpen = false)
        }

        sessionId = ""
        awaitingServerHello = true
        lastHelloJson = buildClientHelloJson()
        try {
            drainPendingInbound()
            if (!XiaozhiMcpResponder.isInitializeResponded()) {
                Log.i(TAG, "openAudioChannel: chờ MCP initialize trước client hello")
                XiaozhiMcpResponder.awaitInitializeResponded(8_000)
            }
            awaitMcpBootstrapSettled()
            delay(300)
            var channelResult = OpenChannelResult(success = false, didOpen = false)
            for (attempt in 0..1) {
                if (helloDeferred == null || helloDeferred!!.isCompleted) {
                    helloDeferred = CompletableDeferred()
                }
                if (!clientHelloPublishStarted) {
                    sendClientHelloOnce()
                } else {
                    Log.i(TAG, "MQTT hello đã gửi – chờ server hello (attempt ${attempt + 1})")
                }
                val helloOk: Boolean? = try {
                    withTimeout(12_000) {
                        val ok = helloDeferred!!.await()
                        if (!ok) {
                            Log.e(TAG, "Server hello rejected")
                            false
                        } else {
                            Log.i(
                                TAG,
                                "openAudioChannel OK session=$sessionId udp=${udpClient != null} " +
                                    "mcp=${XiaozhiMcpResponder.isInitializeResponded()}",
                            )
                            true
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    if (attempt == 0 && mqttClient?.isConnected == true) {
                        Log.w(TAG, "Server hello timeout 12s – thử gửi hello lần 2")
                        clientHelloPublishStarted = false
                        delay(400)
                        null
                    } else {
                        Log.e(TAG, "Server hello timeout 12s (attempt ${attempt + 1})")
                        networkErrorFlow.emit("Server timeout")
                        false
                    }
                }
                when (helloOk) {
                    true -> {
                        channelResult = OpenChannelResult(success = true, didOpen = true)
                        break
                    }
                    false -> {
                        channelResult = OpenChannelResult(success = false, didOpen = false)
                        break
                    }
                    null -> Unit
                }
            }
            return@withContext channelResult
        } finally {
            awaitingServerHello = false
        }
        }
    }

    override fun closeAudioChannel() {
        synchronized(channelMutex) {
            stopUdpRxWorker()
            udpClient?.close()
            udpClient = null
        }
        if (!awaitingServerHello) {
            helloDeferred?.cancel()
        }
        scope.launch {
            if (sessionId.isNotEmpty()) {
                sendText(JSONObject().apply {
                    put("session_id", sessionId)
                    put("type", "goodbye")
                }.toString())
            }
            audioChannelStateFlow.emit(AudioState.CLOSED)
        }
    }

    override fun isAudioChannelOpened(): Boolean = udpClient != null

    override suspend fun sendText(text: String) {
        publishText(text, waitForPubAck = true)
    }

    /** Hello / MCP bootstrap: QoS 0 giống ESP32, không block PUBACK. */
    suspend fun sendTextHandshake(text: String) {
        publishText(text, qos = 0, waitForPubAck = false)
    }

    private suspend fun publishText(text: String, qos: Int = 0, waitForPubAck: Boolean = false) {
        if (publishTopic.isEmpty()) return
        if (mqttClient?.isConnected != true || !brokerReady) {
            Log.w(TAG, "sendText: broker chưa sẵn sàng (connected=${mqttClient?.isConnected} ready=$brokerReady)")
            return
        }
        synchronized(publishLock) {
            try {
                val token = mqttClient?.publish(publishTopic, text.toByteArray(), qos, false)
                if (waitForPubAck) {
                    token?.waitForCompletion(8_000)
                } else {
                    Unit
                }
            } catch (e: MqttException) {
                Log.e(TAG, "Failed to publish: ${e.message}", e)
                scope.launch { networkErrorFlow.emit("Server error") }
            }
        }
    }

    private suspend fun parseServerHello(json: JSONObject) {
        if (json.optString("transport") != "udp") {
            helloDeferred?.complete(false)
            return
        }
        sessionId = json.optString("session_id")
        Log.i(TAG, "Server hello session_id=$sessionId")
        MiniRobotActionInvoker.ingestVisionFromServerHello(json)

        val udp = json.optJSONObject("udp") ?: run {
            helloDeferred?.complete(false)
            return
        }
        aesKey = SecretKeySpec(decodeHexString(udp.optString("key")), "AES")
        aesNonce = decodeHexString(udp.optString("nonce"))
        localSequence = 0
        remoteSequence = 0
        udpTxCount = 0
        udpRxCount = 0
        lastUdpGapLogMs = 0L
        udpTimestampBaseMs = System.currentTimeMillis()

        val ap = json.optJSONObject("audio_params")
        Log.i(
            TAG,
            "MQTT_UDP open session=$sessionId ${udp.optString("server")}:${udp.optInt("port")} " +
                "server_audio format=${ap?.optString("format")} rate=${ap?.optInt("sample_rate")} " +
                "ch=${ap?.optInt("channels")} frame_ms=${ap?.optInt("frame_duration")} " +
                "template=${formatUdpAudioHeader(aesNonce)}",
        )

        synchronized(channelMutex) {
            stopUdpRxWorker()
            udpClient?.close()
            udpClient = UdpClient(udp.optString("server"), udp.optInt("port")).apply {
                setOnMessage { data -> enqueueUdpAudioPacket(data) }
            }
            startUdpRxWorker()
        }
        Log.i(TAG, "UDP → ${udp.optString("server")}:${udp.optInt("port")}")
        audioChannelStateFlow.emit(AudioState.OPENED)
        helloDeferred?.complete(true)
    }

    private fun stopUdpRxWorker() {
        udpRxWorkerJob?.cancel()
        udpRxWorkerJob = null
        while (udpRxChannel.tryReceive().isSuccess) {
            // drain stale packets between sessions
        }
    }

    private fun startUdpRxWorker() {
        udpRxWorkerJob = scope.launch {
            for (data in udpRxChannel) {
                try {
                    processUdpAudioPacketOrdered(data)
                } catch (e: Exception) {
                    Log.e(TAG, "MQTT_UDP RX process error: ${e.message}", e)
                }
            }
        }
    }

    /** Gọi từ luồng UdpClient.receive – chỉ enqueue, không decrypt (tránh xử lý song song). */
    private fun enqueueUdpAudioPacket(data: ByteArray) {
        val result = udpRxChannel.trySend(data)
        if (result.isSuccess) return
        val now = System.currentTimeMillis()
        if (now - lastUdpRxDropLogMs >= UDP_GAP_LOG_INTERVAL_MS) {
            lastUdpRxDropLogMs = now
            Log.w(TAG, "MQTT_UDP RX queue full (cap=$UDP_RX_CHANNEL_CAPACITY) – drop packet")
        }
    }

    /** Tuần tự theo thứ tự nhận socket → seq đúng, giảm rè do decode lệch frame. */
    private suspend fun processUdpAudioPacketOrdered(data: ByteArray) {
        if (data.size < aesNonce.size) {
            Log.w(TAG, "MQTT_UDP RX invalid size=${data.size} (need >=16)")
            return
        }
        val type = data[0].toInt() and 0xff
        if (type != 0x01) {
            Log.e(TAG, "MQTT_UDP RX invalid type=0x${type.toString(16)} ${formatUdpAudioHeader(data)}")
            return
        }
        val headerLen = readUint16Be(data, 2)
        val sequence = readUint32Be(data, 12)
        if (sequence < remoteSequence) {
            Log.w(
                TAG,
                "MQTT_UDP RX drop old seq=$sequence last=$remoteSequence ${formatUdpAudioHeader(data)}",
            )
            return
        }
        if (remoteSequence != 0L && sequence != remoteSequence + 1) {
            val expected = remoteSequence + 1
            val missing = sequence - expected
            val now = System.currentTimeMillis()
            if (now - lastUdpGapLogMs >= UDP_GAP_LOG_INTERVAL_MS) {
                lastUdpGapLogMs = now
                Log.w(
                    TAG,
                    "MQTT_UDP RX gap: got seq=$sequence expected=$expected ($missing frame(s) missing) " +
                        formatUdpAudioHeader(data),
                )
            }
        }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, aesKey, IvParameterSpec(data.copyOfRange(0, aesNonce.size)))
        }
        val payloadLen = data.size - aesNonce.size
        val decrypted = cipher.doFinal(data, aesNonce.size, payloadLen)
        if (headerLen != decrypted.size && headerLen != payloadLen) {
            Log.w(
                TAG,
                "MQTT_UDP RX len mismatch hdr.payload_len=$headerLen pkt_payload=$payloadLen opus=${decrypted.size}",
            )
        }
        logUdpRx(data, decrypted.size)
        if (!incomingAudioFlow.tryEmit(decrypted)) {
            val now = System.currentTimeMillis()
            if (now - lastUdpRxDropLogMs >= UDP_GAP_LOG_INTERVAL_MS) {
                lastUdpRxDropLogMs = now
                Log.w(TAG, "MQTT_UDP RX decode OK nhưng incomingAudioFlow đầy – drop opus")
            }
        }
        remoteSequence = sequence
    }

    private fun decodeHexString(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    override fun dispose() {
        brokerReconnectJob?.cancel()
        scope.cancel()
        helloDeferred?.cancel()
        disconnectQuietly()
        stopUdpRxWorker()
        synchronized(channelMutex) {
            udpClient?.close()
            udpClient = null
        }
    }
}

private class UdpClient(private val server: String, private val port: Int) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var isRunning = false
    private var onMessage: ((ByteArray) -> Unit)? = null

    init {
        try {
            serverAddress = InetAddress.getByName(server)
            socket = DatagramSocket().apply {
                soTimeout = 0
                receiveBufferSize = receiveBufferSize.coerceAtLeast(512 * 1024)
            }
            isRunning = true
            scope.launch {
                while (isRunning && socket != null) {
                    try {
                        val buf = ByteArray(65535)
                        val pkt = DatagramPacket(buf, buf.size)
                        socket?.receive(pkt)
                        onMessage?.invoke(pkt.data.copyOf(pkt.length))
                    } catch (_: Exception) {
                        delay(100)
                    }
                }
            }
        } catch (_: Exception) {
            close()
        }
    }

    fun send(data: ByteArray) {
        if (!isRunning || socket == null || serverAddress == null) return
        scope.launch {
            try {
                socket?.send(DatagramPacket(data, data.size, serverAddress, port))
            } catch (_: Exception) {
            }
        }
    }

    fun setOnMessage(callback: (ByteArray) -> Unit) {
        onMessage = callback
    }

    fun close() {
        isRunning = false
        scope.cancel()
        socket?.close()
        socket = null
    }
}
