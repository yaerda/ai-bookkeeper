package com.aibookkeeper.feature.capture.screenshot

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.io.File

/**
 * Receives images shared from other apps (e.g. system screenshot share sheet).
 * Copies the image to cache and opens CaptureScreen for review + AI extraction.
 */
class ShareImageReceiverActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ShareImageReceiver"
        const val EXTRA_SHARED_IMAGE_URI = "shared_image_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
        if (imageUri == null) {
            Log.w(TAG, "No image URI in share intent")
            Toast.makeText(this, "未收到图片", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        openCaptureScreen(imageUri)
    }

    private fun openCaptureScreen(uri: Uri) {
        // Copy shared image to cache (URI permissions expire when this activity finishes)
        val cachedUri = copyToCache(uri)
        if (cachedUri == null) {
            Toast.makeText(this, "无法读取图片", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra(EXTRA_SHARED_IMAGE_URI, cachedUri.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(launchIntent)
        finish()
    }

    private fun copyToCache(uri: Uri): Uri? {
        return try {
            val cacheFile = File(cacheDir, "shared_capture_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            Uri.fromFile(cacheFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy shared image to cache", e)
            null
        }
    }
}
