package com.aibookkeeper.feature.stats.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.aibookkeeper.feature.stats.category.CategoryDetailScreen
import com.aibookkeeper.feature.stats.overview.StatsScreen
import com.aibookkeeper.feature.stats.settings.SettingsScreen
import com.aibookkeeper.feature.stats.settings.changelog.ChangelogScreen
import com.aibookkeeper.feature.stats.settings.paymentpattern.PaymentPatternListScreen
import com.aibookkeeper.feature.stats.settings.speechdiagnostic.LocalSpeechDiagnosticScreen
import com.aibookkeeper.feature.stats.trends.TrendsScreen

fun NavGraphBuilder.statsNavGraph(
    navController: NavController,
    onNotificationServiceToggle: (Boolean) -> Unit = {},
    onCloudSyncClick: () -> Unit = {},
    onFamilyLedgerClick: () -> Unit = {},
    onProjectsClick: () -> Unit = {}
) {
    composable(StatsRoutes.OVERVIEW) {
        StatsScreen(navController = navController)
    }
    composable(StatsRoutes.SETTINGS) {
        SettingsScreen(
            navController = navController,
            onNotificationServiceToggle = onNotificationServiceToggle,
            onCloudSyncClick = onCloudSyncClick,
            onFamilyLedgerClick = onFamilyLedgerClick,
            onProjectsClick = onProjectsClick
        )
    }
    composable(StatsRoutes.LOCAL_SPEECH_DIAGNOSTIC) {
        LocalSpeechDiagnosticScreen(navController = navController)
    }
    composable(StatsRoutes.PAYMENT_PATTERNS) {
        PaymentPatternListScreen(navController = navController)
    }
    composable(StatsRoutes.CHANGELOG) {
        ChangelogScreen(navController = navController)
    }
    composable(StatsRoutes.TRENDS) {
        TrendsScreen(navController = navController)
    }
    composable(
        route = StatsRoutes.CATEGORY_DETAIL,
        arguments = listOf(
            navArgument("categoryId") { type = NavType.LongType },
            navArgument("yearMonth") { type = NavType.StringType },
            navArgument("projectId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) {
        CategoryDetailScreen(navController = navController)
    }
}
