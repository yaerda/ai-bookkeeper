package com.aibookkeeper.feature.capture.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.aibookkeeper.core.data.ai.NotificationExtractionPipeline
import com.aibookkeeper.core.data.model.SourceApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Listens for payment notifications from WeChat, Alipay, Taobao, Pinduoduo.
 * Extracts transaction info via AI and auto-records it.
 *
 * Requires user to grant Notification Access in system settings.
 */
@AndroidEntryPoint
class PaymentNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "PaymentNotifListener"

        // Notification text patterns that indicate payment
        private val PAYMENT_KEYWORDS = listOf(
            "付款", "支付", "消费", "扣款", "收款", "转账",
            "到账", "收入", "退款", "红包",
            "购买", "订单", "已支付", "交易成功"
        )
    }

    @Inject
    lateinit var extractionPipeline: NotificationExtractionPipeline

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return

        if (packageName !in SourceApp.MONITORED_PACKAGES) return

        val content = extractNotificationContent(sbn) ?: return

        if (!isPaymentNotification(content)) {
            Log.d(TAG, "Non-payment notification from $packageName ignored")
            return
        }

        val sourceApp = SourceApp.fromPackageName(packageName)
        Log.i(TAG, "Payment notification from ${sourceApp.displayName}: ${content.take(50)}...")

        serviceScope.launch {
            try {
                val txId = extractionPipeline.processNotification(sourceApp.name, content)
                if (txId > 0) {
                    Log.i(TAG, "Auto-recorded transaction #$txId from ${sourceApp.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process notification", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No action needed
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun extractNotificationContent(sbn: StatusBarNotification): String? {
        val extras = sbn.notification.extras ?: return null
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
        val subText = extras.getCharSequence("android.subText")?.toString() ?: ""

        val combined = buildString {
            if (title.isNotBlank()) append("$title ")
            if (bigText.isNotBlank()) append(bigText) else append(text)
            if (subText.isNotBlank()) append(" $subText")
        }.trim()

        return combined.ifBlank { null }
    }

    private fun isPaymentNotification(content: String): Boolean =
        PAYMENT_KEYWORDS.any { content.contains(it) }
}
