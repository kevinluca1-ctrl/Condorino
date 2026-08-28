package com.condorino.weekend.domain.model

import java.util.Locale

/** Minor-unit money value. Currency is EUR for v1 but kept explicit for later expansion. */
@JvmInline
value class Money(val cents: Long) : Comparable<Money> {

    val euros: Double get() = cents / 100.0

    operator fun plus(other: Money) = Money(cents + other.cents)

    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)

    fun format(): String =
        if (cents % 100L == 0L) String.format(Locale.GERMANY, "%d €", cents / 100)
        else String.format(Locale.GERMANY, "%.2f €", euros)

    companion object {
        const val CURRENCY = "EUR"
        val ZERO = Money(0)
        fun ofEuros(value: Double) = Money(Math.round(value * 100))
    }
}
