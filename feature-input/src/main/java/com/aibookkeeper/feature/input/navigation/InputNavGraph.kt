package com.aibookkeeper.feature.input.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.aibookkeeper.feature.input.bills.BillsScreen
import com.aibookkeeper.feature.input.home.HomeScreen
import com.aibookkeeper.feature.input.text.TextInputScreen

fun NavGraphBuilder.inputNavGraph(navController: NavController) {
    composable(InputRoutes.HOME) {
        HomeScreen(navController = navController)
    }
    composable(InputRoutes.TEXT_INPUT) {
        TextInputScreen(navController = navController)
    }
    composable(InputRoutes.BILLS) {
        BillsScreen(navController = navController)
    }
    composable(
        route = InputRoutes.DETAIL,
        arguments = listOf(navArgument("transactionId") { type = NavType.LongType })
    ) {
        // TODO: TransactionDetailScreen
    }
}
