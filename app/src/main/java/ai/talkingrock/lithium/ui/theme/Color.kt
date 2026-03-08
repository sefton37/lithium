package ai.talkingrock.lithium.ui.theme

import androidx.compose.ui.graphics.Color

// Lithium color palette — monochrome with a single accent.
// Design principle: neutral dark backgrounds, high-contrast text,
// a single accent for interactive elements. No decorative color.
// Dynamic color is disabled (see Theme.kt).

// Dark theme surfaces
val DarkBackground    = Color(0xFF0D0D0D)
val DarkSurface       = Color(0xFF1A1A1A)
val DarkSurfaceVariant = Color(0xFF242424)

// On-surface text
val OnDark            = Color(0xFFE8E8E8)
val OnDarkMuted       = Color(0xFF9E9E9E)

// Single accent — cool blue-grey, readable on dark surfaces
val AccentPrimary     = Color(0xFF7EAFC4)
val AccentSecondary   = Color(0xFF4E7A8A)
val OnAccent          = Color(0xFF0D1E24)

// Error / destructive
val ErrorRed          = Color(0xFFCF6679)
val OnError           = Color(0xFF1F0007)

// Light theme (for completeness — Lithium defaults to dark)
val LightBackground   = Color(0xFFF4F4F4)
val LightSurface      = Color(0xFFFFFFFF)
val OnLight           = Color(0xFF111111)
