package com.condorino.weekend.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.condorino.weekend.R
import com.condorino.weekend.core.Formatting
import com.condorino.weekend.domain.model.Cabin
import com.condorino.weekend.domain.model.ComponentDetail
import com.condorino.weekend.domain.model.DestinationType
import com.condorino.weekend.domain.model.PriceEntryMode
import com.condorino.weekend.domain.model.ScoreComponent
import com.condorino.weekend.domain.model.TripInsight
import com.condorino.weekend.domain.model.WeekendPattern
import com.condorino.weekend.scoring.RandomMode
import com.condorino.weekend.scoring.RejectionReason
import java.time.DayOfWeek
import kotlin.math.roundToInt

/**
 * Renders domain values into the reader's language.
 *
 * The domain and scoring layers deliberately produce no prose — they emit [TripInsight],
 * [ComponentDetail] and enums. Everything a user reads is assembled here, which is what makes the
 * app translatable without touching a single line of the scoring rules.
 */

@Composable
@ReadOnlyComposable
fun DayOfWeek.label(): String = stringResource(
    when (this) {
        DayOfWeek.MONDAY -> R.string.day_monday
        DayOfWeek.TUESDAY -> R.string.day_tuesday
        DayOfWeek.WEDNESDAY -> R.string.day_wednesday
        DayOfWeek.THURSDAY -> R.string.day_thursday
        DayOfWeek.FRIDAY -> R.string.day_friday
        DayOfWeek.SATURDAY -> R.string.day_saturday
        DayOfWeek.SUNDAY -> R.string.day_sunday
    },
)

@Composable
@ReadOnlyComposable
fun WeekendPattern.label(): String = stringResource(
    when (this) {
        WeekendPattern.FRI_SUN -> R.string.pattern_fri_sun
        WeekendPattern.THU_SUN -> R.string.pattern_thu_sun
        WeekendPattern.FRI_MON -> R.string.pattern_fri_mon
        WeekendPattern.THU_MON -> R.string.pattern_thu_mon
    },
)

@Composable
@ReadOnlyComposable
fun Cabin.label(): String = stringResource(
    when (this) {
        Cabin.ECONOMY -> R.string.cabin_economy
        Cabin.BUSINESS -> R.string.cabin_business
    },
)

@Composable
@ReadOnlyComposable
fun DestinationType.label(): String = stringResource(
    when (this) {
        DestinationType.CITY -> R.string.dest_city
        DestinationType.BEACH -> R.string.dest_beach
        DestinationType.NIGHTLIFE -> R.string.dest_nightlife
        DestinationType.CULTURE -> R.string.dest_culture
        DestinationType.FOOD -> R.string.dest_food
        DestinationType.NATURE -> R.string.dest_nature
    },
)

@Composable
@ReadOnlyComposable
fun PriceEntryMode.label(): String = stringResource(
    when (this) {
        PriceEntryMode.PER_SEGMENT -> R.string.price_mode_per_segment
        PriceEntryMode.ROUND_TRIP -> R.string.price_mode_round_trip
    },
)

@Composable
@ReadOnlyComposable
fun ScoreComponent.label(): String = stringResource(
    when (this) {
        ScoreComponent.FLIGHT_TIME_COMFORT -> R.string.component_flight_time_comfort
        ScoreComponent.STAY_QUALITY -> R.string.component_stay_quality
        ScoreComponent.WEEKEND_COMPATIBILITY -> R.string.component_weekend_compat
        ScoreComponent.LOGISTICS -> R.string.component_logistics_label
        ScoreComponent.COST -> R.string.component_cost_label
        ScoreComponent.DESTINATION_QUALITY -> R.string.component_destination_label
    },
)

