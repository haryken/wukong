package info.dourok.voicebot

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Phát TTS: buffer AudioTrack ×4, pre-buffer ~100ms (giảm giật), CONTENT_TYPE_SPEECH, volume 0.85.
 *
 * @param audioSessionId Shared với AudioRecord (DemoRecognizer) để AEC có reference TTS; 0 = mặc định.
 */
class OpusStreamPlayer(
    private val sampleRate: Int,
    private val channels: Int,
    frameSizeMs: Int,
    private val audioSessionId: Int = 0
) {
    companion object {
        private const val TAG = "OpusStreamPlayer"
        private const val TRACK_BUFFER_MULTIPLIER = 4
        private const val PREBUFFER_MS = 100
        private const val WRITE_CHUNK_MS = 30
        private const val PLAYBACK_VOLUME = 0.85f
    }

    private var audioTrack: AudioTrack
    private val playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var collectJob: Job? = null
    @Volatile
    private var released = false
    private var isPlaying = false

    private val bytesPerMs = sampleRate * channels * 2 / 1000
    private val prebufferBytes = (bytesPerMs * PREBUFFER_MS).coerceAtLeast(960)
    private val writeChunkBytes = (bytesPerMs * WRITE_CHUNK_MS).coerceAtLeast(320)

    private val jitterLock = Any()
    private val pendingPcm = ByteArrayOutputStream(prebufferBytes * 2)
    private var playbackStarted = false

    init {
        val channelConfig =
            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBuf * TRACK_BUFFER_MULTIPLIER

        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (audioSessionId != 0) {
            builder.setSessionId(audioSessionId)
        }
        audioTrack = builder.build()
        Log.i(
            TAG,
            "init rate=$sampleRate buffer=$bufferSize prebuffer=${prebufferBytes}B chunk=${writeChunkBytes}B"
        )
    }

    fun start(pcmFlow: Flow<ByteArray?>) {
        if (released || isPlaying) return
        isPlaying = true
        collectJob = playerScope.launch {
            try {
                pcmFlow.collect { pcmData ->
                    if (released) return@collect
                    pcmData?.let { appendPcm(it) }
                }
            } finally {
                if (!released) flushPending()
            }
        }
    }

    private fun appendPcm(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        synchronized(jitterLock) {
            pendingPcm.write(pcm)
            if (!playbackStarted && pendingPcm.size() < prebufferBytes) {
                return
            }
            playbackStarted = true
            drainPendingLocked()
        }
    }

    private fun drainPendingLocked() {
        ensureTrackPlaying()
        var buf = pendingPcm.toByteArray()
        pendingPcm.reset()
        var offset = 0
        while (offset + writeChunkBytes <= buf.size) {
            writeToTrack(buf, offset, writeChunkBytes)
            offset += writeChunkBytes
        }
        if (offset < buf.size) {
            pendingPcm.write(buf, offset, buf.size - offset)
        }
    }

    private fun flushPending() {
        synchronized(jitterLock) {
            if (pendingPcm.size() == 0) return
            playbackStarted = true
            ensureTrackPlaying()
            val rest = pendingPcm.toByteArray()
            pendingPcm.reset()
            writeToTrack(rest, 0, rest.size)
        }
    }

    private fun ensureTrackPlaying() {
        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) return
        if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack.play()
            audioTrack.setStereoVolume(PLAYBACK_VOLUME, PLAYBACK_VOLUME)
        }
    }

    private fun writeToTrack(buf: ByteArray, offset: Int, size: Int) {
        if (released) return
        try {
            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) return
            var written = 0
            while (written < size) {
                val n = audioTrack.write(buf, offset + written, size - written)
                if (n <= 0) break
                written += n
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to AudioTrack", e)
        }
    }

    /**
     * Dừng phát ngay (wake / interrupt) nhưng giữ job collect — TTS chào sau wake vẫn phát được.
     * Khác [stop]: không set isPlaying=false (tránh hỏng luồng phát liên tục).
     */
    fun interruptPlayback() {
        synchronized(jitterLock) {
            pendingPcm.reset()
            playbackStarted = false
        }
        try {
            if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.pause()
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    audioTrack.flush()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "interruptPlayback", e)
        }
    }

    /** Sau interrupt/stop: sẵn sàng nhận TTS UDP (wake → chào). */
    fun prepareForIncomingTts() {
        synchronized(jitterLock) {
            pendingPcm.reset()
            playbackStarted = false
        }
        try {
            when (audioTrack.state) {
                AudioTrack.STATE_INITIALIZED -> {
                    if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.play()
                    }
                    audioTrack.setStereoVolume(PLAYBACK_VOLUME, PLAYBACK_VOLUME)
                }
                else -> Log.w(TAG, "prepareForIncomingTts: AudioTrack state=${audioTrack.state} (cần INITIALIZED)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "prepareForIncomingTts", e)
        }
    }

    fun stop() {
        if (isPlaying) {
            isPlaying = false
            synchronized(jitterLock) {
                pendingPcm.reset()
                playbackStarted = false
            }
            if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack.stop()
            }
        }
    }

    /** Hủy coroutine phát + AudioTrack (dispose session). */
    fun shutdown() {
        if (released) return
        released = true
        isPlaying = false
        collectJob?.cancel()
        collectJob = null
        synchronized(jitterLock) {
            pendingPcm.reset()
            playbackStarted = false
        }
        try {
            if (audioTrack.state == AudioTrack.STATE_INITIALIZED
                && audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING
            ) {
                audioTrack.stop()
            }
            audioTrack.release()
        } catch (e: Exception) {
            Log.w(TAG, "shutdown AudioTrack", e)
        }
        playerScope.cancel()
    }

    fun release() = shutdown()

    /**
     * Chờ phát hết TTS.
     * Không thoát ngay khi AudioTrack chưa PLAYING — đợi buffer pending rỗng + head ổn định.
     */
    suspend fun waitForPlaybackCompletion() {
        val deadlineMs = System.currentTimeMillis() + 25_000L
        var sawAudio = false
        var headAtDrain = 0
        var headStableMs = 0L
        while (System.currentTimeMillis() < deadlineMs) {
            val pending = synchronized(jitterLock) { pendingPcm.size() }
            val playing = audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING
            if (pending > 0 || playing) {
                sawAudio = true
            }
            if (!sawAudio) {
                delay(50)
                continue
            }
            if (pending == 0 && !playing) {
                break
            }
            // MODE_STREAM: track vẫn PLAYING sau khi hết PCM — head không đổi ~1s = phát xong.
            if (pending == 0 && playing) {
                val head = audioTrack.playbackHeadPosition
                val now = System.currentTimeMillis()
                if (head == headAtDrain) {
                    if (headStableMs == 0L) headStableMs = now
                    if (now - headStableMs >= 900L) {
                        Log.d(TAG, "waitForPlaybackCompletion: head ổn ${now - headStableMs}ms, coi như xong")
                        break
                    }
                } else {
                    headAtDrain = head
                    headStableMs = now
                }
            } else {
                headStableMs = 0L
            }
            delay(50)
        }
        synchronized(jitterLock) {
            if (pendingPcm.size() > 0) {
                flushPending()
            }
        }
        var position = 0
        var stableCount = 0
        val requiredStableChecks = 6
        while (System.currentTimeMillis() < deadlineMs && stableCount < requiredStableChecks) {
            val pending = synchronized(jitterLock) { pendingPcm.size() }
            if (pending > 0) {
                stableCount = 0
                delay(50)
                continue
            }
            if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                stableCount++
                delay(80)
                continue
            }
            val head = audioTrack.playbackHeadPosition
            if (head == position) {
                stableCount++
            } else {
                stableCount = 0
                position = head
            }
            delay(100)
        }
        Log.i(TAG, "waitForPlaybackCompletion done (sawAudio=$sawAudio)")
    }

    protected fun finalize() {
        release()
    }
}
