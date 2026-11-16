package com.example.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.chat.ChatScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.setup.SetupScreen
import kotlinx.serialization.Serializable

/** Type-safe route for the initial API key setup screen. */
@Serializable
data object SetupRoute

/** Type-safe route for the main AI chat screen. */
@Serializable
data object ChatRoute

/** Type-safe route for the settings and account inspection screen. */
@Serializable
data object SettingsRoute

/**
 * Navigation graph for Coarena Chat supporting predictive/system back gestures.
 */
@Composable
fun CoarenaNavGraph(
    startDestination: Any,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable<SetupRoute> {
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(ChatRoute) {
                        popUpTo(SetupRoute) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable<ChatRoute> {
            ChatScreen(
                onOpenSettings = {
                    navController.navigate(SettingsRoute) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToSetup = {
                    navController.navigate(SetupRoute) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}
