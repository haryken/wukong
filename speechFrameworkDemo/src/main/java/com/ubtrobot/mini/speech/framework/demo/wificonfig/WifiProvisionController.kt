package com.ubtrobot.mini.speech.framework.demo.wificonfig

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.ubtrobot.mini.speech.framework.demo.ActivationEyeDisplay
import com.ubtrobot.mini.speech.framework.demo.XiaozhiDeviceIdentityStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline → SoftAP + portal http://192.168.43.1:8888 (danh sách Wi‑Fi + form).
 * SoftAP bị ROM chặn → Settings Wi‑Fi; mắt `SET WIFI`.
 */
object WifiProvisionController {
    private const val TAG = "WifiProvision"
    private const val BOOT_DELAY_MS = 20_000L
    private const val POLL_MS = 10_000L
    private const val OFFLINE_BEFORE_AP_MS = 8_000L

    private val started = AtomicBoolean(false)
    private val inProvision = AtomicBoolean(false)
    /** SoftAP đã fail (SecurityException…) — không spam thử lại trong cùng lần offline. */
    private val softApBlockedThisOffline = AtomicBoolean(false)
    private val settingsShownThisOffline = AtomicBoolean(false)
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var app: Context? = null
    @Volatile private var offlineSinceMs = 0L
    @Volatile private var lastAp: WifiSoftApHelper.ApInfo? = null
    /** SoftAP / Self-Control đang hiện QR trên mắt (sticky — không phụ thuộc race flag mắt). */
    private val provisionQrOnEyes = AtomicBoolean(false)
    @Volatile private var lastToggleMs = 0L

    /** Voice/MCP mở QR — sticky để gõ đầu 2 lần tắt được giống mở bằng đầu. */
    @JvmStatic
    fun markConfigQrSticky(on: Boolean) {
        provisionQrOnEyes.set(on)
    }

    @JvmStatic
    fun start(context: Context) {
        val appCtx = context.applicationContext
        app = appCtx
        ActivationEyeDisplay.bindAppContext(appCtx)
        if (!started.compareAndSet(false, true)) return
        Log.i(TAG, "===== WifiProvisionController STARTED ===== (filter logcat: WifiProvision)")
        main.postDelayed({ tick() }, BOOT_DELAY_MS)
    }

    /** Ép thử SoftAP lại (bỏ block lần offline hiện tại). */
    @JvmStatic
    fun enterProvisionNow(context: Context) {
        app = context.applicationContext
        softApBlockedThisOffline.set(false)
        settingsShownThisOffline.set(false)
        pool.execute { enterProvisionLocked("manual") }
    }

