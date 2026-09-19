package com.ubtrobot.mini.speech.framework.demo

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlStore
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Giống Otto Desktop ([xiaozhi-esp32-main]/otto_music_player):
 * search + stream MP3 qua server nhạc (mặc định youtube.kytuoi.com, đổi được trên :8080).
 * Hết/fail → synthetic detect mở lại WS.
 */
object OttoMusicPlayer {
    private const val TAG = "OttoMusic"
    private const val CONNECT_TIMEOUT_MS = 12_000L
    private const val READ_TIMEOUT_MS = 60_000L
    private const val DOWNLOAD_READ_TIMEOUT_MS = 180_000L
    /** Truyện dài không tải full — stream URL; chỉ cache bài ngắn. */
    private const val MAX_DOWNLOAD_BYTES = 25L * 1024L * 1024L
    private const val STREAM_PREPARE_TIMEOUT_MS = 18_000L

    private const val DETECT_FAIL = "nhạc thất bại"
    private const val DETECT_FINISHED = "phát hết nhạc"

    /** Base URL server nhạc — đọc từ Self-Control (:8080), fallback kytuoi. */
    private fun apiHost(): String = try {
        SelfControlStore.getMusicServerUrl()
    } catch (_: Exception) {
        SelfControlStore.DEFAULT_MUSIC_SERVER
    }

    /** Cloud hay nhầm detect text thành query play — bỏ qua để tránh vòng lặp. */
    private val BLOCKED_QUERIES = setOf(
        DETECT_FAIL.lowercase(),
        DETECT_FINISHED.lowercase(),
        "nhac that bai",
        "phat het nhac"
    )

    private val ipv4OnlyDns = Dns { hostname ->
        val all = Dns.SYSTEM.lookup(hostname)
        val v4 = all.filterIsInstance<Inet4Address>()
        if (v4.isNotEmpty()) v4 else all
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(ipv4OnlyDns)
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Download: HTTP/1.1 tránh HTTP/2 CANCEL giữa chừng (truyện dài). */
    private val downloadClient: OkHttpClient = httpClient.newBuilder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .readTimeout(DOWNLOAD_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OttoMusic").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val stopRequested = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private val playerRef = AtomicReference<MediaPlayer?>(null)
    private val httpCallRef = AtomicReference<Call?>(null)
    private val localServerRef = AtomicReference<java.net.ServerSocket?>(null)
    private val proxyBytesWritten = java.util.concurrent.atomic.AtomicLong(0L)

    @JvmStatic
    fun isPlaying(): Boolean = playing.get()

    @JvmStatic
    fun isActive(): Boolean = active.get() || playing.get()

    @JvmStatic
    fun playFirstSearchResult(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) {
            notifySearchFailed("query empty")
            return """{"success":false,"error":"query is empty"}"""
        }
        if (BLOCKED_QUERIES.contains(q.lowercase()) ||
            BLOCKED_QUERIES.contains(stripDiacritics(q).lowercase())
        ) {
            Log.w(TAG, "Bỏ qua query giả từ detect: \"$q\"")
            return """{"success":false,"error":"ignored detect query"}"""
        }
        stopInternal(clearSession = false, notifyFail = false)
        stopRequested.set(false)
        // Không hiện chữ TIM NHAC / DANG PHAT trên mắt — chỉ phát audio.
        Log.i(TAG, "PlayFirstSearchResult query=\"$q\" (giữ WS đến khi play)")
        exec.execute {
            try {
                Thread.sleep(200L)
            } catch (_: InterruptedException) {
            }
            if (stopRequested.get()) {
                active.set(false)
                return@execute
            }
            searchAndPlay(q)
        }
        return JSONObject().apply {
            put("success", true)
            put("query", q)
            put("status", "starting")
        }.toString()
    }

    @JvmStatic
    fun stop() {
        Log.i(TAG, "Stop music (user / wake) — không NotifyMusicFinished")
        stopInternal(clearSession = true, notifyFail = false)
    }

    private fun searchAndPlay(query: String) {
        try {
            if (stopRequested.get()) {
                active.set(false)
                return
            }
            val track = youtubeSearchFirst(query)
            if (stopRequested.get()) {
                Log.i(TAG, "Search aborted (stop) after HTTP")
                active.set(false)
                return
            }
            if (track == null) {
                Log.e(TAG, "Search failed: $query")
                notifySearchFailed("no track for \"$query\"")
                return
            }
            val streamUrl = "${apiHost()}/api/stream/mp3?id=${urlEncode(track.id)}&format=mp3"
            Log.i(TAG, "Play id=${track.id} title=${track.title}")
            // Mini ROM: pipe FD → setDataSourceFD offset error. Dùng HTTP 127.0.0.1.
            if (playViaLocalHttpProxy(streamUrl, track.title)) {
                Log.i(TAG, "Playing via local HTTP proxy")
                return
            }
            if (stopRequested.get()) {
                active.set(false)
                return
            }
            Log.w(TAG, "Local proxy fail → download cache rồi play file")
            val file = downloadMp3ToCache(streamUrl, track.id)
            if (stopRequested.get()) {
                active.set(false)
                return
            }
            if (file != null && file.exists() && file.length() >= 1024L) {
                if (playLocalFileBlocking(file.absolutePath, track.title)) {
                    Log.i(TAG, "Playing via local file ${file.length()}B")
                    return
                }
            }
            Log.e(TAG, "Stream+download fail id=${track.id}")
            notifySearchFailed("stream fail id=${track.id}")
        } catch (e: Exception) {
            Log.e(TAG, "searchAndPlay: ${e.message}", e)
            if (!stopRequested.get()) notifySearchFailed(e.message ?: "exception")
            else active.set(false)
        }
    }

    private data class Track(val id: String, val title: String)

    private fun youtubeSearchFirst(query: String): Track? {
        val candidates = buildSearchCandidates(query)
        Log.i(TAG, "Search candidates=${candidates.joinToString(" | ")}")
        var best: Track? = null
        var bestScore = Int.MIN_VALUE
        for (q in candidates) {
            if (stopRequested.get()) return null
            val url = "${apiHost()}/api/search?q=${urlEncode(q)}&limit=5"
            Log.i(TAG, "Search GET $url")
            val body = httpGet(url) ?: continue
            val root = try {
                JSONObject(body)
            } catch (e: Exception) {
                Log.w(TAG, "Search JSON parse fail: ${e.message}")
                continue
            }
            if (!root.optBoolean("success", false)) {
                Log.w(TAG, "Search success=false for q=$q err=${root.optString("error")}")
                continue
            }
            val data = root.optJSONArray("data") ?: continue
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("id", "").trim()
                val title = item.optString("title", "").trim()
                if (id.isEmpty()) continue
                val score = scoreSearchHit(query, title)
                Log.i(TAG, "Search cand score=$score id=$id title=$title (q=$q)")
                if (score > bestScore) {
                    bestScore = score
                    best = Track(id, title.ifEmpty { id })
                }
            }
            // Đủ khớp (có đủ từ khóa chính) → lấy luôn, khỏi thử candidate tiếp.
            if (best != null && bestScore >= 40) break
        }
        if (best != null) {
            Log.i(TAG, "Search pick score=$bestScore id=${best.id} title=${best.title}")
        }
        return best
    }

