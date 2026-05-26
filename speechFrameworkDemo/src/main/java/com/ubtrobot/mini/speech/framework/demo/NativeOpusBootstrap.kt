package com.ubtrobot.mini.speech.framework.demo

import android.util.Log
import info.dourok.voicebot.OpusDecoder
import info.dourok.voicebot.OpusEncoder

/**
 * Kiểm tra libapp.so có load và init encoder/decoder native được không.
 * Gọi sớm trong [SpeechApplication] để log rõ trước khi tạo [XiaozhiSessionManager].
 */
object NativeOpusBootstrap {
    private const val TAG = "NativeOpusBootstrap"

    @Volatile
    private var probed = false

    @Volatile
    private var nativeOk = false

    @JvmStatic
    fun isNativeAvailable(): Boolean {
        if (!probed) probe()
        return nativeOk
    }

    /** @return true nếu native Opus sẵn sàng */
    @JvmStatic
    fun probe(): Boolean {
        if (probed) return nativeOk
        synchronized(this) {
            if (probed) return nativeOk
            probed = true
            nativeOk = try {
                System.loadLibrary("app")
                val enc = OpusEncoder(
                    XiaozhiSessionManager.SAMPLE_RATE,
                    XiaozhiSessionManager.CHANNELS,
                    XiaozhiSessionManager.FRAME_MS
                )
                val dec = OpusDecoder(
                    XiaozhiSessionManager.TTS_SAMPLE_RATE,
                    XiaozhiSessionManager.CHANNELS,
                    XiaozhiSessionManager.FRAME_MS
                )
                enc.release()
                dec.release()
                Log.i(TAG, "OK (native libapp.so) – encoder/decoder init thành công")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(
                    TAG,
                    "libapp.so không có trong APK — build: gradlew assembleDebug (KHÔNG -PskipNdk). ${e.message}"
                )
                false
            } catch (e: Throwable) {
                Log.w(TAG, "Native Opus init thất bại — fallback Concentus: ${e.message}")
                false
            }
        }
        return nativeOk
    }
}