    /**
     * Gõ đầu 2 lần (toggle QR):
     * - Đang hiện QR → tắt ngay
     * - Chưa hiện → mở QR trên thread nền (không block handler 60s — nếu block thì tap sau bị xếp hàng và lại “mở”)
     */
    @JvmStatic
    fun onHeadDoubleTap(context: Context) {
        app = context.applicationContext
        val ctx = app ?: return
        try {
            val now = System.currentTimeMillis()
            if (now - lastToggleMs < 700L) {
                Log.i(TAG, "head double-tap debounce skip")
                return
            }
            lastToggleMs = now

            val eyeQr = ActivationEyeDisplay.isQrShowing()
            val stickyQr = provisionQrOnEyes.get()
            Log.i(
                TAG,
                "head double-tap toggle eyeQr=$eyeQr stickyQr=$stickyQr inProvision=${inProvision.get()}"
            )
            if (eyeQr || stickyQr) {
                provisionQrOnEyes.set(false)
                ActivationEyeDisplay.dismissQrEyes()
                Log.i(TAG, "head double-tap: đã tắt QR")
                return
            }
            if (WifiStationHelper.isAssociated(ctx)) {
                Log.i(TAG, "head double-tap: mở Self-Control IP+:8080 + QR")
                try {
                    com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlHttpServer
                        .start(ctx)
                } catch (_: Exception) {
                }
                val url = com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlHttpServer
                    .configUrl()
                val ip = ActivationEyeDisplay.readWifiIpv4() ?: ""
                // Sticky ngay — tránh double thứ 2 (framework) tưởng chưa mở → toggle tắt chớp.
                provisionQrOnEyes.set(true)
                // Đánh dấu QR sớm để single/wake/smile không đè trong lúc encode.
                ActivationEyeDisplay.markQrShowingForHeadTap()
                Thread({
                    try {
                        ActivationEyeDisplay.showSelfControlIpAndQr(ip, url)
                    } catch (e: Exception) {
                        Log.w(TAG, "showSelfControlQr: ${e.message}")
                        ActivationEyeDisplay.dismissQrEyes()
                    } finally {
                        provisionQrOnEyes.set(false)
                        // Không clearQrShowingFlag ở đây — show() tự hạ cờ sau 60s; clear sớm = QR biến mất.
                    }
                }, "ShowSelfControlQr").start()
                try {
                    com.ubtrobot.mini.speech.framework.demo.DemoSpeech.recoverTalkAfterShowConfigFromHead()
                } catch (_: Exception) {
                }
                return
            }
            Log.i(TAG, "head double-tap: offline → mở SoftAP portal mắt")
            softApBlockedThisOffline.set(false)
            if (inProvision.get() && lastAp != null) {
                val ap = lastAp!!
                showProvisionQr(ap.ssid, WifiProvisionHttpServer.portalUrl())
            } else {
                pool.execute { enterProvisionLocked("head-double-tap") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onHeadDoubleTap: ${e.message}", e)
        }
    }

    /** Hiện QR SoftAP — luôn async để double-tap tắt được ngay. */
    private fun showProvisionQr(apSsid: String, portalUrl: String) {
        provisionQrOnEyes.set(true)
        ActivationEyeDisplay.markQrShowingForHeadTap()
        Thread({
            try {
                ActivationEyeDisplay.showWifiProvisionUrlAndQr(apSsid, portalUrl)
            } catch (e: Exception) {
                Log.w(TAG, "showProvisionQr: ${e.message}")
                ActivationEyeDisplay.dismissQrEyes()
            } finally {
                provisionQrOnEyes.set(false)
            }
        }, "ShowProvisionQr").start()
    }

    private fun tick() {
        val ctx = app ?: return
        pool.execute {
            try {
                if (WifiStationHelper.isAssociated(ctx)) {
                    if (offlineSinceMs != 0L) {
                        Log.i(TAG, "Wi‑Fi STA đã gắn – reset offline flags")
                    }
                    offlineSinceMs = 0L
                    softApBlockedThisOffline.set(false)
                    settingsShownThisOffline.set(false)
                    if (inProvision.get()) {
                        exitProvisionLocked("wifi-ok")
                    }
                    // Pre-encode Self-Control QR khi đã có IP (chỉ encode lại nếu IP/URL đổi)
                    try {
                        com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlHttpServer
                            .start(ctx)
                        ActivationEyeDisplay.warmSelfControlEyeCache(
                            com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlHttpServer
                                .configUrl()
                        )
                    } catch (_: Exception) {
                    }
                } else {
                    if (offlineSinceMs == 0L) {
                        offlineSinceMs = System.currentTimeMillis()
                        Log.i(TAG, "Mất / chưa có Wi‑Fi STA – bắt đầu đếm offline")
                    }
                    val waited = System.currentTimeMillis() - offlineSinceMs
                    if (!inProvision.get()
                        && !softApBlockedThisOffline.get()
                        && waited >= OFFLINE_BEFORE_AP_MS
                    ) {
                        enterProvisionLocked("auto-offline")
                    } else if (softApBlockedThisOffline.get()
                        && !settingsShownThisOffline.get()
                        && waited >= OFFLINE_BEFORE_AP_MS
                    ) {
                        // SoftAP đã fail trước đó trong session offline – chỉ Settings 1 lần
                        fallbackOpenSettings(ctx, "softApBlocked-retry-settings-once")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "tick: ${e.message}")
            } finally {
                main.postDelayed({ tick() }, POLL_MS)
            }
        }
    }

    private fun preferredApSsid(ctx: Context): String {
        return try {
            val id = XiaozhiDeviceIdentityStore.getOrCreate(ctx).deviceId
            val tail = id.replace(":", "").takeLast(4).uppercase().ifEmpty { "WIFI" }
            "Mini-$tail"
        } catch (_: Exception) {
            "Mini-WIFI"
        }
    }

    private fun enterProvisionLocked(reason: String) {
        val ctx = app ?: return
        if (!inProvision.compareAndSet(false, true)) return
        Log.i(TAG, "ENTER provision ($reason) – thử SoftAP…")
        try {
            // Log đã chứng minh: SoftAP cần WRITE_SETTINGS (user grant).
            if (!ensureWriteSettings(ctx)) {
                inProvision.set(false)
                // Chưa block hẳn – user bật quyền xong sẽ thử lại ở tick sau
                Log.w(TAG, "Chưa có WRITE_SETTINGS – đã mở màn hình cấp quyền, sẽ retry")
                return
            }

            val ssid = preferredApSsid(ctx)
            // Quét SSID nhà trước khi SoftAP (STA tắt khi AP bật).
            try {
                WifiScanHelper.scanBeforeSoftAp(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "pre-scan: ${e.message}")
            }
            val ap = try {
                WifiSoftApHelper.start(ctx, ssid)
            } catch (e: Exception) {
                val detail = WifiSoftApHelper.lastError.ifEmpty { e.message ?: "fail" }
                Log.e(TAG, "===== SoftAP FAIL ===== $detail", e)
                softApBlockedThisOffline.set(true)
                inProvision.set(false)
                fallbackOpenSettings(ctx, detail)
                return
            }
            lastAp = ap
            softApBlockedThisOffline.set(false)
            Thread.sleep(1200)
            WifiProvisionHttpServer.start(ctx, ap.ssid, ap.password) { homeSsid, homePass ->
                applyHomeWifi(homeSsid, homePass)
            }
            val url = WifiProvisionHttpServer.portalUrl()
            Log.i(TAG, "===== SoftAP OK ===== ${ap.ssid} pass=${ap.password} mode=${ap.mode} $url")
            ActivationEyeDisplay.warmQrCache(url)
            Thread({
                try {
                    showProvisionQr(ap.ssid, url)
                } catch (e: Exception) {
                    Log.w(TAG, "eyes: ${e.message}")
                }
            }, "ProvisionQrEyes").start()
        } catch (e: Exception) {
            Log.e(TAG, "enterProvision: ${e.message}", e)
            softApBlockedThisOffline.set(true)
            inProvision.set(false)
            fallbackOpenSettings(ctx, e.message ?: "fail")
        }
    }

    /**
     * Android 6+: WRITE_SETTINGS phải user bật trong Settings.
     * @return true nếu đã có quyền.
     */
    private fun ensureWriteSettings(ctx: Context): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
                return true
            }
            if (Settings.System.canWrite(ctx)) {
                Log.i(TAG, "WRITE_SETTINGS OK")
                return true
            }
            Log.w(TAG, "Thiếu WRITE_SETTINGS – mở ACTION_MANAGE_WRITE_SETTINGS")
            val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = android.net.Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
            Thread {
                try {
                    ActivationEyeDisplay.showCode("ALLOW SET")
                } catch (_: Exception) {
                }
            }.start()
            false
        } catch (e: Exception) {
            Log.e(TAG, "ensureWriteSettings: ${e.message}")
            false
        }
    }

