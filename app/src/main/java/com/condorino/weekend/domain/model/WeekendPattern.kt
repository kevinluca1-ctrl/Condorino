package com.condorino.weekend.domain.model

import java.time.DayOfWeek

/**
 * The four trip shapes the app searches for, in the user's priority order.
 *
 * [vacationDaysRequired] is what actually drives the priority: leaving on Thursday or Friday
 * *evening* costs no time off, while returning on Monday evening means Monday is not a working
 * day. That is why the Monday patterns rank below the Sunday ones.
 */
enum class WeekendPattern(
    val priority: Int,
    val outboundDay: DayOfWeek,
    val inboundDay: DayOfWeek,
    val vacationDaysRequired: Int,
) {
    FRI_SUN(1, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY, 0),
    THU_SUN(2, DayOfWeek.THURSDAY, DayOfWeek.SUNDAY, 0),
    FRI_MON(3, DayOfWeek.FRIDAY, DayOfWeek.MONDAY, 1),
    THU_MON(4, DayOfWeek.THURSDAY, DayOfWeek.MONDAY, 1),
    ;

    /** Nights away implied by the pattern (before checking the actual flights). */
    val nominalNights: Int
        get() = when (this) {
            FRI_SUN -> 2
            THU_SUN -> 3
            FRI_MON -> 3
            THU_MON -> 4
        }

    /**
     * The day the traveller is back at work. Used to decide whether a very late arrival back at
     * FRA should be penalised.
     */
    val nextWorkingDay: DayOfWeek
        get() = if (inboundDay == DayOfWeek.SUNDAY) DayOfWeek.MONDAY else DayOfWeek.TUESDAY

    companion object {
        val byPriority: List<WeekendPattern> = entries.sortedBy { it.priority }

        fun detect(outboundDay: DayOfWeek, inboundDay: DayOfWeek): WeekendPattern? =
            entries.firstOrNull { it.outboundDay == outboundDay && it.inboundDay == inboundDay }
    }
}
