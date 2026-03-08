package ai.talkingrock.lithium.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Lithium Material3 theme.
 *
 * Dark mode is the default and primary design target. The app handles sensitive
 * notification content — dark mode reduces inadvertent screen visibility in
 * public spaces.
 *
 * Dynamic color (Material You) is disabled: the monochrome accent palette is
 * deliberate. Colorful, distracting UIs are at odds with the target demographic's
 * needs. User-configurable accent in a future settings screen.
 */

private val LithiumDarkColorScheme = darkColorScheme(
    primary           = AccentPrimary,
    onPrimary         = OnAccent,
    secondary         = AccentSecondary,
    onSecondary       = OnAccent,
    background        = DarkBackground,
    onBackground      = OnDark,
    surface           = DarkSurface,
    onSurface         = OnDark,
    surfaceVariant    = DarkSurfaceVariant,
    onSurfaceVariant  = OnDarkMuted,
    error             = ErrorRed,
    onError           = OnError
)

private val LithiumLightColorScheme = lightColorScheme(
    primary           = AccentSecondary,
    onPrimary         = OnAccent,
    secondary         = AccentPrimary,
    onSecondary       = OnAccent,
    background        = LightBackground,
    onBackground      = OnLight,
    surface           = LightSurface,
    onSurface         = OnLight,
    error             = ErrorRed,
    onError           = OnError
)

@Composable
fun LithiumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) LithiumDarkColorScheme else LithiumLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LithiumTypography,
        content = content
    )
}
