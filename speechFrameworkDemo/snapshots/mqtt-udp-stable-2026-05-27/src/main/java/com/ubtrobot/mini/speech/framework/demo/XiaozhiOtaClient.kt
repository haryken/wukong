package com.ubtrobot.mini.speech.framework.demo

import android.content.Context
import android.util.Log
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class XiaozhiActivateResult {
    /** HTTP 200 — broker cho publish (ESP32 ota.cc Activate). */
    SUCCESS,
    /** HTTP 202 — user chưa nhập mã trên web. */
    PENDING,
    FAILED
}

/**
 * Minimal OTA client to talk to Xiaozhi OTA HTTP endpoint.
 * It fetches XiaozhiOtaResult including the 6-digit activation code.
 */
class XiaozhiOtaClient(
    private val context: Context,
    private val deviceId: String,
    private val clientId: String
) {

    companion object {
        private const val TAG = "XiaozhiOtaClient"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    var otaResult: XiaozhiOtaResult? = null
        private set

    /**
     * Call OTA endpoint and parse result.
     *
     * @param checkVersionUrl Full OTA URL, same as used by Xiaozhi_Android-main / ESP32 CONFIG_OTA_URL.
     * @param deviceInfoJson Full board JSON like ESP32 GetSystemInfoJson (cần để OTA trả activation.challenge).
     */
    fun checkVersionBlocking(checkVersionUrl: String, deviceInfoJson: String = ""): Boolean {
        if (checkVersionUrl.length < 10) {
            Log.e(TAG, "Check version URL is not properly set: $checkVersionUrl")
            return false
        }

        val mediaType = MediaType.parse("application/json; charset=utf-8")
        val bodyText = deviceInfoJson.ifEmpty {
            JSONObject().apply {
                put("device_id", deviceId)
                put("client_id", clientId)
                put("platform", "android-mini")
            }.toString()
        }
        val body = RequestBody.create(mediaType, bodyText)

        val request = Request.Builder()
            .url(checkVersionUrl)
            .addHeader("Device-Id", deviceId)
            .addHeader("Client-Id", clientId)
            .addHeader("Activation-Version", "1")
            .addHeader("Accept-Language", "Chinese")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP failed: code=${response.code()}")
                response.close()
                return false
            }
            val respBody = response.body()?.string()
            response.close()
            if (respBody.isNullOrEmpty()) {
                Log.e(TAG, "Empty response body")
                return false
            }
            Log.i(TAG, "OTA response: $respBody")
            val root = JSONObject(respBody)
            otaResult = xiaozhiOtaResultFromJson(root)
            true
        } catch (e: Exception) {
            Log.e(TAG, "checkVersion failed: ${e.message}", e)
            false
        }
    }

    /**
     * ESP32 [Ota::Activate] — POST {checkVersionUrl}/activate sau khi user nhập mã trên xiaozhi.me.
     * Android không có efuse HMAC → body `{}` (Activation-Version 1).
     */
    fun activateBlocking(checkVersionUrl: String): XiaozhiActivateResult {
        val challenge = otaResult?.activation?.challenge.orEmpty()
        if (challenge.isEmpty()) {
            Log.w(TAG, "activate: OTA thiếu activation.challenge — vẫn thử POST /activate (body {})")
        }
        val base = checkVersionUrl.trim().trimEnd('/')
        val url = "$base/activate"
        val mediaType = MediaType.parse("application/json; charset=utf-8")
        val body = RequestBody.create(mediaType, "{}")
        val request = Request.Builder()
            .url(url)
            .addHeader("Device-Id", deviceId)
            .addHeader("Client-Id", clientId)
            .addHeader("Activation-Version", "1")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
        return try {
            val response = client.newCall(request).execute()
            val code = response.code()
            val respBody = response.body()?.string().orEmpty()
            response.close()
            when (code) {
                200 -> {
                    Log.i(TAG, "activate OK (HTTP 200)")
                    XiaozhiActivateResult.SUCCESS
                }
                202 -> {
                    Log.i(TAG, "activate pending (HTTP 202) — nhập mã trên xiaozhi.me rồi thử lại")
                    XiaozhiActivateResult.PENDING
                }
                else -> {
                    Log.w(TAG, "activate failed HTTP $code: $respBody")
                    XiaozhiActivateResult.FAILED
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "activate error: ${e.message}", e)
            XiaozhiActivateResult.FAILED
        }
    }
}

