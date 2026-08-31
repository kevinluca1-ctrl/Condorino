package com.condorino.weekend.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.condorino.weekend.R
import com.condorino.weekend.core.Formatting
import com.condorino.weekend.ui.components.DataStatusBar
import com.condorino.weekend.ui.components.EmptyState
import com.condorino.weekend.ui.components.TripCard
import com.condorino.weekend.ui.planner.EmptyReason
import com.condorino.weekend.ui.planner.PlannerUiState
import com.condorino.weekend.ui.planner.PlannerViewModel
import com.condorino.weekend.ui.planner.TripFilters
import com.condorino.weekend.ui.text.text
import com.condorino.weekend.ui.search.FilterSheet
import com.condorino.weekend.ui.theme.CondorinoColors

/**
 * The screen that has to answer "where can I fly this weekend?" within two seconds:
 * weekend picker at the top, then the ranked trips, with the surprise button always in reach.
 */
@Composable
fun HomeScreen(
    state: PlannerUiState,
    viewModel: PlannerViewModel,
    onOpenTrip: (String) -> Unit,
    onOpenSurprise: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val trips = state.trips

    Box(modifier.fillMaxSize().background(CondorinoColors.Background)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.home_title),
                    color = CondorinoColors.TextPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1).sp,
                )
                Text(
                    stringResource(R.string.home_subtitle, state.preferences.homeCity),
                    color = CondorinoColors.TextTertiary,
                    fontSize = 12.sp,
                )
            }

            item { WeekendSelector(state, viewModel) }

            item {
                DataStatusBar(
                    status = state.status,
                    onRefresh = viewModel::refresh,
                    onOpenSettings = onOpenSettings,
                )
            }

            if (state.isLoading) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)),
                        color = CondorinoColors.Amber,
                        trackColor = CondorinoColors.SurfaceElevated,
                    )
                }
            }

            item { SurpriseButton(onClick = onOpenSurprise) }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.home_best_trips),
                        color = CondorinoColors.TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.home_count, trips.size, state.allTrips.size),
                        color = CondorinoColors.TextTertiary,
                        fontSize = 11.sp,
                    )
                    IconButton(onClick = { showFilters = true }) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.action_filter),
                            tint = if (state.filters.isActive) CondorinoColors.Amber
                            else CondorinoColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            if (trips.isEmpty() && !state.isLoading) {
                // "No flight data at all" is the one empty case a bare refresh can never fix on its
                // own — with zero sources configured, refreshing just repeats the same no-op, so
                // Settings (where every source, including the de-prioritized OpenSky fallback, can
                // be turned on) is offered instead.
                val noSourceConfigured = state.emptyReason is EmptyReason.NoFlightData
                item {
                    EmptyState(
                        emoji = "🛫",
                        title = stringResource(R.string.home_empty_title),
                        message = state.emptyReason.text(),
                        actionLabel = stringResource(
                            when {
                                state.filters.isActive -> R.string.home_reset_filters
                                noSourceConfigured -> R.string.settings_title
                                else -> R.string.action_refresh_now
                            },
                        ),
                        onAction = {
                            when {
                                state.filters.isActive -> viewModel.updateFilters { TripFilters() }
                                noSourceConfigured -> onOpenSettings()
                                else -> viewModel.refresh()
                            }
                        },
                    )
                }
            }

            items(trips, key = { it.id }) { trip ->
                TripCard(
                    trip = trip,
                    rank = trips.indexOf(trip) + 1,
                    onClick = { onOpenTrip(trip.id) },
                    onToggleFavorite = { viewModel.toggleFavorite(trip.iata) },
                )
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            filters = state.filters,
            onDismiss = { showFilters = false },
            onChange = { updated -> viewModel.updateFilters { updated } },
        )
    }
}

@Composable
private fun WeekendSelector(state: PlannerUiState, viewModel: PlannerViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CondorinoColors.Surface)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = viewModel::previousWeekend) {
            Icon(
                Icons.Filled.ChevronLeft,
                stringResource(R.string.home_prev_weekend),
                tint = CondorinoColors.Amber,
            )
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Fr ${Formatting.shortDate(state.friday)} – Mo ${Formatting.shortDate(state.friday.plusDays(3))}",
                color = CondorinoColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            Text(
                Formatting.month(state.friday),
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
            )
        }
        IconButton(onClick = viewModel::nextWeekend) {
            Icon(
                Icons.Filled.ChevronRight,
                stringResource(R.string.home_next_weekend),
                tint = CondorinoColors.Amber,
            )
        }
    }
}

@Composable
private fun SurpriseButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CondorinoColors.Amber,
            contentColor = CondorinoColors.Background,
        ),
    ) {
        Text(stringResource(R.string.home_surprise), fontWeight = FontWeight.Black, fontSize = 16.sp)
    }
}
