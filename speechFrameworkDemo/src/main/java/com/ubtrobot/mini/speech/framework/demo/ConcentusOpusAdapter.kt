package com.ubtrobot.mini.speech.framework.demo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "ConcentusOpus"

/**
 * Opus encoder using Concentus (pure Java, no libapp.so).
 * 16 kHz, mono, 60 ms frames = 960 samples = 1920 bytes input.
 */
class ConcentusOpusEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    frameSizeMs: Int
) : IOpusEncoder {

    private val frameSamples = (sampleRate * frameSizeMs / 1000) * channels
    private val frameBytes = frameSamples * 2

    private val encoder = try {
        org.concentus.OpusEncoder(
            sampleRate,
            channels,
            org.concentus.OpusApplication.OPUS_APPLICATION_VOIP
        ).apply {
            setBitrate(64000)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Concentus encoder init failed", e)
        throw e
    }

    override suspend fun encode(pcmData: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        if (pcmData.size != frameBytes) return@withContext null
        val shorts = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shortArray = ShortArray(frameSamples)
        shorts.get(shortArray)
        val outBuf = ByteArray(1024)
        val len = try {
            encoder.encode(shortArray, 0, frameSamples, outBuf, 0, outBuf.size)
        } catch (e: Exception) {
            Log.e(TAG, "Concentus encode error", e)
            return@withContext null
        }
        if (len > 0) outBuf.copyOf(len) else null
    }

    override fun reset() {
        try {
            val hasReset = encoder.javaClass.methods.any { m: Method -> m.name == "reset" }
            if (hasReset) {
                encoder.javaClass.getMethod("reset").invoke(encoder)
                Log.i(TAG, "Concentus encoder reset (sau dance/reopen WS)")
            }
        } catch (_: Exception) { }
    }

    override fun release() {
        reset()
    }
}

/**
 * Opus decoder using Concentus (pure Java, no libapp.so).
 */
class ConcentusOpusDecoder(
    private val sampleRate: Int,
    private val channels: Int,
    frameSizeMs: Int
) : IOpusDecoder {

    private val frameSamples = (sampleRate * frameSizeMs / 1000) * channels

    private val decoder = try {
        org.concentus.OpusDecoder(sampleRate, channels)
    } catch (e: Exception) {
        Log.e(TAG, "Concentus decoder init failed", e)
        throw e
    }

    override suspend fun decode(opusData: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        if (opusData.isEmpty()) return@withContext null
        val outShorts = ShortArray(frameSamples)
        val nSamples = try {
            decoder.decode(opusData, 0, opusData.size, outShorts, 0, frameSamples, false)
        } catch (e: Exception) {
            Log.e(TAG, "Concentus decode error", e)
            return@withContext null
        }
        if (nSamples <= 0) return@withContext null
        val totalSamples = nSamples * channels
        val buf = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalSamples) buf.putShort(outShorts[i])
        buf.array().copyOf(buf.position())
    }

    override fun release() {
        try {
            val hasReset = decoder.javaClass.getMethods().any { m: Method -> m.name == "reset" }
            if (hasReset) {
                decoder.javaClass.getMethod("reset").invoke(decoder)
            }
        } catch (_: Exception) { }
    }
}