    /** Ưu tiên title chứa đủ từ query (vd "vu map") — tránh trúng "Chuyện Ma Có Thật" chung chung. */
    private fun scoreSearchHit(query: String, title: String): Int {
        val qWords = stripDiacritics(query).lowercase()
            .split(Regex("\\s+")).filter { it.length >= 2 }
        val t = stripDiacritics(title).lowercase()
        if (qWords.isEmpty()) return 0
        var score = 0
        var hit = 0
        for (w in qWords) {
            if (t.contains(w)) {
                hit++
                score += 10
            }
        }
        if (hit == qWords.size) score += 30
        // Phạt title kiểu "TẬP xxxx" dài lệch chủ đề
        if (t.contains("tap ") && hit < qWords.size) score -= 5
        return score
    }

    /**
     * Server nhạc hay 500 (browseId) với "Sơn Tùng M-TP" — thử bỏ M-TP / thêm "nhac ".
     * Không fallback 1 từ (tránh "Son" → bài sai).
     */
    private fun buildSearchCandidates(query: String): List<String> {
        val out = linkedSetOf<String>()
        fun add(s: String?) {
            val t = s?.trim().orEmpty()
            if (t.isNotEmpty()) out.add(t)
        }
        add(query)
        val ascii = stripDiacritics(query).trim()
        add(ascii)
        if (ascii.contains("vu map", ignoreCase = true)) {
            add("vu map chuyen ma")
            add("chuyen ma vu map")
        }
        val noLabel = ascii
            .replace(Regex("(?i)\\bM-?TP\\b"), " ")
            .replace(Regex("(?i)\\bofficial\\b"), " ")
            .replace(Regex("(?i)\\bmv\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        add(noLabel)
        if (noLabel.isNotEmpty() && !noLabel.startsWith("nhac ", ignoreCase = true)) {
            add("nhac $noLabel")
        }
        val words = noLabel.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size >= 2) {
            val two = words.take(2).joinToString(" ")
            add(two)
            add("nhac $two")
        }
        if (words.size >= 3) add(words.take(3).joinToString(" "))
        return out.toList()
    }

    private fun stripDiacritics(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD)
        return n.replace("\\p{M}+".toRegex(), "")
            .replace('đ', 'd').replace('Đ', 'D')
    }

