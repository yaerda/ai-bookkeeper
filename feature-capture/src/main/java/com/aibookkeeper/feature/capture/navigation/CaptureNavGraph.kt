package com.aibookkeeper.feature.capture.navigation

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.aibookkeeper.feature.capture.ocr.CaptureScreen

fun NavGraphBuilder.captureNavGraph(navController: NavController) {
    composable(
        route = CaptureRoutes.CAMERA + "?imageUri={imageUri}",
        arguments = listOf(
            navArgument("imageUri") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        val imageUriStr = backStackEntry.arguments?.getString("imageUri")
        val initialImageUri = imageUriStr?.let { Uri.decode(it) }
        CaptureScreen(
            navController = navController,
            initialImageUri = initialImageUri
        )
    }
}
