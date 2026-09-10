package com.ubtrobot.mini.speech.framework.demo

import android.content.Context
import android.util.Log
import com.ubtrobot.mini.speech.framework.demo.macprobe.RobotWlanMacReader
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * Device-Id (Xiaozhi mac_address): ưu tiên MAC wlan0 thật từ robot; fallback random nếu không đọc được.
 * Client-Id: UUID lưu cố định sau lần đầu.
 */
object XiaozhiDeviceIdentityStore {
    private const val TAG = "XiaozhiDeviceId"
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_DEVICE_ID_JSON = "device_id"

    data class Identity(
        val deviceId: String, // Device-Id header (mac_address in Xiaozhi)
        val clientId: String  // Client-Id header (uuid in Xiaozhi)
    )

    fun getOrCreate(context: Context): Identity {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hardwareMac = RobotWlanMacReader.readWlan0Mac()
        val stored = sp.getString(KEY_DEVICE_ID_JSON, null)?.let { parseIdentity(it) }

        if (stored != null) {
            if (hardwareMac != null && !stored.deviceId.equals(hardwareMac, ignoreCase = true)) {
                val updated = Identity(hardwareMac, stored.clientId)
                save(sp, updated)
                Log.i(TAG, "Device-Id → wlan0 MAC: $hardwareMac (trước: ${stored.deviceId})")
                return updated
            }
            return stored
        }

        val identity = Identity(
            deviceId = hardwareMac ?: generateMacAddress().also {
                Log.w(TAG, "Không đọc được wlan0 MAC — dùng random: $it")
            },
            clientId = UUID.randomUUID().toString()
        )
        if (hardwareMac != null) {
            Log.i(TAG, "Device-Id mới từ wlan0 MAC: $hardwareMac")
        }
        save(sp, identity)
        return identity
    }

    private fun save(sp: android.content.SharedPreferences, identity: Identity) {
        sp.edit().putString(KEY_DEVICE_ID_JSON, toJson(identity).toString()).apply()
    }

    private fun parseIdentity(jsonString: String): Identity? {
        return try {
            val obj = JSONObject(jsonString)
            val mac = obj.optString("mac_address", "")
            val uuid = obj.optString("uuid", "")
            if (mac.isBlank() || uuid.isBlank()) null else Identity(mac, uuid)
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
