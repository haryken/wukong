package com.ubtrobot.mini.speech.framework.demo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log

/**
 * Chờ Wi‑Fi/STA có IP trước khi mở Xiaozhi WS — tránh SSL treo ~7s lúc cold boot
 * (openAudioChannel quá sớm khi route chưa sẵn).
 */
object NetworkReadyGate {
    private const val TAG = "NetworkReady"

    fun hasUsableIpv4(): Boolean {
        val ip = ActivationEyeDisplay.readWifiIpv4()
        return !ip.isNullOrBlank() && ip != "0.0.0.0"
    }

    fun hasValidatedInternet(context: Context): Boolean {
        return try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return hasUsableIpv4()
            if (Build.VERSION.SDK_INT >= 23) {
                val n = cm.activeNetwork ?: return hasUsableIpv4()
                val caps = cm.getNetworkCapabilities(n) ?: return hasUsableIpv4()
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true || hasUsableIpv4()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hasValidatedInternet: ${t.message}")
            hasUsableIpv4()
        }
    }

    fun isReady(context: Context): Boolean = hasUsableIpv4() || hasValidatedInternet(context)

    /**
     * Poll nhanh đến khi có IP / internet, hoặc hết [timeoutMs].
     * @return true nếu sẵn sàng
     */
    suspend fun awaitReady(context: Context, timeoutMs: Long = 45_000L): Boolean {
        val app = context.applicationContext
        val deadline = System.currentTimeMillis() + timeoutMs
        if (isReady(app)) {
            Log.i(TAG, "network already ready ip=${ActivationEyeDisplay.readWifiIpv4()}")
            return true
        }
        Log.i(TAG, "waiting for Wi‑Fi/IP (max ${timeoutMs / 1000}s)…")
        while (System.currentTimeMillis() < deadline) {
            if (isReady(app)) {
                Log.i(TAG, "network ready ip=${ActivationEyeDisplay.readWifiIpv4()}")
                return true
            }
            kotlinx.coroutines.delay(250L)
        }
        Log.w(TAG, "network wait timeout – vẫn thử WS")
        return false
    }

    /**
     * Một lần: khi mạng lên thì gọi [onReady] (trên thread ConnectivityManager).
     * Trả về callback để hủy đăng ký.
     */
    fun whenNetworkAvailable(context: Context, onReady: () -> Unit): (() -> Unit)? {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        if (Build.VERSION.SDK_INT < 21) return null
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try {
                    cm.unregisterNetworkCallback(this)
                } catch (_: Exception) {
                }
                onReady()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    try {
                        cm.unregisterNetworkCallback(this)
                    } catch (_: Exception) {
                    }
                    onReady()
                }
            }
        }
        return try {
            cm.registerNetworkCallback(request, cb)
            ({
                try {
                    cm.unregisterNetworkCallback(cb)
                } catch (_: Exception) {
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "registerNetworkCallback: ${t.message}")
            null
        }
    }
}
