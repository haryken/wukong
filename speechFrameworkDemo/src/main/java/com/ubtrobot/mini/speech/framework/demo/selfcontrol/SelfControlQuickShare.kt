package com.ubtrobot.mini.speech.framework.demo.selfcontrol

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Tạo link công khai 1 nút — giống wire-os Cloudflare Quick Tunnel:
 *   cloudflared tunnel --protocol http2 --edge <ip>:7844 --url http://127.0.0.1:8080
 * → https://xxxx.trycloudflare.com (vào thẳng, không trang IP như loca.lt)
 *
 * Dùng bản cloudflared GOOS=android (Termux), không dùng linux-arm64 (DNS [::1]:53 fail trên Mini).
 */
object SelfControlQuickShare {
    private const val TAG = "SelfControlShare"
    // Chỉ subdomain tunnel thật (vd. abc-xyz.trycloudflare.com), bỏ api/update/...
    private val CF_URL_RE = Pattern.compile(
        "https://(?!(?:api|update|dash|developers|www)\\.)([a-z0-9-]+(?:\\.[a-z0-9-]+)*)\\.trycloudflare\\.com",
        Pattern.CASE_INSENSITIVE
    )
    private const val CF_TERMUX_VER = "2026.9.1"
    private const val CF_TERMUX_DEB_ARM64 =
        "https://packages.termux.dev/apt/termux-main/pool/main/c/cloudflared/cloudflared_${CF_TERMUX_VER}_aarch64.deb"
    private const val CF_TERMUX_DEB_ARM =
        "https://packages.termux.dev/apt/termux-main/pool/main/c/cloudflared/cloudflared_${CF_TERMUX_VER}_arm.deb"
    private const val BIN_NAME = "cloudflared-android"

