package ai.talkingrock.lithium

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ai.talkingrock.lithium.ui.debug.DebugNotificationLogScreen
import ai.talkingrock.lithium.ui.setup.SetupScreen
import ai.talkingrock.lithium.ui.theme.LithiumTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host for the Lithium Compose UI.
 *
 * Security hardening applied before any UI renders:
 * - FLAG_SECURE: prevents screenshots, screen recording, and appearing in the Recents
 *   thumbnail. Notification content must never be captured by third-party tools.
 * - Edge-to-edge layout: setDecorFitsSystemWindows(false) so Compose can draw
 *   behind system bars. The navigation shell handles inset padding.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply FLAG_SECURE before setContent so the window is protected
        // from the first frame render. Do NOT move this after setContent.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Edge-to-edge — Compose handles insets via WindowInsets APIs
        WindowCompat.setDecorFitsSystemWindows(window, false)

        super.onCreate(savedInstanceState)

        setContent {
            LithiumTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = Screen.Setup.route
                    ) {
                        composable(Screen.Setup.route) {
                            SetupScreen(
                                onSetupComplete = {
                                    navController.navigate(Screen.Briefing.route) {
                                        // Pop the setup screen off the back stack so back
                                        // does not return the user to setup after granting access.
                                        popUpTo(Screen.Setup.route) { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable(Screen.Briefing.route) {
                            Text("TODO: Briefing screen")
                        }
                        composable(Screen.Queue.route) {
                            Text("TODO: Queue screen")
                        }
                        composable(Screen.Rules.route) {
                            Text("TODO: Rules screen")
                        }
                        composable(Screen.Settings.route) {
                            Text("TODO: Settings screen")
                        }

                        // Debug log — only reachable in debug builds.
                        if (BuildConfig.DEBUG) {
                            composable(Screen.DebugLog.route) {
                                DebugNotificationLogScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Navigation route definitions.
 *
 * All screens are declared here so subsequent milestones can wire real composables
 * without touching the navigation structure.
 */
sealed class Screen(val route: String) {
    object Setup    : Screen("setup")
    object Briefing : Screen("briefing")
    object Queue    : Screen("queue")
    object Rules    : Screen("rules")
    object Settings : Screen("settings")
    object DebugLog : Screen("debug_log")
}
