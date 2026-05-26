package com.ubtrobot.mini.speech.framework.demo.macprobe

import java.io.BufferedReader
import java.io.FileReader
import java.net.NetworkInterface
import java.util.Collections

/**
 * Đọc MAC wlan0 từ robot (NetworkInterface + sysfs). Dùng cho Device-Id Xiaozhi.
 */
object RobotWlanMacReader {

    private val MAC_PATTERN = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$", RegexOption.IGNORE_CASE)

    /** MAC wlan0 thật, hoặc null nếu không đọc được / bị Android chặn. */
    fun readWlan0Mac(): String? {
        readFromNetworkInterface("wlan0")?.let { return it }
        return readFromSysfs("/sys/class/net/wlan0/address")
    }

    private fun readFromNetworkInterface(name: String): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val ni = interfaces.find { it.name.equals(name, ignoreCase = true) } ?: return null
            normalizeMac(formatHardwareAddress(ni.hardwareAddress))
        } catch (_: Exception) {
            null
        }
    }

    private fun readFromSysfs(path: String): String? {
        return try {
            BufferedReader(FileReader(path)).use { reader ->
                normalizeMac(reader.readLine() ?: "")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatHardwareAddress(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        return bytes.joinToString(":") { String.format("%02x", it.toInt() and 0xff) }
    }

    private fun normalizeMac(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val mac = raw.trim().lowercase()
        if (!MAC_PATTERN.matches(mac)) return null
        if (mac == "02:00:00:00:00:00" || mac == "00:00:00:00:00:00") return null
        return mac
    }
}
