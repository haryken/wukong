package com.ubtrobot.mini.speech.framework.demo.selfcontrol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.util.Arrays
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Camera2 YUV → JPEG (chất lượng thấp, ~12–15 fps) cho MJPEG/tunnel mượt hơn JPEG hardware từng khung.
 */
object SelfControlCameraMjpeg {
    private const val TAG = "SelfControlCam"
    private const val BOUNDARY = "selfcontrolframe"
    private const val JPEG_QUALITY = 55
    private const val MIN_FRAME_GAP_MS = 66L // ~15 fps cap
    private const val TARGET_W = 480
    private const val TARGET_H = 360

    @Volatile private var appContext: Context? = null
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private val frameSeq = AtomicLong(0)
    private val viewers = AtomicInteger(0)
    private val frameListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()

    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private val lock = Any()
    @Volatile private var lastEmitMs = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun latestJpegOrNull(): ByteArray? = latestJpeg.get()

    fun addFrameListener(listener: (ByteArray) -> Unit) {
        frameListeners.add(listener)
        ensureStarted()
    }

    fun removeFrameListener(listener: (ByteArray) -> Unit) {
        frameListeners.remove(listener)
        maybeStop()
    }

    fun snapshotJpeg(timeoutMs: Long = 8_000L): ByteArray? {
        ensureStarted()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            latestJpeg.get()?.let { return it }
            try {
                Thread.sleep(30)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return latestJpeg.get()
    }

    fun writeMjpeg(sock: Socket) {
        ensureStarted()
        viewers.incrementAndGet()
        val out = sock.getOutputStream()
        try {
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            out.flush()
            var lastSeq = -1L
            while (!sock.isClosed && sock.isConnected) {
                val seq = frameSeq.get()
                val jpeg = latestJpeg.get()
                if (jpeg != null && seq != lastSeq) {
                    lastSeq = seq
                    writePart(out, jpeg)
                } else {
                    Thread.sleep(8)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "mjpeg client end: ${e.message}")
        } finally {
            viewers.decrementAndGet()
            maybeStop()
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    fun writeSnapshotResponse(out: OutputStream) {
        val jpeg = snapshotJpeg()
        if (jpeg == null) {
            val err = """{"success":false,"error":"camera unavailable"}""".toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 503 Service Unavailable\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${err.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            out.write(err)
            out.flush()
            return
        }
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpeg.size}\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.write(jpeg)
        out.flush()
    }

    private fun writePart(out: OutputStream, jpeg: ByteArray) {
        out.write(
            ("--$BOUNDARY\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpeg.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.write(jpeg)
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun ensureStarted() {
        synchronized(lock) {
            if (session != null) return
            val ctx = appContext ?: return
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "CAMERA permission missing")
                return
            }
            val ht = HandlerThread("selfcontrol-cam")
            ht.start()
            thread = ht
            bg = Handler(ht.looper)
            bg?.post { openCamera(ctx) }
        }
    }

    private fun maybeStop() {
        synchronized(lock) {
            if (viewers.get() > 0 || frameListeners.isNotEmpty()) return
            bg?.post { closeCamera() }
        }
    }

    private fun openCamera(ctx: Context) {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            val cameraId = pickCameraId(cm)
            val ch = cm.getCameraCharacteristics(cameraId)
            val size = pickYuvSize(ch)
            val imageReader = ImageReader.newInstance(
                size.width, size.height, ImageFormat.YUV_420_888, 4
            )
            reader = imageReader
            imageReader.setOnImageAvailableListener({ ir ->
                val now = System.currentTimeMillis()
                if (now - lastEmitMs < MIN_FRAME_GAP_MS) {
                    ir.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                try {
                    ir.acquireLatestImage()?.use { image ->
                        val jpeg = yuvToJpeg(image, JPEG_QUALITY) ?: return@use
                        lastEmitMs = now
                        latestJpeg.set(jpeg)
                        frameSeq.incrementAndGet()
                        for (l in frameListeners) {
                            try {
                                l(jpeg)
                            } catch (_: Exception) {
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "frame: ${e.message}")
                }
            }, bg)

            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    try {
                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    try {
                                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                        req.addTarget(imageReader.surface)
                                        req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pickFps(ch))
                                        s.setRepeatingRequest(req.build(), null, bg)
                                        Log.i(TAG, "stream ${size.width}x${size.height} q=$JPEG_QUALITY")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "repeating: ${e.message}")
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "configure failed")
                                    closeCamera()
                                }
                            },
                            bg
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "session: ${e.message}")
                        closeCamera()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    closeCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "camera error=$error")
                    closeCamera()
                }
            }, bg)
        } catch (e: SecurityException) {
            Log.e(TAG, "openCamera security", e)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera", e)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera: ${e.message}")
        }
    }

    private fun pickFps(ch: CameraCharacteristics): android.util.Range<Int>? {
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        var best: android.util.Range<Int>? = null
        for (r in ranges) {
            if (r.upper in 15..30) {
                if (best == null || r.upper > best.upper) best = r
            }
        }
        return best ?: ranges.lastOrNull()
    }

    private fun yuvToJpeg(image: Image, quality: Int): ByteArray? {
        val nv21 = yuv420ToNv21(image) ?: return null
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val baos = ByteArrayOutputStream()
        if (!yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, baos)) {
            return null
        }
        return baos.toByteArray()
    }

    private fun yuv420ToNv21(image: Image): ByteArray? {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val ySize = image.width * image.height
        val nv21 = ByteArray(ySize + ySize / 2)
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var pos = 0
        if (yRowStride == image.width && yPixelStride == 1) {
            yBuf.get(nv21, 0, ySize)
            pos = ySize
        } else {
            val row = ByteArray(yRowStride)
            for (rowIdx in 0 until image.height) {
                yBuf.position(rowIdx * yRowStride)
                yBuf.get(row, 0, yRowStride.coerceAtMost(yBuf.remaining()))
                var col = 0
                while (col < image.width) {
                    nv21[pos++] = row[col * yPixelStride]
                    col++
                }
            }
        }
        val vBuf = vPlane.buffer
        val uBuf = uPlane.buffer
        val vRowStride = vPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uPixelStride = uPlane.pixelStride
        val chromaHeight = image.height / 2
        val chromaWidth = image.width / 2
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * vRowStride + col * vPixelStride
                val uIndex = row * uRowStride + col * uPixelStride
                nv21[pos++] = vBuf.get(vIndex)
                nv21[pos++] = uBuf.get(uIndex)
            }
        }
        return nv21
    }

    private fun closeCamera() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
        try {
            thread?.quitSafely()
        } catch (_: Exception) {
        }
        thread = null
        bg = null
        Log.i(TAG, "camera stopped")
    }

    private fun pickCameraId(cm: CameraManager): String {
        for (id in cm.cameraIdList) {
            val facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) return id
        }
        for (id in cm.cameraIdList) {
            val facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        val ids = cm.cameraIdList
        if (ids.isEmpty()) throw CameraAccessException(CameraAccessException.CAMERA_ERROR, "no camera")
        return ids[0]
    }

    private fun pickYuvSize(ch: CameraCharacteristics): Size {
        val map: StreamConfigurationMap = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(640, 480)
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return Size(640, 480)
        Arrays.sort(sizes) { a, b ->
            Integer.compare(a.width * a.height, b.width * b.height)
        }
        var best: Size? = null
        for (s in sizes) {
            if (s.width in 320..TARGET_W && s.height in 240..TARGET_H) {
                best = s
            }
        }
        if (best != null) return best
        for (s in sizes) {
            if (s.width <= 640) return s
        }
        return sizes[0]
    }
}
