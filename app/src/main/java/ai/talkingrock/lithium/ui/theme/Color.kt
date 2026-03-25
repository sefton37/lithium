package ai.talkingrock.lithium.ui.theme

import androidx.compose.ui.graphics.Color

// Lithium color palette — warm dark theme with a single accent.
// Adapted from Helm's design language (Talking Rock ecosystem consistency).
// Design principle: warm charcoal backgrounds, high-contrast warm text,
// a single sandy-brown accent for interactive elements. No decorative color.
// Dynamic color is disabled (see Theme.kt).

// Dark theme surfaces — warm charcoal base
val DarkBackground    = Color(0xFF111110)
val DarkSurface       = Color(0xFF1C1C1A)
val DarkSurfaceVariant = Color(0xFF252523)

// On-surface text — warm off-white
val OnDark            = Color(0xFFE8E4DE)
val OnDarkMuted       = Color(0xFF8A8680)

// Single accent — warm sandy brown, readable on dark surfaces
val AccentPrimary     = Color(0xFFC4956A)
val AccentSecondary   = Color(0xFF9E7855)
val OnAccent          = Color(0xFF111110)

// Error / destructive
val ErrorRed          = Color(0xFFC45C5C)
val OnError           = Color(0xFF1F0007)

// Status colors
val StatusGreen       = Color(0xFF6A9A6E)

// Light theme (for completeness — Lithium defaults to dark)
val LightBackground   = Color(0xFFF4F4F4)
val LightSurface      = Color(0xFFFFFFFF)
val OnLight           = Color(0xFF111110)
