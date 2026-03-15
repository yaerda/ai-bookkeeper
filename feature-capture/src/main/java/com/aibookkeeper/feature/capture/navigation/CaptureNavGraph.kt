package com.aibookkeeper.feature.capture.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.aibookkeeper.feature.capture.ocr.CaptureScreen

fun NavGraphBuilder.captureNavGraph(navController: NavController) {
    composable(CaptureRoutes.CAMERA) {
        CaptureScreen(navController = navController)
    }
}
