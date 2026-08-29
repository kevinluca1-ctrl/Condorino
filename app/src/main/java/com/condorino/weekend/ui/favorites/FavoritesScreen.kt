package com.condorino.weekend.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.condorino.weekend.R
import com.condorino.weekend.ui.components.EmptyState
import com.condorino.weekend.ui.components.TripCard
import com.condorino.weekend.ui.planner.PlannerUiState
import com.condorino.weekend.ui.planner.PlannerViewModel
import com.condorino.weekend.ui.theme.CondorinoColors

/**
 * Favourites (spec §18). Standby prices and preferences live in their own tables keyed by IATA
 * code, so marking/unmarking a favourite never loses them.
 */
@Composable
fun FavoritesScreen(
    state: PlannerUiState,
    viewModel: PlannerViewModel,
    onOpenTrip: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val favoriteTrips = state.allTrips.filter { it.iata in state.favorites }

    Box(modifier.fillMaxSize().background(CondorinoColors.Background)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.favorites_title),
                    color = CondorinoColors.TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1).sp,
                )
                Text(
                    stringResource(R.string.favorites_count, state.favorites.size),
                    color = CondorinoColors.TextTertiary,
                    fontSize = 12.sp,
                )
            }

            if (state.favorites.isEmpty()) {
                item {
                    EmptyState(
                        emoji = "🤍",
                        title = stringResource(R.string.favorites_empty_title),
                        message = stringResource(R.string.favorites_empty_body),
                    )
                }
            } else if (favoriteTrips.isEmpty()) {
                item {
                    EmptyState(
                        emoji = "📭",
                        title = stringResource(R.string.favorites_none_this_weekend_title),
                        message = stringResource(
                            R.string.favorites_none_this_weekend_body,
                            state.favorites.sorted().joinToString(", "),
                        ),
                    )
                }
            }

            items(favoriteTrips, key = { it.id }) { trip ->
                TripCard(
                    trip = trip,
                    onClick = { onOpenTrip(trip.id) },
                    onToggleFavorite = { viewModel.toggleFavorite(trip.iata) },
                )
            }
        }
    }
}
