package com.ubtrobot.mini.speech.framework.demo.selfcontrol

import android.content.Context
import android.util.Log
import com.ubtrobot.mini.speech.framework.demo.XiaozhiActivateResult
import com.ubtrobot.mini.speech.framework.demo.XiaozhiActivationStore
import com.ubtrobot.mini.speech.framework.demo.XiaozhiDeviceIdentityStore
import com.ubtrobot.mini.speech.framework.demo.XiaozhiOtaActivationCoordinator
import com.ubtrobot.mini.speech.framework.demo.XiaozhiOtaClient
import info.dourok.voicebot.data.model.DummyDataGenerator
import info.dourok.voicebot.data.model.toJson
import org.json.JSONObject

/**
 * Self-Control :8080 — kiểm tra Device-Id hiện tại đã liên kết xiaozhi.me chưa.
 * OTA không trả activation.code, hoặc /activate → 200 = đã liên kết; ngược lại trả mã 6 số.
 */
object SelfControlPairing {
    private const val TAG = "SelfControlPairing"
    private const val OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"

    private val lock = Any()

    fun check(context: Context): JSONObject = synchronized(lock) {
        val ctx = context.applicationContext
        XiaozhiActivationStore.init(ctx)
        val id = XiaozhiDeviceIdentityStore.getOrCreate(ctx)
        val base = JSONObject().put("device_id", id.deviceId)
        try {
            val client = XiaozhiOtaClient(ctx, id.deviceId, id.clientId)
            val info = DummyDataGenerator.generate(id.deviceId, id.clientId).toJson().toString()
            if (!client.checkVersionBlocking(OTA_URL, info)) {
                return base.put("success", false)
                    .put("error", "Không gọi được server Xiaozhi — kiểm tra Wi‑Fi/Internet")
            }
            val act = client.otaResult?.activation
            if (act == null || act.code.isEmpty()) {
                XiaozhiActivationStore.markActivated()
                return base.put("success", true).put("paired", true)
            }
            if (client.activateBlocking(OTA_URL) == XiaozhiActivateResult.SUCCESS) {
                XiaozhiActivationStore.markActivated()
                return base.put("success", true).put("paired", true)
            }
            XiaozhiActivationStore.clearActivated()
            XiaozhiOtaActivationCoordinator.bindClient(client)
            XiaozhiOtaActivationCoordinator.startPollIfNeeded("web pair check")
            Log.i(TAG, "Chưa liên kết Device-Id=${id.deviceId} code=${act.code}")
            base.put("success", true)
                .put("paired", false)
                .put("code", act.code)
                .put("message", act.message)
        } catch (e: Exception) {
            Log.w(TAG, "check: ${e.message}", e)
            base.put("success", false).put("error", e.message ?: "fail")
        }
    }
}
