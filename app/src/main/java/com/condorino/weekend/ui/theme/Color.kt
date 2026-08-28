package com.condorino.weekend.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app's semantic palette.
 *
 * Screens never name a raw colour; they name a role (`Background`, `TextSecondary`,
 * `ScoreExcellent`). Light and dark are two instances of this same set of roles, which is what
 * makes the light theme a drop-in rather than a rewrite of every screen.
 */
@Immutable
data class CondorinoPalette(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceHigh: Color,
    val outline: Color,

    val amber: Color,
    val amberDim: Color,
    val sky: Color,
    val mint: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,

    val scoreExcellent: Color,
    val scoreGood: Color,
    val scoreFair: Color,
    val scorePoor: Color,
    val scoreBad: Color,

    val danger: Color,
    val warning: Color,
    val demoBanner: Color,
    val demoBannerText: Color,

    val isLight: Boolean,
)

/** The original very dark travel palette. */
val DarkPalette = CondorinoPalette(
    background = Color(0xFF0B0D12),
    surface = Color(0xFF13161F),
    surfaceElevated = Color(0xFF1B1F2B),
    surfaceHigh = Color(0xFF242938),
    outline = Color(0xFF2E3446),

    amber = Color(0xFFFFC94A),
    amberDim = Color(0xFF8A6A1F),
    sky = Color(0xFF5EC8F5),
    mint = Color(0xFF4ADE9B),

    textPrimary = Color(0xFFF3F5FA),
    textSecondary = Color(0xFFA5AEC2),
    textTertiary = Color(0xFF6C7690),

    scoreExcellent = Color(0xFF4ADE9B),
    scoreGood = Color(0xFFA3E635),
    scoreFair = Color(0xFFFFC94A),
    scorePoor = Color(0xFFFB923C),
    scoreBad = Color(0xFFF87171),

    danger = Color(0xFFF87171),
    warning = Color(0xFFFFB020),
    demoBanner = Color(0xFF4A1D1D),
    demoBannerText = Color(0xFFFFC2C2),

    isLight = false,
)

/**
 * Light counterpart. Not a naive inversion: the accents are darkened until they hold their
 * contrast against a near-white background, because the amber and mint that read well on near-black
 * are illegible on white. The score colours in particular have to stay distinguishable from each
 * other *and* readable, so they move to their 600–700 range.
 */
val LightPalette = CondorinoPalette(
    background = Color(0xFFF7F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF0F2F8),
    surfaceHigh = Color(0xFFE4E8F2),
    outline = Color(0xFFD3D9E6),

    amber = Color(0xFFB57A00),
    amberDim = Color(0xFFFFE7AE),
    sky = Color(0xFF0A6E9E),
    mint = Color(0xFF0F7B4F),

    textPrimary = Color(0xFF11141C),
    textSecondary = Color(0xFF4A5266),
    textTertiary = Color(0xFF737C92),

    scoreExcellent = Color(0xFF0F7B4F),
    scoreGood = Color(0xFF4D7C0F),
    scoreFair = Color(0xFFB57A00),
    scorePoor = Color(0xFFC2410C),
    scoreBad = Color(0xFFB91C1C),

    danger = Color(0xFFB91C1C),
    warning = Color(0xFF9A5B00),
    demoBanner = Color(0xFFFFE1E1),
    demoBannerText = Color(0xFF8A1B1B),

    isLight = true,
)

val LocalCondorinoPalette = staticCompositionLocalOf { DarkPalette }

/**
 * Call sites keep reading `CondorinoColors.Amber`; the value now comes from whichever palette the
 * surrounding [CondorinoTheme] provides.
 */
object CondorinoColors {

    val Background: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.surface
    val SurfaceElevated: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.surfaceElevated
    val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.surfaceHigh
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.outline

    val Amber: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.amber
    val AmberDim: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.amberDim
    val Sky: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.sky
    val Mint: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.mint

    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.textSecondary
    val TextTertiary: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.textTertiary

    val Danger: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.danger
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.warning
    val DemoBanner: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.demoBanner
    val DemoBannerText: Color @Composable @ReadOnlyComposable get() = LocalCondorinoPalette.current.demoBannerText

    /** Colour language for the 0–100 trip score, identical in meaning across both themes. */
    @Composable
    @ReadOnlyComposable
    fun forScore(score: Double): Color {
        val p = LocalCondorinoPalette.current
        return when {
            score >= 90 -> p.scoreExcellent
            score >= 78 -> p.scoreGood
            score >= 62 -> p.scoreFair
            score >= 45 -> p.scorePoor
            else -> p.scoreBad
        }
    }
}
