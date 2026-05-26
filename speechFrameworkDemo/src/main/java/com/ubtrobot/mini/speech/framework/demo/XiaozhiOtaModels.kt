package com.ubtrobot.mini.speech.framework.demo

import org.json.JSONObject

/** OTA response — activation, firmware, server time, mqtt (optional). */
data class XiaozhiOtaResult(
    val activation: XiaozhiActivation?,
    val serverTime: XiaozhiServerTime?,
    val firmware: XiaozhiFirmware?,
    val mqttConfig: XiaozhiMqttConfig? = null
)

data class XiaozhiServerTime(
    val timestamp: Long,
    val timezone: String?,
    val timezoneOffset: Int
)

data class XiaozhiFirmware(
    val version: String,
    val url: String
)

data class XiaozhiActivation(
    val code: String,
    val message: String,
    val challenge: String = "",
    val timeoutMs: Long = 0L
)

fun xiaozhiOtaResultFromJson(json: JSONObject): XiaozhiOtaResult {
    return XiaozhiOtaResult(
        activation = json.optJSONObject("activation")?.let { xiaozhiActivationFromJson(it) },
        serverTime = json.optJSONObject("server_time")?.let { xiaozhiServerTimeFromJson(it) },
        firmware = json.optJSONObject("firmware")?.let { xiaozhiFirmwareFromJson(it) },
        mqttConfig = json.optJSONObject("mqtt")?.let { xiaozhiMqttConfigFromJson(it) }
    )
}

private fun xiaozhiServerTimeFromJson(json: JSONObject): XiaozhiServerTime {
    return XiaozhiServerTime(
        timestamp = json.getLong("timestamp"),
        timezone = json.optString("timezone", null),
        timezoneOffset = json.optInt("timezone_offset", 0)
    )
}

private fun xiaozhiFirmwareFromJson(json: JSONObject): XiaozhiFirmware {
    return XiaozhiFirmware(
        version = json.optString("version", ""),
        url = json.optString("url", "")
    )
}

private fun xiaozhiActivationFromJson(json: JSONObject): XiaozhiActivation {
    return XiaozhiActivation(
        code = json.optString("code", ""),
        message = json.optString("message", ""),
        challenge = json.optString("challenge", ""),
        timeoutMs = json.optLong("timeout_ms", 0L)
    )
}
