package info.dourok.voicebot.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject

enum class AbortReason { WAKE_WORD_DETECTED, NONE }
enum class ListeningMode { ALWAYS_ON, AUTO_STOP, MANUAL }
enum class AudioState { OPENED, CLOSED }

/** Kết quả open: success = kênh đang mở, didOpen = chính lần gọi này vừa mở (để chỉ gửi listen 1 lần). */
data class OpenChannelResult(val success: Boolean, val didOpen: Boolean)

abstract class Protocol {
    protected var sessionId: String = ""
    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val incomingAudioFlow = MutableSharedFlow<ByteArray>()
    val incomingJsonFlow = MutableSharedFlow<JSONObject>()
    val audioChannelStateFlow = MutableSharedFlow<AudioState>()
    val networkErrorFlow = MutableSharedFlow<String>()

    abstract suspend fun start()
    abstract suspend fun sendAudio(data: ByteArray)
    abstract suspend fun openAudioChannel(): OpenChannelResult
    abstract fun closeAudioChannel()
    abstract fun isAudioChannelOpened(): Boolean
    abstract suspend fun sendText(text: String)

    open suspend fun sendTextReliable(text: String): Boolean {
        sendText(text)
        return true
    }

    /** Session sau server hello — bọc ngoài tin MCP (tránh tên getSessionId: trùng JVM với property [sessionId]). */
    fun currentSessionIdForMcp(): String = sessionId

    suspend fun sendAbortSpeaking(reason: AbortReason) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "abort")
            if (reason == AbortReason.WAKE_WORD_DETECTED) put("reason", "wake_word_detected")
        }
        sendText(json.toString())
    }

    suspend fun sendWakeWordDetected(wakeWord: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "detect")
            put("text", wakeWord)
        }
        sendText(json.toString())
    }

    suspend fun sendStartListening(mode: ListeningMode) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "start")
            put("mode", when (mode) {
                ListeningMode.ALWAYS_ON -> "realtime"
                ListeningMode.AUTO_STOP -> "auto"
                ListeningMode.MANUAL -> "manual"
            })
        }
        sendText(json.toString())
    }

    suspend fun sendStopListening() {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "stop")
        }
        sendText(json.toString())
    }

    suspend fun sendIotDescriptors(descriptors: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "iot")
            put("descriptors", JSONObject(descriptors))
        }
        sendText(json.toString())
    }

    suspend fun sendIotStates(states: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "iot")
            put("states", JSONObject(states))
        }
        sendText(json.toString())
    }

    abstract fun dispose()
}

