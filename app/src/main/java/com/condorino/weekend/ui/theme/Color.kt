package com.condorino.weekend.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A very dark, high-contrast travel palette (spec §24). Deep near-black backgrounds, a warm
 * "boarding pass" amber as the accent, and a small set of semantic colours for the score and
 * provenance badges.
 */
object CondorinoColors {
    val Background = Color(0xFF0B0D12)
    val Surface = Color(0xFF13161F)
    val SurfaceElevated = Color(0xFF1B1F2B)
    val SurfaceHigh = Color(0xFF242938)
    val Outline = Color(0xFF2E3446)

    val Amber = Color(0xFFFFC94A)
    val AmberDim = Color(0xFF8A6A1F)
    val Sky = Color(0xFF5EC8F5)
    val Mint = Color(0xFF4ADE9B)

    val TextPrimary = Color(0xFFF3F5FA)
    val TextSecondary = Color(0xFFA5AEC2)
    val TextTertiary = Color(0xFF6C7690)

    val ScoreExcellent = Color(0xFF4ADE9B)
    val ScoreGood = Color(0xFFA3E635)
    val ScoreFair = Color(0xFFFFC94A)
    val ScorePoor = Color(0xFFFB923C)
    val ScoreBad = Color(0xFFF87171)

    val Danger = Color(0xFFF87171)
    val Warning = Color(0xFFFFB020)
    val DemoBanner = Color(0xFF4A1D1D)
    val DemoBannerText = Color(0xFFFFC2C2)

    fun forScore(score: Double): Color = when {
        score >= 90 -> ScoreExcellent
        score >= 78 -> ScoreGood
        score >= 62 -> ScoreFair
        score >= 45 -> ScorePoor
        else -> ScoreBad
    }
}
