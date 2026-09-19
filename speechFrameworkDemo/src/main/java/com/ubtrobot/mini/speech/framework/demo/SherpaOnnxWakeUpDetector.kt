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
import com.ubtrobot.speech.AbstractWakeUpDetector
import com.ubtrobot.speech.WakeUp
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sherpa-onnx keyword spotting for "hey mini".
 * All native calls run on a single [SherpaKwsWorker] thread (required by ONNX).
 */
class SherpaOnnxWakeUpDetector(private val context: Context) :
    AbstractWakeUpDetector(),
    WakeWordPcmFeeder {

  companion object {
    private const val TAG = "SherpaOnnxWakeUp"
    private const val ASSET_DIR = "sherpa-kws"
    private const val SAMPLE_RATE = 16000
    /** 100 ms @ 16 kHz — same as official SherpaOnnxKws sample. */
    private const val CHUNK_SAMPLES = 1600
    private const val COOLDOWN_MS = 2000L
    /**
     * Tune sensitivity here — keywords.txt is tokens only (no :score / #threshold).
     * score ↑ / threshold ↓ = dễ bắt hơn. Quá thấp (vd. 0.08) trên Alpha Mini có thể không wake.
     */
    private const val KEYWORDS_SCORE = 2.0f
    private const val KEYWORDS_THRESHOLD = 0.12f
    private const val MAX_ACTIVE_PATHS = 4
    /**
     * Khi phát nhạc local: chỉ decode 1/N chunk (vẫn feed waveform đủ để stream không lệch).
     * N=2 ≈ giảm ~một nửa CPU KWS lúc MediaPlayer + proxy đang chạy.
     */
    private const val MUSIC_DECODE_EVERY_N = 2

    /** Chỉ publish wake cho hey mini / hi mini — keywords.txt có thêm HEY SIRI để test nhưng không dùng làm wake. */
    private fun isHeyMiniKeyword(normalizedUpper: String): Boolean {
      return (normalizedUpper.contains("HEY") && normalizedUpper.contains("MINI"))
          || (normalizedUpper.contains("HI") && normalizedUpper.contains("MINI"))
    }
  }

  /** Last phrase matched by KWS – for logs / server wake hint. */
  @Volatile var lastDetectedKeyword: String = ""

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
      val config = buildConfig()
      val spotter = KeywordSpotter(context.assets, config)
      // Use keywords from assets/sherpa-kws/keywords.txt (config.keywordsFile).
      val st = spotter.createStream()
      if (st.ptr == 0L) {
        spotter.release()
        LogUtils.e(TAG, "[WakeWord] createStream failed – check keywords.txt / model files")
        return
      }
      kws = spotter
      stream = st
      streamReady.set(true)
      LogUtils.i(
        TAG,
        "[WakeWord] KeywordSpotter ready – score=$KEYWORDS_SCORE threshold=$KEYWORDS_THRESHOLD " +
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
            val normalized = keyword.trim().uppercase().replace(Regex("\\s+"), " ")
            if (!isHeyMiniKeyword(normalized)) {
              LogUtils.d(TAG, "[WakeWord] bỏ qua keyword không phải hey mini: $normalized")
              spotter.reset(st)
              continue
            }
            if (now - lastWakeMs >= COOLDOWN_MS) {
              lastWakeMs = now
              lastDetectedKeyword = normalized
              LogUtils.i(TAG, "[WakeWord] detected: $lastDetectedKeyword")
              spotter.reset(st)
              notifyWakeUp(null)
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

  fun start() {
    if (wantRunning) {
      LogUtils.d(TAG, "[WakeWord] start ignored – already active")
      return
    }
    wantRunning = true
    ensureWorker()
    cmdQueue.offer(WorkerCmd.Init)
    LogUtils.i(TAG, "[WakeWord] start requested – mic may feed after KeywordSpotter ready")
  }

  fun stop() {
    if (!wantRunning) return
    wantRunning = false
    streamReady.set(false)
    cmdQueue.offer(WorkerCmd.Stop)
    LogUtils.d(TAG, "[WakeWord] stop")
  }

  /** Gọi khi TTS bắt đầu – giảm false wake từ loa robot (echo). */
  fun suppressWakeFor(ms: Long) {
    suppressWakeUntilMs = System.currentTimeMillis() + ms
    LogUtils.d(TAG, "[WakeWord] suppressWakeFor ${ms}ms")
  }

  /** Sau ting / lỗi greeting – cho phép hey mini ngay (bỏ suppress từ TTS chào). */
  fun clearSuppressWake() {
    suppressWakeUntilMs = 0L
    LogUtils.d(TAG, "[WakeWord] clearSuppressWake")
  }

  /** True when stream exists and PCM will be decoded (safe to start mic). */
  fun isStreamReady(): Boolean = streamReady.get()

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

  private fun buildConfig(): KeywordSpotterConfig {
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
      keywordsThreshold = KEYWORDS_THRESHOLD,
      numTrailingBlanks = 1
    )
  }

  /**
   * Block until [streamReady] or timeout. Call from background thread before starting mic.
   */
  fun waitUntilReady(timeoutMs: Long = 15_000L): Boolean {
    ensureWorker()
    if (streamReady.get()) return true
    if (!wantRunning) start()
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (streamReady.get()) return true
      try {
        Thread.sleep(50)
      } catch (_: InterruptedException) {
        return false
      }
    }
    LogUtils.e(TAG, "[WakeWord] waitUntilReady timeout (${timeoutMs}ms)")
    return streamReady.get()
  }
}
