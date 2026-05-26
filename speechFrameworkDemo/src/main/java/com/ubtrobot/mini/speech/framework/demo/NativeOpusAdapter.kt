package com.ubtrobot.mini.speech.framework.demo

import info.dourok.voicebot.OpusDecoder
import info.dourok.voicebot.OpusEncoder

/**
 * Wraps native OpusEncoder (libapp.so) as IOpusEncoder.
 * Throws UnsatisfiedLinkError when libapp.so is not loaded.
 */
class NativeOpusEncoderAdapter(
    sampleRate: Int,
    channels: Int,
    frameSizeMs: Int
) : IOpusEncoder {

    private val encoder = OpusEncoder(sampleRate, channels, frameSizeMs)

    override suspend fun encode(pcmData: ByteArray): ByteArray? = encoder.encode(pcmData)

    override fun release() = encoder.release()
}

/**
 * Wraps native OpusDecoder (libapp.so) as IOpusDecoder.
 */
class NativeOpusDecoderAdapter(
    sampleRate: Int,
    channels: Int,
    frameSizeMs: Int
) : IOpusDecoder {

    private val decoder = OpusDecoder(sampleRate, channels, frameSizeMs)

    override suspend fun decode(opusData: ByteArray): ByteArray? = decoder.decode(opusData)

    override fun release() = decoder.release()
}
