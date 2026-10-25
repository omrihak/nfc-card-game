package com.omrihak.nfccards.qr

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * Looks for QR codes with the front camera while the activity is in the foreground (so only
 * while the screen is on). Nothing is shown on screen. Battery: low resolution, the lowest frame
 * rate the camera offers, a few decode attempts per second, and the camera is closed for
 * [PAUSE_AFTER_SCAN_MS] after each code so a code held in view doesn't re-trigger.
 *
 * Only emits the text found in the code. It knows nothing about what the text means.
 */
class QrReader(
    private val activity: ComponentActivity,
    private val onCode: (text: String) -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val reader = QRCodeReader()
    private val bindRunnable = Runnable { bind() }

    private var running = false
    private var provider: ProcessCameraProvider? = null

    /** Written on the analysis thread, read on both. */
    @Volatile private var pausedUntil = 0L
    private var lastDecodeAt = 0L

    fun start() {
        running = true
        scheduleBind()
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacks(bindRunnable)
        provider?.unbindAll()
    }

    fun close() {
        stop()
        analysisExecutor.shutdown()
    }

    private fun scheduleBind() {
        mainHandler.removeCallbacks(bindRunnable)
        val wait = pausedUntil - SystemClock.elapsedRealtime()
        if (wait > 0) mainHandler.postDelayed(bindRunnable, wait) else mainHandler.post(bindRunnable)
    }

    private fun bind() {
        if (!running) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No camera permission; QR scanning is off")
            return
        }
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (!running) return@addListener
            try {
                val p = future.get().also { provider = it }
                if (!p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    Log.e(TAG, "This device has no front camera")
                    return@addListener
                }
                p.unbindAll()
                p.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, buildAnalysis(p))
                Log.d(TAG, "Scanning")
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the camera", e)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildAnalysis(p: ProcessCameraProvider): ImageAnalysis {
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

        // Lowest frame rate the front camera supports (often 7-15 fps instead of 30).
        val info = CameraSelector.DEFAULT_FRONT_CAMERA.filter(p.availableCameraInfos).firstOrNull()
        val slowest = info?.let {
            Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        }?.minWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
        if (slowest != null) {
            Log.d(TAG, "Frame rate range $slowest")
            Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, slowest)
        }

        return builder.build().apply { setAnalyzer(analysisExecutor, ::analyze) }
    }

    /** Analysis thread. */
    private fun analyze(image: ImageProxy) {
        image.use {
            val now = SystemClock.elapsedRealtime()
            if (now < pausedUntil || now - lastDecodeAt < MIN_DECODE_INTERVAL_MS) return
            lastDecodeAt = now
            val text = decode(it) ?: return
            pausedUntil = now + PAUSE_AFTER_SCAN_MS
            Log.i(TAG, "QR code -> $text")
            mainHandler.post {
                if (!running) return@post
                onCode(text)
                provider?.unbindAll()
                scheduleBind()
            }
        }
    }

    /** The Y plane is all a QR decoder needs. */
    private fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
        val source = PlanarYUVLuminanceSource(
            bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false,
        )
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }

    companion object {
        private const val TAG = "QrReader"
        private const val PAUSE_AFTER_SCAN_MS = 10_000L
        private const val MIN_DECODE_INTERVAL_MS = 250L
    }
}
