package com.aibookkeeper.navigation

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aibookkeeper.core.common.permission.NotificationPermissionHelper
import com.aibookkeeper.feature.input.navigation.InputRoutes
import com.aibookkeeper.feature.input.navigation.inputNavGraph
import com.aibookkeeper.feature.capture.navigation.captureNavGraph
import com.aibookkeeper.feature.capture.notification.PaymentNotificationService
import com.aibookkeeper.feature.stats.navigation.statsNavGraph
import com.aibookkeeper.onboarding.OnboardingScreen

private const val ROUTE_ONBOARDING = "onboarding"

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
fun AppNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current

    val startDestination = if (NotificationPermissionHelper.isOnboardingCompleted(context)) {
        InputRoutes.HOME
    } else {
        ROUTE_ONBOARDING
    }

    val bottomNavItems = listOf(
        BottomNavItem.Home,
        BottomNavItem.Stats,
        BottomNavItem.Add,
        BottomNavItem.Bills,
        BottomNavItem.Settings
    )

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

            // Only show bottom bar on top-level tab screens
            val topLevelRoutes = setOf(
                InputRoutes.HOME,
                "stats",
                InputRoutes.BILLS,
                "settings"
            )
            val showBottomBar = topLevelRoutes.contains(currentRoute)

            if (showBottomBar) {
                NavigationBar {
                    val currentDestination = navBackStackEntry?.destination

                    bottomNavItems.forEach { item ->
                        if (item is BottomNavItem.Add) {
                            // Prominent center button
                            NavigationBarItem(
                                icon = {
                                    FloatingActionButton(
                                        onClick = {
                                            navController.navigate(item.navigateRoute) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .offset(y = (-4).dp),
                                        elevation = FloatingActionButtonDefaults.elevation(
                                            defaultElevation = 4.dp
                                        )
                                    ) {
                                        Icon(
                                            item.icon,
                                            contentDescription = item.label,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                },
                                label = { Text(item.label) },
                                selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                                onClick = {
                                    navController.navigate(item.navigateRoute) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        } else {
                            NavigationBarItem(
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                                selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                                onClick = {
                                    navController.navigate(item.navigateRoute) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
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
                }
            )
        }
    }
}
