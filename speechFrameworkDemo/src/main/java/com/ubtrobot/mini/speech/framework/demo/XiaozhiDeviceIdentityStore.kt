package com.ubtrobot.mini.speech.framework.demo

import android.content.Context
import android.util.Log
import com.ubtrobot.mini.speech.framework.demo.macprobe.RobotWlanMacReader
import com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlPresets
import com.ubtrobot.mini.speech.framework.demo.selfcontrol.SelfControlStore
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * Device-Id = MAC khóa Self-Control.
 * Client-Id = UUID ổn định giữa các lần boot; chỉ random khi Apply đổi cấu hình.
 */
object XiaozhiDeviceIdentityStore {
    private const val TAG = "XiaozhiDeviceId"
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_DEVICE_ID_JSON = "device_id"

    data class Identity(
        val deviceId: String,
        val clientId: String
    )

    fun getOrCreate(context: Context): Identity {
        SelfControlStore.init(context)
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = sp.getString(KEY_DEVICE_ID_JSON, null)?.let { parseIdentity(it) }
        val clientId = stored?.clientId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val deviceId = resolveDeviceIdOrFallback()
        val identity = Identity(deviceId, clientId)
        if (stored == null || stored.clientId != clientId || !stored.deviceId.equals(deviceId, true)) {
            save(sp, identity)
            Log.i(TAG, "Identity Device-Id=$deviceId Client-Id=$clientId (preset=${SelfControlStore.getPresetMacIdx()})")
        }
        return identity
    }

    /**
     * Random Client-Id mới, giữ Device-Id (MAC) hiện tại.
     * Chỉ gọi khi Self-Control Apply đổi cấu hình — không gọi mỗi boot.
     */
    fun rotateClientId(context: Context): Identity {
        SelfControlStore.init(context)
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deviceId = resolveDeviceIdOrFallback()
        val clientId = UUID.randomUUID().toString()
        val identity = Identity(deviceId, clientId)
        save(sp, identity)
        Log.i(
            TAG,
            "Rotated Client-Id=$clientId Device-Id=$deviceId (preset=${SelfControlStore.getPresetMacIdx()})"
        )
        return identity
    }

    /** Cập nhật snapshot Device-Id sau Apply (Client-Id giữ bản đã rotate gần nhất). */
    fun updateDeviceIdSnapshot(context: Context, deviceId: String) {
        val cur = getOrCreate(context)
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        save(sp, Identity(SelfControlPresets.normalizeMac(deviceId), cur.clientId))
    }

    private fun resolveDeviceIdOrFallback(): String {
        return SelfControlStore.resolveDeviceId().ifBlank {
            RobotWlanMacReader.readWlan0Mac()?.lowercase()
                ?: generateMacAddress()
        }
    }

    private fun save(sp: android.content.SharedPreferences, identity: Identity) {
        sp.edit().putString(KEY_DEVICE_ID_JSON, toJson(identity).toString()).apply()
    }

    private fun parseIdentity(jsonString: String): Identity? {
        return try {
            val obj = JSONObject(jsonString)
            val mac = obj.optString("mac_address", "")
            val uuid = obj.optString("uuid", "")
            if (uuid.isBlank()) null else Identity(mac.ifBlank { "00:00:00:00:00:00" }, uuid)
        } catch (_: Exception) {
            null
        }
    }

    private fun toJson(identity: Identity): JSONObject {
        return JSONObject().apply {
            put("mac_address", identity.deviceId)
            put("uuid", identity.clientId)
        }
    }

    private fun generateMacAddress(): String {
        return List(6) { Random.nextInt(0x00, 0xFF) }
            .joinToString(":") { String.format("%02x", it) }
    }
}