    @Volatile private var appContext: Context? = null
    private val running = AtomicBoolean(false)
    private val publicUrl = AtomicReference("")
    private val provider = AtomicReference("")
    private val publicIp = AtomicReference("")
    @Volatile private var lastError: String = ""
    @Volatile private var cfProcess: Process? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        ensureRelaxedSsl()
    }

    /**
     * ROM Alpha Mini thường thiếu trust store đầy đủ → CertPathValidatorException
     * khi gọi loca.lt / GitHub. Chỉ dùng cho QuickShare (không đụng app khác).
     */
    @Volatile private var sslReady = false
    private var relaxedSslFactory: javax.net.ssl.SSLSocketFactory? = null
    private var relaxedHostnameVerifier: HostnameVerifier? = null

    private fun ensureRelaxedSsl() {
        if (sslReady) return
        synchronized(this) {
            if (sslReady) return
            try {
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                })
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, trustAll, SecureRandom())
                relaxedSslFactory = ctx.socketFactory
                relaxedHostnameVerifier = HostnameVerifier { _, _ -> true }
                sslReady = true
            } catch (e: Exception) {
                Log.w(TAG, "relaxed SSL init: ${e.message}")
            }
        }
    }

    internal fun openHttps(url: String): HttpURLConnection {
        ensureRelaxedSsl()
        // Redirect (GitHub → objects.githubusercontent.com) tạo connection mới —
        // set default factory tạm thời để mọi hop HTTPS đều tin được trên ROM thiếu CA.
        relaxedSslFactory?.let { HttpsURLConnection.setDefaultSSLSocketFactory(it) }
        relaxedHostnameVerifier?.let { HttpsURLConnection.setDefaultHostnameVerifier(it) }
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            relaxedSslFactory?.let { conn.sslSocketFactory = it }
            relaxedHostnameVerifier?.let { conn.hostnameVerifier = it }
        }
        return conn
    }

    fun statusJson(): JSONObject = JSONObject().apply {
        put("success", true)
        put("active", running.get())
        put("share_url", publicUrl.get().orEmpty())
        put("provider", provider.get().orEmpty())
        put("public_ip", publicIp.get().orEmpty())
        put("last_error", lastError)
        put("lan_url", SelfControlHttpServer.configUrl())
        val tip = when {
            provider.get() == "cloudflare" && publicUrl.get().isNotEmpty() ->
                "Link Cloudflare — mở thẳng, không cần nhập IP"
            else -> ""
        }
        if (tip.isNotEmpty()) put("hint", tip)
    }

    fun createLink(): JSONObject {
        if (running.get() && publicUrl.get().isNotEmpty()) {
            return statusJson().put("message", "Link đang hoạt động — sao chép gửi người khác")
        }
        stop()
        lastError = ""
        publicIp.set("")
        // Cloudflare Quick Tunnel only (giống wire-os) — không dùng loca.lt (trang hỏi IP)
        val cf = try {
            tryCloudflared()
        } catch (t: Throwable) {
            lastError = "cloudflared: ${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, lastError, t)
            null
        }
        if (cf != null) {
            publicUrl.set(cf)
            provider.set("cloudflare")
            running.set(true)
            Log.i(TAG, "Cloudflare quick tunnel: $cf")
            return statusJson().put("message", "Đã tạo link Cloudflare")
        }
        running.set(false)
        publicUrl.set("")
        provider.set("")
        if (lastError.isBlank()) lastError = "Không tạo được Cloudflare tunnel"
        return statusJson().put("success", false).put("error", lastError)
    }

    fun stop() {
        running.set(false)
        destroyCfProcess()
        publicUrl.set("")
        provider.set("")
        publicIp.set("")
    }

    private fun destroyCfProcess() {
        val p = cfProcess ?: return
        cfProcess = null
        try {
            p.destroy()
        } catch (_: Throwable) {
        }
        // destroyForcibly = API 26+; ROM Mini thường 24/25 → chỉ destroy()
        try {
            Thread.sleep(150)
            if (processStillRunning(p)) {
                try {
                    val m = Process::class.java.getMethod("destroyForcibly")
                    m.invoke(p)
                } catch (_: Throwable) {
                    try {
                        p.destroy()
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun tryCloudflared(): String? {
        val bin = resolveCloudflaredBinary() ?: run {
            lastError = "Không tải được cloudflared Android"
            return null
        }
        return try {
            startCloudflaredAndWaitUrl(bin)
        } catch (e: Exception) {
            lastError = "cloudflared: ${e.message}"
            Log.w(TAG, lastError)
            destroyCfProcess()
            null
        } catch (t: Throwable) {
            lastError = "cloudflared: ${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, lastError, t)
            destroyCfProcess()
            null
        }
    }

    private fun resolveCloudflaredBinary(): File? {
        val ctx = appContext ?: return null
        val dest = File(ctx.filesDir, BIN_NAME)
        if (dest.isFile && dest.length() > 5_000_000L) {
            dest.setExecutable(true, false)
            if (dest.canExecute() && isAndroidCloudflared(dest)) return dest
        }
        // Bản linux cũ trong files/cloudflared không dùng được (DNS [::1]:53)
        if (downloadTermuxCloudflared(dest)) {
            dest.setExecutable(true, false)
            if (dest.canExecute()) return dest
        }
        return null
    }

    /** ELF Android (linker64) vs linux glibc — đọc vài KB đầu. */
    private fun isAndroidCloudflared(bin: File): Boolean {
        return try {
            bin.inputStream().use { input ->
                val buf = ByteArray(512)
                val n = input.read(buf)
                if (n < 64) return false
                val s = String(buf, 0, n, Charsets.ISO_8859_1)
                s.contains("linker64") || s.contains("linker")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun downloadTermuxCloudflared(dest: File): Boolean {
        val url = if (Build.SUPPORTED_ABIS.any { it.contains("arm64") }) {
            CF_TERMUX_DEB_ARM64
        } else {
            CF_TERMUX_DEB_ARM
        }
        return try {
            Log.i(TAG, "Downloading Termux cloudflared: $url")
            val tmpDeb = File(dest.parentFile, "cloudflared-termux.deb")
            val conn = openHttps(url).apply {
                connectTimeout = 20_000
                readTimeout = 180_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SelfControlMini/1.0")
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "download HTTP ${conn.responseCode}")
                conn.disconnect()
                return false
            }
            conn.inputStream.use { input ->
                FileOutputStream(tmpDeb).use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            val ok = extractCloudflaredFromDeb(tmpDeb, dest)
            try {
                tmpDeb.delete()
            } catch (_: Exception) {
            }
            ok && dest.length() > 5_000_000L
        } catch (t: Throwable) {
            Log.w(TAG, "download termux cloudflared: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * Giải nén .deb (AR + data.tar.xz + TAR) không dùng commons-compress —
     * thư viện đó kéo java.nio.file.LinkOption (API 26+) → crash trên Mini API 24.
     */
    private fun extractCloudflaredFromDeb(deb: File, dest: File): Boolean {
        return try {
            java.io.BufferedInputStream(java.io.FileInputStream(deb)).use { raw ->
                val magic = ByteArray(8)
                if (raw.read(magic) != 8 || String(magic, Charsets.US_ASCII) != "!<arch>\n") {
                    Log.w(TAG, "not an ar archive")
                    return false
                }
                while (true) {
                    val hdr = ByteArray(60)
                    val nr = readFully(raw, hdr)
                    if (nr < 60) break
                    val name = String(hdr, 0, 16, Charsets.US_ASCII).trim()
                    val sizeStr = String(hdr, 48, 10, Charsets.US_ASCII).trim()
                    val size = sizeStr.toLongOrNull() ?: break
                    if (name.startsWith("data.tar")) {
                        val tarPayload = ByteArray(size.toInt())
                        if (readFully(raw, tarPayload) < size.toInt()) return false
                        if (size % 2L != 0L) raw.read() // ar padding
                        val tarStream: java.io.InputStream = when {
                            name.contains(".xz") -> org.tukaani.xz.XZInputStream(
                                java.io.ByteArrayInputStream(tarPayload)
                            )
                            name.contains(".gz") -> java.util.zip.GZIPInputStream(
                                java.io.ByteArrayInputStream(tarPayload)
                            )
                            else -> java.io.ByteArrayInputStream(tarPayload)
                        }
                        return extractCloudflaredFromTar(tarStream, dest)
                    } else {
                        skipFully(raw, size)
                        if (size % 2L != 0L) raw.read()
                    }
                }
            }
            false
        } catch (t: Throwable) {
            Log.w(TAG, "extract deb: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun extractCloudflaredFromTar(tarIn: java.io.InputStream, dest: File): Boolean {
        tarIn.use { input ->
            val header = ByteArray(512)
            while (true) {
                val n = readFully(input, header)
                if (n < 512) return false
                if (header.all { it.toInt() == 0 }) return false // end of archive
                val name = tarName(header)
                val size = tarOctal(header, 124, 12)
                val typeflag = header[156].toInt().toChar()
                val isDir = typeflag == '5' || name.endsWith("/")
                if (!isDir && (name.endsWith("/bin/cloudflared") || name.endsWith("bin/cloudflared")
                            || name == "cloudflared")
                ) {
                    FileOutputStream(dest).use { out ->
                        copyLimited(input, out, size)
                    }
                    val pad = ((512 - (size % 512)) % 512).toInt()
                    if (pad > 0) skipFully(input, pad.toLong())
                    Log.i(TAG, "extracted cloudflared ${dest.length()} bytes from $name")
                    return dest.length() > 5_000_000L
                }
                skipFully(input, size)
                val pad = ((512 - (size % 512)) % 512).toInt()
                if (pad > 0) skipFully(input, pad.toLong())
            }
        }
    }

    private fun tarName(header: ByteArray): String {
        val prefix = String(header, 345, 155, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
        val name = String(header, 0, 100, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun tarOctal(header: ByteArray, off: Int, len: Int): Long {
        val s = String(header, off, len, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
        if (s.isEmpty()) return 0L
        return s.toLong(8)
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) return off
            off += r
        }
        return off
    }

    private fun skipFully(input: java.io.InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() < 0) return
                left--
            } else {
                left -= s
            }
        }
    }

    private fun copyLimited(input: java.io.InputStream, out: java.io.OutputStream, n: Long) {
        val buf = ByteArray(16 * 1024)
        var left = n
        while (left > 0) {
            val want = minOf(buf.size.toLong(), left).toInt()
            val r = input.read(buf, 0, want)
            if (r < 0) break
            out.write(buf, 0, r)
            left -= r
        }
    }

    /** API 24–25 không có Process.isAlive() — dùng exitValue(). */
    private fun processStillRunning(proc: Process): Boolean {
        return try {
            proc.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidCfTunnelUrl(url: String): Boolean {
        return try {
            val host = URL(url).host.lowercase()
            if (!host.endsWith(".trycloudflare.com")) return false
            val sub = host.removeSuffix(".trycloudflare.com")
            if (sub.isEmpty() || sub.contains('.')) return false
            sub !in setOf("api", "update", "dash", "developers", "www", "cloudflared")
                    && sub.length >= 8
        } catch (_: Exception) {
            false
        }
    }

    /** Android DNS resolve A-record → --edge (tránh SRV lookup [::1]:53). */
    private fun resolveCfEdgeArgs(): List<String> {
        val hosts = arrayOf("region1.v2.argotunnel.com", "region2.v2.argotunnel.com")
        val edges = linkedSetOf<String>()
        for (h in hosts) {
            try {
                for (a in java.net.InetAddress.getAllByName(h)) {
                    if (a is java.net.Inet4Address) {
                        edges += "${a.hostAddress}:7844"
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "edge resolve $h: ${e.message}")
            }
        }
        if (edges.isEmpty()) {
            edges += listOf(
                "198.41.192.67:7844",
                "198.41.192.7:7844",
                "198.41.200.63:7844",
                "198.41.200.13:7844"
            )
        }
        return edges.take(8).toList()
    }

    private fun startCloudflaredAndWaitUrl(bin: File): String {
        val local = "http://127.0.0.1:${SelfControlHttpServer.PORT}"
        val edges = resolveCfEdgeArgs()
        // Flag order như bản Android Termux: cloudflared --no-autoupdate tunnel ...
        val cmd = mutableListOf(
            bin.absolutePath,
            "--no-autoupdate",
            "tunnel",
            "--protocol", "http2"
        )
        for (e in edges) {
            cmd += "--edge"
            cmd += e
        }
        cmd += listOf("--url", local)
        Log.i(TAG, "start cloudflared edges=${edges.joinToString(",")}")
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        pb.directory(bin.parentFile)
        val proc = pb.start()
        cfProcess = proc
        val urlRef = AtomicReference<String?>(null)
        val registered = AtomicBoolean(false)
        val t = Thread {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        Log.d(TAG, "cf: $l")
                        if (l.contains("Registered tunnel connection")) {
                            registered.set(true)
                        }
                        val m = CF_URL_RE.matcher(l)
                        while (m.find()) {
                            val u = m.group()
                            if (isValidCfTunnelUrl(u)) {
                                urlRef.set(u)
                                break
                            } else {
                                Log.d(TAG, "skip non-tunnel cf url: $u")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "cf reader: ${e.message}")
            }
        }
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 60_000L
        var urlSeenAt = 0L
        while (System.currentTimeMillis() < deadline) {
            val u = urlRef.get()
            if (u != null) {
                if (urlSeenAt == 0L) urlSeenAt = System.currentTimeMillis()
                // Đợi registered (tối đa ~8s sau khi có URL) rồi trả — link mở được luôn
                if (registered.get() || System.currentTimeMillis() - urlSeenAt > 8_000L) {
                    return u
                }
            }
            if (!processStillRunning(proc) && urlRef.get() == null) {
                throw IllegalStateException("cloudflared exited early")
            }
            Thread.sleep(200)
        }
        urlRef.get()?.let { return it }
        throw IllegalStateException("timeout chờ trycloudflare.com URL")
    }
}
