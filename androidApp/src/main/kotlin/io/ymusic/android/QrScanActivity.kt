package io.ymusic.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_close
import io.ymusic.ui.resources.device_link_scan_hint
import org.jetbrains.compose.resources.stringResource
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen camera preview that returns one QR payload (or cancels). Decode is on-device
 * ZXing — no Play services / ML Kit.
 */
class QrScanActivity : ComponentActivity() {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val found = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var previewView: PreviewView
    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) bindCamera() else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        val dark = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        setContent {
            QrScanOverlay(previewView, onClose = { finish() })
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            previewView.post { bindCamera() }
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                if (isDestroyed || isFinishing) return@addListener
                val provider = try {
                    future.get()
                } catch (_: Exception) {
                    finish()
                    return@addListener
                }
                cameraProvider = provider
                val selector = when {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> {
                        finish()
                        return@addListener
                    }
                }
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image ->
                    try {
                        if (found.get()) return@setAnalyzer
                        val text = decodeQr(image) ?: return@setAnalyzer
                        if (found.compareAndSet(false, true)) {
                            runOnUiThread {
                                setResult(RESULT_OK, Intent().putExtra(EXTRA_VALUE, text))
                                finish()
                            }
                        }
                    } finally {
                        image.close()
                    }
                }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, preview, analysis)
                } catch (t: Exception) {
                    android.util.Log.e("QrScan", "bind failed", t)
                    finish()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VALUE = "qr"
    }
}

@Composable
private fun QrScanOverlay(previewView: PreviewView, onClose: () -> Unit) {
    val closeCd = stringResource(Res.string.cd_close)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .size(44.dp)
                .semantics { contentDescription = closeCd }
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = Color.White, fontSize = 22.sp)
        }
        Text(
            stringResource(Res.string.device_link_scan_hint),
            style = TextStyle(fontSize = 14.sp, color = Color.White),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 32.dp),
        )
    }
}

private val qrHints: Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
)

private fun decodeQr(image: ImageProxy): String? {
    val y = image.planes[0]
    val buffer = y.buffer
    buffer.rewind()
    val width = image.width
    val height = image.height
    val data = ByteArray(width * height)
    val rowStride = y.rowStride
    val pixelStride = y.pixelStride
    if (pixelStride == 1 && rowStride == width) {
        buffer.get(data, 0, data.size.coerceAtMost(buffer.remaining()))
    } else {
        var dst = 0
        for (row in 0 until height) {
            var src = row * rowStride
            for (col in 0 until width) {
                data[dst++] = buffer.get(src)
                src += pixelStride
            }
        }
    }
    val (rotated, rw, rh) = rotateY(data, width, height, image.imageInfo.rotationDegrees)
    val source = PlanarYUVLuminanceSource(rotated, rw, rh, 0, 0, rw, rh, false)
    val reader = MultiFormatReader()
    return try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), qrHints).text
    } catch (_: ReaderException) {
        null
    }
}

private fun rotateY(data: ByteArray, width: Int, height: Int, rotation: Int): Triple<ByteArray, Int, Int> {
    val r = ((rotation % 360) + 360) % 360
    if (r == 0) return Triple(data, width, height)
    val out = ByteArray(data.size)
    when (r) {
        90 -> {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[x * height + (height - 1 - y)] = data[y * width + x]
                }
            }
            return Triple(out, height, width)
        }
        180 -> {
            for (i in data.indices) out[data.size - 1 - i] = data[i]
            return Triple(out, width, height)
        }
        270 -> {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[(width - 1 - x) * height + y] = data[y * width + x]
                }
            }
            return Triple(out, height, width)
        }
        else -> return Triple(data, width, height)
    }
}
