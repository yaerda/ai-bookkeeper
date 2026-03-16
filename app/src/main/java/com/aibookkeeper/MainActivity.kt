package com.aibookkeeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aibookkeeper.core.common.permission.NotificationPermissionHelper
import com.aibookkeeper.feature.capture.notification.PaymentNotificationService
import com.aibookkeeper.navigation.AppNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var isComposeReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        // Keep system splash on screen until Compose splash is rendered
        splashScreen.setKeepOnScreenCondition { !isComposeReady }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Signal that Compose is ready after first frame
            LaunchedEffect(Unit) { isComposeReady = true }
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncNotificationService()
    }

    /**
     * Ensure the persistent notification service matches the user's preference
     * and actual permission state. Covers the case where the user toggled
     * notification permission in system settings while the app was backgrounded.
     */
    private fun syncNotificationService() {
        val permissionGranted = NotificationPermissionHelper.isPermissionGranted(this)
        val userEnabled = NotificationPermissionHelper.isNotificationEnabled(this)
        val onboardingDone = NotificationPermissionHelper.isOnboardingCompleted(this)

        if (onboardingDone && permissionGranted && userEnabled) {
            PaymentNotificationService.start(this)
        } else {
            PaymentNotificationService.stop(this)
        }
    }
}
