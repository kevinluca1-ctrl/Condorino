package com.condorino.weekend.ui.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.condorino.weekend.R
import com.condorino.weekend.core.Formatting
import com.condorino.weekend.domain.model.WeekendTrip
import com.condorino.weekend.ui.components.AirportSearch
import com.condorino.weekend.ui.components.EmptyState
import com.condorino.weekend.ui.components.SearchField
import com.condorino.weekend.ui.planner.PlannerUiState
import com.condorino.weekend.ui.planner.PlannerViewModel
import com.condorino.weekend.ui.text.label
import com.condorino.weekend.ui.text.text
import com.condorino.weekend.ui.theme.CondorinoColors
import kotlin.math.roundToInt

/** Side-by-side comparison of up to four destinations (spec §17). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    state: PlannerUiState,
    viewModel: PlannerViewModel,
    modifier: Modifier = Modifier,
) {
    val selected = state.comparedTrips
    val candidates = state.comparableTrips
    var query by rememberSaveable { mutableStateOf("") }

    val matches = remember(candidates, query) {
        if (query.isBlank()) {
            candidates
        } else {
            val byIata = candidates.associateBy { it.iata }
            AirportSearch
                .rank(candidates.map { it.destination.airport }, query, limit = 30)
                .mapNotNull { byIata[it.iata] }
        }
    }

    // Country heading carries the flag, so the chips themselves do not have to repeat it. Sorted
    // by the country *name* the reader sees, not by its code or flag codepoint.
    val byCountry = remember(matches) {
        matches
            .groupBy { it.destination.airport.displayCountry }
            .toList()
            .sortedBy { (country, _) -> country.lowercase() }
            .map { (country, trips) ->
                val flag = trips.first().destination.airport.flag
                "$flag  $country" to trips.sortedBy { it.destination.airport.cityWithCode.lowercase() }
            }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(CondorinoColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            stringResource(R.string.compare_title),
            color = CondorinoColors.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1).sp,
        )
        Text(
            stringResource(
                R.string.compare_subtitle,
                PlannerViewModel.MAX_COMPARE,
                Formatting.shortDate(state.friday),
            ),
            color = CondorinoColors.TextTertiary,
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(12.dp))

        if (candidates.isEmpty()) {
            EmptyState(
                emoji = "\u2696\uFE0F",
                title = stringResource(R.string.compare_nothing_title),
                message = state.emptyReason.text(),
            )
            return@Column
        }

        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.compare_search_hint),
            clearContentDescription = stringResource(R.string.action_clear_search),
        )

        Spacer(Modifier.height(10.dp))

        Text(
            stringResource(R.string.compare_selected_count, selected.size, PlannerViewModel.MAX_COMPARE),
            color = CondorinoColors.TextTertiary,
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(6.dp))

        if (matches.isEmpty()) {
            Text(
                stringResource(R.string.compare_no_match, query),
                color = CondorinoColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            // One flat run of chips put four indistinguishable "London"s next to each other and
            // read as a wall. Grouping under the country, and labelling every chip with its
            // airport code, makes the list both scannable and unambiguous.
            byCountry.forEach { (country, trips) ->
                Text(
                    country,
                    color = CondorinoColors.TextTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    trips.forEach { trip ->
                        val isSelected = trip.iata in state.compareSelection
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.toggleCompare(trip.iata) },
                            label = {
                                Text(trip.destination.airport.cityWithCode, fontSize = 12.sp)
                            },
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
        }

        if (state.compareSelection.isNotEmpty()) {
            TextButton(onClick = viewModel::clearCompare) {
                Text(stringResource(R.string.compare_clear), color = CondorinoColors.Amber, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        if (selected.isEmpty()) {
            EmptyState(
                emoji = "👆",
                title = stringResource(R.string.compare_none_selected_title),
                message = stringResource(R.string.compare_none_selected_body),
            )
        } else {
            ComparisonTable(selected)
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ComparisonTable(trips: List<WeekendTrip>) {
    val labelWidth = 132.dp
    val columnWidth = 108.dp

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CondorinoColors.Surface)
            .padding(vertical = 10.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                HeaderCell(stringResource(R.string.compare_factor), labelWidth)
                compareRowLabels().forEach { LabelCell(it, labelWidth) }
            }
            trips.forEach { trip ->
                Column {
                    Box(
                        Modifier.width(columnWidth).height(42.dp).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            "${trip.destination.airport.flag} ${trip.destination.airport.cityWithCode}",
                            color = CondorinoColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            lineHeight = 14.sp,
                        )
                    }
                    ValueCell(trip.score.total.roundToInt().toString(), columnWidth,
                        CondorinoColors.forScore(trip.score.total), bold = true)
                    val dash = stringResource(R.string.value_dash)
                    ValueCell(trip.economyPrice?.format() ?: dash, columnWidth)
                    ValueCell(trip.businessPrice?.format() ?: dash, columnWidth)
                    ValueCell("${trip.effectiveTime.toHours()} h", columnWidth)
                    ValueCell(Formatting.time(trip.outbound.departureLocal), columnWidth)
                    ValueCell(Formatting.time(trip.inbound.departureLocal), columnWidth)
                    ValueCell(trip.pattern.label(), columnWidth)
                    ValueCell(trip.nights.toString(), columnWidth)
                    ValueCell(Formatting.duration(trip.outbound.duration), columnWidth)
                    ValueCell(
                        if (trip.pattern.vacationDaysRequired == 0) "0" else trip.pattern.vacationDaysRequired.toString(),
                        columnWidth,
                        if (trip.pattern.vacationDaysRequired == 0) CondorinoColors.Mint else CondorinoColors.Warning,
                    )
                }
            }
        }
    }
}

@Composable
@ReadOnlyComposable
private fun compareRowLabels() = listOf(
    stringResource(R.string.compare_trip_score),
    stringResource(R.string.compare_economy),
    stringResource(R.string.compare_business),
    stringResource(R.string.compare_effective_time),
    stringResource(R.string.compare_outbound),
    stringResource(R.string.compare_inbound),
    stringResource(R.string.compare_pattern),
    stringResource(R.string.compare_nights),
    stringResource(R.string.compare_flight_time),
    stringResource(R.string.compare_leave_days),
)

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Box(Modifier.width(width).height(42.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, color = CondorinoColors.TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun LabelCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Box(Modifier.width(width).height(34.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, color = CondorinoColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun ValueCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    color: androidx.compose.ui.graphics.Color = CondorinoColors.TextPrimary,
    bold: Boolean = false,
) {
    Box(Modifier.width(width).height(34.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            text,
            color = color,
            fontSize = if (bold) 15.sp else 12.sp,
            fontWeight = if (bold) FontWeight.Black else FontWeight.Normal,
        )
    }
}
