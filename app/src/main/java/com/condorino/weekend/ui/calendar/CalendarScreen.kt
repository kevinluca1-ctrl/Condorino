package com.condorino.weekend.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.condorino.weekend.R
import com.condorino.weekend.core.Formatting
import com.condorino.weekend.domain.repository.WeekendSearchResult
import com.condorino.weekend.ui.components.DataStatusBar
import com.condorino.weekend.ui.text.label
import com.condorino.weekend.ui.text.text
import com.condorino.weekend.ui.components.EmptyState
import com.condorino.weekend.ui.theme.CondorinoColors
import java.time.LocalDate
import kotlin.math.roundToInt

private enum class RangePreset(@StringRes val label: Int, val months: Long?) {
    ONE(R.string.calendar_preset_1, 1),
    THREE(R.string.calendar_preset_3, 3),
    SIX(R.string.calendar_preset_6, 6),

    /** Explicit from/to selection (spec §16). */
    CUSTOM(R.string.calendar_preset_custom, null),
}

/**
 * Calendar + multi-weekend search in one screen (spec §15/§16): a star-rated list of every
 * weekend in the chosen range, plus a best-weekends ranking on top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    state: CalendarUiState,
    viewModel: CalendarViewModel,
    onSelectWeekend: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var preset by remember { mutableStateOf(RangePreset.THREE) }
    var showRangePicker by remember { mutableStateOf(false) }

    if (showRangePicker) {
        RangePickerDialog(
            initialFrom = state.from,
            initialTo = state.to,
            onDismiss = { showRangePicker = false },
            onConfirm = { from, to ->
                showRangePicker = false
                preset = RangePreset.CUSTOM
                viewModel.setRange(from, to)
            },
        )
    }

    Box(modifier.fillMaxSize().background(CondorinoColors.Background)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.calendar_title),
                    color = CondorinoColors.TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1).sp,
                    lineHeight = 32.sp,
                )
                Text(
                    stringResource(
                        R.string.calendar_range,
                        Formatting.shortDate(state.from),
                        Formatting.shortDate(state.to),
                    ),
                    color = CondorinoColors.TextTertiary,
                    fontSize = 12.sp,
                )
            }

            item {
                DataStatusBar(
                    status = state.status,
                    onRefresh = viewModel::refresh,
                )
            }

            item {
                if (state.status.isDemo) {
                    Text(
                        stringResource(R.string.calendar_demo_note),
                        color = CondorinoColors.Warning,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RangePreset.entries.forEach { option ->
                        FilterChip(
                            selected = preset == option,
                            onClick = {
                                val months = option.months
                                if (months == null) {
                                    showRangePicker = true
                                } else {
                                    preset = option
                                    val from = LocalDate.now()
                                    viewModel.setRange(from, from.plusMonths(months))
                                }
                            },
                            label = { Text(stringResource(option.label), fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = CondorinoColors.SurfaceElevated,
                                labelColor = CondorinoColors.TextSecondary,
                                selectedContainerColor = CondorinoColors.Amber,
                                selectedLabelColor = CondorinoColors.Background,
                            ),
                        )
                    }
                }
            }

            if (state.isLoading) {
                item {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)),
                        color = CondorinoColors.Amber,
                        trackColor = CondorinoColors.SurfaceElevated,
                    )
                }
            }

            state.message?.let { message ->
                item {
                    EmptyState(
                        emoji = "📅",
                        title = stringResource(R.string.calendar_empty_title),
                        message = message.text(),
                    )
                }
            }

            if (state.ranked.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.calendar_best)) }
                items(state.ranked.take(8), key = { "best-${it.friday}" }) { result ->
                    BestWeekendRow(
                        rank = state.ranked.indexOf(result) + 1,
                        result = result,
                        onClick = { onSelectWeekend(result.friday) },
                    )
                }

                item { SectionTitle(stringResource(R.string.calendar_all)) }
                state.byMonth.forEach { (month, results) ->
                    item(key = "month-$month") {
                        Text(
                            month,
                            color = CondorinoColors.TextTertiary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                    }
                    items(results, key = { "row-${it.friday}" }) { result ->
                        WeekendRow(result) { onSelectWeekend(result.friday) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = CondorinoColors.TextPrimary,
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun BestWeekendRow(rank: Int, result: WeekendSearchResult, onClick: () -> Unit) {
    val best = result.best ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CondorinoColors.Surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            color = CondorinoColors.TextTertiary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.width(22.dp),
        )
        Text(best.destination.airport.flag, fontSize = 22.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${Formatting.shortDate(result.friday)}–${Formatting.shortDate(result.friday.plusDays(2))} " +
                    best.destination.airport.city,
                color = CondorinoColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${best.pattern.label()} · ${Formatting.time(best.outbound.departureLocal)} → " +
                    Formatting.time(best.inbound.departureLocal),
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
            )
        }
        Text(
            "${best.score.total.roundToInt()}",
            color = CondorinoColors.forScore(best.score.total),
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun WeekendRow(result: WeekendSearchResult, onClick: () -> Unit) {
    val best = result.best
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CondorinoColors.Surface.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Formatting.dayDate(result.friday),
            color = CondorinoColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(78.dp),
        )
        if (best == null) {
            Text(
                stringResource(R.string.calendar_no_connection),
                color = CondorinoColors.TextTertiary,
                fontSize = 12.sp,
            )
        } else {
            Text(stars(best.score.total), color = CondorinoColors.Amber, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "${best.destination.airport.flag} ${best.destination.airport.city}",
                color = CondorinoColors.TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${best.score.total.roundToInt()}",
                color = CondorinoColors.forScore(best.score.total),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 0–100 score rendered as the 1–5 star rating shown in the calendar overview. */
internal fun stars(score: Double): String {
    val filled = ((score / 100.0) * 5.0).roundToInt().coerceIn(0, 5)
    return "★".repeat(filled) + "☆".repeat(5 - filled)
}

/**
 * Von/Bis selection for the multi-weekend search.
 *
 * The Material date-range picker works in UTC midnight millis, so the conversion is pinned to
 * [ZoneOffset.UTC] on the way in and out — reading those millis in the device zone would shift the
 * chosen day by one either side of midnight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerDialog(
    initialFrom: LocalDate,
    initialTo: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialFrom.toUtcMillis(),
        initialSelectedEndDateMillis = initialTo.toUtcMillis(),
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val from = pickerState.selectedStartDateMillis?.toUtcLocalDate()
                    val to = pickerState.selectedEndDateMillis?.toUtcLocalDate()
                    if (from != null && to != null && !to.isBefore(from)) onConfirm(from, to)
                },
                enabled = pickerState.selectedStartDateMillis != null &&
                    pickerState.selectedEndDateMillis != null,
            ) {
                Text(stringResource(R.string.action_apply), color = CondorinoColors.Amber)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = CondorinoColors.TextSecondary)
            }
        },
        colors = androidx.compose.material3.DatePickerDefaults.colors(
            containerColor = CondorinoColors.Surface,
        ),
    ) {
        DateRangePicker(
            state = pickerState,
            title = {
                Text(
                    stringResource(R.string.calendar_pick_range),
                    color = CondorinoColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                )
            },
            showModeToggle = false,
        )
    }
}

private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcLocalDate(): LocalDate =
    java.time.Instant.ofEpochMilli(this).atZone(java.time.ZoneOffset.UTC).toLocalDate()
