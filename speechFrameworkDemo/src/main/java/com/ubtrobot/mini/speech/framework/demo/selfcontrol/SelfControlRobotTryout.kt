package com.ubtrobot.mini.speech.framework.demo.selfcontrol

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ubtrobot.commons.Priority
import com.ubtrobot.express.ExpressApi
import com.ubtrobot.express.listeners.AnimationListener
import com.ubtrobot.mini.speech.framework.demo.ActivationEyeDisplay
import com.ubtrobot.mini.speech.framework.demo.AlphaMiniRomDanceSkills
import com.ubtrobot.mini.speech.framework.demo.MiniRobotActionInvoker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.TreeSet

/**
 * Catalog + thử nhanh skill / biểu cảm ROM từ Self-Control :8080.
 */
object SelfControlRobotTryout {
    private const val TAG = "SelfControlTryout"
    private val main = Handler(Looper.getMainLooper())
    private val EXPRESS_DIRS = arrayOf(
        "/system/files/expresss",
        "/system/files/express",
        "/sdcard/ubt/expresss"
    )

    fun listActions(): JSONObject {
        val dances = JSONArray()
        val skills = JSONArray()
        val names = loadAllSkillNames()
        for (n in names) {
            val item = JSONObject().put("id", n).put("label", n)
            if (AlphaMiniRomDanceSkills.isRomDanceSkillName(n)) {
                dances.put(item)
            } else {
                skills.put(item)
            }
        }
        // Fallback dances nếu ROM chưa trả skill dance
        if (dances.length() == 0) {
            for (n in AlphaMiniRomDanceSkills.resolveDancePool()) {
                dances.put(JSONObject().put("id", n).put("label", n))
            }
        }
        return JSONObject().apply {
            put("success", true)
            put("dances", dances)
            put("skills", skills)
            put("dance_count", dances.length())
            put("skill_count", skills.length())
        }
    }

    fun listExpresses(): JSONObject {
        val names = loadExpressNames()
        val arr = JSONArray()
        for (n in names) {
            arr.put(JSONObject().put("id", n).put("label", n))
        }
        return JSONObject().apply {
            put("success", true)
            put("expresses", arr)
            put("count", arr.length())
        }
    }

    fun playSkill(name: String?): JSONObject {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) {
            return JSONObject().put("success", false).put("error", "missing name")
        }
        return try {
            MiniRobotActionInvoker.suppressLlmEmotionForRobotAction(25_000L)
            val ok = MiniRobotActionInvoker.startSkillByNameForTryout(n)
            JSONObject().apply {
                put("success", ok)
                put("name", n)
                put("type", "skill")
                if (!ok) put("error", "SkillApi/SkillHelper không chạy được \"$n\"")
            }
        } catch (e: Exception) {
            Log.w(TAG, "playSkill: ${e.message}")
            JSONObject().put("success", false).put("error", e.message ?: "fail")
        }
    }

    fun playExpress(name: String?): JSONObject {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) {
            return JSONObject().put("success", false).put("error", "missing name")
        }
        if (ActivationEyeDisplay.isQrShowing()) {
            return JSONObject().put("success", false).put("error", "Đang hiện QR — tắt QR trước")
        }
        return try {
            // Không chen nhạc streaming_media nếu user đang thử express
            ActivationEyeDisplay.clearMusicPlayingEyes()
            main.post {
                try {
                    ExpressApi.get().stopExpress()
                } catch (_: Exception) {
                }
                try {
                    ExpressApi.get().doExpress(n, 1, Priority.HIGH, object : AnimationListener {
                        override fun onAnimationStart() {}
                        override fun onAnimationEnd(i: Int) {}
                        override fun onAnimationRepeat(loopNumber: Int) {}
                    })
                    Log.i(TAG, "playExpress doExpress=\"$n\"")
                } catch (e: Exception) {
                    Log.w(TAG, "doExpress $n: ${e.message}")
                }
            }
            JSONObject().put("success", true).put("name", n).put("type", "express")
        } catch (e: Exception) {
            JSONObject().put("success", false).put("error", e.message ?: "fail")
        }
    }

    fun stopAll(): JSONObject {
        return try {
            MiniRobotActionInvoker.stopTryoutMotion()
            main.post {
                try {
                    ExpressApi.get().stopExpress()
                } catch (_: Exception) {
                }
                try {
                    ActivationEyeDisplay.clearMusicPlayingEyes()
                } catch (_: Exception) {
                }
            }
            JSONObject().put("success", true).put("message", "Đã dừng skill / express")
        } catch (e: Exception) {
            JSONObject().put("success", false).put("error", e.message ?: "fail")
        }
    }

    private fun loadAllSkillNames(): List<String> {
        val out = TreeSet<String>(String.CASE_INSENSITIVE_ORDER)
        try {
            val skillApiClass = Class.forName("com.ubtechinc.skill.SkillApi")
            val api = skillApiClass.getMethod("get").invoke(null)
            val list = skillApiClass.getMethod("loadAllSkills").invoke(api) as? List<*>
            if (list != null) {
                for (item in list) {
                    if (item is Enum<*>) out.add(item.name)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadAllSkills: ${t.message}")
        }
        if (out.isEmpty()) {
            for (n in AlphaMiniRomDanceSkills.resolveDancePool()) out.add(n)
            out.add("GO_AHEAD")
            out.add("BACK_UP")
            out.add("TURN_LEFT")
            out.add("TURN_RIGHT")
            out.add("TAIJI")
            out.add("BOW_STEP")
            out.add("SCRATCH")
            out.add("HAND_KISS")
            out.add("HUG")
            out.add("BE_CUTE")
            out.add("FRIGHTEN")
            out.add("SHUT_DOWN")
        }
        return out.toList()
    }

    private fun loadExpressNames(): List<String> {
        val out = TreeSet<String>(String.CASE_INSENSITIVE_ORDER)
        for (dir in EXPRESS_DIRS) {
            val root = File(dir)
            if (!root.isDirectory) continue
            val files = root.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                val name = f.name
                val lower = name.lowercase(Locale.US)
                if (!(lower.endsWith(".gif") || lower.endsWith(".json") || lower.endsWith(".webp"))) {
                    continue
                }
                val base = name.substringBeforeLast('.')
                if (base.isBlank()) continue
                // Bỏ asset con kiểu img_0 trong thư mục con — chỉ file trực tiếp.
                if (base.startsWith("img_")) continue
                out.add(base)
            }
        }
        // Thử ExpressApi.getExpressList nếu có
        try {
            val api = ExpressApi.get()
            for (m in api.javaClass.methods) {
                if (m.name != "getExpressList" || m.parameterCount != 0) continue
                val list = m.invoke(api) ?: break
                when (list) {
                    is List<*> -> {
                        for (item in list) {
                            val s = when (item) {
                                is String -> item
                                is Enum<*> -> item.name
                                else -> item?.javaClass?.getMethod("getName")?.invoke(item)?.toString()
                                    ?: item?.toString()
                            }
                            val base = s?.substringBeforeLast('.')?.trim().orEmpty()
                            if (base.isNotEmpty()) out.add(base)
                        }
                    }
                    is Array<*> -> {
                        for (item in list) {
                            val s = item?.toString()?.substringBeforeLast('.')?.trim().orEmpty()
                            if (s.isNotEmpty()) out.add(s)
                        }
                    }
                }
                break
            }
        } catch (t: Throwable) {
            Log.d(TAG, "getExpressList: ${t.message}")
        }
        return out.toList()
    }
}
