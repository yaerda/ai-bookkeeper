package com.aibookkeeper.feature.stats.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.aibookkeeper.feature.stats.overview.StatsScreen
import com.aibookkeeper.feature.stats.settings.SettingsScreen
import com.aibookkeeper.feature.stats.settings.speechdiagnostic.LocalSpeechDiagnosticScreen

fun NavGraphBuilder.statsNavGraph(
    navController: NavController,
    onNotificationServiceToggle: (Boolean) -> Unit = {}
) {
    composable(StatsRoutes.OVERVIEW) {
        StatsScreen(navController = navController)
    }
    composable(StatsRoutes.SETTINGS) {
        SettingsScreen(
            navController = navController,
            onNotificationServiceToggle = onNotificationServiceToggle
        )
    }
    composable(StatsRoutes.LOCAL_SPEECH_DIAGNOSTIC) {
        LocalSpeechDiagnosticScreen(navController = navController)
    }
}
