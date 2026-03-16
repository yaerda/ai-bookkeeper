package com.aibookkeeper.feature.capture.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aibookkeeper.core.data.security.SecureConfigStore
import com.aibookkeeper.feature.capture.R
import com.aibookkeeper.feature.capture.screenshot.ScreenshotCaptureActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that maintains a persistent notification in the status bar
 * with quick-access bookkeeping buttons (text, voice, camera, category shortcuts).
 *
 * Implements US-10.1 (persistent notification entry), US-10.3 (category quick buttons),
 * and US-10.4 (success feedback notification).
 */
@AndroidEntryPoint
class PaymentNotificationService : Service() {

    @Inject
    lateinit var secureConfigStore: SecureConfigStore

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, PaymentNotificationService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, PaymentNotificationService::class.java)
            context.stopService(intent)
        }

        /**
         * Post a transient success-feedback notification (US-10.4).
         * Auto-cancels after a short display. Includes an undo action.
         */
        fun showSuccessFeedback(
            context: Context,
            categoryName: String,
            amount: Double,
            transactionId: Long,
            lowConfidence: Boolean = false
        ) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(nm)

            val message = if (lowConfidence) {
                "已记录：$categoryName ¥${"%.2f".format(amount)}（建议确认）"
            } else {
                "已记录：$categoryName ¥${"%.2f".format(amount)}"
            }

            val undoIntent = Intent(NotificationConstants.ACTION_UNDO_TRANSACTION).apply {
                setPackage(context.packageName)
                putExtra(NotificationConstants.EXTRA_TRANSACTION_ID, transactionId)
            }
            val undoPending = PendingIntent.getBroadcast(
                context, transactionId.toInt(), undoIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, NotificationConstants.CHANNEL_ID_QUICK_INPUT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentText(message)
                .setAutoCancel(true)
                .setTimeoutAfter(3_000L)
                .addAction(R.drawable.ic_notification, "撤销", undoPending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            nm.notify(NotificationConstants.NOTIFICATION_ID_FEEDBACK, notification)
        }

        private fun ensureChannel(nm: NotificationManager) {
            if (nm.getNotificationChannel(NotificationConstants.CHANNEL_ID_QUICK_INPUT) == null) {
                val channel = NotificationChannel(
                    NotificationConstants.CHANNEL_ID_QUICK_INPUT,
                    NotificationConstants.CHANNEL_NAME_QUICK_INPUT,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "常驻通知栏快捷记账入口"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        startForeground(NotificationConstants.NOTIFICATION_ID_PERSISTENT, buildPersistentNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // ── Build the persistent notification ────────────────

    private fun buildPersistentNotification(): Notification {
        // Single tap opens the app's home screen (user can tap FAB there)
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openAppPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, NotificationConstants.CHANNEL_ID_QUICK_INPUT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AI 智能记账")
            .setContentText("点击打开记账")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPending)

        if (secureConfigStore.isScreenshotCaptureEnabled()) {
            builder.addAction(
                android.R.drawable.ic_menu_camera,
                "📸 截图记账",
                buildScreenshotCapturePending()
            )
        }

        return builder.build()
    }

    private fun buildQuickInputPending(action: String): PendingIntent {
        val intent = Intent(action).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildScreenshotCapturePending(): PendingIntent {
        val intent = Intent(this, ScreenshotCaptureActivity::class.java).apply {
            action = NotificationConstants.ACTION_SCREENSHOT_CAPTURE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this,
            NotificationConstants.REQUEST_CODE_SCREENSHOT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildCategoryPending(categoryName: String, categoryIcon: String): PendingIntent {
        val intent = Intent(NotificationConstants.ACTION_QUICK_CATEGORY).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(NotificationConstants.EXTRA_CATEGORY_NAME, categoryName)
            putExtra(NotificationConstants.EXTRA_CATEGORY_ICON, categoryIcon)
        }
        return PendingIntent.getActivity(
            this, categoryName.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
