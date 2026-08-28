package com.condorino.weekend.ui.settings

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import com.condorino.weekend.R
import com.condorino.weekend.core.MoneyInput
import com.condorino.weekend.domain.model.Destination
import com.condorino.weekend.domain.model.PriceEntryMode
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.ui.components.AirportSearch
import com.condorino.weekend.ui.components.EmptyState
import com.condorino.weekend.ui.components.SearchField
import com.condorino.weekend.ui.text.label
import com.condorino.weekend.ui.theme.CondorinoColors

/**
 * Manual standby-price entry (spec §6). Per destination: economy/business, outbound/inbound,
 * optional taxes, and whether the entered figure is per segment or for the whole round trip.
 */
@Composable
fun StandbyPricesScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(state.focusPriceIata) }
    var query by rememberSaveable { mutableStateOf("") }

    // Reachable destinations and anything that already has a price come first; the search reaches
    // across the whole public reference, so a destination the app has not seen yet can still get a
    // price entered ahead of time.
    val entries: List<Pair<String, Destination?>> = remember(state.destinations, state.prices, state.allAirports, query) {
        val known = state.destinations.associateBy { it.iata }
        val priced = state.prices.filterValues { it.hasAnyPrice }.keys

        if (query.isBlank()) {
            (known.keys + priced).sortedWith(
                compareByDescending<String> { it in priced }
                    .thenBy { known[it]?.airport?.city ?: it },
            ).map { it to known[it] }
        } else {
            val pool = state.allAirports.ifEmpty { known.values.map { it.airport } }
            AirportSearch.rank(
                airports = pool,
                query = query,
                limit = 60,
                boost = { airport ->
                    when {
                        airport.iata in priced -> 120
                        airport.iata in known -> 60
                        else -> 0
                    }
                },
            ).map { it.iata to (known[it.iata] ?: Destination(airport = it)) }
        }
    }

    Box(modifier.fillMaxSize().background(CondorinoColors.Background)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                            tint = CondorinoColors.TextPrimary,
                        )
                    }
                    Text(
                        stringResource(R.string.settings_title),
                        color = CondorinoColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
                Text(
                    stringResource(R.string.prices_title),
                    color = CondorinoColors.TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1).sp,
                )
                Text(
                    stringResource(R.string.prices_body),
                    color = CondorinoColors.TextTertiary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.prices_search_hint),
                    clearContentDescription = stringResource(R.string.action_clear_search),
                )
                Spacer(Modifier.height(4.dp))
            }

            if (entries.isEmpty()) {
                item {
                    EmptyState(
                        emoji = "💶",
                        title = stringResource(
                            if (query.isBlank()) R.string.prices_empty_title
                            else R.string.prices_no_match_title,
                        ),
                        message = stringResource(
                            if (query.isBlank()) R.string.prices_empty_body
                            else R.string.prices_no_match_body,
                        ),
                    )
                }
            }

            items(entries, key = { it.first }) { (iata, destination) ->
                PriceCard(
                    iata = iata,
                    destination = destination,
                    price = state.prices[iata] ?: StandbyPrice.empty(iata),
                    expanded = expanded == iata,
                    onToggle = { expanded = if (expanded == iata) null else iata },
                    onSave = viewModel::savePrice,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriceCard(
    iata: String,
    destination: Destination?,
    price: StandbyPrice,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSave: (StandbyPrice) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CondorinoColors.Surface)
            .padding(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(destination?.airport?.flag ?: "🏳", fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    destination?.airport?.city ?: iata,
                    color = CondorinoColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(iata, color = CondorinoColors.TextTertiary, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    price.economyRoundTrip?.let { stringResource(R.string.card_economy, it.format()) }
                        ?: stringResource(R.string.card_economy, stringResource(R.string.value_dash)),
                    color = if (price.economyRoundTrip != null) CondorinoColors.Mint else CondorinoColors.TextTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    price.businessRoundTrip?.let { stringResource(R.string.card_business, it.format()) }
                        ?: stringResource(R.string.card_business, stringResource(R.string.value_dash)),
                    color = if (price.businessRoundTrip != null) CondorinoColors.Sky else CondorinoColors.TextTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (!expanded) return@Column

        Spacer(Modifier.height(12.dp))

        Text(stringResource(R.string.prices_entry_mode), color = CondorinoColors.TextSecondary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            PriceEntryMode.entries.forEach { mode ->
                FilterChip(
                    selected = price.mode == mode,
                    onClick = { onSave(price.copy(mode = mode)) },
                    label = { Text(mode.label(), fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = CondorinoColors.SurfaceElevated,
                        labelColor = CondorinoColors.TextSecondary,
                        selectedContainerColor = CondorinoColors.Amber,
                        selectedLabelColor = CondorinoColors.Background,
                    ),
                )
            }
        }

        EuroField(stringResource(R.string.prices_eco_out), price.economyOutboundCents) {
            onSave(price.copy(economyOutboundCents = it))
        }
        if (price.mode == PriceEntryMode.PER_SEGMENT) {
            EuroField(stringResource(R.string.prices_eco_in), price.economyInboundCents) {
                onSave(price.copy(economyInboundCents = it))
            }
        }
        EuroField(stringResource(R.string.prices_biz_out), price.businessOutboundCents) {
            onSave(price.copy(businessOutboundCents = it))
        }
        if (price.mode == PriceEntryMode.PER_SEGMENT) {
            EuroField(stringResource(R.string.prices_biz_in), price.businessInboundCents) {
                onSave(price.copy(businessInboundCents = it))
            }
        }
        EuroField(stringResource(R.string.prices_taxes), price.taxesCents) {
            onSave(price.copy(taxesCents = it))
        }

        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CondorinoColors.SurfaceElevated)
                .padding(10.dp),
        ) {
            Column {
                Text(
                    stringResource(R.string.prices_total),
                    color = CondorinoColors.TextTertiary,
                    fontSize = 10.sp,
                )
                val dash = stringResource(R.string.value_dash)
                Text(
                    stringResource(
                        R.string.prices_total_value,
                        price.economyRoundTrip?.format() ?: dash,
                        price.businessRoundTrip?.format() ?: dash,
                    ),
                    color = CondorinoColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun EuroField(label: String, cents: Long?, onChange: (Long?) -> Unit) {
    DecimalField(
        label = label,
        value = MoneyInput.formatCentsForEditing(cents),
        suffix = "€",
        onValueChange = { raw -> onChange(MoneyInput.parseEuroToCents(raw)) },
        modifier = Modifier.padding(vertical = 3.dp),
    )
}
