package com.ubtrobot.mini.speech.framework.demo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * DNS cho kết nối Xiaozhi (WebSocket, OTA, UDP): DNS hệ thống (giới hạn thời gian, ưu tiên IPv4)
 * → DNS-over-HTTPS (AliDNS / Cloudflare / Google, hỏi song song) → IP đã kết nối được lần trước.
 * Xem docs/XIAOZHI_DNS.md.
 */
object XiaozhiDns : Dns {
  private const val TAG = "XiaozhiDns"
  /** Router có DNS IPv6 chết làm resolver Android chờ ~10 s rồi mới thử DNS thứ hai – quá hạn server hello. */
  private const val SYSTEM_TIMEOUT_MS = 3000L
  private const val DOH_TIMEOUT_MS = 4000L
  private const val PREFS_NAME = "xiaozhi_dns_cache"

  /** URL dùng IP trực tiếp → gọi DoH không cần DNS. Chứng chỉ của 3 dịch vụ này có IP trong SAN. */
  private val DOH_PROVIDERS = listOf(
    "AliDNS" to "https://223.5.5.5/resolve?type=1&name=",
    "Cloudflare" to "https://1.1.1.1/dns-query?type=A&name=",
    "Google" to "https://8.8.8.8/resolve?type=A&name="
  )

  private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

  private val executor = Executors.newCachedThreadPool { r ->
    Thread(r, "XiaozhiDns").apply { isDaemon = true }
  }
  private val memCache = ConcurrentHashMap<String, List<InetAddress>>()
  @Volatile private var prefs: SharedPreferences? = null

  private val dohClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(DOH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .readTimeout(DOH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .build()
  }

  fun init(context: Context) {
    prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }

  override fun lookup(hostname: String): List<InetAddress> {
    if (IPV4.matches(hostname) || hostname.contains(':')) return Dns.SYSTEM.lookup(hostname)
    val start = System.currentTimeMillis()

    systemLookup(hostname)?.let {
      remember(hostname, it, "DNS hệ thống", start)
      return it
    }
    dohLookup(hostname)?.let { (provider, ips) ->
      remember(hostname, ips, "DoH $provider", start)
      return ips
    }
    cached(hostname)?.let {
      Log.w(TAG, "$hostname: DNS hệ thống + DoH đều lỗi → dùng IP đã nhớ $it")
      return it
    }
    throw UnknownHostException("XiaozhiDns: không phân giải được $hostname (DNS hệ thống, DoH, cache đều lỗi)")
  }

  private fun systemLookup(host: String): List<InetAddress>? {
    val future = executor.submit(Callable { InetAddress.getAllByName(host).toList() })
    return try {
      val all = future.get(SYSTEM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      val v4 = all.filterIsInstance<Inet4Address>()
      (if (v4.isNotEmpty()) v4 else all).ifEmpty { null }
    } catch (e: TimeoutException) {
      future.cancel(true)
      Log.w(TAG, "$host: DNS hệ thống quá ${SYSTEM_TIMEOUT_MS}ms → thử DoH")
      null
    } catch (e: ExecutionException) {
      Log.w(TAG, "$host: DNS hệ thống lỗi (${e.cause?.message}) → thử DoH")
      null
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      null
    }
  }

  private fun dohLookup(host: String): Pair<String, List<InetAddress>>? {
    val cs = ExecutorCompletionService<Pair<String, List<InetAddress>>>(executor)
    val futures = DOH_PROVIDERS.map { (name, url) -> cs.submit(Callable { name to queryDoh(url, host) }) }
    val deadline = System.currentTimeMillis() + DOH_TIMEOUT_MS
    try {
      repeat(futures.size) {
        val left = deadline - System.currentTimeMillis()
        if (left <= 0) return null
        val done = cs.poll(left, TimeUnit.MILLISECONDS) ?: return null
        val result = try {
          done.get()
        } catch (e: ExecutionException) {
          Log.w(TAG, "$host: DoH lỗi: ${e.cause?.message}")
          null
        }
        if (result != null && result.second.isNotEmpty()) return result
      }
      return null
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      return null
    } finally {
      futures.forEach { it.cancel(true) }
    }
  }

  private fun queryDoh(baseUrl: String, host: String): List<InetAddress> {
    val request = Request.Builder()
      .url(baseUrl + host)
      .header("Accept", "application/dns-json")
      .build()
    dohClient.newCall(request).execute().use { resp ->
      if (!resp.isSuccessful) return emptyList()
      val answers = JSONObject(resp.body()?.string().orEmpty()).optJSONArray("Answer") ?: return emptyList()
      val out = ArrayList<InetAddress>()
      for (i in 0 until answers.length()) {
        val answer = answers.optJSONObject(i) ?: continue
        if (answer.optInt("type") != 1) continue
        toInet(host, answer.optString("data"))?.let { out += it }
      }
      return out
    }
  }

  private fun toInet(host: String, ip: String): InetAddress? {
    if (!IPV4.matches(ip)) return null
    val parts = ip.split('.').map { it.toInt() }
    if (parts.any { it > 255 }) return null
    return InetAddress.getByAddress(host, ByteArray(4) { parts[it].toByte() })
  }

  private fun remember(host: String, ips: List<InetAddress>, source: String, startMs: Long) {
    val ms = System.currentTimeMillis() - startMs
    val text = ips.joinToString(",") { it.hostAddress }
    if (source != "DNS hệ thống" || ms > 1000) {
      Log.i(TAG, "$host → $text qua $source (${ms}ms)")
    }
    memCache[host] = ips
    prefs?.edit()?.putString(host, text)?.apply()
  }

  private fun cached(host: String): List<InetAddress>? {
    memCache[host]?.let { return it }
    val saved = prefs?.getString(host, null) ?: return null
    return saved.split(',').mapNotNull { toInet(host, it.trim()) }.ifEmpty { null }
  }
}