    private fun applyHomeWifi(ssid: String, password: String) {
        val ctx = app ?: return
        Log.i(TAG, "Apply home Wi‑Fi ssid=$ssid")
        // SoftAP phải tắt TRƯỚC khi nối STA (Mini không AP+STA cùng lúc).
        provisionQrOnEyes.set(false)
        try {
            ActivationEyeDisplay.dismissQrEyes()
        } catch (_: Exception) {
        }
        try {
            ActivationEyeDisplay.showWifiConnecting(ssid)
        } catch (e: Exception) {
            Log.e(TAG, "showWifiConnecting: ${e.message}", e)
        }
        try {
            WifiProvisionHttpServer.stop()
            WifiSoftApHelper.releaseLocalOnly()
            WifiSoftApHelper.stop(ctx)
            Thread.sleep(600)
            val ok = WifiStationHelper.connect(ctx, ssid, password)
            Log.i(TAG, "connect result=$ok – đợi associate")
            var associated = false
            repeat(24) {
                Thread.sleep(500)
                if (WifiStationHelper.isAssociated(ctx)) {
                    associated = true
                    return@repeat
                }
            }
            inProvision.set(false)
            // Đợi settle: sai pass đôi khi associate giả rồi rớt (log: associated=true rồi offline).
            Thread.sleep(2_000)
            val stillUp = WifiStationHelper.isAssociated(ctx)
            val ip = ActivationEyeDisplay.readWifiIpv4()
            val realOk = stillUp && !ip.isNullOrBlank()
            Log.i(TAG, "after connect associated=$associated stillUp=$stillUp ip=${ip ?: "(no-ip)"} realOk=$realOk")
            try {
                if (realOk) {
                    offlineSinceMs = 0L
                    WifiProvisionHttpServer.clearConnectFail()
                    ActivationEyeDisplay.showWifiOk()
                    try {
                        com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlHttpServer
                            .start(ctx)
                        ActivationEyeDisplay.warmSelfControlEyeCache(
                            com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlHttpServer
                                .configUrl()
                        )
                    } catch (_: Exception) {
                    }
                } else {
                    WifiProvisionHttpServer.noteConnectFail(ssid)
                    ActivationEyeDisplay.showWifiFail()
                    offlineSinceMs = 0L
                    softApBlockedThisOffline.set(false)
                    Log.w(TAG, "Nối Wi‑Fi nhà FAIL – lưu lastFailSsid=$ssid, SoftAP lại")
                    Thread.sleep(2_500)
                    if (!WifiStationHelper.isAssociated(ctx) && !inProvision.get()) {
                        enterProvisionLocked("retry-after-fail")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "eyes status: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyHomeWifi: ${e.message}", e)
            inProvision.set(false)
            softApBlockedThisOffline.set(false)
            try {
                WifiProvisionHttpServer.noteConnectFail(ssid)
                ActivationEyeDisplay.showWifiFail()
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(2_000)
                if (!WifiStationHelper.isAssociated(ctx) && !inProvision.get()) {
                    enterProvisionLocked("retry-after-error")
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun exitProvisionLocked(reason: String) {
        if (!inProvision.getAndSet(false)) return
        Log.i(TAG, "EXIT provision ($reason)")
        provisionQrOnEyes.set(false)
        try {
            ActivationEyeDisplay.dismissQrEyes()
        } catch (_: Exception) {
        }
        try {
            WifiProvisionHttpServer.stop()
            WifiSoftApHelper.releaseLocalOnly()
            WifiSoftApHelper.stop(app ?: return)
        } catch (e: Exception) {
            Log.w(TAG, "exit: ${e.message}")
        }
    }

    private fun fallbackOpenSettings(ctx: Context, detail: String) {
        if (!settingsShownThisOffline.compareAndSet(false, true)) {
            Log.w(TAG, "Settings đã mở lần này – bỏ spam. detail=$detail")
            return
        }
        Log.e(
            TAG,
            "===== KHÔNG PHÁT ĐƯỢC HOTSPOT ===== ROM chặn SoftAP. " +
                "Mở Settings Wi‑Fi thủ công. detail=$detail"
        )
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "open WIFI_SETTINGS: ${e.message}")
        }
        Thread {
            try {
                // SET WIFI = SoftAP fail, KHÔNG phải tên hotspot
                ActivationEyeDisplay.showCode("SET WIFI")
            } catch (_: Exception) {
            }
        }.start()
    }
}
