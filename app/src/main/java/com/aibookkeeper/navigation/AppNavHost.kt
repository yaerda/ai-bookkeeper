package com.aibookkeeper.navigation

import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aibookkeeper.feature.input.navigation.InputRoutes
import com.aibookkeeper.feature.input.navigation.inputNavGraph
import com.aibookkeeper.feature.capture.navigation.captureNavGraph
import com.aibookkeeper.feature.stats.navigation.statsNavGraph

sealed class BottomNavItem(
    val route: String,
    val icon: ImageVector,
    val label: String
) {
    data object Home : BottomNavItem(InputRoutes.HOME, Icons.Default.Home, "首页")
    data object Stats : BottomNavItem("stats", Icons.Default.BarChart, "统计")
    data object Add : BottomNavItem(InputRoutes.TEXT_INPUT, Icons.Default.Add, "记账")
    data object Bills : BottomNavItem("bills", Icons.Default.Receipt, "账单")
    data object Settings : BottomNavItem("settings", Icons.Default.Settings, "设置")
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val bottomNavItems = listOf(
        BottomNavItem.Home,
        BottomNavItem.Stats,
        BottomNavItem.Add,
        BottomNavItem.Bills,
        BottomNavItem.Settings
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomNavItems.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                        onClick = {
                            navController.navigate(item.route) {
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
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = InputRoutes.HOME,
            modifier = Modifier.padding(innerPadding)
        ) {
            inputNavGraph(navController)
            captureNavGraph(navController)
            statsNavGraph(navController)
        }
    }
}
