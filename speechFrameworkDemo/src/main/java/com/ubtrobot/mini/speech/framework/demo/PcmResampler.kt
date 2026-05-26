package com.ubtrobot.mini.speech.framework.demo

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resamples 16-bit mono PCM from 24 kHz to 44.1 kHz using linear interpolation.
 * Alpha Mini hardware typically uses 44100 Hz; resampling avoids distortion from system resampler.
 */
object PcmResampler {
    private const val IN_RATE = 24000
    private const val OUT_RATE = 44100

    /**
     * @param pcm24k 16-bit little-endian mono PCM at 24 kHz (bytes = 2 * numSamples)
     * @return 16-bit little-endian mono PCM at 44.1 kHz, or null if input empty
     */
    fun resample24kTo44100(pcm24k: ByteArray): ByteArray? {
        if (pcm24k.isEmpty() || pcm24k.size and 1 != 0) return null
        val inSamples = pcm24k.size / 2
        if (inSamples == 0) return null
        val outSamples = (inSamples.toLong() * OUT_RATE / IN_RATE).toInt()
        if (outSamples <= 0) return null

        val inBuf = ByteBuffer.wrap(pcm24k).order(ByteOrder.LITTLE_ENDIAN)
        val outBuf = ByteBuffer.allocate(outSamples * 2).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until outSamples) {
            val srcIdx = i * IN_RATE.toDouble() / OUT_RATE
            val idx0 = srcIdx.toInt().coerceIn(0, inSamples - 1)
            val idx1 = (idx0 + 1).coerceIn(0, inSamples - 1)
            val frac = srcIdx - idx0
            inBuf.position(idx0 * 2)
            val s0 = inBuf.short.toInt()
            inBuf.position(idx1 * 2)
            val s1 = inBuf.short.toInt()
            val interp = s0 + (frac * (s1 - s0)).toInt()
            val clamped = interp.coerceIn(-32768, 32767)
            outBuf.putShort(clamped.toShort())
        }
        outBuf.flip()
        return ByteArray(outBuf.remaining()).also { outBuf.get(it) }
    }
}
