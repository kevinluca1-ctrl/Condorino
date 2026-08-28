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
                    "Favoriten",
                    color = CondorinoColors.TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1).sp,
                )
                Text(
                    "${state.favorites.size} gemerkte Ziele",
                    color = CondorinoColors.TextTertiary,
                    fontSize = 12.sp,
                )
            }

            if (state.favorites.isEmpty()) {
                item {
                    EmptyState(
                        emoji = "🤍",
                        title = "Noch keine Favoriten",
                        message = "Tippe auf einer Trip-Karte auf das Herz, um ein Ziel zu merken. " +
                            "Gespeicherte Standby-Preise bleiben dabei erhalten.",
                    )
                }
            } else if (favoriteTrips.isEmpty()) {
                item {
                    EmptyState(
                        emoji = "📭",
                        title = "Kein Favorit an diesem Wochenende",
                        message = "Für deine gemerkten Ziele (${state.favorites.sorted().joinToString(", ")}) " +
                            "gibt es an diesem Wochenende keine passende Verbindung. " +
                            "Probiere ein anderes Wochenende oder schau in den Kalender.",
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
