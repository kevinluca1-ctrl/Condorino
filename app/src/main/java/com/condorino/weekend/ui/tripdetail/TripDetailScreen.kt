package com.condorino.weekend.ui.tripdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.condorino.weekend.core.Formatting
import com.condorino.weekend.domain.model.WeekendTrip
import com.condorino.weekend.scoring.TimeCompatibilityCalculator
import com.condorino.weekend.ui.components.EmptyState
import com.condorino.weekend.ui.components.Pill
import com.condorino.weekend.ui.components.ProvenancePill
import com.condorino.weekend.ui.components.ScoreBadge
import com.condorino.weekend.ui.planner.PlannerUiState
import com.condorino.weekend.ui.planner.PlannerViewModel
import com.condorino.weekend.ui.theme.CondorinoColors
import kotlin.math.roundToInt

/** Full breakdown of one trip (spec §14), including the time budget and the score components. */
@Composable
fun TripDetailScreen(
    tripId: String,
    state: PlannerUiState,
    viewModel: PlannerViewModel,
    onBack: () -> Unit,
    onEditPrice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trip = state.allTrips.firstOrNull { it.id == tripId }

    Box(modifier.fillMaxSize().background(CondorinoColors.Background)) {
        if (trip == null) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                BackRow(onBack, "Trip")
                Spacer(Modifier.height(24.dp))
                EmptyState(
                    emoji = "🔍",
                    title = "Trip nicht mehr verfügbar",
                    message = "Die zugrundeliegenden Flugdaten wurden inzwischen aktualisiert. " +
                        "Gehe zurück und wähle den Trip erneut aus.",
                    actionLabel = "Zurück",
                    onAction = onBack,
                )
            }
            return@Box
        }

        val time = TimeCompatibilityCalculator(state.preferences)
        val workLost = time.workingMinutesLost(trip.outbound.departureLocal)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            BackRow(onBack, trip.destination.airport.city) {
                IconButton(onClick = { viewModel.toggleFavorite(trip.iata) }) {
                    Icon(
                        if (trip.destination.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Favorit",
                        tint = if (trip.destination.isFavorite) CondorinoColors.Danger else CondorinoColors.TextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trip.destination.airport.flag, fontSize = 40.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        trip.destination.airport.city,
                        color = CondorinoColors.TextPrimary,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-1).sp,
                    )
                    Text(
                        "${trip.destination.airport.name} · ${trip.destination.airport.country}",
                        color = CondorinoColors.TextTertiary,
                        fontSize = 12.sp,
                    )
                }
                ScoreBadge(trip.score.total, size = 66)
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(trip.pattern.label, color = CondorinoColors.Sky)
                Pill(Formatting.nights(trip.nights))
                ProvenancePill(trip.provenance)
            }

            Spacer(Modifier.height(18.dp))

            LegBlock("Hinflug", trip, outbound = true)
            Spacer(Modifier.height(10.dp))
            LegBlock("Rückflug", trip, outbound = false)

            Spacer(Modifier.height(18.dp))
            SectionHeader("Standby")
            Card {
                PriceRow("Economy (Roundtrip)", trip.economyPrice?.format() ?: "nicht hinterlegt",
                    trip.economyPrice != null)
                Spacer(Modifier.height(6.dp))
                PriceRow("Business (Roundtrip)", trip.businessPrice?.format() ?: "nicht hinterlegt",
                    trip.businessPrice != null)
                trip.standbyPrice?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Eingabemodus: ${it.mode.label}",
                        color = CondorinoColors.TextTertiary,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { onEditPrice(trip.iata) }) {
                    Text(
                        if (trip.standbyPrice?.hasAnyPrice == true) "Preise bearbeiten" else "Standby-Preis eintragen",
                        color = CondorinoColors.Amber,
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionHeader("Zeitbilanz")
            Card {
                DetailRow(
                    "Arbeitszeit verloren",
                    if (workLost == 0L) "0 h" else Formatting.duration(java.time.Duration.ofMinutes(workLost)),
                    valueColor = if (workLost == 0L) CondorinoColors.Mint else CondorinoColors.Warning,
                )
                DetailRow("Effektive Zeit vor Ort", trip.effectiveHoursText, valueColor = CondorinoColors.Amber)
                DetailRow("Urlaubstage nötig", trip.pattern.vacationDaysRequired.toString())
                DetailRow(
                    "Frühester sinnvoller Abflug",
                    "%02d:%02d".format(
                        state.preferences.earliestReachableDeparture.hour,
                        state.preferences.earliestReachableDeparture.minute,
                    ),
                )
                DetailRow(
                    "Losfahren in ${state.preferences.homeCity} um",
                    "%02d:%02d".format(
                        state.preferences.latestDepartureFromHomeFor(
                            trip.outbound.departureLocal.toLocalTime(),
                        ).hour,
                        state.preferences.latestDepartureFromHomeFor(
                            trip.outbound.departureLocal.toLocalTime(),
                        ).minute,
                    ),
                )
                DetailRow(
                    "Wieder zu Hause",
                    Formatting.time(time.homeArrivalLocal(trip.inbound)),
                )
                DetailRow("Transfer Flughafen ↔ Zentrum", "${trip.destination.transferMinutes} min")
            }

            Spacer(Modifier.height(18.dp))
            SectionHeader("Warum dieser Trip?")
            Card {
                trip.score.reasons.forEach { reason ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text("•", color = CondorinoColors.Amber, fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(reason, color = CondorinoColors.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
                if (trip.score.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    trip.score.warnings.forEach { warning ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text("⚠", color = CondorinoColors.Warning, fontSize = 12.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(warning, color = CondorinoColors.Warning, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionHeader("Score-Zusammensetzung")
            Card {
                trip.score.components.forEach { component ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                component.component.label,
                                color = CondorinoColors.TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${(component.weight * 100).roundToInt()} %",
                                color = CondorinoColors.TextTertiary,
                                fontSize = 10.sp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${component.value.roundToInt()}",
                                color = CondorinoColors.forScore(component.value),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (component.value / 100.0).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = CondorinoColors.forScore(component.value),
                            trackColor = CondorinoColors.SurfaceHigh,
                        )
                        Text(
                            component.explanation,
                            color = CondorinoColors.TextTertiary,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun BackRow(onBack: () -> Unit, title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = CondorinoColors.TextPrimary)
        }
        Text(title, color = CondorinoColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        color = CondorinoColors.TextTertiary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CondorinoColors.Surface)
            .padding(14.dp),
        content = content,
    )
}

@Composable
private fun LegBlock(title: String, trip: WeekendTrip, outbound: Boolean) {
    val flight = if (outbound) trip.outbound else trip.inbound
    Column {
        SectionHeader(title)
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${flight.origin.iata} → ${flight.destination.iata}",
                    color = CondorinoColors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                flight.flightNumber?.let {
                    Pill(it, color = CondorinoColors.Amber)
                }
                Spacer(Modifier.weight(1f))
                Pill(if (flight.isDirect) "Nonstop" else "Umsteigen",
                    color = if (flight.isDirect) CondorinoColors.Mint else CondorinoColors.Warning)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                Formatting.longDate(flight.departureLocal.toLocalDate()),
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    Formatting.time(flight.departureLocal),
                    color = CondorinoColors.TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        Formatting.duration(flight.duration),
                        color = CondorinoColors.TextTertiary,
                        fontSize = 10.sp,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(CondorinoColors.Outline),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    Formatting.time(flight.arrivalLocal),
                    color = CondorinoColors.TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Text(
                "Ortszeit ${flight.origin.timeZoneId} → ${flight.destination.timeZoneId}",
                color = CondorinoColors.TextTertiary.copy(alpha = 0.75f),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            flight.availabilityNote?.let {
                Text(it, color = CondorinoColors.TextTertiary, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = CondorinoColors.TextPrimary,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CondorinoColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PriceRow(label: String, value: String, available: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CondorinoColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = if (available) CondorinoColors.TextPrimary else CondorinoColors.Warning,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
        )
    }
}
