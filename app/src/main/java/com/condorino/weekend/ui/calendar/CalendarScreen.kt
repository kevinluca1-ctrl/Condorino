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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
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
import com.condorino.weekend.core.Formatting
import com.condorino.weekend.domain.repository.WeekendSearchResult
import com.condorino.weekend.ui.components.EmptyState
import com.condorino.weekend.ui.theme.CondorinoColors
import java.time.LocalDate
import kotlin.math.roundToInt

private enum class RangePreset(val label: String, val months: Long) {
    ONE("1 Monat", 1), THREE("3 Monate", 3), SIX("6 Monate", 6),
}

/**
 * Calendar + multi-weekend search in one screen (spec §15/§16): a star-rated list of every
 * weekend in the chosen range, plus a "Beste Wochenenden" ranking on top.
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

    Box(modifier.fillMaxSize().background(CondorinoColors.Background)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Welche Wochenenden sind gut?",
                    color = CondorinoColors.TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1).sp,
                    lineHeight = 32.sp,
                )
                Text(
                    "${Formatting.shortDate(state.from)} – ${Formatting.shortDate(state.to)}",
                    color = CondorinoColors.TextTertiary,
                    fontSize = 12.sp,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RangePreset.entries.forEach { option ->
                        FilterChip(
                            selected = preset == option,
                            onClick = {
                                preset = option
                                val from = LocalDate.now()
                                viewModel.setRange(from, from.plusMonths(option.months))
                            },
                            label = { Text(option.label, fontSize = 12.sp) },
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
                        title = "Keine bewerteten Wochenenden",
                        message = message,
                    )
                }
            }

            if (state.ranked.isNotEmpty()) {
                item { SectionTitle("Beste Wochenenden") }
                items(state.ranked.take(8), key = { "best-${it.friday}" }) { result ->
                    BestWeekendRow(
                        rank = state.ranked.indexOf(result) + 1,
                        result = result,
                        onClick = { onSelectWeekend(result.friday) },
                    )
                }

                item { SectionTitle("Alle Wochenenden") }
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
                "${best.pattern.label} · ${Formatting.time(best.outbound.departureLocal)} → " +
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
            "Fr ${Formatting.shortDate(result.friday)}",
            color = CondorinoColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(78.dp),
        )
        if (best == null) {
            Text("keine Verbindung", color = CondorinoColors.TextTertiary, fontSize = 12.sp)
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
