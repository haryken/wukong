package com.ubtrobot.mini.speech.framework.demo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;

import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chụp một khung JPEG bằng Camera2 ({@link CameraDevice} + {@link ImageReader}), không qua Master /
 * {@code TakePicApi}. Dùng làm fallback khi ROM không expose {@code /api/camera/...}.
 */
public final class DirectCamera2JpegCapture {
    private static final String TAG = "DirectCamera2";

    private DirectCamera2JpegCapture() {}

    /**
     * @return JPEG bytes hoặc {@code null} (permission, không có camera, timeout, lỗi)
     */
    public static byte[] captureOneJpegBlocking(Context appContext, long timeoutMs) {
        if (appContext == null) {
            Log.w(TAG, "context null");
            return null;
        }
        Context ctx = appContext.getApplicationContext();
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "CAMERA chưa được cấp — cấp trong Cài đặt hoặc cho phép khi app hỏi");
            return null;
        }

        HandlerThread ht = new HandlerThread("camera2-jpeg");
        ht.start();
        Handler bg = new Handler(ht.getLooper());
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        AtomicReference<CameraCaptureSession> sessionRef = new AtomicReference<>();
        AtomicReference<CameraDevice> deviceRef = new AtomicReference<>();
        AtomicReference<ImageReader> readerRef = new AtomicReference<>();

        Runnable releaseAll = () -> {
            CameraCaptureSession s = sessionRef.getAndSet(null);
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
            CameraDevice d = deviceRef.getAndSet(null);
            if (d != null) {
                try {
                    d.close();
                } catch (Exception ignored) {
                }
            }
            ImageReader r = readerRef.getAndSet(null);
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        };

        bg.post(() -> {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                result.completeExceptionally(new IllegalStateException("no CameraManager"));
                ht.quitSafely();
                return;
            }
            final String cameraId;
            final Size jpegSize;
            try {
                cameraId = pickCameraId(cm);
                CameraCharacteristics ch = cm.getCameraCharacteristics(cameraId);
                jpegSize = pickJpegSize(ch);
            } catch (Throwable t) {
                Log.e(TAG, "pick camera", t);
                result.completeExceptionally(t);
                ht.quitSafely();
                return;
            }

            ImageReader reader =
                    ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
            readerRef.set(reader);

            reader.setOnImageAvailableListener(
                    imageReader -> {
                        try (Image image = imageReader.acquireLatestImage()) {
                            if (image == null) {
                                if (!result.isDone()) {
                                    result.completeExceptionally(new IllegalStateException("empty Image"));
                                }
                                return;
                            }
                            ByteBuffer buf = image.getPlanes()[0].getBuffer();
                            byte[] copy = new byte[buf.remaining()];
                            buf.get(copy);
                            if (copy.length == 0) {
                                if (!result.isDone()) {
                                    result.completeExceptionally(new IllegalStateException("zero-length JPEG"));
                                }
                            } else if (!result.complete(copy)) {
                                // already completed (race) — ignore
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "read JPEG", t);
                            result.completeExceptionally(t);
                        } finally {
                            releaseAll.run();
                            ht.quitSafely();
                        }
                    },
                    bg);

            try {
                cm.openCamera(
                        cameraId,
                        new CameraDevice.StateCallback() {
                            @Override
                            public void onOpened(CameraDevice camera) {
                                deviceRef.set(camera);
                                try {
                                    camera.createCaptureSession(
                                            Collections.singletonList(reader.getSurface()),
                                            new CameraCaptureSession.StateCallback() {
                                                @Override
                                                public void onConfigured(CameraCaptureSession session) {
                                                    sessionRef.set(session);
                                                    try {
                                                        CaptureRequest.Builder b =
                                                                camera.createCaptureRequest(
                                                                        CameraDevice.TEMPLATE_STILL_CAPTURE);
                                                        b.addTarget(reader.getSurface());
                                                        session.capture(
                                                                b.build(),
                                                                new CameraCaptureSession.CaptureCallback() {
                                                                    @Override
                                                                    public void onCaptureFailed(
                                                                            CameraCaptureSession s,
                                                                            CaptureRequest request,
                                                                            CaptureFailure failure) {
                                                                        if (!result.isDone()) {
                                                                            result.completeExceptionally(
                                                                                    new IllegalStateException(
                                                                                            "capture failed reason="
                                                                                                    + failure.getReason()));
                                                                        }
                                                                        releaseAll.run();
                                                                        ht.quitSafely();
                                                                    }
                                                                },
                                                                bg);
                                                    } catch (CameraAccessException e) {
                                                        Log.e(TAG, "capture", e);
                                                        if (!result.isDone()) {
                                                            result.completeExceptionally(e);
                                                        }
                                                        releaseAll.run();
                                                        ht.quitSafely();
                                                    }
                                                }

                                                @Override
                                                public void onConfigureFailed(CameraCaptureSession session) {
                                                    if (!result.isDone()) {
                                                        result.completeExceptionally(
                                                                new IllegalStateException("session configure failed"));
                                                    }
                                                    releaseAll.run();
                                                    ht.quitSafely();
                                                }
                                            },
                                            bg);
                                } catch (CameraAccessException e) {
                                    Log.e(TAG, "createCaptureSession", e);
                                    if (!result.isDone()) {
                                        result.completeExceptionally(e);
                                    }
                                    releaseAll.run();
                                    ht.quitSafely();
                                }
                            }

                            @Override
                            public void onDisconnected(CameraDevice camera) {
                                if (!result.isDone()) {
                                    result.completeExceptionally(new IllegalStateException("camera disconnected"));
                                }
                                releaseAll.run();
                                ht.quitSafely();
                            }

                            @Override
                            public void onError(CameraDevice camera, int error) {
                                if (!result.isDone()) {
                                    result.completeExceptionally(
                                            new IllegalStateException("camera error code=" + error));
                                }
                                releaseAll.run();
                                ht.quitSafely();
                            }
                        },
                        bg);
            } catch (SecurityException | CameraAccessException e) {
                Log.e(TAG, "openCamera", e);
                if (!result.isDone()) {
                    result.completeExceptionally(e);
                }
                releaseAll.run();
                ht.quitSafely();
            }
        });

        byte[] jpeg = null;
        try {
            jpeg = result.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Log.e(TAG, "timeout " + timeoutMs + "ms", e);
            result.cancel(true);
            bg.post(
                    () -> {
                        releaseAll.run();
                        ht.quitSafely();
                    });
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            Log.w(TAG, "Camera2 failed: " + (c != null ? c.getMessage() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.cancel(true);
            bg.post(
                    () -> {
                        releaseAll.run();
                        ht.quitSafely();
                    });
        }

        try {
            ht.join(8000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (jpeg != null) {
            Log.i(TAG, "JPEG ok, len=" + jpeg.length);
        }
        return jpeg;
    }

    private static String pickCameraId(CameraManager cm) throws CameraAccessException {
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        String[] ids = cm.getCameraIdList();
        if (ids == null || ids.length == 0) {
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "no camera");
        }
        return ids[0];
    }

    private static Size pickJpegSize(CameraCharacteristics ch) {
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return new Size(640, 480);
        }
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) {
            return new Size(640, 480);
        }
        Arrays.sort(
                sizes,
                (a, b) ->
                        Integer.compare(
                                b.getWidth() * b.getHeight(), a.getWidth() * a.getHeight()));
        for (Size s : sizes) {
            if (s.getWidth() <= 1280 && s.getHeight() <= 960) {
                return s;
            }
        }
        return sizes[0];
    }
}
