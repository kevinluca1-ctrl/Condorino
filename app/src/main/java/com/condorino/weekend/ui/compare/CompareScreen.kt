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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.condorino.weekend.core.Formatting
import com.condorino.weekend.domain.model.WeekendTrip
import com.condorino.weekend.ui.components.EmptyState
import com.condorino.weekend.ui.planner.PlannerUiState
import com.condorino.weekend.ui.planner.PlannerViewModel
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

    Column(
        modifier
            .fillMaxSize()
            .background(CondorinoColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            "Vergleichen",
            color = CondorinoColors.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1).sp,
        )
        Text(
            "Wähle bis zu ${PlannerViewModel.MAX_COMPARE} Ziele des Wochenendes ab Fr " +
                Formatting.shortDate(state.friday),
            color = CondorinoColors.TextTertiary,
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(12.dp))

        if (state.trips.isEmpty()) {
            EmptyState(
                emoji = "⚖️",
                title = "Nichts zu vergleichen",
                message = state.emptyReason,
            )
            return@Column
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.trips.take(20).forEach { trip ->
                FilterChip(
                    selected = trip.iata in state.compareSelection,
                    onClick = { viewModel.toggleCompare(trip.iata) },
                    label = {
                        Text("${trip.destination.airport.flag} ${trip.destination.airport.city}", fontSize = 12.sp)
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

        if (state.compareSelection.isNotEmpty()) {
            TextButton(onClick = viewModel::clearCompare) {
                Text("Auswahl leeren", color = CondorinoColors.Amber, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        if (selected.isEmpty()) {
            EmptyState(
                emoji = "👆",
                title = "Noch nichts ausgewählt",
                message = "Tippe oben auf zwei oder mehr Ziele, um sie gegenüberzustellen.",
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
                HeaderCell("Faktor", labelWidth)
                CompareRowLabels().forEach { LabelCell(it, labelWidth) }
            }
            trips.forEach { trip ->
                Column {
                    Box(
                        Modifier.width(columnWidth).height(42.dp).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            "${trip.destination.airport.flag} ${trip.destination.airport.city}",
                            color = CondorinoColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            lineHeight = 14.sp,
                        )
                    }
                    ValueCell(trip.score.total.roundToInt().toString(), columnWidth,
                        CondorinoColors.forScore(trip.score.total), bold = true)
                    ValueCell(trip.economyPrice?.format() ?: "–", columnWidth)
                    ValueCell(trip.businessPrice?.format() ?: "–", columnWidth)
                    ValueCell("${trip.effectiveTime.toHours()} h", columnWidth)
                    ValueCell(Formatting.time(trip.outbound.departureLocal), columnWidth)
                    ValueCell(Formatting.time(trip.inbound.departureLocal), columnWidth)
                    ValueCell(trip.pattern.label, columnWidth)
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

private fun CompareRowLabels() = listOf(
    "Trip Score",
    "Economy",
    "Business",
    "Effektive Zeit",
    "Hinflug",
    "Rückflug",
    "Muster",
    "Nächte",
    "Flugzeit",
    "Urlaubstage",
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
