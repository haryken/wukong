package com.ubtrobot.mini.speech.framework.demo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cấu hình engine đánh thức (process chính) – web :8080 GET/POST /api/wake_engine.
 * AccessKey Picovoice chỉ lưu trên robot, GET chỉ trả bản che.
 */
object WakeEngineSettings {
  private const val PREFS = "wake_engine"
  private const val K_ENGINE = "engine"
  private const val K_SHERPA_KEYWORDS = "sherpa_keywords"
  private const val K_SHERPA_SENS = "sherpa_sensitivity"
  private const val K_PV_KEY = "porcupine_access_key"
  private const val K_PV_SENS = "porcupine_sensitivity"
  private const val MAX_PHRASES = 8
  private const val MAX_WORDS_PER_PHRASE = 4

  data class Snapshot(
    val engine: String,
    val sherpaKeywords: List<String>,
    val sherpaSensitivity: Float,
    val porcupineAccessKey: String,
    val porcupineSensitivity: Float
  )

  @Volatile private var appContext: Context? = null
  @Volatile private var prefs: SharedPreferences? = null
  @Volatile private var detector: SwitchableWakeUpDetector? = null

  fun init(context: Context) {
    if (prefs != null) return
    appContext = context.applicationContext
    prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  }

  fun attach(d: SwitchableWakeUpDetector) {
    detector = d
  }

  fun snapshot(): Snapshot {
    val p = prefs
    val engine = p?.getString(K_ENGINE, null)
      ?.takeIf { it == SwitchableWakeUpDetector.ENGINE_PORCUPINE }
      ?: SwitchableWakeUpDetector.ENGINE_SHERPA
    val keywords = p?.getString(K_SHERPA_KEYWORDS, null)
      ?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
      ?.takeIf { it.isNotEmpty() }
      ?: SherpaOnnxWakeUpDetector.DEFAULT_PHRASES
    return Snapshot(
      engine = engine,
      sherpaKeywords = keywords,
      sherpaSensitivity = p?.getFloat(K_SHERPA_SENS, 0.5f) ?: 0.5f,
      porcupineAccessKey = p?.getString(K_PV_KEY, "") ?: "",
      porcupineSensitivity = p?.getFloat(K_PV_SENS, 0.5f) ?: 0.5f
    )
  }

  private fun maskKey(key: String): String =
    if (key.length <= 8) "*".repeat(key.length) else key.take(4) + "…" + key.takeLast(4)

  fun statusJson(): JSONObject {
    val s = snapshot()
    return JSONObject().apply {
      put("success", true)
      put("engine", s.engine)
      put("sherpa_keywords", JSONArray(s.sherpaKeywords))
      put("sherpa_sensitivity", s.sherpaSensitivity.toDouble())
      put("porcupine_has_key", s.porcupineAccessKey.isNotEmpty())
      put("porcupine_key_masked", maskKey(s.porcupineAccessKey))
      put("porcupine_sensitivity", s.porcupineSensitivity.toDouble())
      put("porcupine_phrase", PorcupineWakeEngine.PHRASE)
      put("runtime", detector?.statusJson() ?: JSONObject().put("active", "").put("ready", false))
    }
  }

  /**
   * Body: engine, sherpa_keywords (mảng hoặc chuỗi xuống dòng), sherpa_sensitivity 0..1,
   * porcupine_access_key (bỏ trống = giữ key cũ), porcupine_clear_key, porcupine_sensitivity 0..1.
   */
  fun applyPostJson(req: JSONObject): JSONObject {
    val p = prefs ?: return error("Robot chưa sẵn sàng")
    val cur = snapshot()

    val engine = when (val e = req.optString("engine", cur.engine)) {
      SwitchableWakeUpDetector.ENGINE_SHERPA, SwitchableWakeUpDetector.ENGINE_PORCUPINE -> e
      else -> return error("Engine không hợp lệ: $e")
    }

    val keywords = if (req.has("sherpa_keywords")) {
      val raw = req.opt("sherpa_keywords")
      val lines = when (raw) {
        is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
        else -> raw.toString().split('\n')
      }.map { it.trim() }.filter { it.isNotEmpty() }
      val parsed = parseKeywords(lines)
      parsed.first ?: return error(parsed.second)
    } else cur.sherpaKeywords

    val sherpaSens = sens(req, "sherpa_sensitivity", cur.sherpaSensitivity)
    val pvSens = sens(req, "porcupine_sensitivity", cur.porcupineSensitivity)
    val newKey = req.optString("porcupine_access_key", "").trim()
    val pvKey = when {
      req.optBoolean("porcupine_clear_key", false) -> ""
      newKey.isNotEmpty() -> newKey
      else -> cur.porcupineAccessKey
    }
    if (engine == SwitchableWakeUpDetector.ENGINE_PORCUPINE && pvKey.isEmpty()) {
      return error("Porcupine cần AccessKey (lấy ở console.picovoice.ai)")
    }

    p.edit()
      .putString(K_ENGINE, engine)
      .putString(K_SHERPA_KEYWORDS, keywords.joinToString("\n"))
      .putFloat(K_SHERPA_SENS, sherpaSens)
      .putString(K_PV_KEY, pvKey)
      .putFloat(K_PV_SENS, pvSens)
      .apply()
    detector?.applySettings(snapshot())
    return statusJson()
  }

  /** (cụm đã normalize, "") khi hợp lệ; (null, lỗi) khi không. */
  private fun parseKeywords(lines: List<String>): Pair<List<String>?, String> {
    if (lines.isEmpty()) return null to "Cần ít nhất 1 từ đánh thức"
    if (lines.size > MAX_PHRASES) return null to "Tối đa $MAX_PHRASES cụm từ"
    val ctx = appContext ?: return null to "Robot chưa sẵn sàng"
    val tokenizer = try {
      SherpaKwsTokenizer.get(ctx)
    } catch (e: Exception) {
      return null to "Không đọc được bpe.model: ${e.message}"
    }
    val out = LinkedHashSet<String>()
    for (line in lines) {
      val norm = SherpaKwsTokenizer.normalizePhrase(line)
      if (norm.isEmpty()) return null to "\"$line\": chỉ dùng chữ tiếng Anh không dấu (A-Z)"
      if (norm.split(' ').size > MAX_WORDS_PER_PHRASE) {
        return null to "\"$line\": tối đa $MAX_WORDS_PER_PHRASE từ"
      }
      if (tokenizer.encodePhrase(norm) == null) return null to "\"$line\": model không tách được cụm này"
      out += norm
    }
    return out.toList() to ""
  }

  private fun sens(req: JSONObject, key: String, fallback: Float): Float {
    if (!req.has(key)) return fallback
    val v = req.optDouble(key, fallback.toDouble())
    return if (v.isNaN()) fallback else v.toFloat().coerceIn(0f, 1f)
  }

  private fun error(msg: String): JSONObject =
    JSONObject().put("success", false).put("error", msg)
}
