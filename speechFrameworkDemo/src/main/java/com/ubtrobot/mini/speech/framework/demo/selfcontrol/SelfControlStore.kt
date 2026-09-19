package com.ubtrobot.mini.speech.framework.demo.selfcontrol

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ubtrobot.mini.speech.framework.demo.macprobe.RobotWlanMacReader
import org.json.JSONObject

/**
 * SharedPreferences Self-Control — tương đương NVS Otto
 * (pool MAC + giọng + custom_mac).
 */
object SelfControlStore {
    private const val TAG = "SelfControlStore"
    private const val PREFS = "xiaozhi_self_control"
    /** Server tìm/stream nhạc mặc định (kytuoi YouTube API). */
    const val DEFAULT_MUSIC_SERVER = "https://youtube.kytuoi.com"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Boot: khóa pool → random lại MAC theo giọng đang chọn
        val idx = getPresetMacIdx()
        if (SelfControlPresets.courseUsesMacPool(idx)) {
            val mac = pickAndSavePoolMac(idx)
            Log.i(TAG, "Boot pool idx=$idx → random MAC $mac")
        }
    }

    private fun sp(): SharedPreferences =
        prefs ?: error("SelfControlStore.init() chưa gọi")

    fun getPresetMacIdx(): Int {
        val p = prefs ?: return SelfControlPresets.DEFAULT_IDX
        if (!p.contains("preset_mac_idx")) return SelfControlPresets.DEFAULT_IDX
        return p.getInt("preset_mac_idx", SelfControlPresets.DEFAULT_IDX)
            .coerceIn(0, SelfControlPresets.MAX_IDX)
    }

    fun setPresetMacIdx(idx: Int) {
        sp().edit().putInt("preset_mac_idx", idx.coerceIn(0, SelfControlPresets.MAX_IDX)).apply()
    }

    fun getCustomMac(): String =
        SelfControlPresets.normalizeMac(prefs?.getString("custom_mac", "") ?: "")

    fun setCustomMac(mac: String) {
        val n = SelfControlPresets.normalizeMac(mac)
        sp().edit().putString("custom_mac", n).apply()
    }

    fun getStudentName(): String = prefs?.getString("student_name", "") ?: ""

    fun setStudentName(name: String) {
        sp().edit().putString("student_name", name.trim()).apply()
    }

    /**
     * Base URL server nhạc (search + stream MP3), không dấu / cuối.
     * Mặc định: [DEFAULT_MUSIC_SERVER] (youtube.kytuoi.com).
     */
    fun getMusicServerUrl(): String {
        val raw = prefs?.getString("music_server_url", null)?.trim().orEmpty()
        return normalizeMusicServerUrl(if (raw.isEmpty()) DEFAULT_MUSIC_SERVER else raw)
    }

    fun setMusicServerUrl(url: String) {
        val n = normalizeMusicServerUrl(url)
        sp().edit().putString("music_server_url", n).apply()
        Log.i(TAG, "music_server_url=$n")
    }

    fun normalizeMusicServerUrl(url: String): String {
        var u = url.trim()
        if (u.isEmpty()) return DEFAULT_MUSIC_SERVER
        if (!u.startsWith("http://", ignoreCase = true) &&
            !u.startsWith("https://", ignoreCase = true)
        ) {
            u = "https://$u"
        }
        while (u.endsWith("/")) u = u.dropLast(1)
        return u
    }

    private fun getVoice(key: String): Int {
        val v = prefs?.getInt(key, SelfControlPresets.VOICE_A) ?: SelfControlPresets.VOICE_A
        return if (v == SelfControlPresets.VOICE_B) SelfControlPresets.VOICE_B else SelfControlPresets.VOICE_A
    }

    private fun setVoice(key: String, voice: Int) {
        val v = if (voice == SelfControlPresets.VOICE_B) SelfControlPresets.VOICE_B else SelfControlPresets.VOICE_A
        sp().edit().putInt(key, v).apply()
    }

    fun getExVoice(): Int = getVoice("ex_voice")
    fun setExVoice(v: Int) = setVoice("ex_voice", v)
    fun getYiVoice(): Int = getVoice("yi_voice")
    fun setYiVoice(v: Int) = setVoice("yi_voice", v)
    fun getFlVoice(): Int = getVoice("fl_voice")
    fun setFlVoice(v: Int) = setVoice("fl_voice", v)
    fun getIeltsVoice(): Int = getVoice("ielts_voice")
    fun setIeltsVoice(v: Int) = setVoice("ielts_voice", v)
    fun getToeicVoice(): Int = getVoice("toeic_voice")
    fun setToeicVoice(v: Int) = setVoice("toeic_voice", v)

    fun voiceForCourse(idx: Int): Int = when (idx) {
        1 -> getExVoice()
        2 -> getYiVoice()
        3 -> getFlVoice()
        4 -> getIeltsVoice()
        5 -> getToeicVoice()
        else -> SelfControlPresets.VOICE_A
    }

    /** Random 1 MAC từ pool khóa (+ giọng) → ghi custom_mac. */
    fun pickAndSavePoolMac(idx: Int): String {
        val pool = SelfControlPresets.poolForCourse(idx, voiceForCourse(idx))
            ?: SelfControlPresets.DAILY_CHAT_POOL
        val mac = SelfControlPresets.pickFromPool(pool)
        setCustomMac(mac)
        return mac
    }

    fun getUnitsFlat(courseIdx: Int): String =
        prefs?.getString("units_$courseIdx", "1") ?: "1"

    fun setUnitsFlat(courseIdx: Int, unit: String) {
        sp().edit().putString("units_$courseIdx", unit.ifBlank { "1" }).apply()
    }

    fun getExSub(): Int = prefs?.getInt("ex_sub", 0) ?: 0
    fun setExSub(v: Int) { sp().edit().putInt("ex_sub", v.coerceIn(0, 4)).apply() }
    fun getExUnit(sub: Int): String = prefs?.getString("ex_u$sub", "1") ?: "1"
    fun setExUnit(sub: Int, u: String) { sp().edit().putString("ex_u$sub", u.ifBlank { "1" }).apply() }

    fun getYiSub(): Int = prefs?.getInt("yi_sub", 0) ?: 0
    fun setYiSub(v: Int) { sp().edit().putInt("yi_sub", v.coerceIn(0, 2)).apply() }
    fun getYiUnit(sub: Int): String = prefs?.getString("yi_u$sub", "1") ?: "1"
    fun setYiUnit(sub: Int, u: String) { sp().edit().putString("yi_u$sub", u.ifBlank { "1" }).apply() }

    fun getFlSub(): Int = prefs?.getInt("fl_sub", 0) ?: 0
    fun setFlSub(v: Int) { sp().edit().putInt("fl_sub", v.coerceIn(0, 0)).apply() }
    fun getFlUnit(sub: Int): String = prefs?.getString("fl_u$sub", "1") ?: "1"
    fun setFlUnit(sub: Int, u: String) { sp().edit().putString("fl_u$sub", u.ifBlank { "1" }).apply() }

    fun chipMac(): String =
        RobotWlanMacReader.readWlan0Mac()?.lowercase()
            ?: "00:00:00:00:00:00"

    /**
     * Device-Id runtime (Otto GetMacAddress):
     * pool courses → custom_mac; 0/6 → custom hoặc chip.
     */
    fun resolveDeviceId(): String {
        val idx = getPresetMacIdx()
        when {
            SelfControlPresets.courseUsesMacPool(idx) -> {
                val c = getCustomMac()
                if (SelfControlPresets.isValidMac(c)) return c
                return pickAndSavePoolMac(idx)
            }
            idx == SelfControlPresets.MANUAL_CUSTOM_MAC_IDX -> {
                val c = getCustomMac()
                return if (SelfControlPresets.isValidMac(c)) c else chipMac()
            }
            idx == 0 -> {
                val c = getCustomMac()
                if (SelfControlPresets.isValidMac(c) && !SelfControlPresets.isMacInAnyCoursePool(c)) {
                    return c
                }
                if (SelfControlPresets.isMacInAnyCoursePool(c)) {
                    setCustomMac("")
                    Log.w(TAG, "idx=0 clear leftover pool MAC $c → chip")
                }
                return chipMac()
            }
            else -> return chipMac()
        }
    }

    fun activeSubIdx(): Int = when (getPresetMacIdx()) {
        1 -> getExSub()
        2 -> getYiSub()
        3 -> getFlSub()
        else -> 0
    }

    fun activeUnitSelection(): String {
        val idx = getPresetMacIdx()
        return when (idx) {
            1 -> getExUnit(getExSub())
            2 -> getYiUnit(getYiSub())
            3 -> getFlUnit(getFlSub())
            in 4..5 -> getUnitsFlat(idx)
            else -> "1"
        }
    }

    /** Ghi sub+unit đồng bộ (commit) — tránh apply() race khiến web reload sai unit. */
    private fun persistActiveLesson(sub: Int, unit: Int) {
        val idx = getPresetMacIdx()
        val u = unit.coerceAtLeast(1).toString()
        val ed = sp().edit()
        when (idx) {
            1 -> {
                ed.putInt("ex_sub", sub.coerceIn(0, 4))
                ed.putString("ex_u$sub", u)
            }
            2 -> {
                ed.putInt("yi_sub", sub.coerceIn(0, 2))
                ed.putString("yi_u$sub", u)
            }
            3 -> {
                ed.putInt("fl_sub", 0)
                ed.putString("fl_u0", u)
            }
            in 4..5 -> ed.putString("units_$idx", u)
        }
        ed.commit()
    }

    data class ShiftUnitResult(
        val success: Boolean,
        val message: String,
        val courseIdx: Int = 0,
        val subIdx: Int = 0,
        val unitIdx: Int = 0,
        val unitName: String = "",
        val clamped: Boolean = false,
    )

    /**
     * Unit tiếp theo / trước trong khóa đang chọn.
     * Có sách con: hết unit → sang sách tiếp (hoặc lùi sách trước).
     * @param delta +1 next, -1 prev
     */
    fun shiftActiveUnit(delta: Int): ShiftUnitResult {
        val d = when {
            delta > 0 -> 1
            delta < 0 -> -1
            else -> return ShiftUnitResult(false, "direction phải là next hoặc prev")
        }
        val idx = getPresetMacIdx()
        val course = SelfControlCourses.get(idx)
            ?: return ShiftUnitResult(false, "Không có khóa học")
        if (idx !in 1..5) {
            return ShiftUnitResult(
                false,
                "Khóa \"${course.displayName}\" không có danh sách unit — chọn Explorers…TOEIC trước."
            )
        }

        val oldSub = activeSubIdx()
        val oldUnit = activeUnitSelection().toIntOrNull()?.coerceAtLeast(1) ?: 1
        var sub = oldSub
        var unit = oldUnit
        var clamped = false

        if (course.subs.isNotEmpty()) {
            val list = SelfControlCourses.activeUnitList(idx, sub)
                ?: return ShiftUnitResult(false, "Không có unit trong sách hiện tại")
            val maxU = list.units.size.coerceAtLeast(1)
            unit = unit.coerceIn(1, maxU)
            if (d > 0) {
                if (unit < maxU) {
                    unit++
                } else if (sub < course.subs.lastIndex) {
                    sub++
                    unit = 1
                } else {
                    clamped = true
                }
            } else {
                if (unit > 1) {
                    unit--
                } else if (sub > 0) {
                    sub--
                    val prevList = SelfControlCourses.activeUnitList(idx, sub)
                    unit = prevList?.units?.size?.coerceAtLeast(1) ?: 1
                } else {
                    clamped = true
                }
            }
            persistActiveLesson(sub, unit)
        } else {
            val list = SelfControlCourses.activeUnitList(idx, 0)
                ?: return ShiftUnitResult(false, "Không có danh sách unit")
            val maxU = list.units.size.coerceAtLeast(1)
            unit = unit.coerceIn(1, maxU)
            val next = (unit + d).coerceIn(1, maxU)
            clamped = next == unit && ((d > 0 && unit >= maxU) || (d < 0 && unit <= 1))
            unit = next
            persistActiveLesson(0, unit)
        }

        val unitName = SelfControlCourses.resolveUnitName(idx, activeSubIdx(), activeUnitSelection())
        val subName = course.subs.getOrNull(activeSubIdx())?.name
        val dir = if (d > 0) "tiếp theo" else "trước"
        val msg = when {
            clamped && d > 0 ->
                "Đã ở unit cuối: $unitName" + (subName?.let { " ($it)" } ?: "")
            clamped && d < 0 ->
                "Đã ở unit đầu: $unitName" + (subName?.let { " ($it)" } ?: "")
            sub != oldSub ->
                "Đã chuyển sách → ${subName ?: sub}, unit $dir: $unitName"
            else ->
                "Đã chọn unit $dir: $unitName"
        }
        Log.i(TAG, "shiftUnit delta=$d idx=$idx sub=${activeSubIdx()} unit=${activeUnitSelection()} clamped=$clamped")
        return ShiftUnitResult(
            success = true,
            message = msg,
            courseIdx = idx,
            subIdx = activeSubIdx(),
            unitIdx = activeUnitSelection().toIntOrNull() ?: unit,
            unitName = unitName,
            clamped = clamped,
        )
    }

    fun buildGetConfigJson(): JSONObject {
        val idx = getPresetMacIdx()
        return JSONObject().apply {
            put("preset_mac_idx", idx)
            put("student_name", getStudentName())
            put("music_server_url", getMusicServerUrl())
            put("device_id", resolveDeviceId())
            put("custom_mac", getCustomMac())
            put("ex_voice", getExVoice())
            put("yi_voice", getYiVoice())
            put("fl_voice", getFlVoice())
            put("ielts_voice", getIeltsVoice())
            put("toeic_voice", getToeicVoice())
            put("preset_macs", JSONObject().apply {
                for (i in 1..5) put(i.toString(), SelfControlPresets.getPresetMac(i))
            })
            put("all_units", JSONObject().apply {
                for (i in 1..5) put(i.toString(), getUnitsFlat(i))
            })
            put("yi_sub", getYiSub())
            put("yi_units", JSONObject().apply {
                for (i in 0..2) put(i.toString(), getYiUnit(i))
            })
            put("ex_sub", getExSub())
            put("ex_units", JSONObject().apply {
                for (i in 0..4) put(i.toString(), getExUnit(i))
            })
            put("fl_sub", getFlSub())
            put("fl_units", JSONObject().apply {
                put("0", getFlUnit(0))
            })
            // Unit đang học — web reload highlight đúng
            val sub = activeSubIdx()
            val unitSel = activeUnitSelection()
            put("active_sub_idx", sub)
            put("active_unit_idx", unitSel.toIntOrNull() ?: 1)
            put("active_unit", unitSel)
            put(
                "active_unit_name",
                SelfControlCourses.resolveUnitName(idx, sub, unitSel)
            )
            val course = SelfControlCourses.get(idx)
            val subName = course?.subs?.getOrNull(sub)?.name
            if (subName != null) put("active_sub_name", subName)
            if (course != null) put("active_course_name", course.displayName)
        }
    }

    data class SaveResult(val success: Boolean, val identityChanged: Boolean, val error: String? = null)

    fun applyPostConfig(body: JSONObject): SaveResult {
        val oldIdx = getPresetMacIdx()
        val oldMac = resolveDeviceId()
        val oldExVoice = getExVoice()
        val oldYiVoice = getYiVoice()
        val oldFlVoice = getFlVoice()
        val oldIeltsVoice = getIeltsVoice()
        val oldToeicVoice = getToeicVoice()

        if (body.has("student_name")) {
            setStudentName(body.optString("student_name", ""))
        }
        if (body.has("music_server_url")) {
            setMusicServerUrl(body.optString("music_server_url", DEFAULT_MUSIC_SERVER))
        }

        var newIdx = oldIdx
        if (body.has("preset_mac_idx")) {
            newIdx = body.optInt("preset_mac_idx", oldIdx).coerceIn(0, 7)
            setPresetMacIdx(newIdx)
        }
        val courseChanged = newIdx != oldIdx

        if (body.has("ex_voice")) setExVoice(body.optInt("ex_voice", 0))
        if (body.has("yi_voice")) setYiVoice(body.optInt("yi_voice", 0))
        if (body.has("fl_voice")) setFlVoice(body.optInt("fl_voice", 0))
        if (body.has("ielts_voice")) setIeltsVoice(body.optInt("ielts_voice", 0))
        if (body.has("toeic_voice")) setToeicVoice(body.optInt("toeic_voice", 0))

        val voiceChanged = when (newIdx) {
            1 -> getExVoice() != oldExVoice
            2 -> getYiVoice() != oldYiVoice
            3 -> getFlVoice() != oldFlVoice
            4 -> getIeltsVoice() != oldIeltsVoice
            5 -> getToeicVoice() != oldToeicVoice
            else -> false
        }

        val rawCustom = body.optString("custom_mac", "")
        val macNorm = SelfControlPresets.normalizeMac(rawCustom)
        when {
            newIdx == SelfControlPresets.MANUAL_CUSTOM_MAC_IDX -> {
                if (!SelfControlPresets.isValidMac(macNorm)) {
                    return SaveResult(false, false, "Invalid MAC")
                }
                setCustomMac(macNorm)
            }
            newIdx == 0 -> {
                // Tự cấu hình: để trống = chip. Bỏ MAC pool khóa cũ nếu còn sót.
                if (macNorm.isEmpty() || SelfControlPresets.isMacInAnyCoursePool(macNorm)) {
                    setCustomMac("")
                } else if (SelfControlPresets.isValidMac(macNorm)) {
                    setCustomMac(macNorm)
                }
            }
            SelfControlPresets.courseUsesMacPool(newIdx) -> {
                // Random khi đổi khóa pool hoặc đổi giọng; không random chỉ sửa tên/unit.
                if (courseChanged || voiceChanged) {
                    pickAndSavePoolMac(newIdx)
                } else if (!SelfControlPresets.isValidMac(getCustomMac())) {
                    pickAndSavePoolMac(newIdx)
                }
            }
        }

        body.optJSONObject("all_units")?.let { au ->
            for (i in 1..5) {
                if (au.has(i.toString())) setUnitsFlat(i, au.optString(i.toString(), "1"))
            }
        }
        if (body.has("yi_sub")) setYiSub(body.optInt("yi_sub", 0))
        body.optJSONObject("yi_units")?.let { yu ->
            for (i in 0..2) if (yu.has(i.toString())) setYiUnit(i, yu.optString(i.toString(), "1"))
        }
        if (body.has("ex_sub")) setExSub(body.optInt("ex_sub", 0))
        body.optJSONObject("ex_units")?.let { eu ->
            for (i in 0..4) if (eu.has(i.toString())) setExUnit(i, eu.optString(i.toString(), "1"))
        }
        if (body.has("fl_sub")) setFlSub(body.optInt("fl_sub", 0))
        body.optJSONObject("fl_units")?.let { fu ->
            if (fu.has("0")) setFlUnit(0, fu.optString("0", "1"))
        }

        val newMac = resolveDeviceId()
        val identityChanged = courseChanged || voiceChanged || !newMac.equals(oldMac, ignoreCase = true)
        Log.i(
            TAG,
            "Save config idx=$newIdx voice=${voiceForCourse(newIdx)} deviceId=$newMac " +
                "courseChanged=$courseChanged voiceChanged=$voiceChanged identityChanged=$identityChanged"
        )
        return SaveResult(true, identityChanged)
    }
}
