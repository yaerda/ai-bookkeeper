package com.aibookkeeper.feature.capture.screenshot

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.aibookkeeper.core.data.ai.NotificationExtractionPipeline
import com.aibookkeeper.core.data.model.SourceApp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenshotCaptureActivity : ComponentActivity() {

    private val mediaProjectionManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            captureScreenshot(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "截图权限被拒绝", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun captureScreenshot(resultCode: Int, data: Intent) {
        val mediaProjection = try {
            mediaProjectionManager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Toast.makeText(this, "无法启动截图", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )

        lifecycleScope.launch {
            try {
                delay(300)
                var image: Image? = null
                for (attempt in 0 until 5) {
                    image = imageReader.acquireLatestImage()
                    if (image != null) break
                    if (attempt < 4) {
                        delay(100)
                    }
                }

                val bitmap = image?.let {
                    try {
                        imageToBitmap(it)
                    } finally {
                        it.close()
                    }
                }

                if (bitmap == null) {
                    Toast.makeText(this@ScreenshotCaptureActivity, "截图失败", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                processScreenshot(bitmap)
            } catch (e: Exception) {
                Toast.makeText(this@ScreenshotCaptureActivity, "截图失败", Toast.LENGTH_SHORT).show()
                finish()
            } finally {
                imageReader.close()
                virtualDisplay.release()
                mediaProjection.stop()
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes.first()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        buffer.rewind()
        val paddedBitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        paddedBitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height).also {
            if (it != paddedBitmap) {
                paddedBitmap.recycle()
            }
        }
    }

    private fun processScreenshot(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val extractedText = visionText.text.trim()
                if (extractedText.isBlank()) {
                    Toast.makeText(this, "未识别到文字内容", Toast.LENGTH_SHORT).show()
                    finish()
                    return@addOnSuccessListener
                }

                val pipeline = EntryPointAccessors.fromApplication(
                    applicationContext,
                    ScreenshotCaptureEntryPoint::class.java
                ).notificationExtractionPipeline()

                lifecycleScope.launch {
                    val txId = withContext(Dispatchers.IO) {
                        pipeline.processNotification(SourceApp.SCREENSHOT.name, extractedText)
                    }
                    val message = if (txId > 0) {
                        "✅ 截图记账成功"
                    } else {
                        "未识别到有效消费信息"
                    }
                    Toast.makeText(this@ScreenshotCaptureActivity, message, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "OCR识别失败: ${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnCompleteListener {
                recognizer.close()
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScreenshotCaptureEntryPoint {
    fun notificationExtractionPipeline(): NotificationExtractionPipeline
}
