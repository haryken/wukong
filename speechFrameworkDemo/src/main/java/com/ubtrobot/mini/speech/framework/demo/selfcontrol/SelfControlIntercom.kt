package com.ubtrobot.mini.speech.framework.demo.selfcontrol

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Đàm thoại 2 chiều Self-Control (PCM 16kHz mono):
 * - Robot mic → phone (trừ khi robotTxMuted)
 * - Phone mic → robot loa (trừ khi phoneTxMuted)
 *
 * WebSocket binary = PCM; text JSON = mute / ping.
 */
object SelfControlIntercom {
    private const val TAG = "SelfControlIntercom"
    const val SAMPLE_RATE = 16_000
    private const val FRAME_SAMPLES = 320 // 20ms
    private const val FRAME_BYTES = FRAME_SAMPLES * 2

    @Volatile private var appContext: Context? = null
    @Volatile var robotTxMuted: Boolean = false
    @Volatile var phoneTxMuted: Boolean = false

    private val clients = CopyOnWriteArrayList<SelfControlWebSocket>()
    private val pool = Executors.newCachedThreadPool()
    private val micRunning = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val trackLock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun statusJson(): JSONObject = JSONObject().apply {
        put("success", true)
        put("clients", clients.size)
        put("robot_tx_muted", robotTxMuted)
        put("phone_tx_muted", phoneTxMuted)
        put("sample_rate", SAMPLE_RATE)
    }

    fun setMutes(robotTx: Boolean?, phoneTx: Boolean?) {
        if (robotTx != null) robotTxMuted = robotTx
        if (phoneTx != null) phoneTxMuted = phoneTx
        broadcastText(
            JSONObject()
                .put("type", "mute")
                .put("robot_tx_muted", robotTxMuted)
                .put("phone_tx_muted", phoneTxMuted)
                .toString()
        )
        Log.i(TAG, "mute robotTx=$robotTxMuted phoneTx=$phoneTxMuted")
    }

    fun handleWebSocket(sock: Socket, secKey: String) {
        val ws = try {
            SelfControlWebSocket.accept(sock, secKey)
        } catch (e: Exception) {
            Log.w(TAG, "ws accept: ${e.message}")
            try {
                sock.close()
            } catch (_: Exception) {
            }
            return
        }
        clients.add(ws)
        ensureMicStarted()
        ensureTrack()
        try {
            ws.sendText(
                JSONObject()
                    .put("type", "hello")
                    .put("role", "robot")
                    .put("robot_tx_muted", robotTxMuted)
                    .put("phone_tx_muted", phoneTxMuted)
                    .put("sample_rate", SAMPLE_RATE)
                    .toString()
            )
            ws.readLoop(
                onText = { text -> handleText(text) },
                onBinary = { pcm -> onPhonePcm(pcm) }
            )
        } finally {
            clients.remove(ws)
            ws.close()
            if (clients.isEmpty()) {
                stopMic()
            }
        }
    }

    /** Tunnel / relay cũng đẩy PCM phone → robot. */
    fun onPhonePcm(pcm: ByteArray) {
        if (phoneTxMuted || pcm.isEmpty()) return
        playPcm(pcm)
    }

    /** Tunnel đẩy mute JSON. */
    fun handleText(text: String) {
        try {
            val o = JSONObject(text)
            when (o.optString("type")) {
                "mute" -> setMutes(
                    if (o.has("robot_tx_muted")) o.optBoolean("robot_tx_muted") else null,
                    if (o.has("phone_tx_muted")) o.optBoolean("phone_tx_muted") else null
                )
                "ping" -> {}
            }
        } catch (_: Exception) {
        }
    }

    fun broadcastPcmFromRobot(pcm: ByteArray) {
        if (robotTxMuted || pcm.isEmpty()) return
        for (c in clients) {
            try {
                if (c.isOpen()) c.sendBinary(pcm)
            } catch (_: Exception) {
            }
        }
    }

    fun broadcastText(text: String) {
        for (c in clients) {
            try {
                if (c.isOpen()) c.sendText(text)
            } catch (_: Exception) {
            }
        }
    }

    private fun ensureMicStarted() {
        if (!micRunning.compareAndSet(false, true)) return
        pool.execute {
            var record: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = maxOf(min, FRAME_BYTES * 8)
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord not init")
                    micRunning.set(false)
                    return@execute
                }
                audioRecord = record
                record.startRecording()
                Log.i(TAG, "intercom mic started")
                val buf = ByteArray(FRAME_BYTES)
                while (micRunning.get() && clients.isNotEmpty()) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        val frame = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                        broadcastPcmFromRobot(frame)
                        SelfControlTunnelBridge.forwardRobotPcm(frame)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "mic loop: ${e.message}")
            } finally {
                try {
                    record?.stop()
                } catch (_: Exception) {
                }
                try {
                    record?.release()
                } catch (_: Exception) {
                }
                audioRecord = null
                micRunning.set(false)
                Log.i(TAG, "intercom mic stopped")
            }
        }
    }

    private fun stopMic() {
        micRunning.set(false)
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
    }

    private fun ensureTrack() {
        synchronized(trackLock) {
            if (audioTrack != null) return
            val min = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, FRAME_BYTES * 8),
                AudioTrack.MODE_STREAM
            )
            track.play()
            audioTrack = track
            Log.i(TAG, "intercom speaker started")
        }
    }

    private fun playPcm(pcm: ByteArray) {
        synchronized(trackLock) {
            ensureTrack()
            try {
                audioTrack?.write(pcm, 0, pcm.size)
            } catch (e: Exception) {
                Log.d(TAG, "play: ${e.message}")
            }
        }
    }
}
