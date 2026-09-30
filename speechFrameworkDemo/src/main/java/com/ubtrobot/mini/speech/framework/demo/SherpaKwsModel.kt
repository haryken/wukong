package com.ubtrobot.mini.speech.framework.demo

import java.text.Normalizer

/**
 * Bộ file model KWS trong assets + quy tắc cụm từ của ngôn ngữ đó.
 * Thư mục assets phải có encoder/decoder/joiner, tokens.txt, bpe.model (sentencepiece unigram), keywords.txt.
 */
class SherpaKwsModel private constructor(
  val engineId: String,
  val assetDir: String,
  fileSuffix: String,
  /** "bpe" cần bpeVocab tồn tại; model tiếng Việt chạy với mặc định "cjkchar" (không dùng bpeVocab). */
  val modelingUnit: String,
  val useBpeVocab: Boolean,
  /** Luồng ONNX + ưu tiên worker: model tiếng Việt 30M cần 2 luồng / ưu tiên thường mới theo kịp mic trên Alpha Mini. */
  val numThreads: Int,
  val backgroundPriority: Boolean,
  val defaultPhrases: List<String>,
  private val phraseRegex: Regex,
  val invalidPhraseHint: String
) {
  val encoder = "$assetDir/encoder-$fileSuffix.onnx"
  val decoder = "$assetDir/decoder-$fileSuffix.onnx"
  val joiner = "$assetDir/joiner-$fileSuffix.onnx"
  val tokens = "$assetDir/tokens.txt"
  val bpeModel = "$assetDir/bpe.model"
  val keywordsFile = "$assetDir/keywords.txt"

  /** "  mini   ơi " → "MINI ƠI"; trả rỗng nếu có ký tự không hợp lệ với ngôn ngữ này. */
  fun normalizePhrase(raw: String): String {
    val up = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
      .uppercase()
      .replace(Regex("\\s+"), " ")
    return if (up.matches(phraseRegex)) up else ""
  }

  companion object {
    /** gigaspeech KWS 3.3M (k2-fsa, Apache-2.0). */
    val ENGLISH = SherpaKwsModel(
      engineId = SwitchableWakeUpDetector.ENGINE_SHERPA,
      assetDir = "sherpa-kws",
      fileSuffix = "epoch-12-avg-2-chunk-16-left-64",
      modelingUnit = "bpe",
      useBpeVocab = true,
      numThreads = 1,
      backgroundPriority = true,
      defaultPhrases = listOf("HEY MINI", "HI MINI"),
      phraseRegex = Regex("[A-Z' ]+"),
      invalidPhraseHint = "chỉ dùng chữ tiếng Anh không dấu (A-Z)"
    )

    /**
     * hynt/Zipformer-30M-RNNT-Streaming-6000h (ASR streaming tiếng Việt, chunk-16).
     * Giấy phép CC BY-NC-ND 4.0: chỉ dùng phi thương mại, không phân phối bản đã chỉnh sửa (giữ nguyên file fp16).
     */
    val VIETNAMESE = SherpaKwsModel(
      engineId = SwitchableWakeUpDetector.ENGINE_SHERPA_VI,
      assetDir = "sherpa-kws-vi",
      fileSuffix = "epoch-31-avg-11-chunk-16-left-128.fp16",
      modelingUnit = "cjkchar",
      useBpeVocab = false,
      numThreads = 2,
      backgroundPriority = false,
      defaultPhrases = listOf("MINI ƠI", "NÀY MINI"),
      phraseRegex = Regex("[\\p{L}' ]+"),
      invalidPhraseHint = "chỉ dùng chữ cái (có dấu), không số / ký hiệu"
    )

    fun forEngine(engineId: String): SherpaKwsModel =
      if (engineId == SwitchableWakeUpDetector.ENGINE_SHERPA_VI) VIETNAMESE else ENGLISH
  }
}
