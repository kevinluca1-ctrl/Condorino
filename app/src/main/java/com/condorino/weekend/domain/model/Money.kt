package com.condorino.weekend.domain.model

import java.util.Locale

/** Minor-unit money value. Currency is EUR for v1 but kept explicit for later expansion. */
@JvmInline
value class Money(val cents: Long) : Comparable<Money> {

    val euros: Double get() = cents / 100.0

    operator fun plus(other: Money) = Money(cents + other.cents)

    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)

    /**
     * Formats in the reader's locale. German puts the symbol after the amount, English before it,
     * so the two are laid out differently rather than one being a transliteration of the other.
     */
    fun format(locale: Locale = Locale.getDefault()): String {
        val whole = cents % 100L == 0L
        return if (locale.language == "de") {
            if (whole) String.format(locale, "%d €", cents / 100)
            else String.format(locale, "%.2f €", euros)
        } else {
            if (whole) String.format(locale, "€%d", cents / 100)
            else String.format(locale, "€%.2f", euros)
        }
    }

    companion object {
        const val CURRENCY = "EUR"
        val ZERO = Money(0)
        fun ofEuros(value: Double) = Money(Math.round(value * 100))
    }
}
