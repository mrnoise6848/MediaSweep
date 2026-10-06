package com.noise.mediasweep.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.noise.mediasweep.core.media.openAppSettings
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.feature.candidates.CandidatesRoute
import com.noise.mediasweep.feature.candidates.CategoryRoute
import com.noise.mediasweep.feature.groupdetail.GroupDetailRoute
import com.noise.mediasweep.feature.home.HomeRoute
import com.noise.mediasweep.feature.review.ReviewSummaryRoute
import com.noise.mediasweep.feature.scan.ScanRoute

/**
 * Root navigation graph.
 *
 * Routes are added by the phase that introduces the screen behind them, so a destination
 * always points at a real screen (no placeholder destinations).
 */
@Composable
fun MediaSweepNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        composable(Routes.HOME) {
            HomeRoute(
                onScanClick = { navController.navigate(Routes.SCAN) },
                onReviewClick = { navController.navigate(Routes.CANDIDATES) },
                onOpenSettings = { openAppSettings(context) },
            )
        }

        composable(Routes.SCAN) {
            // The run lives in the application-scoped scan controller: this destination
            // only observes progress and starts/cancels the shared run (§18, §27).
            ScanRoute(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.CANDIDATES) {
            CandidatesRoute(
                onBack = { navController.popBackStack() },
                onOpenCategory = { type -> navController.navigate(Routes.category(type)) },
            )
        }

        composable(
            route = Routes.CATEGORY,
            arguments = listOf(navArgument(Routes.CATEGORY_ARG) { type = NavType.StringType }),
        ) { entry ->
            val typeName = entry.arguments?.getString(Routes.CATEGORY_ARG)
            val type = CandidateType.entries.firstOrNull { it.name == typeName }
            if (type == null) {
                LaunchedEffect(typeName) { navController.popBackStack() }
            } else {
                CategoryRoute(
                    type = type,
                    onBack = { navController.popBackStack() },
                    onOpenGroup = { groupId -> navController.navigate(Routes.group(groupId)) },
                    onReview = { navController.navigate(Routes.REVIEW) },
                )
            }
        }

        composable(
            route = Routes.GROUP,
            arguments = listOf(navArgument(Routes.GROUP_ARG) { type = NavType.LongType }),
        ) { entry ->
            val groupId = entry.arguments?.getLong(Routes.GROUP_ARG) ?: -1L
            if (groupId < 0) {
                LaunchedEffect(groupId) { navController.popBackStack() }
            } else {
                GroupDetailRoute(
                    groupId = groupId,
                    onBack = { navController.popBackStack() },
                    onReview = { navController.navigate(Routes.REVIEW) },
                )
            }
        }

        composable(Routes.REVIEW) {
            // The trash flow (Phase 8) owns the system confirmation and reconciles the
            // index only after MediaStore reports the real outcome.
            ReviewSummaryRoute(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
