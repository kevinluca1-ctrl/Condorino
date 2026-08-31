package com.condorino.weekend.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.condorino.weekend.ui.text.label
import com.condorino.weekend.ui.text.leaveDaysLabel
import com.condorino.weekend.ui.text.nightsLabel
import com.condorino.weekend.ui.text.text
import com.condorino.weekend.ui.theme.CondorinoColors

/**
 * The list card used on Home, Favourites and the multi-weekend results.
 *
 * Shows, in one glance: destination + flag, pattern, both flight times, nights, effective time,
 * both standby prices and the score — exactly the fields listed in spec §12.
 */
@Composable
fun TripCard(
    trip: WeekendTrip,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rank: Int? = null,
    onToggleFavorite: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CondorinoColors.Surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {

            if (rank != null) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(CondorinoColors.SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$rank",
                        color = CondorinoColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(10.dp))
            }

            Text(trip.destination.airport.flag, fontSize = 24.sp)
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    trip.destination.airport.city,
                    color = CondorinoColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "FRA → ${trip.iata}",
                        color = CondorinoColors.TextTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("·", color = CondorinoColors.TextTertiary, fontSize = 11.sp)
                    Text(
                        trip.pattern.label(),
                        color = CondorinoColors.Sky,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (onToggleFavorite != null) {
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (trip.destination.isFavorite) Icons.Filled.Favorite
                        else Icons.Filled.FavoriteBorder,
                        contentDescription = stringResource(
                            if (trip.destination.isFavorite) R.string.favorite_remove else R.string.favorite_add,
                        ),
                        tint = if (trip.destination.isFavorite) CondorinoColors.Danger else CondorinoColors.TextTertiary,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }

            ScoreBadge(trip.score.total, size = 52)
        }

        Spacer(Modifier.height(12.dp))

        // Flight times row — the two numbers that actually decide the trip.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CondorinoColors.SurfaceElevated)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegTime(
                label = stringResource(R.string.leg_out),
                time = Formatting.time(trip.outbound.departureLocal),
                date = Formatting.dayDate(trip.outbound.departureDateLocal),
                sub = stringResource(R.string.leg_arrives, Formatting.time(trip.outbound.arrivalLocal)),
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✈", color = CondorinoColors.Amber, fontSize = 15.sp)
                Text(
                    nightsLabel(trip.nights),
                    color = CondorinoColors.TextTertiary,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            LegTime(
                label = stringResource(R.string.leg_back),
                time = Formatting.time(trip.inbound.departureLocal),
                date = Formatting.dayDate(trip.inbound.departureLocal.toLocalDate()),
                sub = stringResource(R.string.leg_arrives, Formatting.time(trip.inbound.arrivalLocal)),
                alignEnd = true,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(stringResource(R.string.card_time_on_site, trip.effectiveHoursText))
            // Which airline this trip is on decides which standby price applies to it, so it
            // belongs on the card rather than only in the detail view.
            AirlineTag(trip.outbound.airlineCode, trip.outbound.airline)
            trip.economyPrice?.let {
                Pill(stringResource(R.string.card_economy, it.format()), color = CondorinoColors.Mint)
            }
            trip.businessPrice?.let {
                Pill(stringResource(R.string.card_business, it.format()), color = CondorinoColors.Sky)
            }
            if (trip.economyPrice == null && trip.businessPrice == null) {
                Pill(stringResource(R.string.card_no_standby_price), color = CondorinoColors.Warning)
            }
        }

        trip.score.reasons.firstOrNull()?.let { reason ->
            Text(
                text = "\u201E${reason.text()}\u201C",
                color = CondorinoColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 9.dp),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProvenancePill(trip.provenance)
            Spacer(Modifier.weight(1f))
            Text(
                if (trip.pattern.vacationDaysRequired == 0) stringResource(R.string.leave_none)
                else leaveDaysLabel(trip.pattern.vacationDaysRequired.toDouble()),
                color = if (trip.pattern.vacationDaysRequired == 0) CondorinoColors.Mint
                else CondorinoColors.Warning,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LegTime(
    label: String,
    time: String,
    date: String,
    sub: String,
    alignEnd: Boolean = false,
) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(label, color = CondorinoColors.TextTertiary, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
        Text(time, color = CondorinoColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(date, color = CondorinoColors.TextTertiary, fontSize = 10.sp)
        Text(sub, color = CondorinoColors.TextTertiary.copy(alpha = 0.8f), fontSize = 10.sp)
    }
}
