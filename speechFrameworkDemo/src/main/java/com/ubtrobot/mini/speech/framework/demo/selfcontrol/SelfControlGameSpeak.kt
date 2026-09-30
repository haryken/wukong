package com.ubtrobot.mini.speech.framework.demo.selfcontrol

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bình luận game (web /games/) → Google Translate TTS → loa robot.
 * Giống wired drainChessSpeak: đang đọc thì chỉ giữ câu mới nhất chờ đọc tiếp.
 */
object SelfControlGameSpeak {
    private const val TAG = "SelfControlGameSpeak"
    /** translate_tts từ chối q dài (~200 ký tự). */
    private const val MAX_CHUNK = 180
    private const val MAX_TEXT = 600
    private val LANGS = setOf("vi", "zh-CN", "en", "it", "ru", "fr", "de", "es", "pt")

    private val lock = Any()
    private var pending: Pair<String, String>? = null
    private var worker: Thread? = null
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun speak(text: String?, lang: String?): JSONObject {
        val t = text?.trim().orEmpty().take(MAX_TEXT)
        if (t.isEmpty()) {
            return JSONObject().put("success", false).put("error", "missing text")
        }
        val l = lang?.trim()?.takeIf { it in LANGS } ?: "vi"
        synchronized(lock) {
            pending = t to l
            if (worker == null) {
                worker = Thread({ drain() }, "GameSpeak").also { it.start() }
            }
        }
        return JSONObject().put("success", true).put("queued", true).put("lang", l)
    }

    private fun drain() {
        while (true) {
            val job = synchronized(lock) {
                val p = pending
                pending = null
                if (p == null) worker = null
                p
            } ?: return
            try {
                play(job.first, job.second)
            } catch (e: Exception) {
                Log.w(TAG, "play: ${e.message}")
            }
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun play(text: String, lang: String) {
        val ctx = appContext ?: return
        val files = splitChunks(text).mapIndexedNotNull { i, chunk ->
            download(chunk, lang, File(ctx.cacheDir, "game_tts_$i.mp3"))
        }
        Log.i(TAG, "đọc [$lang] ${files.size} đoạn: ${text.take(80)}")
        for (f in files) playFile(f)
    }

    private fun download(chunk: String, lang: String, out: File): File? {
        val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob" +
            "&tl=" + URLEncoder.encode(lang, "UTF-8") +
            "&q=" + URLEncoder.encode(chunk, "UTF-8")
        val conn = SelfControlQuickShare.openHttps(url)
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 7.0) AppleWebKit/537.36 Chrome/96.0 Mobile Safari/537.36")
            conn.setRequestProperty("Referer", "https://translate.google.com/")
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "translate_tts HTTP $code")
                return null
            }
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            if (out.length() < 200) null else out
        } catch (e: Exception) {
            Log.w(TAG, "download: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun playFile(f: File) {
        val done = CountDownLatch(1)
        val mp = MediaPlayer()
        try {
            @Suppress("DEPRECATION")
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error $what/$extra")
                done.countDown()
                true
            }
            mp.prepare()
            mp.start()
            done.await(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "playFile: ${e.message}")
        } finally {
            try {
                mp.release()
            } catch (_: Exception) {
            }
        }
    }

    /** Cắt ở dấu câu / khoảng trắng để mỗi đoạn ≤ [MAX_CHUNK] ký tự. */
    private fun splitChunks(text: String): List<String> {
        val out = ArrayList<String>()
        var rest = text.replace(Regex("\\s+"), " ").trim()
        while (rest.length > MAX_CHUNK) {
            val window = rest.substring(0, MAX_CHUNK)
            var cut = window.indexOfLast { it in ".!?。！？;；,，" } + 1
            if (cut < MAX_CHUNK / 3) cut = window.lastIndexOf(' ').takeIf { it > 0 } ?: MAX_CHUNK
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }
}
