package com.ubtrobot.mini.speech.framework.demo;

import android.media.AudioManager;
import android.media.MediaActionSound;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Tiếng "tách" khi chụp ảnh thành công — user biết ROM/Camera2 đã chụp xong.
 */
public final class CameraShutterSound {
    private static final String TAG = "CameraShutter";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile MediaActionSound mediaActionSound;
    private static volatile boolean shutterLoaded;

    private CameraShutterSound() {}

    /** Gọi sớm từ {@link SpeechApplication} để lần chụp đầu không bị trễ load. */
    public static void preload() {
        MAIN.post(CameraShutterSound::ensureLoaded);
    }

    /** Phát trên main thread sau khi TakePic/Camera2 trả JPEG thành công. */
    public static void playOnCaptureSuccess() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            playInternal();
        } else {
            MAIN.post(CameraShutterSound::playInternal);
        }
    }

    private static void ensureLoaded() {
        if (mediaActionSound == null) {
            try {
                mediaActionSound = new MediaActionSound();
                mediaActionSound.load(MediaActionSound.SHUTTER_CLICK);
                shutterLoaded = true;
            } catch (Throwable t) {
                Log.d(TAG, "MediaActionSound load: " + t.getMessage());
            }
        }
    }

    private static void playInternal() {
        ensureLoaded();
        try {
            if (mediaActionSound != null && shutterLoaded) {
                mediaActionSound.play(MediaActionSound.SHUTTER_CLICK);
                Log.d(TAG, "SHUTTER_CLICK");
                return;
            }
        } catch (Throwable t) {
            Log.d(TAG, "play shutter: " + t.getMessage());
        }
        playBeepFallback();
    }

    private static void playBeepFallback() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85);
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 130);
            MAIN.postDelayed(tg::release, 220);
            Log.d(TAG, "beep fallback");
        } catch (Throwable t) {
            Log.w(TAG, "shutter sound failed: " + t.getMessage());
        }
    }
}
