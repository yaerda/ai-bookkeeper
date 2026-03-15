package com.aibookkeeper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import com.aibookkeeper.feature.capture.notification.NotificationConstants
import com.aibookkeeper.feature.input.quick.QuickInputSheet
import com.aibookkeeper.feature.input.quick.QuickInputViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Transparent Activity that hosts [QuickInputSheet] as a bottom-sheet overlay.
 * Launched from the persistent notification (PaymentNotificationService) or
 * category quick-action buttons.
 *
 * The activity has a transparent theme so the user stays visually in their
 * current app/launcher context while the sheet slides up from the bottom.
 *
 * Implements US-10.2 (notification quick text input) and US-10.3 (category quick buttons).
 */
@AndroidEntryPoint
class QuickInputActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val inputMode = resolveInputMode(intent)
        val categoryName = intent.getStringExtra(NotificationConstants.EXTRA_CATEGORY_NAME)
        val categoryIcon = intent.getStringExtra(NotificationConstants.EXTRA_CATEGORY_ICON)

        // For voice/camera modes, delegate to MainActivity with appropriate route and finish
        when (inputMode) {
            NotificationConstants.MODE_VOICE -> {
                launchMainActivity("voice_input")
                return
            }
            NotificationConstants.MODE_CAMERA -> {
                launchMainActivity("capture/camera")
                return
            }
        }

        setContent {
            MaterialTheme {
                val viewModel: QuickInputViewModel = hiltViewModel()

                // Pre-select category if launched from a category button
                LaunchedEffect(categoryName) {
                    if (categoryName != null) {
                        viewModel.setPreselectedCategory(categoryName, categoryIcon)
                    }
                }

                QuickInputSheet(
                    viewModel = viewModel,
                    onDismiss = { finish() },
                    onOpenFullEditor = { transactionId ->
                        launchMainActivity("transaction/$transactionId")
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Recreate to handle new action
        recreate()
    }

    private fun resolveInputMode(intent: Intent): String {
        return when (intent.action) {
            NotificationConstants.ACTION_QUICK_TEXT -> NotificationConstants.MODE_TEXT
            NotificationConstants.ACTION_QUICK_VOICE -> NotificationConstants.MODE_VOICE
            NotificationConstants.ACTION_QUICK_CAMERA -> NotificationConstants.MODE_CAMERA
            NotificationConstants.ACTION_QUICK_CATEGORY -> NotificationConstants.MODE_CATEGORY
            else -> intent.getStringExtra(NotificationConstants.EXTRA_INPUT_MODE)
                ?: NotificationConstants.MODE_TEXT
        }
    }

    private fun launchMainActivity(route: String) {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("navigate_to", route)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(mainIntent)
        finish()
    }
}
