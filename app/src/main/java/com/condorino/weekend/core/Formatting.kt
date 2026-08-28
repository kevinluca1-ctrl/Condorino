package com.condorino.weekend.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** German-locale formatting helpers used across the UI. */
object Formatting {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)
    private val dayDateFormatter = DateTimeFormatter.ofPattern("EEE dd.MM.", Locale.GERMANY)
    private val longDateFormatter = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", Locale.GERMANY)
    private val shortDateFormatter = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY)
    private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMANY)

    fun time(value: ZonedDateTime): String = value.format(timeFormatter)

    fun time(instant: Instant, zone: ZoneId): String = instant.atZone(zone).format(timeFormatter)

    fun dayDate(date: LocalDate): String = date.format(dayDateFormatter)

    fun longDate(date: LocalDate): String = date.format(longDateFormatter)

    fun shortDate(date: LocalDate): String = date.format(shortDateFormatter)

    fun month(date: LocalDate): String = date.format(monthFormatter)

    fun clock(instant: Instant): String =
        instant.atZone(ZoneId.systemDefault()).format(timeFormatter)

    fun duration(duration: Duration): String {
        val minutes = duration.toMinutes().coerceAtLeast(0)
        val h = minutes / 60
        val m = minutes % 60
        return if (h == 0L) "$m min" else "$h h ${m.toString().padStart(2, '0')} min"
    }

    /** "2 Nächte", "1 Nacht". */
    fun nights(count: Int): String = if (count == 1) "1 Nacht" else "$count Nächte"

    fun relativeAge(instant: Instant?): String {
        if (instant == null) return "noch nie"
        val minutes = Duration.between(instant, Instant.now()).toMinutes()
        return when {
            minutes < 1 -> "gerade eben"
            minutes < 60 -> "vor $minutes min"
            minutes < 60 * 24 -> "vor ${minutes / 60} h"
            else -> "vor ${minutes / (60 * 24)} Tagen"
        }
    }
}
