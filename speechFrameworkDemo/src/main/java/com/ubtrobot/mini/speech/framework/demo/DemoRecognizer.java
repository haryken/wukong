package com.ubtrobot.mini.speech.framework.demo;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;
import com.ubtechinc.mini.weinalib.TencentVadRecorder;
import com.ubtrobot.speech.AbstractRecognizer;
import com.ubtrobot.speech.AudioRecordListener;
import com.ubtrobot.speech.RecognitionOption;

import java.lang.reflect.Method;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class DemoRecognizer extends AbstractRecognizer {
  private static final String TAG = "SpeechDebug";
  private static final int SAMPLE_RATE = 16000;
  private static final int FRAME_MS = 60;
  private static final int FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * 2; // 16-bit mono

  private final TencentVadRecorder recorder;
  private volatile XiaozhiSessionManager xiaozhiSessionManager;
  /** Cùng session với OpusStreamPlayer (TTS) để AEC có reference signal – giống Xiaozhi_Android-main. 0 = mặc định. */
  private final int audioSessionId;
  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
  private long lastLogMs = 0;

  private AudioRecord directRecord;
  private AcousticEchoCanceler aec;
  private NoiseSuppressor ns;
  private Thread directRecordThread;
  private final AtomicBoolean directRecordRunning = new AtomicBoolean(false);
  private volatile boolean loggedNoXiaozhiOnce = false;
  private volatile boolean loggedNoWakeFeederOnce = false;
  /** Cùng luồng PCM gửi vào wake word (hey mini) – đảm bảo audio đang gửi Xiaozhi cũng được nhận bởi Porcupine. */
  private WakeWordPcmFeeder wakeWordPcmFeeder;

  public void updateXiaozhiSession(XiaozhiSessionManager sessionManager) {
    this.xiaozhiSessionManager = sessionManager;
    loggedNoXiaozhiOnce = false;
    Log.i(TAG, "XiaozhiSessionManager updated: " + (sessionManager != null ? "OK" : "NULL"));
  }

  public void setWakeWordPcmFeeder(WakeWordPcmFeeder feeder) {
    this.wakeWordPcmFeeder = feeder;
    Log.i(TAG, "[WakeWord] setWakeWordPcmFeeder: " + (feeder != null ? "OK (cùng PCM với Xiaozhi) – có thể hey mini" : "null – sẽ không thể hey mini"));
  }

  public DemoRecognizer(TencentVadRecorder recorder, XiaozhiSessionManager sessionManager) {
    this(recorder, sessionManager, 0);
  }

  public DemoRecognizer(TencentVadRecorder recorder, XiaozhiSessionManager sessionManager, int audioSessionId) {
    this.recorder = recorder;
    this.xiaozhiSessionManager = sessionManager;
    this.audioSessionId = audioSessionId;
    Log.i(TAG, "DemoRecognizer created, XiaozhiSessionManager=" + (sessionManager != null ? "OK" : "NULL (no libapp.so)"));
    recorder.registerRecordListener(new AudioRecordListener() {
      @Override public void onRecord(byte[] asrData, int length) {
        if (asrData == null || length <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastLogMs > 3000) {
          Log.d(TAG, "onRecord (WeiNa): mic received " + length + " bytes");
          lastLogMs = now;
        }
        buffer.write(asrData, 0, length);
        processFrames();
      }
    }, null, null);
  }

  private void processFrames() {
    byte[] data = buffer.toByteArray();
    int offset = 0;
    while (data.length - offset >= FRAME_BYTES) {
      byte[] frame = Arrays.copyOfRange(data, offset, offset + FRAME_BYTES);
      offset += FRAME_BYTES;
      sendFrameToXiaozhi(frame);
    }
    buffer.reset();
    if (data.length - offset > 0) {
      buffer.write(data, offset, data.length - offset);
    }
  }

  private void sendFrameToXiaozhi(byte[] frame) {
    if (wakeWordPcmFeeder != null) {
      wakeWordPcmFeeder.feedPcmFrame(frame);
    } else {
      if (!loggedNoWakeFeederOnce) {
        loggedNoWakeFeederOnce = true;
        Log.w(TAG, "[WakeWord] feeder=NULL – PCM không feed vào sherpa KWS, không thể hey mini (cần setWakeWordPcmFeeder sau init)");
      }
    }
    if (xiaozhiSessionManager != null) {
      xiaozhiSessionManager.sendPcmFrameFromJava(frame);
    } else {
      if (!loggedNoXiaozhiOnce) {
        loggedNoXiaozhiOnce = true;
        Log.w(TAG, "PCM captured but XiaozhiSessionManager is null (no libapp.so) - not sending to server. Add libapp.so/Opus JNI to enable WebSocket + TTS.");
      }
    }
  }

  /** Bật mic ngay khi mở app để feed PCM vào wake word (hey mini) – gọi sau init. */
  public void startMicForWakeWord() {
    startDirectAudioRecord();
  }

  /** Start Android AudioRecord in app process; feed PCM to Xiaozhi when available (WeiNa often does not deliver). */
  private void startDirectAudioRecord() {
    if (directRecordRunning.getAndSet(true)) return;

    int bufSize = Math.max(FRAME_BYTES * 4, AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
    try {
      if (audioSessionId != 0) {
        AudioFormat format = new AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build();
        AudioRecord.Builder recordBuilder = new AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufSize);
        try {
          Method setSessionId = AudioRecord.Builder.class.getMethod("setSessionId", int.class);
          setSessionId.invoke(recordBuilder, audioSessionId);
        } catch (Exception e) {
          Log.w(TAG, "setSessionId not available (API < 25?): " + e.getMessage());
        }
        directRecord = recordBuilder.build();
      } else {
        directRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
      }
      if (directRecord.getState() != AudioRecord.STATE_INITIALIZED) {
        Log.e(TAG, "AudioRecord not initialized (state=" + directRecord.getState() + "), mic may be in use by another process");
        directRecord.release();
        directRecord = null;
        directRecordRunning.set(false);
        return;
      }
      if (AcousticEchoCanceler.isAvailable()) {
        aec = AcousticEchoCanceler.create(directRecord.getAudioSessionId());
        if (aec != null) {
          aec.setEnabled(true);
          Log.i(TAG, "AEC initialized (giảm echo khi TTS phát)");
        }
      } else {
        Log.w(TAG, "AEC not available on this device");
      }
      if (NoiseSuppressor.isAvailable()) {
        ns = NoiseSuppressor.create(directRecord.getAudioSessionId());
        if (ns != null) {
          ns.setEnabled(true);
          Log.i(TAG, "NoiseSuppressor initialized");
        }
      } else {
        Log.w(TAG, "NoiseSuppressor not available on this device");
      }
      directRecord.startRecording();
      Log.i(TAG, "AudioRecord started (fallback mic), buffer=" + bufSize + (xiaozhiSessionManager != null ? ", will send to Xiaozhi" : ", Xiaozhi=null (no libapp.so)"));
    } catch (SecurityException e) {
      Log.e(TAG, "AudioRecord: no RECORD_AUDIO permission?", e);
      directRecordRunning.set(false);
      return;
    }

    directRecordThread = new Thread(() -> {
      byte[] readBuf = new byte[FRAME_BYTES];
      ByteArrayOutputStream pcmAlign = new ByteArrayOutputStream();
      long lastLogNs = System.currentTimeMillis();
      int framesSent = 0;
      int readCalls = 0;
      while (directRecordRunning.get() && directRecord != null) {
        int read = directRecord.read(readBuf, 0, readBuf.length);
        if (read <= 0) {
          if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) break;
          continue;
        }
        readCalls++;
        pcmAlign.write(readBuf, 0, read);
        byte[] data = pcmAlign.toByteArray();
        int offset = 0;
        while (data.length - offset >= FRAME_BYTES) {
          byte[] frame = Arrays.copyOfRange(data, offset, offset + FRAME_BYTES);
          offset += FRAME_BYTES;
          sendFrameToXiaozhi(frame);
          framesSent++;
        }
        pcmAlign.reset();
        if (data.length - offset > 0) {
          pcmAlign.write(data, offset, data.length - offset);
        }
        long now = System.currentTimeMillis();
        if (now - lastLogNs > 10000) {
          String sendStatus;
          if (xiaozhiSessionManager == null) {
            sendStatus = ", NOT sending (no libapp.so - add lib to enable STT/TTS)";
          } else if (xiaozhiSessionManager.isAcceptingServerPcm()) {
            sendStatus = ", sending to server (Xiaozhi)";
          } else {
            sendStatus = ", mic local only (Xiaozhi chặn PCM – xem sendPcmFrameFromJava)";
          }
          Log.d(TAG, "onRecord (AudioRecord): reads=" + readCalls + ", framesSent=" + framesSent + ", lastRead=" + read + sendStatus);
          lastLogNs = now;
        }
      }
      Log.i(TAG, "AudioRecord thread exit, frames sent=" + framesSent);
    }, "XiaozhiMic");
    directRecordThread.start();
  }

  private void stopDirectAudioRecord() {
    directRecordRunning.set(false);
    if (aec != null) {
      try {
        aec.setEnabled(false);
        aec.release();
      } catch (Exception e) {
        Log.w(TAG, "AEC release: " + e.getMessage());
      }
      aec = null;
    }
    if (ns != null) {
      try {
        ns.setEnabled(false);
        ns.release();
      } catch (Exception e) {
        Log.w(TAG, "NS release: " + e.getMessage());
      }
      ns = null;
    }
    if (directRecord != null) {
      try {
        directRecord.stop();
        directRecord.release();
      } catch (Exception e) {
        Log.w(TAG, "AudioRecord stop: " + e.getMessage());
      }
      directRecord = null;
    }
    if (directRecordThread != null) {
      try {
        directRecordThread.join(500);
      } catch (InterruptedException ignored) { }
      directRecordThread = null;
    }
    Log.i(TAG, "AudioRecord stopped");
  }

  @Override protected void startRecognizing(RecognitionOption recognitionOption) {
    Log.i(TAG, "startRecognizing: Xiaozhi flow – chỉ directRecord, không WeiNa");
    startDirectAudioRecord();
    if (xiaozhiSessionManager != null) {
      if (xiaozhiSessionManager.wasWakeHandledRecently()) {
        Log.i(TAG, "startRecognizing: skip WS reopen (wake vừa xử lý bởi handleWakeup)");
      } else {
        xiaozhiSessionManager.onWakeOrResumeListening(false);
      }
    }
    // Không recorder.start() – tránh conflict, dùng directRecord thuần như Xiaozhi_Android
  }

  @Override protected void stopRecognizing() {
    Log.i(TAG, "stopRecognizing: directRecord giữ chạy (như Xiaozhi liên tục)");
    recorder.stop();
    // Không stopDirectAudioRecord – chạy liên tục
  }

  /** Gọi ngay khi phát hiện hey mini – dừng loa trước, ưu tiên hàng đầu. */
  public void forceStopForHeyMini() {
    Log.i(TAG, "[WakeWord] forceStopForHeyMini() – dừng phát + set flags");
    if (xiaozhiSessionManager != null) {
      xiaozhiSessionManager.forceStopPlaybackForHeyMini();
    } else {
      Log.w(TAG, "[WakeWord] forceStopForHeyMini: xiaozhiSessionManager=null");
    }
  }

  /** Call this after wake-up when framework does not start recognition (e.g. event subscribers empty). */
  public void startRecognitionAfterWakeup() {
    startRecognitionAfterWakeup(false);
  }

  /** @param forceReconnect true khi chạm đầu – bỏ qua debounce nếu WS đã đóng. */
  public void startRecognitionAfterWakeup(boolean forceReconnect) {
    Log.i(TAG, "[WakeWord] startRecognitionAfterWakeup(forceReconnect=" + forceReconnect + ") → onWakeOrResumeListening");
    startDirectAudioRecord();
    if (xiaozhiSessionManager != null) {
      xiaozhiSessionManager.onWakeOrResumeListening(forceReconnect);
    }
  }

  /**
   * Sau khi TTS stop và đã reconnect + send listen – chỉ đảm bảo mic (directRecord) chạy,
   * KHÔNG gọi onNewConversationTurn() để tránh đóng WebSocket vừa mở. Hey mini / head-touch
   * vẫn sẽ gọi startRecognitionAfterWakeup() → full onNewConversationTurn() khi cần.
   */
  /** Sau dance/TTS/ting: stop + start lại AudioRecord (AEC/mic bị motor loa làm lệch). */
  public void restartDirectAudioRecord() {
    Log.i(TAG, "restartDirectAudioRecord: stop + start AudioRecord (reset mic sau dance/TTS)");
    stopDirectAudioRecord();
    directRecordRunning.set(false);
    buffer.reset();
    startDirectAudioRecord();
  }

  public void startRecognizingAfterTtsReconnect() {
    Log.i(TAG, "startRecognizingAfterTtsReconnect: restart directRecord, giữ nguyên WS");
    restartDirectAudioRecord();
  }
}