@Composable
@ReadOnlyComposable
fun RejectionReason.label(): String = stringResource(
    when (this) {
        RejectionReason.NO_OUTBOUND -> R.string.reject_no_outbound
        RejectionReason.NO_INBOUND -> R.string.reject_no_inbound
        RejectionReason.NEGATIVE_STAY -> R.string.reject_negative_stay
        RejectionReason.FLIGHT_TOO_LONG -> R.string.reject_flight_too_long
        RejectionReason.NIGHTS_OUT_OF_RANGE -> R.string.reject_nights
        RejectionReason.NOT_DIRECT -> R.string.reject_not_direct
        RejectionReason.OVER_BUDGET -> R.string.reject_over_budget
        RejectionReason.BELOW_MIN_SCORE -> R.string.reject_below_min_score
    },
)

@Composable
@ReadOnlyComposable
fun RandomMode.label(): String = stringResource(
    when (this) {
        RandomMode.ANY -> R.string.random_mode_any
        RandomMode.TOP_TEN -> R.string.random_mode_top_ten
        RandomMode.UNDER_BUDGET -> R.string.random_mode_under_budget
        RandomMode.SUN -> R.string.random_mode_sun
        RandomMode.CITY_TRIP -> R.string.random_mode_city
        RandomMode.BEST_SCORE -> R.string.random_mode_best
    },
)

@Composable
@ReadOnlyComposable
fun RandomMode.description(): String = stringResource(
    when (this) {
        RandomMode.ANY -> R.string.random_mode_any_desc
        RandomMode.TOP_TEN -> R.string.random_mode_top_ten_desc
        RandomMode.UNDER_BUDGET -> R.string.random_mode_under_budget_desc
        RandomMode.SUN -> R.string.random_mode_sun_desc
        RandomMode.CITY_TRIP -> R.string.random_mode_city_desc
        RandomMode.BEST_SCORE -> R.string.random_mode_best_desc
    },
)

/** "2 nights" / "2 Nächte" — pluralised by the resource table. */
@Composable
fun nightsLabel(count: Int): String =
    pluralStringResource(R.plurals.nights, count, count)

/** "1,5 Urlaubstage" / "1.5 days of leave" — the number itself is locale-formatted. */
@Composable
fun leaveDaysLabel(days: Double): String {
    val locale = LocalContext.current.resources.configuration.locales[0]
    val rounded = if (days >= 0.95) {
        String.format(locale, "%.0f", days)
    } else {
        String.format(locale, "%.1f", days)
    }
    return pluralStringResource(R.plurals.leave_days, days.roundToInt().coerceAtLeast(1), rounded)
}

@Composable
fun TripInsight.text(): String = when (this) {
    is TripInsight.RelaxedDeparture ->
        stringResource(R.string.insight_relaxed_departure, day.label(), Formatting.time(time))
    is TripInsight.DepartureCostsNoWork ->
        stringResource(R.string.insight_departure_no_work, day.label(), Formatting.time(time))
    is TripInsight.SlightWorkTimeLost ->
        stringResource(R.string.insight_slight_work_lost, minutes.toInt())
    is TripInsight.EarlyDepartureCostsWork ->
        stringResource(R.string.insight_early_departure, Formatting.time(time))
    is TripInsight.LateArrival ->
        stringResource(R.string.insight_late_arrival, Formatting.time(time))
    is TripInsight.VeryLateReturn ->
        stringResource(R.string.insight_very_late_return, day.label(), Formatting.time(time))
    is TripInsight.Return ->
        stringResource(R.string.insight_return, day.label(), Formatting.time(time))
    is TripInsight.EarlyReturnCostsWeekend ->
        stringResource(R.string.insight_early_return, Formatting.time(time))
    is TripInsight.HomeLate ->
        stringResource(R.string.insight_home_late, Formatting.time(time))
    is TripInsight.GoodStayLength ->
        stringResource(R.string.insight_good_stay, hours)
    is TripInsight.ShortStay ->
        stringResource(R.string.insight_short_stay, hours)
    is TripInsight.NoLeaveNeeded ->
        stringResource(R.string.insight_no_leave, pattern.label())
    is TripInsight.LeaveNeeded ->
        stringResource(R.string.insight_leave_needed, leaveDaysLabel(days), pattern.label())
    is TripInsight.Nonstop ->
        stringResource(R.string.insight_nonstop, Formatting.minutes(minutes))
    TripInsight.NoUsableStay ->
        stringResource(R.string.insight_no_usable_stay)
    is TripInsight.NightsBelowMinimum ->
        stringResource(R.string.insight_nights_below_min, nights, minimum)
    is TripInsight.NightsAboveMaximum ->
        stringResource(R.string.insight_nights_above_max, nights, maximum)
    is TripInsight.MissingStandbyPrice ->
        stringResource(R.string.insight_missing_price, cabin.label())
    is TripInsight.OverBudget ->
        stringResource(R.string.insight_over_budget, price.format())
    TripInsight.NotNonstop ->
        stringResource(R.string.insight_not_nonstop)
}

