package com.ubtrobot.mini.speech.framework.demo.selfcontrol

import android.util.Log
import com.ubtrobot.mini.speech.framework.demo.XiaozhiMqttConfig
import com.ubtrobot.mini.speech.framework.demo.XiaozhiMqttConfigStore
import com.ubtrobot.mini.speech.framework.demo.deriveMqttSubscribeTopic

/**
 * Patch MQTT client_id / username theo Device-Id mới (Otto PatchMqttIdentityToCurrentMac).
 * client_id dạng GID_xxx@@@mac_safe@@@mac_safe (hoặc …@@@uuid → đổi phần mac).
 */
object SelfControlMqttIdentityPatch {
    private const val TAG = "SelfControlMqttPatch"

    fun patchStoreToDeviceId(deviceId: String): Boolean {
        val cfg = XiaozhiMqttConfigStore.get() ?: run {
            Log.w(TAG, "Không có MQTT config — bỏ patch")
            return false
        }
        val mac = SelfControlPresets.normalizeMac(deviceId)
        val safe = SelfControlPresets.macToSafe(mac)
        val newClientId = patchClientId(cfg.clientId, safe)
        val newUser = patchUsername(cfg.username, mac, safe)
        val newSub = deriveMqttSubscribeTopic(newClientId).ifBlank { cfg.subscribeTopic }
        val updated = XiaozhiMqttConfig(
            endpoint = cfg.endpoint,
            clientId = newClientId,
            username = newUser,
            password = cfg.password,
            publishTopic = cfg.publishTopic,
            subscribeTopic = newSub
        )
        XiaozhiMqttConfigStore.set(updated)
        Log.i(TAG, "Patched MQTT clientId=$newClientId username=$newUser")
        return true
    }

    private fun patchClientId(old: String, macSafe: String): String {
        val parts = old.split("@@@")
        return when {
            parts.size >= 3 -> listOf(parts[0], macSafe, macSafe).joinToString("@@@")
            parts.size == 2 -> listOf(parts[0], macSafe, macSafe).joinToString("@@@")
            else -> "GID_self@@@$macSafe@@@$macSafe"
        }
    }

    private fun patchUsername(old: String, mac: String, macSafe: String): String {
        val o = old.trim()
        return when {
            SelfControlPresets.isValidMac(o) -> mac
            o.contains('_') && o.count { it == '_' } >= 5 -> macSafe
            o.contains(':') -> mac
            else -> o // password-style username — giữ
        }
    }
}
