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
import androidx.compose.ui.res.stringResource
import com.condorino.weekend.R
import com.condorino.weekend.core.Formatting
import com.condorino.weekend.domain.model.WeekendTrip
import com.condorino.weekend.scoring.TimeCompatibilityCalculator
import com.condorino.weekend.ui.components.EmptyState
import com.condorino.weekend.ui.components.Pill
import com.condorino.weekend.ui.components.ProvenancePill
import com.condorino.weekend.ui.components.ScoreBadge
import com.condorino.weekend.ui.planner.CommercialPriceUiState
import com.condorino.weekend.ui.planner.PlannerUiState
import com.condorino.weekend.ui.planner.PlannerViewModel
import com.condorino.weekend.ui.text.label
import com.condorino.weekend.ui.text.nightsLabel
import com.condorino.weekend.ui.text.text
import com.condorino.weekend.ui.theme.CondorinoColors
import kotlin.math.roundToInt

/** Full breakdown of one trip (spec §14), including the time budget and the score components. */
@Composable
fun TripDetailScreen(
    state: PlannerUiState,
    viewModel: PlannerViewModel,
    onBack: () -> Unit,
    onEditPrice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trip = state.selectedTrip

    Box(modifier.fillMaxSize().background(CondorinoColors.Background)) {
        if (trip == null) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                BackRow(onBack, stringResource(R.string.detail_outbound))
                Spacer(Modifier.height(24.dp))
                EmptyState(
                    emoji = "🔍",
                    title = stringResource(R.string.detail_missing_title),
                    message = stringResource(R.string.detail_missing_body),
                    actionLabel = stringResource(R.string.action_back),
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
                        contentDescription = stringResource(R.string.detail_favorite),
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
                        "${trip.destination.airport.name} · ${trip.destination.airport.displayCountry}",
                        color = CondorinoColors.TextTertiary,
                        fontSize = 12.sp,
                    )
                }
                ScoreBadge(trip.score.total, size = 66)
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(trip.pattern.label(), color = CondorinoColors.Sky)
                Pill(nightsLabel(trip.nights))
                ProvenancePill(trip.provenance)
            }

            Spacer(Modifier.height(18.dp))

            LegBlock(stringResource(R.string.detail_outbound), trip, outbound = true)
            Spacer(Modifier.height(10.dp))
            LegBlock(stringResource(R.string.detail_inbound), trip, outbound = false)

            Spacer(Modifier.height(18.dp))
            SectionHeader(stringResource(R.string.detail_standby))
            Card {
                val notSet = stringResource(R.string.detail_not_set)
                PriceRow(
                    stringResource(R.string.detail_economy_roundtrip),
                    trip.economyPrice?.format() ?: notSet,
                    trip.economyPrice != null,
                )
                Spacer(Modifier.height(6.dp))
                PriceRow(
                    stringResource(R.string.detail_business_roundtrip),
                    trip.businessPrice?.format() ?: notSet,
                    trip.businessPrice != null,
                )
                trip.standbyPrice?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.detail_entry_mode, it.mode.label()),
                        color = CondorinoColors.TextTertiary,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { onEditPrice(trip.iata) }) {
                    Text(
                        stringResource(
                            if (trip.standbyPrice?.hasAnyPrice == true) R.string.detail_edit_prices
                            else R.string.detail_add_price,
                        ),
                        color = CondorinoColors.Amber,
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionHeader(stringResource(R.string.detail_commercial_price))
            Card {
                when (val priceState = state.commercialPrices[trip.id]) {
                    null -> {
                        Text(
                            stringResource(R.string.detail_commercial_price_hint),
                            color = CondorinoColors.TextTertiary,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { viewModel.checkCommercialPrice(trip) }) {
                            Text(
                                stringResource(R.string.detail_check_commercial_price),
                                color = CondorinoColors.Amber,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    CommercialPriceUiState.Loading -> {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = CondorinoColors.Amber,
                            trackColor = CondorinoColors.SurfaceHigh,
                        )
                    }
                    is CommercialPriceUiState.Success -> {
                        val quote = priceState.quote
                        PriceRow(
                            stringResource(R.string.detail_commercial_roundtrip),
                            quote.roundTripPrice.format(),
                            true,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(
                                when (quote.carryOnIncluded) {
                                    true -> R.string.detail_carry_on_included
                                    false -> R.string.detail_carry_on_not_included
                                    null -> R.string.detail_carry_on_unknown
                                },
                            ),
                            color = CondorinoColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                        quote.carryOnNote?.let {
                            Text(it, color = CondorinoColors.TextTertiary, fontSize = 11.sp)
                        }
                        quote.airline?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.detail_commercial_airline, it),
                                color = CondorinoColors.TextTertiary,
                                fontSize = 11.sp,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { viewModel.checkCommercialPrice(trip) }) {
                            Text(
                                stringResource(R.string.detail_refresh_commercial_price),
                                color = CondorinoColors.Amber,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    is CommercialPriceUiState.NotConfigured -> {
                        Text(priceState.reason, color = CondorinoColors.Warning, fontSize = 12.sp)
                        Text(
                            priceState.howToFix,
                            color = CondorinoColors.TextTertiary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    is CommercialPriceUiState.Failure -> {
                        Text(priceState.message, color = CondorinoColors.Warning, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { viewModel.checkCommercialPrice(trip) }) {
                            Text(
                                stringResource(R.string.action_retry),
                                color = CondorinoColors.Amber,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionHeader(stringResource(R.string.detail_time_budget))
            Card {
                DetailRow(
                    stringResource(R.string.detail_work_lost),
                    if (workLost == 0L) "0 h" else Formatting.duration(java.time.Duration.ofMinutes(workLost)),
                    valueColor = if (workLost == 0L) CondorinoColors.Mint else CondorinoColors.Warning,
                )
                DetailRow(
                    stringResource(R.string.detail_effective_time),
                    trip.effectiveHoursText,
                    valueColor = CondorinoColors.Amber,
                )
                DetailRow(
                    stringResource(R.string.detail_leave_needed),
                    trip.pattern.vacationDaysRequired.toString(),
                )
                DetailRow(
                    stringResource(R.string.detail_earliest_departure),
                    "%02d:%02d".format(
                        state.preferences.earliestReachableDeparture.hour,
                        state.preferences.earliestReachableDeparture.minute,
                    ),
                )
                DetailRow(
                    stringResource(R.string.detail_leave_home_at, state.preferences.homeCity),
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
                    stringResource(R.string.detail_home_again),
                    Formatting.time(time.homeArrivalLocal(trip.inbound)),
                )
                DetailRow(
                    stringResource(R.string.detail_transfer),
                    "${trip.destination.transferMinutes} min",
                )
            }

            Spacer(Modifier.height(18.dp))
            SectionHeader(stringResource(R.string.detail_why))
            Card {
                trip.score.reasons.forEach { reason ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text("•", color = CondorinoColors.Amber, fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(reason.text(), color = CondorinoColors.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
                if (trip.score.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    trip.score.warnings.forEach { warning ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text("⚠", color = CondorinoColors.Warning, fontSize = 12.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(warning.text(), color = CondorinoColors.Warning, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionHeader(stringResource(R.string.detail_score_breakdown))
            Card {
                trip.score.components.forEach { component ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                component.component.label(),
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
                            component.detail.text(),
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
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.action_back),
                tint = CondorinoColors.TextPrimary,
            )
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
                Pill(
                    stringResource(if (flight.isDirect) R.string.detail_nonstop else R.string.detail_connecting),
                    color = if (flight.isDirect) CondorinoColors.Mint else CondorinoColors.Warning,
                )
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
                stringResource(
                    R.string.detail_local_zones,
                    flight.origin.timeZoneId,
                    flight.destination.timeZoneId,
                ),
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
