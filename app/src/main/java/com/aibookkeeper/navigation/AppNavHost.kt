package com.aibookkeeper.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aibookkeeper.core.common.permission.NotificationPermissionHelper
import com.aibookkeeper.feature.capture.navigation.CaptureRoutes
import com.aibookkeeper.feature.input.navigation.InputRoutes
import com.aibookkeeper.feature.input.navigation.inputNavGraph
import com.aibookkeeper.feature.capture.navigation.captureNavGraph
import com.aibookkeeper.feature.capture.notification.PaymentNotificationService
import com.aibookkeeper.feature.stats.navigation.statsNavGraph
import com.aibookkeeper.feature.sync.ui.SyncScreen
import com.aibookkeeper.feature.sync.ui.FamilyScreen
import com.aibookkeeper.onboarding.OnboardingScreen
import com.aibookkeeper.splash.SplashScreen
import com.aibookkeeper.update.UpdateCheckEffect

private const val ROUTE_ONBOARDING = "onboarding"
private const val ROUTE_SPLASH = "splash"
private const val ROUTE_SYNC = "cloud-sync"
private const val ROUTE_FAMILY = "family-ledger"

sealed class BottomNavItem(
    val route: String,
    val icon: ImageVector,
    val label: String,
    val navigateRoute: String = route
) {
    data object Home : BottomNavItem(InputRoutes.HOME, Icons.Default.Home, "首页")
    data object Stats : BottomNavItem("stats", Icons.Default.BarChart, "统计")
    data object Add : BottomNavItem(
        route = InputRoutes.TEXT_INPUT,
        icon = Icons.Default.Add,
        label = "记账",
        navigateRoute = InputRoutes.textInput()
    )
    data object Bills : BottomNavItem(InputRoutes.BILLS, Icons.Default.Receipt, "账单")
    data object Settings : BottomNavItem("settings", Icons.Default.Settings, "设置")
}

@Composable
fun AppNavHost(
    sharedImageUri: String? = null,
    ledgerAccessViewModel: LedgerAccessViewModel = hiltViewModel()
) {
    val ledgerAccessState by ledgerAccessViewModel.accessState.collectAsStateWithLifecycle()
    if (ledgerAccessState is LedgerAccessState.Loading) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }
    if (ledgerAccessState is LedgerAccessState.AccountMismatch) {
        AccountMismatchScreen(
            email = (ledgerAccessState as LedgerAccessState.AccountMismatch).email,
            onSignOut = ledgerAccessViewModel::signOut
        )
        return
    }

    val navController = rememberNavController()
    val context = LocalContext.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val actualDestination = if (NotificationPermissionHelper.isOnboardingCompleted(context)) {
        InputRoutes.HOME
    } else {
        ROUTE_ONBOARDING
    }

    val startDestination = ROUTE_SPLASH

    val bottomNavItems = listOf(
        BottomNavItem.Home,
        BottomNavItem.Stats,
        BottomNavItem.Add,
        BottomNavItem.Bills,
        BottomNavItem.Settings
    )

    if (currentRoute != null && currentRoute != ROUTE_SPLASH) {
        UpdateCheckEffect()
    }

    Scaffold(
        bottomBar = {
            // Only show bottom bar on top-level tab screens
            val topLevelRoutes = setOf(
                InputRoutes.HOME,
                "stats",
                InputRoutes.TEXT_INPUT_BASE,
                InputRoutes.BILLS,
                "settings"
            )
            val showBottomBar = currentRoute != ROUTE_SPLASH && currentRoute != ROUTE_ONBOARDING &&
                topLevelRoutes.any { currentRoute?.startsWith(it) == true }

            if (showBottomBar) {
                NavigationBar {
                    val currentDestination = navBackStackEntry?.destination

                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                                    || (item is BottomNavItem.Add && currentRoute?.startsWith(InputRoutes.TEXT_INPUT_BASE) == true),
                            onClick = {
                                navController.navigate(item.navigateRoute) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = item !is BottomNavItem.Add
                                }
                            }
                        )
                    }
                }

            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            // Full-screen splash image — fade out when leaving
            composable(
                ROUTE_SPLASH,
                exitTransition = { fadeOut() }
            ) {
                SplashScreen(
                    onSplashFinished = {
                        navController.navigate(actualDestination) {
                            popUpTo(ROUTE_SPLASH) { inclusive = true }
                        }
                        // Open CaptureScreen with shared image if launched via share
                        if (sharedImageUri != null) {
                            navController.navigate(
                                CaptureRoutes.CAMERA + "?imageUri=${android.net.Uri.encode(sharedImageUri)}"
                            )
                        }
                    }
                )
            }

            // Onboarding (first launch only)
            composable(ROUTE_ONBOARDING) {
                OnboardingScreen(
                    onComplete = {
                        // Start service if permission was granted
                        if (NotificationPermissionHelper.isPermissionGranted(context) &&
                            NotificationPermissionHelper.isNotificationEnabled(context)
                        ) {
                            PaymentNotificationService.start(context)
                        }
                        navController.navigate(InputRoutes.HOME) {
                            popUpTo(ROUTE_ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }

            inputNavGraph(navController)
            captureNavGraph(navController)
            statsNavGraph(
                navController = navController,
                onNotificationServiceToggle = { enabled ->
                    if (enabled) {
                        PaymentNotificationService.start(context)
                    } else {
                        PaymentNotificationService.stop(context)
                    }
                },
                onCloudSyncClick = { navController.navigate(ROUTE_SYNC) },
                onFamilyLedgerClick = { navController.navigate(ROUTE_FAMILY) }
            )
            composable(ROUTE_SYNC) {
                SyncScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_FAMILY) {
                FamilyScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
