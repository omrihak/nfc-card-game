package com.omrihak.nfccards.qr

import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * Front-camera QR reader: a QR code held up to the camera counts as a card tap. Like NfcReader,
 * it only emits the text found and knows nothing about what it means.
 *
 * The camera follows the activity's lifecycle (off while the app is in the background).
 * Main thread, except for frame decoding.
 */
class QrScanner(
    private val activity: ComponentActivity,
    private val onCode: (text: String) -> Unit,
) {
    /** Live preview, shown small so it is clear the camera is on. */
    val previewView = PreviewView(activity).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        // TextureView, so Compose can clip it to a circle.
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    /** True once the camera is bound; the preview is only shown then. */
    var isRunning by mutableStateOf(false)
        private set

    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    private var starting = false
    private var lastCode: String? = null
    private var lastSeenAt = 0L

    /** Needs the camera permission. Safe to call repeatedly. */
    fun start() {
        if (isRunning || starting) return
        starting = true
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            starting = false
            try {
                val provider = future.get()
                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    Log.e(TAG, "No front camera; QR scanning off")
                    return@addListener
                }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(decodeExecutor) { image ->
                    val text = image.use { decode(it) }
                    if (text != null) activity.runOnUiThread { onDecoded(text) }
                }
                provider.unbindAll()
                provider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                isRunning = true
                Log.i(TAG, "QR scanning on (front camera)")
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the camera", e)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun release() = decodeExecutor.shutdown()

    /** Decoding thread. The Y plane is all a QR code needs. */
    private fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        // The last row can be shorter than rowStride; pad so ZXing can index every row.
        val bytes = ByteArray(plane.rowStride * image.height)
        buffer.get(bytes, 0, minOf(buffer.remaining(), bytes.size))
        val source = PlanarYUVLuminanceSource(
            bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false,
        )
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }

    /**
     * A code fires once when it appears. Holding it up keeps it quiet (so a song that ends doesn't
     * restart while the code is still in view); it fires again after being out of view for a moment.
     */
    private fun onDecoded(text: String) {
        val now = SystemClock.elapsedRealtime()
        val isNew = text != lastCode || now - lastSeenAt > FORGET_AFTER_MS
        lastCode = text
        lastSeenAt = now
        if (isNew) {
            Log.i(TAG, "QR -> $text")
            onCode(text)
        }
    }

    private companion object {
        const val TAG = "QrScanner"
        const val FORGET_AFTER_MS = 2000L
    }
}
