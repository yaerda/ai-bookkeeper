package com.aibookkeeper.feature.capture.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.aibookkeeper.core.data.ai.NotificationExtractionPipeline
import com.aibookkeeper.core.data.model.PaymentPagePattern
import com.aibookkeeper.core.data.model.SourceApp
import com.aibookkeeper.core.data.repository.PaymentPagePatternRepository
import com.aibookkeeper.core.data.repository.RawEventRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PaymentAccessibilityService : AccessibilityService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PaymentAccessibilityServiceEntryPoint {
        fun paymentPagePatternRepository(): PaymentPagePatternRepository
        fun notificationExtractionPipeline(): NotificationExtractionPipeline
        fun rawEventRepository(): RawEventRepository
    }

    companion object {
        private const val TAG = "PaymentA11yService"
        private const val EVENT_DEBOUNCE_MS = 500L
        private const val SUCCESS_COOLDOWN_MS = 10_000L
        private val AMOUNT_REGEXES = listOf(
            Regex("""[¥￥]\s*([0-9,]+(?:\.[0-9]{1,2})?)"""),
            Regex("""([0-9,]+(?:\.[0-9]{1,2})?)\s*元""")
        )

        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val componentName =
                ComponentName(context, PaymentAccessibilityService::class.java).flattenToString()
            return enabledServices.contains(componentName)
        }
    }

    private lateinit var paymentPagePatternRepository: PaymentPagePatternRepository
    private lateinit var extractionPipeline: NotificationExtractionPipeline
    private lateinit var rawEventRepository: RawEventRepository

    private var serviceScope = createServiceScope()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastEventTimestamps = HashMap<String, Long>()
    private val lastSuccessTimestamps = HashMap<String, Long>()
    private val timestampLock = Any()

    @Volatile
    private var monitoredPackages: Set<String> = emptySet()

    @Volatile
    private var patternsByPackage: Map<String, List<PaymentPagePattern>> = emptyMap()

    override fun onServiceConnected() {
        super.onServiceConnected()
        initializeDependencies()
        serviceScope.cancel()
        serviceScope = createServiceScope()
        serviceScope.launch {
            refreshPatternCache()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        if (packageName !in monitoredPackages) return

        val now = System.currentTimeMillis()
        val isDebounced = synchronized(timestampLock) {
            val lastTimestamp = lastEventTimestamps[packageName]
            if (lastTimestamp != null && now - lastTimestamp < EVENT_DEBOUNCE_MS) {
                true
            } else {
                lastEventTimestamps[packageName] = now
                false
            }
        }
        if (isDebounced) return

        val rootNode = rootInActiveWindow ?: return
        val extractedText = try {
            extractTextFromNode(rootNode)
                .replace(Regex("""\s+"""), " ")
                .trim()
        } finally {
            rootNode.recycle()
        }
        if (extractedText.isBlank()) return

        val patterns = patternsByPackage[packageName].orEmpty()
        if (patterns.isEmpty() || !matchesPattern(extractedText, patterns)) return

        val inCooldown = synchronized(timestampLock) {
            val lastSuccess = lastSuccessTimestamps[packageName]
            lastSuccess != null && now - lastSuccess < SUCCESS_COOLDOWN_MS
        }
        if (inCooldown) {
            Log.d(TAG, "Skipping $packageName during cooldown window")
            return
        }

        val sourceApp = SourceApp.fromPackageName(packageName)
        serviceScope.launch {
            try {
                if (rawEventRepository.isDuplicate(extractedText)) {
                    Log.d(TAG, "Duplicate accessibility content ignored for $packageName")
                    return@launch
                }

                val transactionId = extractionPipeline.processNotification(sourceApp.name, extractedText)
                if (transactionId > 0) {
                    synchronized(timestampLock) {
                        lastSuccessTimestamps[packageName] = System.currentTimeMillis()
                    }
                    val amount = extractAmountPreview(extractedText)
                    showToast(amount?.let { "已自动记录 ¥$it" } ?: "已自动记录")
                    Log.i(
                        TAG,
                        "Recorded transaction #$transactionId from ${sourceApp.displayName} accessibility page"
                    )
                } else {
                    Log.d(TAG, "No transaction created for ${sourceApp.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process accessibility event for $packageName", e)
            }
        }
    }

    override fun onInterrupt() {
        serviceScope.cancel()
        serviceScope = createServiceScope()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        monitoredPackages = emptySet()
        patternsByPackage = emptyMap()
        synchronized(timestampLock) {
            lastEventTimestamps.clear()
            lastSuccessTimestamps.clear()
        }
        super.onDestroy()
    }

    private fun initializeDependencies() {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            PaymentAccessibilityServiceEntryPoint::class.java
        )
        paymentPagePatternRepository = entryPoint.paymentPagePatternRepository()
        extractionPipeline = entryPoint.notificationExtractionPipeline()
        rawEventRepository = entryPoint.rawEventRepository()
    }

    private suspend fun refreshPatternCache() {
        runCatching {
            val patterns = paymentPagePatternRepository.getEnabledPatterns()
            patternsByPackage = patterns.groupBy { it.packageName }
            monitoredPackages = paymentPagePatternRepository.getMonitoredPackages()
            Log.i(TAG, "Loaded ${patterns.size} payment page patterns for ${monitoredPackages.size} packages")
        }.onFailure { error ->
            monitoredPackages = emptySet()
            patternsByPackage = emptyMap()
            Log.e(TAG, "Failed to load accessibility payment patterns", error)
        }
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        if (node.text != null) sb.append(node.text).append(" ")
        if (!node.contentDescription.isNullOrBlank()) {
            sb.append(node.contentDescription).append(" ")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            try {
                sb.append(extractTextFromNode(child))
            } finally {
                child?.recycle()
            }
        }
        return sb.toString()
    }

    private fun matchesPattern(text: String, patterns: List<PaymentPagePattern>): Boolean {
        return patterns.any { pattern ->
            pattern.keywords.any { keyword -> text.contains(keyword) }
        }
    }

    private fun extractAmountPreview(text: String): String? {
        for (regex in AMOUNT_REGEXES) {
            val match = regex.find(text) ?: continue
            val amount = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: continue
            return amount.replace(",", "")
        }
        return null
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createServiceScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
