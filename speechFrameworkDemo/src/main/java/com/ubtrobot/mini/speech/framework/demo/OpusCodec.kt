package com.ubtrobot.mini.speech.framework.demo

/**
 * Abstraction for Opus encode/decode so we can use either native (libapp.so) or pure-Java (Concentus).
 */
interface IOpusEncoder {
    suspend fun encode(pcmData: ByteArray): ByteArray?
    /** Sau reopen WS / dance – xóa state Opus cũ (tránh frame ~100 byte im lặng). */
    fun reset() {}
    fun release()
}

interface IOpusDecoder {
    suspend fun decode(opusData: ByteArray): ByteArray?
    fun release()
}
