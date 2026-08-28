package com.condorino.weekend.scoring

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Helpers for turning arbitrary dates into the Friday that anchors "a weekend". */
object WeekendCalendar {

    /**
     * The Friday of the weekend [date] belongs to. Monday–Friday map to the coming Friday;
     * Saturday and Sunday map back to the Friday that just passed, so that "this weekend" still
     * means the current one when the user opens the app on a Saturday.
     */
    fun anchorFriday(date: LocalDate): LocalDate = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> date.minusDays(1)
        DayOfWeek.SUNDAY -> date.minusDays(2)
        DayOfWeek.FRIDAY -> date
        else -> date.with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
    }

    fun nextFriday(friday: LocalDate): LocalDate = friday.plusWeeks(1)

    fun previousFriday(friday: LocalDate): LocalDate = friday.minusWeeks(1)

    /** Every anchoring Friday in [from]..[to], inclusive. */
    fun fridaysBetween(from: LocalDate, to: LocalDate): List<LocalDate> {
        if (to.isBefore(from)) return emptyList()
        var cursor = if (from.dayOfWeek == DayOfWeek.FRIDAY) from
        else from.with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
        val out = mutableListOf<LocalDate>()
        while (!cursor.isAfter(to)) {
            out += cursor
            cursor = cursor.plusWeeks(1)
        }
        return out
    }

    /** Thursday..Monday window around a given anchoring Friday — the days we need flights for. */
    fun searchWindow(friday: LocalDate): ClosedRange<LocalDate> =
        friday.minusDays(1)..friday.plusDays(3)
}
