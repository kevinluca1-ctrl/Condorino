package com.condorino.weekend.ui.random

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.condorino.weekend.R
import com.condorino.weekend.core.Formatting
import com.condorino.weekend.scoring.RandomMode
import com.condorino.weekend.ui.components.EmptyState
import com.condorino.weekend.ui.components.Pill
import com.condorino.weekend.ui.components.ProvenancePill
import com.condorino.weekend.ui.components.ScoreBadge
import com.condorino.weekend.ui.planner.PlannerUiState
import com.condorino.weekend.ui.planner.PlannerViewModel
import com.condorino.weekend.ui.text.description
import com.condorino.weekend.ui.text.label
import com.condorino.weekend.ui.text.nightsLabel
import com.condorino.weekend.ui.theme.CondorinoColors

/** "Surprise me" (spec §11): pick a mode, draw a destination, show it as one big travel card. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RandomScreen(
    state: PlannerUiState,
    viewModel: PlannerViewModel,
    onBack: () -> Unit,
    onOpenTrip: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.friday) {
        if (state.surprise == null && state.trips.isNotEmpty()) viewModel.surpriseMe()
    }

    Column(
        modifier
            .fillMaxSize()
            .background(CondorinoColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.action_back),
                    tint = CondorinoColors.TextPrimary,
                )
            }
            Text(
                stringResource(R.string.random_nav_title),
                color = CondorinoColors.TextSecondary,
                fontSize = 13.sp,
            )
        }

        Text(
            stringResource(R.string.random_title),
            color = CondorinoColors.TextPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1).sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            stringResource(R.string.random_weekend_from, Formatting.shortDate(state.friday)),
            color = CondorinoColors.TextTertiary,
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(14.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RandomMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.surpriseMode == mode,
                    onClick = {
                        viewModel.setSurpriseMode(mode)
                        viewModel.surpriseMe()
                    },
                    label = { Text(mode.label(), fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = CondorinoColors.SurfaceElevated,
                        labelColor = CondorinoColors.TextSecondary,
                        selectedContainerColor = CondorinoColors.Amber,
                        selectedLabelColor = CondorinoColors.Background,
                    ),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        val trip = state.surprise
        if (trip == null) {
            EmptyState(
                emoji = "🎲",
                title = stringResource(R.string.random_empty_title),
                message = if (state.surpriseFailed) {
                    stringResource(R.string.random_no_match, state.surpriseMode.label())
                } else {
                    stringResource(R.string.random_empty_body)
                },
                actionLabel = stringResource(R.string.random_reroll),
                onAction = viewModel::surpriseMe,
            )
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(CondorinoColors.Surface)
                    .clickable { onOpenTrip(trip.id) }
                    .padding(20.dp),
            ) {
                Text(trip.destination.airport.flag, fontSize = 52.sp)
                Text(
                    trip.destination.airport.city,
                    color = CondorinoColors.TextPrimary,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1.5).sp,
                )
                Text(
                    "${trip.destination.airport.displayCountry} · FRA → ${trip.iata}",
                    color = CondorinoColors.TextTertiary,
                    fontSize = 12.sp,
                )

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            trip.pattern.label(),
                            color = CondorinoColors.Sky,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${Formatting.time(trip.outbound.departureLocal)} → " +
                                Formatting.time(trip.inbound.departureLocal),
                            color = CondorinoColors.TextPrimary,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            "${nightsLabel(trip.nights)} · " +
                                stringResource(R.string.card_time_on_site, trip.effectiveHoursText),
                            color = CondorinoColors.TextTertiary,
                            fontSize = 12.sp,
                        )
                    }
                    ScoreBadge(trip.score.total, size = 70)
                }

                Spacer(Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    trip.economyPrice?.let {
                        Pill(stringResource(R.string.random_economy_standby, it.format()), color = CondorinoColors.Mint)
                    }
                    trip.businessPrice?.let {
                        Pill(stringResource(R.string.random_business, it.format()), color = CondorinoColors.Sky)
                    }
                    if (!(trip.standbyPrice?.hasAnyPrice ?: false)) {
                        Pill(stringResource(R.string.random_missing_price), color = CondorinoColors.Warning)
                    }
                }

                trip.destination.profile?.note?.let { note ->
                    Text(
                        "„$note“",
                        color = CondorinoColors.TextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))
                ProvenancePill(trip.provenance)
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = viewModel::surpriseMe,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CondorinoColors.Amber,
                    contentColor = CondorinoColors.Background,
                ),
            ) {
                Text(stringResource(R.string.random_reroll), fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    stringResource(
                        R.string.random_pool,
                        state.trips.size,
                        state.surpriseMode.description(),
                    ),
                    color = CondorinoColors.TextTertiary,
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}
