package com.ubtrobot.mini.speech.framework.demo

import android.util.Log
import com.ubtech.utilcode.utils.thread.ThreadPool

/** Sau OTA hiện mã → loop POST /activate cho tới HTTP 200. */
object XiaozhiOtaActivationCoordinator {
    private const val TAG = "XiaozhiActivate"
    private const val OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"

    @Volatile
    private var pollRunning = false

    @Volatile
    private var otaClient: XiaozhiOtaClient? = null

    fun bindClient(client: XiaozhiOtaClient) {
        otaClient = client
    }

    fun startPollIfNeeded(reason: String) {
        if (XiaozhiActivationStore.isActivated()) {
            Log.i(TAG, "Đã activate — bỏ qua poll ($reason)")
            return
        }
        if (pollRunning) {
            Log.d(TAG, "Poll activate đang chạy ($reason)")
            return
        }
        val client = otaClient
        if (client == null) {
            Log.w(TAG, "Chưa có OTA client — restart app để chạy OTA ($reason)")
            return
        }
        val activation = client.otaResult?.activation
        if (activation == null || activation.code.isEmpty()) {
            Log.w(TAG, "OTA không có activation.code — poll POST /activate ($reason)")
            startActivatePollWithoutCode(reason)
            return
        }
        pollRunning = true
        Log.i(
            TAG,
            "Bắt đầu poll /activate ($reason) code=${activation.code} " +
                "challenge=${if (activation.challenge.isEmpty()) "MISSING" else "ok"}"
        )
        ThreadPool.runOnNonUIThread {
            try {
                for (i in 1..20) {
                    if (XiaozhiActivationStore.isActivated()) return@runOnNonUIThread
                    when (client.activateBlocking(OTA_URL)) {
                        XiaozhiActivateResult.SUCCESS -> {
                            XiaozhiActivationStore.markActivated()
                            Log.i(TAG, "activate OK ($i/20)")
                            return@runOnNonUIThread
                        }
                        XiaozhiActivateResult.PENDING -> {
                            Log.i(
                                TAG,
                                "Chờ nhập mã ${activation.code} trên xiaozhi.me ($i/20, HTTP 202)"
                            )
                            Thread.sleep(3000)
                        }
                        XiaozhiActivateResult.FAILED -> Thread.sleep(8000)
                    }
                }
                Log.w(TAG, "Hết 20 lần /activate — nhập mã ${activation.code} trên xiaozhi.me")
            } finally {
                pollRunning = false
            }
        }
    }

    private fun startActivatePollWithoutCode(reason: String) {
        pollRunning = true
        ThreadPool.runOnNonUIThread {
            try {
                val client = otaClient ?: return@runOnNonUIThread
                for (i in 1..10) {
                    if (XiaozhiActivationStore.isActivated()) return@runOnNonUIThread
                    when (client.activateBlocking(OTA_URL)) {
                        XiaozhiActivateResult.SUCCESS -> {
                            XiaozhiActivationStore.markActivated()
                            Log.i(TAG, "activate OK không cần code ($i/10, $reason)")
                            return@runOnNonUIThread
                        }
                        XiaozhiActivateResult.PENDING ->
                            Log.i(TAG, "activate HTTP 202 ($i/10)")
                        XiaozhiActivateResult.FAILED ->
                            Log.w(TAG, "activate FAILED ($i/10)")
                    }
                    Thread.sleep(3000)
                }
                Log.w(TAG, "Hết 10 lần /activate ($reason)")
            } finally {
                pollRunning = false
            }
        }
    }
}
