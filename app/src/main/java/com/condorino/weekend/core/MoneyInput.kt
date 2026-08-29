package com.condorino.weekend.core

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Text handling for the manually entered standby prices (spec §6).
 *
 * These live outside the Compose layer on purpose: a price field that drops a keystroke or turns a
 * blank into 0 € is exactly the kind of bug that is invisible in a screenshot and expensive in the
 * app, so it belongs somewhere a unit test can reach it.
 */
object MoneyInput {

    /** Longest whole-euro part accepted; nine digits is far past any standby fare. */
    private const val MAX_WHOLE_DIGITS = 9

    /**
     * Keeps a partially typed decimal usable: "12," and "12." must survive unchanged so the user
     * can carry on typing the fractional part. Both separators are accepted because the keyboard
     * offers whichever one the device locale prefers.
     */
    fun sanitizeDecimal(raw: String): String {
        val filtered = raw.filter { it.isDigit() || it == '.' || it == ',' }
        val firstSeparator = filtered.indexOfFirst { it == '.' || it == ',' }
        if (firstSeparator < 0) return filtered.take(MAX_WHOLE_DIGITS)

        val whole = filtered.substring(0, firstSeparator).take(MAX_WHOLE_DIGITS)
        val fraction = filtered.substring(firstSeparator + 1).filter { it.isDigit() }.take(2)
        return whole + filtered[firstSeparator] + fraction
    }

    /** Digits only, for the plain numeric settings fields (minutes, budgets, hours). */
    fun sanitizeInteger(raw: String): String = raw.filter { it.isDigit() }.take(MAX_WHOLE_DIGITS)

    /**
     * Shows a stored amount the way it will be typed back in: whole euros stay whole, so 45 €
     * reads "45" rather than "45.00" and stays easy to edit.
     */
    fun formatCentsForEditing(cents: Long?): String = when {
        cents == null -> ""
        cents % 100L == 0L -> (cents / 100).toString()
        else -> String.format(Locale.US, "%.2f", cents / 100.0)
    }

    /**
     * Parses "45", "45.5", "45,50" into cents.
     *
     * Blank input, a lone separator and anything unparseable all mean *not set*, which is why this
     * returns null rather than zero: a missing standby price must never look like a free flight.
     */
    fun parseEuroToCents(raw: String): Long? {
        val normalised = raw.trim().replace(',', '.')
        if (normalised.isEmpty() || normalised == ".") return null
        val value = normalised.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value < 0.0) return null
        return (value * 100.0).roundToLong()
    }
}
