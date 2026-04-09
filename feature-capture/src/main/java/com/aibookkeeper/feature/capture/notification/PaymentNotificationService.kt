package com.aibookkeeper.feature.capture.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.aibookkeeper.feature.capture.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground service that maintains a persistent notification in the status bar
 * with quick-access bookkeeping buttons (text, voice, camera, category shortcuts).
 *
 * Implements US-10.1 (persistent notification entry), US-10.3 (category quick buttons),
 * and US-10.4 (success feedback notification).
 */
@AndroidEntryPoint
class PaymentNotificationService : Service() {

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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationConstants.NOTIFICATION_ID_PERSISTENT,
                buildPersistentNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationConstants.NOTIFICATION_ID_PERSISTENT, buildPersistentNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // ── Build the persistent notification ────────────────

    private fun buildPersistentNotification(): Notification {
        // Single tap opens the app's home screen
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openAppPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Compact view: title + subtitle (tap to open app)
        val compactView = RemoteViews(packageName, R.layout.notification_compact)

        // Expanded view: quick-action buttons (text, voice, camera) + category shortcuts
        val expandedView = RemoteViews(packageName, R.layout.notification_quick_actions)
        expandedView.setOnClickPendingIntent(R.id.btn_text_input, openAppPending)
        expandedView.setOnClickPendingIntent(
            R.id.btn_voice_input,
            buildQuickInputPending(NotificationConstants.ACTION_QUICK_VOICE)
        )
        expandedView.setOnClickPendingIntent(
            R.id.btn_camera_input,
            buildQuickInputPending(NotificationConstants.ACTION_QUICK_CAMERA)
        )

        return NotificationCompat.Builder(this, NotificationConstants.CHANNEL_ID_QUICK_INPUT)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPending)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compactView)
            .setCustomBigContentView(expandedView)
            .build()
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
