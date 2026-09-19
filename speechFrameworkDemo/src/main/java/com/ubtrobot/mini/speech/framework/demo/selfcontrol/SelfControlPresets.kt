package com.ubtrobot.mini.speech.framework.demo.selfcontrol

/**
 * MAC pools + voice — port từ device_identity_presets.h (Otto).
 * idx 1..5: 2 pool theo giọng; idx 7: 1 pool 20 MAC; 0/6: không random.
 */
object SelfControlPresets {
    const val MANUAL_CUSTOM_MAC_IDX = 6
    const val DAILY_CHAT_IDX = 7
    const val MAX_IDX = 7
    /** Mặc định khi chưa cấu hình: Explorers */
    const val DEFAULT_IDX = 1

    const val VOICE_A = 0 // Song ngữ / Việt
    const val VOICE_B = 1 // Anh

    /** Explorers · Song ngữ (ex_voice=0) */
    val EX_BILINGUAL_POOL: List<String> = listOf(
        "ba:53:9e:c5:ba:10", "ba:53:9e:c5:ba:11", "ba:53:9e:c5:ba:12",
        "ba:53:9e:c5:ba:13", "ba:53:9e:c5:ba:14", "ba:53:9e:c5:ba:15",
        "ba:53:9e:c5:ba:16", "ba:53:9e:c5:ba:17", "ba:53:9e:c5:ba:18",
        "ba:53:9e:c5:ba:19",
    )

    /** Explorers · Anh (ex_voice=1) */
    val EX_ENGLISH_POOL: List<String> = listOf(
        "ba:53:9e:c5:fe:10", "ba:53:9e:c5:fe:11", "ba:53:9e:c5:fe:12",
        "ba:53:9e:c5:fe:13", "ba:53:9e:c5:fe:14", "ba:53:9e:c5:fe:15",
        "ba:53:9e:c5:fe:16", "ba:53:9e:c5:fe:17", "ba:53:9e:c5:fe:18",
        "ba:53:9e:c5:fe:19",
    )

    /** Young Innovators · Việt (yi_voice=0) */
    val YI_VIET_POOL: List<String> = listOf(
        "ba:53:9e:c5:ef:10", "ba:53:9e:c5:ef:11", "ba:53:9e:c5:ef:12",
        "ba:53:9e:c5:ef:13", "ba:53:9e:c5:ef:14", "ba:53:9e:c5:ef:15",
        "ba:53:9e:c5:ef:16", "ba:53:9e:c5:ef:17", "ba:53:9e:c5:ef:18",
        "ba:53:9e:c5:ef:19",
    )

    /** Young Innovators · Anh (yi_voice=1) */
    val YI_ENGLISH_POOL: List<String> = listOf(
        "ba:53:9e:c6:fe:10", "ba:53:9e:c6:fe:11", "ba:53:9e:c6:fe:12",
        "ba:53:9e:c6:fe:13", "ba:53:9e:c6:fe:14", "ba:53:9e:c6:fe:15",
        "ba:53:9e:c6:fe:16", "ba:53:9e:c6:fe:17", "ba:53:9e:c6:fe:18",
        "ba:53:9e:c6:fe:19",
    )

    /** Future Leaders · Việt (fl_voice=0) */
    val FL_VIET_POOL: List<String> = listOf(
        "ba:53:9e:c7:ef:10", "ba:53:9e:c7:ef:11", "ba:53:9e:c7:ef:12",
        "ba:53:9e:c7:ef:13", "ba:53:9e:c7:ef:14", "ba:53:9e:c7:ef:15",
        "ba:53:9e:c7:ef:16", "ba:53:9e:c7:ef:17", "ba:53:9e:c7:ef:18",
        "ba:53:9e:c7:ef:19",
    )

    /** Future Leaders · Anh (fl_voice=1) */
    val FL_ENGLISH_POOL: List<String> = listOf(
        "ba:53:9e:c8:ef:10", "ba:53:9e:c8:ef:11", "ba:53:9e:c8:ef:12",
        "ba:53:9e:c8:ef:13", "ba:53:9e:c8:ef:14", "ba:53:9e:c8:ef:15",
        "ba:53:9e:c8:ef:16", "ba:53:9e:c8:ef:17", "ba:53:9e:c8:ef:18",
        "ba:53:9e:c8:ef:19",
    )

    /** IELTS · Việt (ielts_voice=0) */
    val IELTS_VIET_POOL: List<String> = listOf(
        "ba:53:9e:c2:ef:10", "ba:53:9e:c2:ef:11", "ba:53:9e:c2:ef:12",
        "ba:53:9e:c2:ef:13", "ba:53:9e:c2:ef:14", "ba:53:9e:c2:ef:15",
        "ba:53:9e:c2:ef:16", "ba:53:9e:c2:ef:17", "ba:53:9e:c2:ef:18",
        "ba:53:9e:c2:ef:19",
    )

    /** IELTS · Anh (ielts_voice=1) */
    val IELTS_ENGLISH_POOL: List<String> = listOf(
        "ba:53:9e:c3:ef:10", "ba:53:9e:c3:ef:11", "ba:53:9e:c3:ef:12",
        "ba:53:9e:c3:ef:13", "ba:53:9e:c3:ef:14", "ba:53:9e:c3:ef:15",
        "ba:53:9e:c3:ef:16", "ba:53:9e:c3:ef:17", "ba:53:9e:c3:ef:18",
        "ba:53:9e:c3:ef:19",
    )

