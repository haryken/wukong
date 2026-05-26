package com.ubtrobot.mini.speech.framework.demo

import org.json.JSONObject

/** MQTT broker config từ OTA (giống Xiaozhi_Android / xiaozhi-esp32). */
data class XiaozhiMqttConfig(
    val endpoint: String,
    val clientId: String,
    val username: String,
    val password: String,
    val publishTopic: String,
    val subscribeTopic: String
)

fun xiaozhiMqttConfigFromJson(json: JSONObject): XiaozhiMqttConfig {
    val clientId = json.getString("client_id")
    val rawSub = json.optString("subscribe_topic", "").trim()
    val subscribe = when {
        rawSub.isNotEmpty() && !rawSub.equals("null", ignoreCase = true) -> rawSub
        else -> deriveMqttSubscribeTopic(clientId)
    }
    return XiaozhiMqttConfig(
        endpoint = json.getString("endpoint"),
        clientId = clientId,
        username = json.getString("username"),
        password = json.getString("password"),
        publishTopic = json.getString("publish_topic"),
        subscribeTopic = subscribe
    )
}

/** client_id dạng GID_test@@@fc_53_9e_c3_d1_6a@@@uuid → devices/p2p/fc_53_9e_c3_d1_6a */
fun deriveMqttSubscribeTopic(clientId: String): String {
    val parts = clientId.split("@@@")
    if (parts.size >= 2 && parts[1].isNotBlank()) {
        return "devices/p2p/${parts[1]}"
    }
    return ""
}
