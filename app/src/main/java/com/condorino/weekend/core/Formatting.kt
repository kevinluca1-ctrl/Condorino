package com.condorino.weekend.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Locale-aware date and time formatting.
 *
 * Two rules hold in every language:
 *
 *  * **Times are 24-hour.** The whole app is an argument about 18:15 versus 13:00; am/pm would
 *    make its central comparison harder to read at a glance.
 *  * **Dates are day-before-month.** `dd.MM.` in German, `dd MMM` in English. US month-first
 *    (MM/DD/YYYY) is deliberately not used anywhere, in any locale.
 *
 * Formatters are cached per locale, because the device language can change while the app is alive.
 */
object Formatting {

    private data class Patterns(
        val time: DateTimeFormatter,
        val dayDate: DateTimeFormatter,
        val longDate: DateTimeFormatter,
        val shortDate: DateTimeFormatter,
        val month: DateTimeFormatter,
    )

    private val cache = HashMap<String, Patterns>()

    @Synchronized
    private fun patterns(locale: Locale): Patterns = cache.getOrPut(locale.toLanguageTag()) {
        val german = locale.language == "de"
        Patterns(
            time = DateTimeFormatter.ofPattern("HH:mm", locale),
            // German: "Fr 04.09."  ·  English: "Fri 04 Sep"
            dayDate = DateTimeFormatter.ofPattern(if (german) "EEE dd.MM." else "EEE dd MMM", locale),
            longDate = DateTimeFormatter.ofPattern(
                if (german) "EEEE, dd.MM.yyyy" else "EEEE, dd MMM yyyy", locale,
            ),
            shortDate = DateTimeFormatter.ofPattern(if (german) "dd.MM." else "dd MMM", locale),
            month = DateTimeFormatter.ofPattern("MMMM yyyy", locale),
        )
    }

    private fun current(): Locale = Locale.getDefault()

    fun time(value: ZonedDateTime, locale: Locale = current()): String =
        value.format(patterns(locale).time)

    fun time(instant: Instant, zone: ZoneId, locale: Locale = current()): String =
        instant.atZone(zone).format(patterns(locale).time)

    fun time(value: java.time.LocalTime, locale: Locale = current()): String =
        value.format(patterns(locale).time)

    fun dayDate(date: LocalDate, locale: Locale = current()): String =
        date.format(patterns(locale).dayDate)

    fun longDate(date: LocalDate, locale: Locale = current()): String =
        date.format(patterns(locale).longDate)

    fun shortDate(date: LocalDate, locale: Locale = current()): String =
        date.format(patterns(locale).shortDate)

    fun month(date: LocalDate, locale: Locale = current()): String =
        date.format(patterns(locale).month)

    fun clock(instant: Instant, locale: Locale = current()): String =
        instant.atZone(ZoneId.systemDefault()).format(patterns(locale).time)

    /** "2 h 15 min" — the unit abbreviations read the same in both supported languages. */
    fun duration(duration: Duration): String {
        val minutes = duration.toMinutes().coerceAtLeast(0)
        val h = minutes / 60
        val m = minutes % 60
        return if (h == 0L) "$m min" else "$h h ${m.toString().padStart(2, '0')} min"
    }

    fun minutes(value: Long): String = duration(Duration.ofMinutes(value))

    /** Whole minutes between now and [instant]; the wording lives in the string resources. */
    fun minutesSince(instant: Instant): Long = Duration.between(instant, Instant.now()).toMinutes()

    /** "12.3 MB" — the decimal separator follows the locale, same as every other number in the app. */
    fun megabytes(bytes: Long, locale: Locale = current()): String =
        String.format(locale, "%.1f MB", bytes / 1_000_000.0)
}
