package com.aibookkeeper.feature.capture.screenshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aibookkeeper.feature.capture.R

/**
 * Foreground service required on Android 14+ (API 34) to hold a
 * [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION] token before
 * [android.media.projection.MediaProjectionManager.getMediaProjection] is called.
 *
 * Lifecycle is managed by [ScreenshotCaptureActivity]:
 *   1. Activity starts this service after the user grants the screen-capture consent dialog.
 *   2. Activity calls [getMediaProjection], captures the screenshot, then stops this service.
 */
class ScreenshotForegroundService : Service() {

    companion object {
        private const val TAG = "ScreenshotFgService"
        private const val CHANNEL_ID = "screenshot_capture_channel"
        private const val CHANNEL_NAME = "截图记账"
        private const val NOTIFICATION_ID = 1010

        fun start(context: Context) {
            val intent = Intent(context, ScreenshotForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenshotForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.javaClass.simpleName}", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "截图记账前台服务"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在截图记账…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
