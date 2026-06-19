package com.cellocoach.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Material3 theme wrapper for the app — the Compose equivalent of the
 * `Theme.CelloCoach` style declared in `themes.xml` (which only the Activity
 * window background uses; the real UI colours come from here).
 *
 * A small warm palette evoking varnished wood / sheet music, with the realtime
 * feedback colours ([FeedbackColors]) kept separate so [ScoreView] and the
 * practice panel can paint pitch status (good/close/off/wrong) consistently in
 * both light and dark mode.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF7A4B2B),       // cello varnish brown
    onPrimary = Color.White,
    secondary = Color(0xFF4A6FA5),
    background = Color(0xFFFAF7F2),     // off-white paper
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD8A066),
    onPrimary = Color(0xFF3A2412),
    secondary = Color(0xFF9DB8E0),
    background = Color(0xFF1A1714),
    surface = Color(0xFF221E1A),
)

/**
 * Realtime pitch-feedback colours, shared by [ScoreView] (current-note head
 * tint) and the practice feedback panel so the same status always reads the same
 * colour. Mirrors the good/close/off/wrong status from `main.py`.
 */
object FeedbackColors {
    val good = Color(0xFF2E7D32)   // green  — within 20¢
    val close = Color(0xFFF9A825)  // orange — within 50¢
    val off = Color(0xFFC62828)    // red    — ≥50¢ but correct note
    val wrong = Color(0xFFC62828)  // red    — wrong note entirely
    val idle = Color(0xFF9E9E9E)   // grey   — no detection
}

@Composable
fun CelloCoachTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