    /** TOEIC · Việt (toeic_voice=0) */
    val TOEIC_VIET_POOL: List<String> = listOf(
        "ba:53:9e:c9:ef:10", "ba:53:9e:c9:ef:11", "ba:53:9e:c9:ef:12",
        "ba:53:9e:c9:ef:13", "ba:53:9e:c9:ef:14", "ba:53:9e:c9:ef:15",
        "ba:53:9e:c9:ef:16", "ba:53:9e:c9:ef:17", "ba:53:9e:c9:ef:18",
        "ba:53:9e:c9:ef:19",
    )

    /** TOEIC · Anh (toeic_voice=1) */
    val TOEIC_ENGLISH_POOL: List<String> = listOf(
        "ba:53:9e:c1:ef:10", "ba:53:9e:c1:ef:11", "ba:53:9e:c1:ef:12",
        "ba:53:9e:c1:ef:13", "ba:53:9e:c1:ef:14", "ba:53:9e:c1:ef:15",
        "ba:53:9e:c1:ef:16", "ba:53:9e:c1:ef:17", "ba:53:9e:c1:ef:18",
        "ba:53:9e:c1:ef:19",
    )

    /** Pool 20 MAC — Giao tiếp hằng ngày (idx=7). */
    val DAILY_CHAT_POOL: List<String> = listOf(
        "1c:db:d4:b5:73:3c",
        "58:a0:23:a6:fe:31",
        "a8:b5:44:dd:e3:cf",
        "1c:db:d4:b5:74:7c",
        "1c:db:d4:b5:6a:d8",
        "1c:db:d4:b5:74:54",
        "1c:db:d4:b5:72:ec",
        "1c:db:d4:b5:71:d4",
        "1c:db:d4:b5:74:d4",
        "1c:db:d4:a9:48:84",
        "1c:db:d4:a9:59:b4",
        "1c:db:d4:a9:5b:a0",
        "28:df:eb:02:6c:7d",
        "bc:fc:e7:8a:d8:06",
        "dc:b4:d9:0c:a4:9c",
        "dc:b4:d9:0c:a4:80",
        "dc:b4:d9:0c:a6:00",
        "dc:b4:d9:03:4e:f4",
        "dc:b4:d9:0c:a5:38",
        "dc:b4:d9:03:43:38",
    )

    fun courseUsesMacPool(idx: Int): Boolean =
        idx in 1..5 || idx == DAILY_CHAT_IDX

    fun usesCustomMacNvs(idx: Int): Boolean =
        idx == 0 || idx == MANUAL_CUSTOM_MAC_IDX || courseUsesMacPool(idx)

    /** MAC đại diện (voice=0) để UI/API preset_macs. */
    fun getPresetMac(idx: Int): String? = when (idx) {
        1 -> EX_BILINGUAL_POOL.first()
        2 -> YI_VIET_POOL.first()
        3 -> FL_VIET_POOL.first()
        4 -> IELTS_VIET_POOL.first()
        5 -> TOEIC_VIET_POOL.first()
        else -> null
    }

    fun poolForCourse(idx: Int, voice: Int): List<String>? {
        val v = if (voice == VOICE_B) VOICE_B else VOICE_A
        return when (idx) {
            1 -> if (v == VOICE_B) EX_ENGLISH_POOL else EX_BILINGUAL_POOL
            2 -> if (v == VOICE_B) YI_ENGLISH_POOL else YI_VIET_POOL
            3 -> if (v == VOICE_B) FL_ENGLISH_POOL else FL_VIET_POOL
            4 -> if (v == VOICE_B) IELTS_ENGLISH_POOL else IELTS_VIET_POOL
            5 -> if (v == VOICE_B) TOEIC_ENGLISH_POOL else TOEIC_VIET_POOL
            DAILY_CHAT_IDX -> DAILY_CHAT_POOL
            else -> null
        }
    }

    fun pickFromPool(pool: List<String>): String = pool.random()

    fun pickDailyChatMac(): String = pickFromPool(DAILY_CHAT_POOL)

    /** MAC còn sót từ pool khóa — không dùng cho idx=0 (Tự cấu hình). */
    fun isMacInAnyCoursePool(mac: String?): Boolean {
        val n = normalizeMac(mac)
        if (n.isEmpty()) return false
        val all = EX_BILINGUAL_POOL + EX_ENGLISH_POOL + YI_VIET_POOL + YI_ENGLISH_POOL +
            FL_VIET_POOL + FL_ENGLISH_POOL + IELTS_VIET_POOL + IELTS_ENGLISH_POOL +
            TOEIC_VIET_POOL + TOEIC_ENGLISH_POOL + DAILY_CHAT_POOL
        return all.any { it.equals(n, ignoreCase = true) }
    }

    fun normalizeMac(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val hex = raw.lowercase().replace(Regex("[^0-9a-f]"), "")
        if (hex.length != 12) {
            return raw.trim().lowercase().replace('-', ':')
        }
        return hex.chunked(2).joinToString(":")
    }

    fun isValidMac(mac: String?): Boolean {
        val n = normalizeMac(mac)
        return Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$").matches(n)
    }

    fun macToSafe(mac: String): String = normalizeMac(mac).replace(':', '_')
}