@Composable
fun ComponentDetail.text(): String = when (this) {
    is ComponentDetail.FlightTimeComfort ->
        stringResource(R.string.component_flight, outboundScore, inboundScore)
    is ComponentDetail.StayQuality ->
        stringResource(R.string.component_stay, hours, nightsLabel(nights))
    ComponentDetail.NoStay ->
        stringResource(R.string.component_no_stay)
    is ComponentDetail.WeekendFit -> {
        val leave = if (leaveDays <= 0.05) stringResource(R.string.leave_none) else leaveDaysLabel(leaveDays)
        if (preferred) stringResource(R.string.component_weekend, pattern.label(), leave)
        else stringResource(R.string.component_weekend_not_preferred, pattern.label(), leave)
    }
    is ComponentDetail.Logistics ->
        stringResource(R.string.component_logistics, Formatting.minutes(averageFlightMinutes), transferMinutes)
    is ComponentDetail.Cost ->
        if (price == null) stringResource(R.string.component_cost_missing)
        else stringResource(R.string.component_cost, price.format(), cabin.label())
    is ComponentDetail.DestinationQuality ->
        stringResource(R.string.component_destination, topType?.label() ?: stringResource(R.string.value_dash), best)
    ComponentDetail.NoDestinationProfile ->
        stringResource(R.string.component_destination_missing)
}

/** "gerade eben" / "5 min ago" — resolved from the raw age so the wording is translatable. */
@Composable
fun relativeAge(instant: java.time.Instant?): String {
    if (instant == null) return stringResource(R.string.status_never)
    val minutes = Formatting.minutesSince(instant)
    return when {
        minutes < 1 -> stringResource(R.string.age_just_now)
        minutes < 60 -> stringResource(R.string.age_minutes, minutes.toInt())
        minutes < 60 * 24 -> stringResource(R.string.age_hours, (minutes / 60).toInt())
        else -> stringResource(R.string.age_days, (minutes / (60 * 24)).toInt())
    }
}

@Composable
fun com.condorino.weekend.ui.planner.EmptyReason.text(): String = when (this) {
    com.condorino.weekend.ui.planner.EmptyReason.NoFlightData -> stringResource(R.string.empty_no_data)
    is com.condorino.weekend.ui.planner.EmptyReason.Rejected -> reason.label()
    com.condorino.weekend.ui.planner.EmptyReason.NoMatch -> stringResource(R.string.empty_no_match)
    com.condorino.weekend.ui.planner.EmptyReason.FiltersTooTight -> stringResource(R.string.empty_filters)
}

@Composable
@ReadOnlyComposable
fun com.condorino.weekend.ui.calendar.CalendarMessage.text(): String = stringResource(
    when (this) {
        com.condorino.weekend.ui.calendar.CalendarMessage.NO_WEEKEND_IN_RANGE ->
            R.string.calendar_msg_no_weekend
        com.condorino.weekend.ui.calendar.CalendarMessage.NO_CONNECTIONS ->
            R.string.calendar_msg_none
    },
)

@Composable
@ReadOnlyComposable
fun com.condorino.weekend.domain.model.ThemeMode.label(): String = stringResource(
    when (this) {
        com.condorino.weekend.domain.model.ThemeMode.SYSTEM -> R.string.theme_system
        com.condorino.weekend.domain.model.ThemeMode.LIGHT -> R.string.theme_light
        com.condorino.weekend.domain.model.ThemeMode.DARK -> R.string.theme_dark
    },
)
