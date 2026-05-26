package com.ubtrobot.mini.speech.framework.demo

import android.content.Context

/** Lưu transport user chọn trên MainActivity (WebSocket vs MQTT+UDP). */
object XiaozhiTransportPreference {
    private const val PREFS = "xiaozhi_transport"
    private const val KEY = "transport"

    @JvmStatic
    fun get(context: Context): XiaozhiTransportType {
        val name = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, XiaozhiTransportType.WEBSOCKET.name)
        return try {
            XiaozhiTransportType.valueOf(name ?: XiaozhiTransportType.WEBSOCKET.name)
        } catch (_: Exception) {
            XiaozhiTransportType.WEBSOCKET
        }
    }

    @JvmStatic
    fun set(context: Context, type: XiaozhiTransportType) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, type.name)
            .apply()
    }
}
