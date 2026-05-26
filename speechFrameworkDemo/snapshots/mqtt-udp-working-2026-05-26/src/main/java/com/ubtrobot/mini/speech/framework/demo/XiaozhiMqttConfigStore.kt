package com.ubtrobot.mini.speech.framework.demo

import android.content.Context
import org.json.JSONObject

/** MQTT config từ OTA — lưu disk để sau cài APK / restart vẫn dùng MQTT ngay (không fallback WS). */
object XiaozhiMqttConfigStore {
    private const val PREFS = "xiaozhi_mqtt_config"
    private const val KEY_JSON = "mqtt_json"

    @Volatile
    private var config: XiaozhiMqttConfig? = null

    @Volatile
    private var prefsContext: Context? = null

    @JvmStatic
    fun init(context: Context) {
        prefsContext = context.applicationContext
        if (config != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_JSON, null)?.trim().orEmpty()
        if (raw.isEmpty()) return
        try {
            config = xiaozhiMqttConfigFromJson(JSONObject(raw))
        } catch (e: Exception) {
            android.util.Log.w("XiaozhiMqttConfigStore", "load persisted mqtt failed: ${e.message}")
        }
    }

    @JvmStatic
    fun set(value: XiaozhiMqttConfig?) {
        config = value
        val ctx = prefsContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value == null) {
            prefs.edit().remove(KEY_JSON).apply()
            return
        }
        val json = JSONObject().apply {
            put("endpoint", value.endpoint)
            put("client_id", value.clientId)
            put("username", value.username)
            put("password", value.password)
            put("publish_topic", value.publishTopic)
            put("subscribe_topic", value.subscribeTopic)
        }
        prefs.edit().putString(KEY_JSON, json.toString()).apply()
    }

    @JvmStatic
    fun get(): XiaozhiMqttConfig? = config

    @JvmStatic
    fun hasConfig(): Boolean = config != null
}
