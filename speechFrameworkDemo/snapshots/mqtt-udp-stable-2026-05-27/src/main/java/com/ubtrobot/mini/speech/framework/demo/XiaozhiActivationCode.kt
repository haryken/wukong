package com.ubtrobot.mini.speech.framework.demo

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * Local deterministic 6-digit code generator (fallback).
 *
 * IMPORTANT: In Xiaozhi_Android-main, the activation code is provided by OTA server response.
 * This function is only a local fallback for display/logging when server does not provide a code.
 * If your backend expects a specific algorithm, replace this with the backend's algorithm.
 */
object XiaozhiActivationCode {
    fun generate6Digits(deviceId: String, clientId: String): String {
        val input = "${deviceId.trim()}|${clientId.trim()}"
        val crc = CRC32().apply { update(input.toByteArray(StandardCharsets.UTF_8)) }.value
        val num = (crc % 1_000_000L).toInt()
        return num.toString().padStart(6, '0')
    }
}