    private fun downloadMp3ToCache(streamUrl: String, videoId: String): java.io.File? {
        if (stopRequested.get()) return null
        return try {
            val ctx = com.ubtech.utilcode.utils.Utils.getContext().applicationContext
            val dir = java.io.File(ctx.cacheDir, "otto_music")
            if (!dir.exists()) dir.mkdirs()
            dir.listFiles()?.forEach { f ->
                if (f.isFile && System.currentTimeMillis() - f.lastModified() > 86_400_000L) {
                    f.delete()
                }
            }
            val out = java.io.File(dir, "${videoId}.mp3")
            if (out.exists() && out.length() > 1024L) {
                Log.i(TAG, "Cache hit ${out.name} ${out.length()}B")
                return out
            }
            val req = Request.Builder()
                .url(streamUrl)
                .get()
                .header("Accept", "audio/mpeg,*/*")
                .header("User-Agent", "AlphaMini-OttoMusic/1.0")
                .build()
            val call = downloadClient.newCall(req)
            httpCallRef.set(call)
            call.execute().use { resp ->
                if (stopRequested.get()) return null
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Download HTTP ${resp.code()} for $streamUrl")
                    return null
                }
                val body = resp.body() ?: return null
                val contentLen = body.contentLength()
                if (contentLen > MAX_DOWNLOAD_BYTES) {
                    Log.w(TAG, "File quá lớn ($contentLen) — bỏ download, cần stream")
                    return null
                }
                out.outputStream().use { os ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(16 * 1024)
                        var n: Int
                        var total = 0L
                        while (input.read(buf).also { n = it } >= 0) {
                            if (stopRequested.get()) {
                                out.delete()
                                return null
                            }
                            total += n
                            if (total > MAX_DOWNLOAD_BYTES) {
                                Log.w(TAG, "Download vượt ${MAX_DOWNLOAD_BYTES}B — dừng")
                                out.delete()
                                return null
                            }
                            os.write(buf, 0, n)
                        }
                        Log.i(TAG, "Downloaded $total bytes → ${out.absolutePath}")
                    }
                }
            }
            if (out.exists() && out.length() > 1024L) out else null
        } catch (e: Exception) {
            if (stopRequested.get()) {
                Log.i(TAG, "download cancelled: ${e.message}")
            } else {
                Log.e(TAG, "downloadMp3: ${e.message}", e)
            }
            null
        } finally {
            httpCallRef.set(null)
        }
    }

    /**
     * Mini MediaPlayer không đọc được pipe FD (offset error).
     * OkHttp → ServerSocket 127.0.0.1 → MediaPlayer HTTP URL (giống proxy local).
     */
    private fun playViaLocalHttpProxy(streamUrl: String, title: String): Boolean {
        closeLocalServer()
        proxyBytesWritten.set(0L)
        val server = try {
            java.net.ServerSocket(0).also { it.soTimeout = 20_000 }
        } catch (e: Exception) {
            Log.e(TAG, "ServerSocket: ${e.message}")
            return false
        }
        localServerRef.set(server)
        val port = server.localPort
        val playUrl = "http://127.0.0.1:$port/stream.mp3"
        Log.i(TAG, "Local HTTP proxy ready $playUrl ← $streamUrl")

        Thread({
            pumpHttpToLocalClient(streamUrl, server)
        }, "OttoMusicProxy").apply { isDaemon = true; start() }

        // Cho proxy thread sẵn sàng accept.
        try {
            Thread.sleep(120L)
        } catch (_: InterruptedException) {
        }
        if (stopRequested.get()) {
            closeLocalServer()
            return false
        }

        val latch = java.util.concurrent.CountDownLatch(1)
        val started = AtomicBoolean(false)
        main.post {
            try {
                releasePlayerKeepPipes()
                val mp = MediaPlayer()
                playerRef.set(mp)
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                mp.setDataSource(playUrl)
                wirePlayerCallbacks(mp, title, started, latch)
                mp.prepareAsync()
                Log.i(TAG, "MediaPlayer prepareAsync via $playUrl")
            } catch (e: Exception) {
                Log.e(TAG, "playViaLocalHttpProxy: ${e.message}", e)
                releasePlayer()
                latch.countDown()
            }
        }
        val ok = awaitStarted(latch, started, STREAM_PREPARE_TIMEOUT_MS)
        if (!ok) {
            Log.w(TAG, "Local HTTP proxy prepare fail/timeout")
            try {
                httpCallRef.getAndSet(null)?.cancel()
            } catch (_: Exception) {
            }
            releasePlayer()
        }
        return ok
    }

    private fun pumpHttpToLocalClient(streamUrl: String, server: java.net.ServerSocket) {
        var client: java.net.Socket? = null
        try {
            client = server.accept()
            client.tcpNoDelay = true
            Log.i(TAG, "Local proxy: MediaPlayer connected")
            // Đọc request (GET /stream.mp3) rồi bỏ — MP đôi khi gửi HEAD/Range.
            try {
                client.soTimeout = 3_000
                val input = client.getInputStream()
                val reqBuf = ByteArray(2048)
                val n = input.read(reqBuf)
                if (n > 0) {
                    val req = String(reqBuf, 0, n)
                    Log.d(TAG, "Local proxy request: ${req.lineSequence().firstOrNull()}")
                }
            } catch (_: Exception) {
            }
            client.soTimeout = 0
            val out = client.getOutputStream()
            // Trả header ngay — MediaPlayer không timeout chờ OkHttp upstream.
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: audio/mpeg\r\n" +
                    "Connection: close\r\n" +
                    "Accept-Ranges: none\r\n" +
                    "\r\n").toByteArray(Charsets.US_ASCII)
            )
            out.flush()

            val req = Request.Builder()
                .url(streamUrl)
                .get()
                .header("Accept", "audio/mpeg,*/*")
                .header("User-Agent", "AlphaMini-OttoMusic/1.0")
                .build()
            val call = downloadClient.newCall(req)
            httpCallRef.set(call)
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Proxy upstream HTTP ${resp.code()}")
                    return
                }
                val body = resp.body() ?: return
                Log.i(TAG, "Proxy upstream CT=${body.contentType()} len=${body.contentLength()}")
                body.byteStream().use { upstream ->
                    val buf = ByteArray(16 * 1024)
                    var nRead: Int
                    while (upstream.read(buf).also { nRead = it } >= 0) {
                        if (stopRequested.get()) break
                        out.write(buf, 0, nRead)
                        val total = proxyBytesWritten.addAndGet(nRead.toLong())
                        if (total == nRead.toLong() || total % (512 * 1024) < nRead) {
                            Log.d(TAG, "Proxy wrote ${total / 1024}KB")
                        }
                    }
                    out.flush()
                    Log.i(TAG, "Proxy EOF total=${proxyBytesWritten.get()}B")
                }
            }
        } catch (e: Exception) {
            if (stopRequested.get()) {
                Log.i(TAG, "Proxy cancelled: ${e.message}")
            } else {
                Log.e(TAG, "pumpHttpToLocalClient: ${e.message}", e)
            }
        } finally {
            httpCallRef.set(null)
            try {
                client?.close()
            } catch (_: Exception) {
            }
            try {
                server.close()
            } catch (_: Exception) {
            }
            localServerRef.compareAndSet(server, null)
        }
    }

    private fun playLocalFileBlocking(path: String, title: String): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        val started = AtomicBoolean(false)
        main.post {
            try {
                releasePlayerKeepPipes()
                val mp = MediaPlayer()
                playerRef.set(mp)
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                mp.setDataSource(path)
                wirePlayerCallbacks(mp, title, started, latch)
                mp.prepareAsync()
                Log.i(TAG, "MediaPlayer prepareAsync local $path")
            } catch (e: Exception) {
                Log.e(TAG, "playLocalFileBlocking: ${e.message}", e)
                releasePlayer()
                latch.countDown()
            }
        }
        return awaitStarted(latch, started, STREAM_PREPARE_TIMEOUT_MS)
    }

    private fun wirePlayerCallbacks(
        mp: MediaPlayer,
        title: String,
        started: AtomicBoolean,
        latch: java.util.concurrent.CountDownLatch
    ) {
        mp.setOnPreparedListener {
            if (stopRequested.get()) {
                releasePlayer()
                latch.countDown()
                return@setOnPreparedListener
            }
            playing.set(true)
            active.set(true)
            started.set(true)
            Log.i(TAG, "Playing: $title")
            it.start()
            try {
                ActivationEyeDisplay.showMusicPlayingEyes()
            } catch (e: Exception) {
                Log.w(TAG, "showMusicPlayingEyes: ${e.message}")
            }
            exec.execute {
                try {
                    DemoSpeech.enterMusicOnlyMode()
                } catch (e: Exception) {
                    Log.w(TAG, "enterMusicOnlyMode: ${e.message}")
                }
            }
            latch.countDown()
        }
        mp.setOnCompletionListener {
            Log.i(TAG, "Playback finished: $title")
            playing.set(false)
            try {
                ActivationEyeDisplay.clearMusicPlayingEyes()
            } catch (_: Exception) {
            }
            releasePlayer()
            notifyFinished()
        }
        mp.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
            playing.set(false)
            try {
                ActivationEyeDisplay.clearMusicPlayingEyes()
            } catch (_: Exception) {
            }
            releasePlayer()
            latch.countDown()
            true
        }
    }

    private fun awaitStarted(
        latch: java.util.concurrent.CountDownLatch,
        started: AtomicBoolean,
        timeoutMs: Long
    ): Boolean {
        return try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Stream prepare timeout ${timeoutMs}ms")
                main.post { releasePlayer() }
                false
            } else {
                started.get() && !stopRequested.get()
            }
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun startMediaPlayerLocal(path: String, title: String) {
        playLocalFileBlocking(path, title)
    }

    private fun stopInternal(clearSession: Boolean, notifyFail: Boolean) {
        stopRequested.set(true)
        playing.set(false)
        try {
            ActivationEyeDisplay.clearMusicPlayingEyes()
        } catch (_: Exception) {
        }
        if (clearSession) {
            active.set(false)
            DemoSpeech.exitMusicOnlyMode()
        }
        try {
            httpCallRef.getAndSet(null)?.cancel()
        } catch (_: Exception) {
        }
        closeLocalServer()
        main.post { releasePlayer() }
        if (notifyFail) notifySearchFailed("stopInternal")
    }

    private fun releasePlayerKeepPipes() {
        try {
            playerRef.getAndSet(null)?.run {
                try {
                    if (isPlaying) stop()
                } catch (_: Exception) {
                }
                try {
                    reset()
                } catch (_: Exception) {
                }
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "releasePlayerKeepPipes: ${e.message}")
        }
    }

    private fun releasePlayer() {
        releasePlayerKeepPipes()
        closeLocalServer()
    }

    private fun closeLocalServer() {
        try {
            localServerRef.getAndSet(null)?.close()
        } catch (_: Exception) {
        }
    }

    private fun closePipes() {
        closeLocalServer()
    }

    private fun notifySearchFailed(reason: String) {
        try {
            ActivationEyeDisplay.clearMusicPlayingEyes()
        } catch (_: Exception) {
        }
        if (stopRequested.get()) {
            Log.i(TAG, "Skip NotifyMusicSearchFailed (stopped): $reason")
            playing.set(false)
            active.set(false)
            DemoSpeech.exitMusicOnlyMode()
            return
        }
        playing.set(false)
        active.set(false)
        DemoSpeech.exitMusicOnlyMode()
        Log.e(TAG, "NotifyMusicSearchFailed: $reason")
        main.postDelayed({
            DemoSpeech.requestSyntheticDetect(DETECT_FAIL)
        }, 800L)
    }

    private fun notifyFinished() {
        try {
            ActivationEyeDisplay.clearMusicPlayingEyes()
        } catch (_: Exception) {
        }
        if (stopRequested.get()) {
            playing.set(false)
            active.set(false)
            DemoSpeech.exitMusicOnlyMode()
            return
        }
        playing.set(false)
        active.set(false)
        DemoSpeech.exitMusicOnlyMode()
        main.postDelayed({
            DemoSpeech.requestSyntheticDetect(DETECT_FINISHED)
        }, 600L)
    }

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** OkHttp + hostname (SNI OK) + DNS IPv4-only. */
    private fun httpGet(urlStr: String): String? {
        if (stopRequested.get()) return null
        return try {
            val req = Request.Builder()
                .url(urlStr)
                .get()
                .header("Accept", "*/*")
                .header("User-Agent", "AlphaMini-OttoMusic/1.0")
                .build()
            val call = httpClient.newCall(req)
            httpCallRef.set(call)
            call.execute().use { resp ->
                if (stopRequested.get()) return null
                val code = resp.code()
                val body = resp.body()?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "HTTP $code for $urlStr body=${body.take(200)}")
                    return null
                }
                Log.i(TAG, "HTTP 200 len=${body.length} for $urlStr")
                body
            }
        } catch (e: Exception) {
            if (stopRequested.get()) {
                Log.i(TAG, "httpGet cancelled: ${e.message}")
            } else {
                Log.e(TAG, "httpGet: ${e.message}", e)
            }
            null
        } finally {
            httpCallRef.set(null)
        }
    }
}
