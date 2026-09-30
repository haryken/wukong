package com.ubtrobot.mini.speech.framework.demo

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tách cụm thành token cho sherpa KWS (vd. "hey mini" → "▁HE Y ▁MIN I", "mini ơi" → "▁MI NI ▁ƠI").
 * bpe.model của cả model Anh lẫn Việt là sentencepiece *unigram* → Viterbi theo score từng piece
 * (đọc thẳng protobuf ModelProto, không cần thư viện sentencepiece).
 */
class SherpaKwsTokenizer private constructor(private val pieces: Map<String, Float>) {

  companion object {
    private const val WORD_BOUNDARY = "\u2581"
    private const val MAX_PIECE_CHARS = 16
    /** SentencePiece.Type.NORMAL – bỏ qua <unk>, control, user-defined. */
    private const val TYPE_NORMAL = 1

    private val instances = HashMap<String, SherpaKwsTokenizer>()

    fun get(context: Context, model: SherpaKwsModel): SherpaKwsTokenizer {
      synchronized(instances) {
        instances[model.bpeModel]?.let { return it }
        val bytes = context.assets.open(model.bpeModel).use { it.readBytes() }
        return SherpaKwsTokenizer(parseModel(bytes)).also { instances[model.bpeModel] = it }
      }
    }

    private fun parseModel(buf: ByteArray): Map<String, Float> {
      val out = HashMap<String, Float>()
      val r = Reader(buf, 0, buf.size)
      while (r.hasMore()) {
        val key = r.varint()
        val field = (key ushr 3).toInt()
        when ((key and 7).toInt()) {
          2 -> {
            val len = r.varint().toInt()
            if (field == 1) parsePiece(Reader(buf, r.pos, r.pos + len))?.let { out[it.first] = it.second }
            r.pos += len
          }
          else -> r.skip((key and 7).toInt())
        }
      }
      return out
    }

    private fun parsePiece(r: Reader): Pair<String, Float>? {
      var piece: String? = null
      var score = 0f
      var type = TYPE_NORMAL
      while (r.hasMore()) {
        val key = r.varint()
        val field = (key ushr 3).toInt()
        val wt = (key and 7).toInt()
        when {
          field == 1 && wt == 2 -> {
            val len = r.varint().toInt()
            piece = String(r.buf, r.pos, len, Charsets.UTF_8)
            r.pos += len
          }
          field == 2 && wt == 5 -> {
            score = ByteBuffer.wrap(r.buf, r.pos, 4).order(ByteOrder.LITTLE_ENDIAN).float
            r.pos += 4
          }
          field == 3 && wt == 0 -> type = r.varint().toInt()
          else -> r.skip(wt)
        }
      }
      return if (piece != null && type == TYPE_NORMAL) piece to score else null
    }

    private class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
      fun hasMore() = pos < end
      fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
          val b = buf[pos++].toInt() and 0xff
          result = result or ((b and 0x7f).toLong() shl shift)
          if (b and 0x80 == 0) return result
          shift += 7
        }
      }
      fun skip(wireType: Int) {
        when (wireType) {
          0 -> varint()
          1 -> pos += 8
          2 -> pos += varint().toInt()
          5 -> pos += 4
          else -> throw IllegalStateException("protobuf wire type $wireType")
        }
      }
    }
  }

  /** Cụm đã normalize ([SherpaKwsModel.normalizePhrase]) → "▁HE Y ▁MIN I", hoặc null nếu có từ không tách được. */
  fun encodePhrase(normalized: String): String? {
    val out = ArrayList<String>()
    for (word in normalized.split(' ')) {
      if (word.isEmpty()) continue
      out += encodeWord(word) ?: return null
    }
    return if (out.isEmpty()) null else out.joinToString(" ")
  }

  private fun encodeWord(word: String): List<String>? {
    val chars = WORD_BOUNDARY + word
    val n = chars.length
    val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
    val back = IntArray(n + 1) { -1 }
    best[0] = 0.0
    for (e in 1..n) {
      for (s in maxOf(0, e - MAX_PIECE_CHARS) until e) {
        if (best[s] == Double.NEGATIVE_INFINITY) continue
        val sc = pieces[chars.substring(s, e)] ?: continue
        if (best[s] + sc > best[e]) {
          best[e] = best[s] + sc
          back[e] = s
        }
      }
    }
    if (best[n] == Double.NEGATIVE_INFINITY) return null
    val out = ArrayList<String>()
    var e = n
    while (e > 0) {
      out.add(0, chars.substring(back[e], e))
      e = back[e]
    }
    return out
  }
}
