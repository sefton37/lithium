package ai.talkingrock.lithium

import android.content.SharedPreferences
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ai.talkingrock.lithium.data.Prefs
import ai.talkingrock.lithium.ui.briefing.BriefingScreen
import ai.talkingrock.lithium.ui.debug.DebugNotificationLogScreen
import ai.talkingrock.lithium.ui.queue.QueueScreen
import ai.talkingrock.lithium.ui.rules.AddRuleScreen
import ai.talkingrock.lithium.ui.rules.RulesScreen
import ai.talkingrock.lithium.ui.settings.SettingsScreen
import ai.talkingrock.lithium.ui.setup.SetupScreen
import ai.talkingrock.lithium.ui.theme.LithiumTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity host for the Lithium Compose UI.
 *
 * Security hardening applied before any UI renders:
 * - FLAG_SECURE: prevents screenshots, screen recording, and appearing in the Recents
 *   thumbnail. Notification content must never be captured by third-party tools.
 * - Edge-to-edge layout: setDecorFitsSystemWindows(false) so Compose can draw
 *   behind system bars. The navigation shell handles inset padding.
 *
 * Navigation structure:
 * - Setup is shown first when permissions are missing; it pops itself on completion.
 * - Briefing is the default home route after setup.
 * - The bottom navigation bar is shown for all main tabs (Briefing, Queue, Rules, Settings).
 *   It is hidden on the Setup screen and the debug log.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply FLAG_SECURE in release builds to prevent screenshots/recording.
        // Disabled in debug builds so Maestro and other testing tools can access the UI.
        if (!BuildConfig.DEBUG) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        // Edge-to-edge — Compose handles insets via WindowInsets APIs
        WindowCompat.setDecorFitsSystemWindows(window, false)

        super.onCreate(savedInstanceState)

        // Determine start destination:
        // - New user (never completed onboarding) → Setup
        // - Returning user with notification access → Briefing
        // - Returning user who lost notification access → Setup (to re-grant)
        //
        // IMPORTANT: Use NotificationManagerCompat (synchronous system call) instead of
        // ListenerState.isConnected, which initializes to false at cold start before the
        // service reconnects. This prevents routing every cold start to Setup.
        val onboardingDone = sharedPreferences.getBoolean(Prefs.ONBOARDING_COMPLETE, false)
        val hasNotificationAccess = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)
        val startRoute = if (onboardingDone && hasNotificationAccess) {
            Screen.Briefing.route
        } else {
            Screen.Setup.route
        }

        setContent {
            LithiumTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LithiumNavHost(startDestination = startRoute)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// Navigation host
// -----------------------------------------------------------------------------------------

@Composable
private fun LithiumNavHost(startDestination: String = Screen.Setup.route) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    // Show the bottom navigation bar only for the four main tabs.
    val showBottomBar = currentRoute in MainTab.routes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                LithiumBottomBar(navController = navController, currentRoute = currentRoute)
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
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
                BriefingScreen()
            }

            composable(Screen.Queue.route) {
                QueueScreen()
            }

            composable(Screen.Rules.route) {
                RulesScreen(
                    onAddRule = {
                        navController.navigate(Screen.AddRule.route)
                    }
                )
            }

            composable(Screen.AddRule.route) {
                AddRuleScreen(
                    onSaved = { navController.popBackStack() }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen()
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

// -----------------------------------------------------------------------------------------
// Bottom navigation bar
// -----------------------------------------------------------------------------------------

@Composable
private fun LithiumBottomBar(
    navController: NavController,
    currentRoute: String?
) {
    NavigationBar {
        MainTab.all.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = {
                    if (currentRoute != tab.route) {
                        navController.navigate(tab.route) {
                            // Avoid building up a large back stack; restore to the top of
                            // the graph when switching tabs.
                            popUpTo(Screen.Briefing.route) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label
                    )
                },
                label = { Text(tab.label) }
            )
        }
    }
}

// -----------------------------------------------------------------------------------------
// Tab descriptors
// -----------------------------------------------------------------------------------------

/**
 * Descriptor for a main-tab destination.
 */
private data class MainTab(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    companion object {
        val all = listOf(
            MainTab(Screen.Briefing.route, "Briefing",  Icons.Filled.Home),
            MainTab(Screen.Queue.route,    "Queue",     Icons.AutoMirrored.Filled.List),
            MainTab(Screen.Rules.route,    "Rules",     Icons.Filled.Star),
            MainTab(Screen.Settings.route, "Settings",  Icons.Filled.Settings)
        )

        /** Set of routes where the bottom bar should be shown. */
        val routes: Set<String> = all.map { it.route }.toSet()
    }
}

// -----------------------------------------------------------------------------------------
// Route definitions
// -----------------------------------------------------------------------------------------

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
    object AddRule  : Screen("add_rule")
    object Settings : Screen("settings")
    object DebugLog : Screen("debug_log")
}
