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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sherpa-onnx keyword spotting (mặc định "hey mini" / "hi mini", đổi được qua [configure]).
 * All native calls run on a single [SherpaKwsWorker] thread (required by ONNX).
 */
class SherpaOnnxWakeUpDetector(private val context: Context) : WakeEngine {

  companion object {
    private const val TAG = "SherpaOnnxWakeUp"
    private const val ASSET_DIR = "sherpa-kws"
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

    val DEFAULT_PHRASES = listOf("HEY MINI", "HI MINI")

    /** Độ nhạy 0..1 → threshold 0.20..0.04 (0.5 → 0.12 như trước). */
    fun thresholdForSensitivity(s: Float): Float = (0.20f - 0.16f * s.coerceIn(0f, 1f))

    /** "HEY_MINI" / "▁HEY▁MINI" / "▁HE Y ▁MIN I" / "HEY MINI" → "HEYMINI" (khoá so khớp). */
    private fun compactKey(keyword: String): String =
      keyword.uppercase().replace(Regex("[\\s_\u2581]+"), "")
  }

  override val engineId: String = SwitchableWakeUpDetector.ENGINE_SHERPA

  /** Last phrase matched by KWS – for logs / server wake hint. */
  @Volatile override var lastDetectedKeyword: String = ""
  @Volatile override var onDetected: ((String) -> Unit)? = null
  @Volatile override var onFatalError: ((String) -> Unit)? = null
  @Volatile private var errorText: String = ""
  override val lastError: String get() = errorText

  /** Cụm được phép wake (đã normalize) – keywords.txt có thêm HEY SIRI... nhưng chỉ publish cụm trong list này. */
  @Volatile private var phrases: List<String> = DEFAULT_PHRASES
  @Volatile private var sensitivity: Float = DEFAULT_SENSITIVITY

  private sealed class WorkerCmd {
    object Init : WorkerCmd()
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
  private var pcmChunksDecoded = 0L
  private var pcmChunksSkippedMusic = 0L
  private var lastAliveLogMs = 0L
  private val loggedFirstPcm = AtomicBoolean(false)

  private val worker = Thread({
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    var kws: KeywordSpotter? = null
    var stream: OnlineStream? = null
    val pcmAccum = FloatArray(CHUNK_SAMPLES)
    var pcmFill = 0
    var lastWakeMs = 0L
    var musicDecodeCounter = 0
    var activePhrases: Map<String, String> = DEFAULT_PHRASES.associateBy { compactKey(it) }

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
      val config = buildConfig(threshold)
      val spotter = KeywordSpotter(context.assets, config)
      // Cụm trên web được thêm vào stream (gộp với keywords.txt); "@HEY_MINI" = tên trả về trong result.keyword.
      val streamKeywords = buildStreamKeywords(wanted)
      val st = spotter.createStream(streamKeywords)
      if (st.ptr == 0L) {
        spotter.release()
        errorText = "createStream lỗi – kiểm tra từ khoá / model sherpa-kws"
        LogUtils.e(TAG, "[WakeWord] createStream failed – keywords=\"$streamKeywords\"")
        return
      }
      kws = spotter
      stream = st
      activePhrases = wanted.associateBy { compactKey(it) }
      errorText = ""
      streamReady.set(true)
      LogUtils.i(
        TAG,
        "[WakeWord] KeywordSpotter ready – phrases=$wanted score=$KEYWORDS_SCORE threshold=$threshold " +
          "maxActivePaths=$MAX_ACTIVE_PATHS (chunk-16, feed $CHUNK_SAMPLES samples)"
      )
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
      val n = frame.size / 2
      var off = 0
      while (off < n) {
        val take = minOf(n - off, CHUNK_SAMPLES - pcmFill)
        for (i in 0 until take) {
          val lo = frame[(off + i) * 2].toInt() and 0xff
          val hi = frame[(off + i) * 2 + 1].toInt()
          val s = (hi shl 8) or lo
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
            "[WakeWord] PCM alive: decoded=$pcmChunksDecoded musicSkip=$pcmChunksSkippedMusic drops=$pcmQueueDrops"
          )
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
          } catch (e: Exception) {
            LogUtils.e(TAG, "[WakeWord] init failed: ${e.message}", e)
            errorText = "init lỗi: ${e.message}"
            releaseNative()
          }
        }
        is WorkerCmd.Stop -> {
          releaseNative()
        }
        is WorkerCmd.Pcm -> {
          if (wantRunning) processPcm(cmd.frame)
        }
      }
    }
  }, "SherpaKwsWorker")

  /**
   * Đổi cụm wake (đã normalize, đã kiểm tra tách token được) + độ nhạy 0..1.
   * Đang chạy thì dựng lại KeywordSpotter trên worker (mic vẫn chạy, chỉ mất vài trăm ms KWS).
   */
  fun configure(newPhrases: List<String>, newSensitivity: Float) {
    val p = newPhrases.ifEmpty { DEFAULT_PHRASES }
    val s = newSensitivity.coerceIn(0f, 1f)
    if (p == phrases && s == sensitivity) return
    phrases = p
    sensitivity = s
    LogUtils.i(TAG, "[WakeWord] configure phrases=$p sensitivity=$s")
    if (wantRunning) {
      streamReady.set(false)
      cmdQueue.offer(WorkerCmd.Init)
    }
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
    if (!cmdQueue.offer(WorkerCmd.Pcm(copy))) {
      pcmQueueDrops++
      if (pcmQueueDrops == 1 || pcmQueueDrops % 100 == 0) {
        LogUtils.w(TAG, "[WakeWord] PCM queue full – dropped $pcmQueueDrops frames")
      }
    }
  }

  private fun ensureWorker() {
    if (workerStarted.compareAndSet(false, true)) {
      worker.isDaemon = true
      worker.start()
    }
  }

  /** ["HEY MINI","HI MINI"] → "▁HE Y ▁MIN I @HEY_MINI/▁HI ▁MIN I @HI_MINI". */
  private fun buildStreamKeywords(list: List<String>): String {
    val tokenizer = SherpaKwsTokenizer.get(context)
    return list.mapNotNull { phrase ->
      val tokens = tokenizer.encodePhrase(phrase)
      if (tokens == null) {
        LogUtils.w(TAG, "[WakeWord] bỏ cụm không tách token được: $phrase")
        null
      } else {
        "$tokens @${phrase.replace(' ', '_')}"
      }
    }.joinToString("/")
  }

  private fun buildConfig(keywordsThreshold: Float): KeywordSpotterConfig {
    val transducer = OnlineTransducerModelConfig(
      encoder = "$ASSET_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.onnx",
      decoder = "$ASSET_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
      joiner = "$ASSET_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.onnx"
    )
    val modelConfig = OnlineModelConfig(
      transducer = transducer,
      tokens = "$ASSET_DIR/tokens.txt",
      numThreads = 1,
      debug = false,
      provider = "cpu",
      modelType = "",
      modelingUnit = "bpe",
      bpeVocab = "$ASSET_DIR/bpe.model"
    )
    return KeywordSpotterConfig(
      featConfig = FeatureConfig(
        sampleRate = SAMPLE_RATE,
        featureDim = 80,
        dither = 0.0f
      ),
      modelConfig = modelConfig,
      maxActivePaths = MAX_ACTIVE_PATHS,
      keywordsFile = "$ASSET_DIR/keywords.txt",
      keywordsScore = KEYWORDS_SCORE,
      keywordsThreshold = keywordsThreshold,
      numTrailingBlanks = 1
    )
  }
}
