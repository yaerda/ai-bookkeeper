package com.aibookkeeper.feature.input.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.aibookkeeper.feature.input.bills.BillsScreen
import com.aibookkeeper.feature.input.detail.TransactionDetailScreen
import com.aibookkeeper.feature.input.home.HomeScreen
import com.aibookkeeper.feature.input.text.TextInputScreen

fun NavGraphBuilder.inputNavGraph(navController: NavController) {
    composable(InputRoutes.HOME) {
        HomeScreen(navController = navController)
    }
    composable(
        route = InputRoutes.TEXT_INPUT,
        arguments = listOf(
            navArgument("categoryId") {
                type = NavType.LongType
                defaultValue = -1L
            }
        )
    ) { backStackEntry ->
        val categoryId = backStackEntry.arguments?.getLong("categoryId") ?: -1L
        TextInputScreen(
            navController = navController,
            initialCategoryId = if (categoryId > 0) categoryId else null
        )
    }
    composable(InputRoutes.BILLS) {
        BillsScreen(navController = navController)
    }
    composable(
        route = InputRoutes.DETAIL,
        arguments = listOf(navArgument("transactionId") { type = NavType.LongType })
    ) {
        TransactionDetailScreen(navController = navController)
    }
}
