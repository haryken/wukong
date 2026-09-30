package com.ubtrobot.mini.speech.framework.demo

import android.content.Context
import android.os.Process
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.ubtech.utilcode.utils.LogUtils
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sherpa-onnx keyword spotting theo [model] (Anh: "hey mini" / "hi mini", Việt: "mini ơi" / "này mini";
 * đổi được qua [configure]). All native calls run on a single [SherpaKwsWorker] thread (required by ONNX).
 */
class SherpaOnnxWakeUpDetector(
  private val context: Context,
  val model: SherpaKwsModel
) : WakeEngine {

  companion object {
    private const val TAG = "SherpaOnnxWakeUp"
    private const val SAMPLE_RATE = 16000
    /** 100 ms @ 16 kHz — same as official SherpaOnnxKws sample. */
    private const val CHUNK_SAMPLES = 1600
    private const val COOLDOWN_MS = 2000L
    /**
     * score ↑ / threshold ↓ = dễ bắt hơn. Quá thấp (vd. 0.08) trên Alpha Mini có thể không wake.
     * Threshold thực tế lấy từ độ nhạy web qua [thresholdForSensitivity].
     */
    private const val KEYWORDS_SCORE = 2.0f
    private const val DEFAULT_SENSITIVITY = 0.5f
    private const val MAX_ACTIVE_PATHS = 4
    /**
     * Khi phát nhạc local: chỉ decode 1/N chunk (vẫn feed waveform đủ để stream không lệch).
     * N=2 ≈ giảm ~một nửa CPU KWS lúc MediaPlayer + proxy đang chạy.
     */
    private const val MUSIC_DECODE_EVERY_N = 2
    /**
     * PCM chờ decode quá ~1 s (16 kHz, 16-bit) → bỏ frame cũ nhất. Worker chậm hơn mic thì hàng đợi 512 frame
     * dồn tới ~27 s, wake bị trễ theo; nghe muộn vô ích hơn là mất một đoạn cũ.
     */
    private const val MAX_BACKLOG_BYTES = SAMPLE_RATE * 2
    /** 12 s PCM gần nhất model nhận – tải qua /api/wake_debug.wav để thử lại offline. */
    private const val DEBUG_RING_BYTES = SAMPLE_RATE * 2 * 12

    /** Độ nhạy 0..1 → threshold 0.20..0.04 (0.5 → 0.12 như trước). */
    fun thresholdForSensitivity(s: Float): Float = (0.20f - 0.16f * s.coerceIn(0f, 1f))

    /** "HEY_MINI" / "▁HEY▁MINI" / "▁HE Y ▁MIN I" / "HEY MINI" → "HEYMINI" (khoá so khớp). */
    private fun compactKey(keyword: String): String =
      keyword.uppercase().replace(Regex("[\\s_\u2581]+"), "")
  }

  override val engineId: String = model.engineId

  /** Last phrase matched by KWS – for logs / server wake hint. */
  @Volatile override var lastDetectedKeyword: String = ""
  @Volatile override var onDetected: ((String) -> Unit)? = null
  @Volatile override var onFatalError: ((String) -> Unit)? = null
  /** Gọi trên worker khi KeywordSpotter nạp xong (model tiếng Việt mất ~1 phút trên Alpha Mini). */
  @Volatile var onReady: (() -> Unit)? = null
  @Volatile private var errorText: String = ""
  override val lastError: String get() = errorText

  /** Cụm được phép wake (đã normalize) – keywords.txt có thêm HEY SIRI... nhưng chỉ publish cụm trong list này. */
  @Volatile private var phrases: List<String> = model.defaultPhrases
  @Volatile private var sensitivity: Float = DEFAULT_SENSITIVITY

  private sealed class WorkerCmd {
    object Init : WorkerCmd()
    /** Đổi cụm / độ nhạy: chỉ tạo stream mới trên KeywordSpotter đã nạp. */
    object Restream : WorkerCmd()
    object Stop : WorkerCmd()
    data class Pcm(val frame: ByteArray) : WorkerCmd()
  }

  private val cmdQueue = ArrayBlockingQueue<WorkerCmd>(512)
  private val workerStarted = AtomicBoolean(false)
  private val streamReady = AtomicBoolean(false)
  @Volatile private var wantRunning = false
  /** Tạm chặn publish wake (vd. trong lúc TTS robot phát – tránh false trigger). */
  @Volatile private var suppressWakeUntilMs = 0L
  private var pcmQueueDrops = 0
  private val queuedPcmBytes = AtomicInteger(0)
  private var pcmStaleSkipped = 0L
  private var pcmChunksDecoded = 0L
  private var pcmChunksSkippedMusic = 0L
  private var lastAliveLogMs = 0L
  private var windowPeak = 0
  private val loggedFirstPcm = AtomicBoolean(false)
  private val debugRing = ByteArray(DEBUG_RING_BYTES)
  private var debugPos = 0
  private var debugFilled = false

  private val worker = Thread({
    Process.setThreadPriority(
      if (model.backgroundPriority) Process.THREAD_PRIORITY_BACKGROUND else Process.THREAD_PRIORITY_DEFAULT
    )
    var kws: KeywordSpotter? = null
    var stream: OnlineStream? = null
    val pcmAccum = FloatArray(CHUNK_SAMPLES)
    var pcmFill = 0
    var lastWakeMs = 0L
    var musicDecodeCounter = 0
    var activePhrases: Map<String, String> = model.defaultPhrases.associateBy { compactKey(it) }

    fun releaseNative() {
      try {
        stream?.release()
      } catch (_: Exception) {
      }
      stream = null
      try {
        kws?.release()
      } catch (_: Exception) {
      }
      kws = null
      pcmFill = 0
      streamReady.set(false)
    }

    fun initNative() {
      releaseNative()
      val wanted = phrases
      val threshold = thresholdForSensitivity(sensitivity)
      val loadStart = System.currentTimeMillis()
      val config = buildConfig(threshold)
      val spotter = KeywordSpotter(context.assets, config)
      // Cụm trên web được thêm vào stream (gộp với keywords.txt); "@HEY_MINI" = tên trả về trong result.keyword.
      val streamKeywords = buildStreamKeywords(wanted, threshold)
      val st = spotter.createStream(streamKeywords)
      if (st.ptr == 0L) {
        spotter.release()
        errorText = "createStream lỗi – kiểm tra từ khoá / model ${model.assetDir}"
        LogUtils.e(TAG, "[WakeWord] createStream failed – keywords=\"$streamKeywords\"")
        onFatalError?.invoke(errorText)
        return
      }
      kws = spotter
      stream = st
      activePhrases = wanted.associateBy { compactKey(it) }
      errorText = ""
      streamReady.set(true)
      LogUtils.i(
        TAG,
        "[WakeWord] KeywordSpotter ready (${model.assetDir}, ${System.currentTimeMillis() - loadStart}ms) – " +
          "phrases=$wanted score=$KEYWORDS_SCORE threshold=$threshold " +
          "maxActivePaths=$MAX_ACTIVE_PATHS (chunk-16, feed $CHUNK_SAMPLES samples)"
      )
      onReady?.invoke()
    }

    fun restream() {
      val spotter = kws ?: return
      val wanted = phrases
      val threshold = thresholdForSensitivity(sensitivity)
      val streamKeywords = buildStreamKeywords(wanted, threshold)
      val st = spotter.createStream(streamKeywords)
      if (st.ptr == 0L) {
        errorText = "createStream lỗi – giữ cụm cũ"
        LogUtils.e(TAG, "[WakeWord] restream failed – keywords=\"$streamKeywords\"")
        return
      }
      try {
        stream?.release()
      } catch (_: Exception) {
      }
      stream = st
      pcmFill = 0
      activePhrases = wanted.associateBy { compactKey(it) }
      errorText = ""
      LogUtils.i(TAG, "[WakeWord] restream (${model.assetDir}) – phrases=$wanted threshold=$threshold")
    }

    fun processPcm(frame: ByteArray) {
      if (!streamReady.get()) return
      // Đang suppress (TTS chào / echo) — không decode ONNX = tiết CPU rõ khi robot nói.
      if (System.currentTimeMillis() < suppressWakeUntilMs) return
      val spotter = kws ?: return
      val st = stream ?: return
      val musicPlaying = try {
        OttoMusicPlayer.isPlaying()
      } catch (_: Throwable) {
        false
      }
      recordDebug(frame)
      val n = frame.size / 2
      var off = 0
      while (off < n) {
        val take = minOf(n - off, CHUNK_SAMPLES - pcmFill)
        for (i in 0 until take) {
          val lo = frame[(off + i) * 2].toInt() and 0xff
          val hi = frame[(off + i) * 2 + 1].toInt()
          val s = (hi shl 8) or lo
          val a = if (s < 0) -s else s
          if (a > windowPeak) windowPeak = a
          pcmAccum[pcmFill + i] = s / 32768.0f
        }
        pcmFill += take
        off += take
        if (pcmFill < CHUNK_SAMPLES) continue

        // Lúc phát nhạc: bỏ cả accept+decode 1/N chunk (tránh backlog isReady).
        if (musicPlaying && MUSIC_DECODE_EVERY_N > 1) {
          musicDecodeCounter++
          if (musicDecodeCounter % MUSIC_DECODE_EVERY_N != 0) {
            pcmFill = 0
            pcmChunksSkippedMusic++
            continue
          }
        }
        st.acceptWaveform(pcmAccum, SAMPLE_RATE)
        pcmFill = 0
        pcmChunksDecoded++
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastAliveLogMs >= 15_000L) {
          lastAliveLogMs = nowMs
          LogUtils.i(
            TAG,
            "[WakeWord] PCM alive (${model.assetDir}): decoded=$pcmChunksDecoded musicSkip=$pcmChunksSkippedMusic " +
              "drops=$pcmQueueDrops staleSkip=$pcmStaleSkipped peak=$windowPeak/32768"
          )
          windowPeak = 0
        }
        while (spotter.isReady(st)) {
          spotter.decode(st)
          val result = spotter.getResult(st)
          val keyword = result.keyword
          if (keyword.isNotBlank()) {
            val now = System.currentTimeMillis()
            if (now < suppressWakeUntilMs) continue
            val normalized = activePhrases[compactKey(keyword)]
            if (normalized == null) {
              LogUtils.d(TAG, "[WakeWord] bỏ qua keyword không nằm trong danh sách wake: $keyword")
              spotter.reset(st)
              continue
            }
            if (now - lastWakeMs >= COOLDOWN_MS) {
              lastWakeMs = now
              lastDetectedKeyword = normalized
              LogUtils.i(TAG, "[WakeWord] detected: $lastDetectedKeyword")
              spotter.reset(st)
              onDetected?.invoke(normalized)
            }
          }
        }
      }
    }

    while (true) {
      val cmd = cmdQueue.take()
      when (cmd) {
        is WorkerCmd.Init -> {
          try {
            initNative()
          } catch (e: Throwable) {
            LogUtils.e(TAG, "[WakeWord] init failed (${model.assetDir}): ${e.message}", e)
            errorText = "init lỗi: ${e.message}"
            releaseNative()
            onFatalError?.invoke(errorText)
          }
        }
        is WorkerCmd.Restream -> {
          try {
            restream()
          } catch (e: Throwable) {
            LogUtils.e(TAG, "[WakeWord] restream failed: ${e.message}", e)
            errorText = "đổi cụm lỗi: ${e.message}"
          }
        }
        is WorkerCmd.Stop -> {
          releaseNative()
        }
        is WorkerCmd.Pcm -> {
          val remaining = queuedPcmBytes.addAndGet(-cmd.frame.size)
          if (remaining > MAX_BACKLOG_BYTES) {
            pcmStaleSkipped++
            pcmFill = 0
          } else if (wantRunning) {
            processPcm(cmd.frame)
          }
        }
      }
    }
  }, "SherpaKwsWorker")

  /**
   * Đổi cụm wake (đã normalize, đã kiểm tra tách token được) + độ nhạy 0..1.
   * Threshold ghi theo từng cụm trong stream nên không phải nạp lại model; đang nạp dở thì
   * Restream xếp sau Init và áp cụm mới ngay khi model sẵn sàng.
   */
  fun configure(newPhrases: List<String>, newSensitivity: Float) {
    val p = newPhrases.ifEmpty { model.defaultPhrases }
    val s = newSensitivity.coerceIn(0f, 1f)
    if (p == phrases && s == sensitivity) return
    phrases = p
    sensitivity = s
    LogUtils.i(TAG, "[WakeWord] configure phrases=$p sensitivity=$s")
    if (wantRunning) cmdQueue.offer(WorkerCmd.Restream)
  }

  override fun start() {
    if (wantRunning) {
      LogUtils.d(TAG, "[WakeWord] start ignored – already active")
      return
    }
    wantRunning = true
    ensureWorker()
    cmdQueue.offer(WorkerCmd.Init)
    LogUtils.i(TAG, "[WakeWord] start requested – mic may feed after KeywordSpotter ready")
  }

  override fun stop() {
    if (!wantRunning) return
    wantRunning = false
    streamReady.set(false)
    cmdQueue.offer(WorkerCmd.Stop)
    LogUtils.d(TAG, "[WakeWord] stop")
  }

  /** Gọi khi TTS bắt đầu – giảm false wake từ loa robot (echo). */
  override fun suppressWakeFor(ms: Long) {
    suppressWakeUntilMs = System.currentTimeMillis() + ms
    LogUtils.d(TAG, "[WakeWord] suppressWakeFor ${ms}ms")
  }

  /** Sau ting / lỗi greeting – cho phép hey mini ngay (bỏ suppress từ TTS chào). */
  override fun clearSuppressWake() {
    suppressWakeUntilMs = 0L
    LogUtils.d(TAG, "[WakeWord] clearSuppressWake")
  }

  /** True when stream exists and PCM will be decoded (safe to start mic). */
  override fun isStreamReady(): Boolean = streamReady.get()

  override fun feedPcmFrame(frame: ByteArray) {
    if (!wantRunning || !streamReady.get() || frame.isEmpty()) return
    // TTS/suppress: đừng copy+enqueue → giảm GC + CPU queue lúc loa đang phát.
    if (System.currentTimeMillis() < suppressWakeUntilMs) return
    if (loggedFirstPcm.compareAndSet(false, true)) {
      LogUtils.i(TAG, "[WakeWord] first PCM frame from mic (${frame.size} bytes) → KWS queue")
    }
    // Hàng đợi đầy / gần đầy: bỏ frame (ưu tiên không block mic thread).
    if (cmdQueue.remainingCapacity() < 64) {
      pcmQueueDrops++
      if (pcmQueueDrops == 1 || pcmQueueDrops % 100 == 0) {
        LogUtils.w(TAG, "[WakeWord] PCM queue busy – dropped $pcmQueueDrops frames")
      }
      return
    }
    // Must copy: AudioRecord thread reuses one buffer; queue holds refs async on SherpaKwsWorker.
    val copy = frame.copyOf()
    queuedPcmBytes.addAndGet(copy.size)
    if (!cmdQueue.offer(WorkerCmd.Pcm(copy))) {
      queuedPcmBytes.addAndGet(-copy.size)
      pcmQueueDrops++
      if (pcmQueueDrops == 1 || pcmQueueDrops % 100 == 0) {
        LogUtils.w(TAG, "[WakeWord] PCM queue full – dropped $pcmQueueDrops frames")
      }
    }
  }

  private fun recordDebug(frame: ByteArray) {
    synchronized(debugRing) {
      var src = 0
      while (src < frame.size) {
        val n = minOf(frame.size - src, DEBUG_RING_BYTES - debugPos)
        System.arraycopy(frame, src, debugRing, debugPos, n)
        src += n
        debugPos += n
        if (debugPos == DEBUG_RING_BYTES) {
          debugPos = 0
          debugFilled = true
        }
      }
    }
  }

  /** WAV 16 kHz mono 16-bit của tối đa 12 s PCM gần nhất đã đưa vào KWS. */
  fun recentPcmWav(): ByteArray {
    val pcm = synchronized(debugRing) {
      if (debugFilled) {
        debugRing.copyOfRange(debugPos, DEBUG_RING_BYTES) + debugRing.copyOfRange(0, debugPos)
      } else {
        debugRing.copyOfRange(0, debugPos)
      }
    }
    val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
      put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size)
      put("WAVE".toByteArray(Charsets.US_ASCII))
      put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
      putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
      put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size)
    }.array()
    return header + pcm
  }

  private fun ensureWorker() {
    if (workerStarted.compareAndSet(false, true)) {
      worker.isDaemon = true
      worker.start()
    }
  }

  /**
   * ["HEY MINI","HI MINI"] → "▁HE Y ▁MIN I #0.120 @HEY_MINI/▁HI ▁MIN I #0.120 @HI_MINI".
   * Cụm trùng với dòng trong keywords.txt sẽ dùng threshold của file → keywords.txt chỉ để 1 cụm giữ chỗ.
   */
  private fun buildStreamKeywords(list: List<String>, threshold: Float): String {
    val tokenizer = SherpaKwsTokenizer.get(context, model)
    val th = String.format(Locale.US, "%.3f", threshold)
    return list.mapNotNull { phrase ->
      val tokens = tokenizer.encodePhrase(phrase)
      if (tokens == null) {
        LogUtils.w(TAG, "[WakeWord] bỏ cụm không tách token được: $phrase")
        null
      } else {
        "$tokens #$th @${phrase.replace(' ', '_')}"
      }
    }.joinToString("/")
  }

  private fun buildConfig(keywordsThreshold: Float): KeywordSpotterConfig {
    val transducer = OnlineTransducerModelConfig(
      encoder = model.encoder,
      decoder = model.decoder,
      joiner = model.joiner
    )
    val modelConfig = OnlineModelConfig(
      transducer = transducer,
      tokens = model.tokens,
      numThreads = model.numThreads,
      debug = false,
      provider = "cpu",
      modelType = "",
      modelingUnit = model.modelingUnit,
      bpeVocab = if (model.useBpeVocab) model.bpeModel else ""
    )
    return KeywordSpotterConfig(
      featConfig = FeatureConfig(
        sampleRate = SAMPLE_RATE,
        featureDim = 80,
        dither = 0.0f
      ),
      modelConfig = modelConfig,
      maxActivePaths = MAX_ACTIVE_PATHS,
      keywordsFile = model.keywordsFile,
      keywordsScore = KEYWORDS_SCORE,
      keywordsThreshold = keywordsThreshold,
      numTrailingBlanks = 1
    )
  }
}
