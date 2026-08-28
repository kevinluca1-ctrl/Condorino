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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.condorino.weekend.domain.model.Destination
import com.condorino.weekend.domain.model.PriceEntryMode
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.ui.components.EmptyState
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

    // Destinations known from flight data, plus any destination that already has a saved price.
    val entries: List<Pair<String, Destination?>> = remember(state.destinations, state.prices) {
        val known = state.destinations.associateBy { it.iata }
        val iatas = (known.keys + state.prices.keys).sorted()
        iatas.map { it to known[it] }
    }

    Box(modifier.fillMaxSize().background(CondorinoColors.Background)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = CondorinoColors.TextPrimary)
                    }
                    Text("Einstellungen", color = CondorinoColors.TextSecondary, fontSize = 13.sp)
                }
                Text(
                    "Standby-Preise",
                    color = CondorinoColors.TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1).sp,
                )
                Text(
                    "Trage die Preise ein, die du in MyID Travel siehst. Die App fragt MyID Travel " +
                        "nicht ab und speichert keine Zugangsdaten.",
                    color = CondorinoColors.TextTertiary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (entries.isEmpty()) {
                item {
                    EmptyState(
                        emoji = "💶",
                        title = "Noch keine Ziele bekannt",
                        message = "Sobald Flugdaten geladen wurden, erscheinen hier alle erreichbaren " +
                            "Ziele und du kannst für jedes einen Standby-Preis hinterlegen.",
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
                    price.economyRoundTrip?.let { "Eco ${it.format()}" } ?: "Eco –",
                    color = if (price.economyRoundTrip != null) CondorinoColors.Mint else CondorinoColors.TextTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    price.businessRoundTrip?.let { "Biz ${it.format()}" } ?: "Biz –",
                    color = if (price.businessRoundTrip != null) CondorinoColors.Sky else CondorinoColors.TextTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (!expanded) return@Column

        Spacer(Modifier.height(12.dp))

        Text("Eingabemodus", color = CondorinoColors.TextSecondary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            PriceEntryMode.entries.forEach { mode ->
                FilterChip(
                    selected = price.mode == mode,
                    onClick = { onSave(price.copy(mode = mode)) },
                    label = { Text(mode.label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = CondorinoColors.SurfaceElevated,
                        labelColor = CondorinoColors.TextSecondary,
                        selectedContainerColor = CondorinoColors.Amber,
                        selectedLabelColor = CondorinoColors.Background,
                    ),
                )
            }
        }

        EuroField("Economy Hinflug", price.economyOutboundCents) {
            onSave(price.copy(economyOutboundCents = it))
        }
        if (price.mode == PriceEntryMode.PER_SEGMENT) {
            EuroField("Economy Rückflug", price.economyInboundCents) {
                onSave(price.copy(economyInboundCents = it))
            }
        }
        EuroField("Business Hinflug", price.businessOutboundCents) {
            onSave(price.copy(businessOutboundCents = it))
        }
        if (price.mode == PriceEntryMode.PER_SEGMENT) {
            EuroField("Business Rückflug", price.businessInboundCents) {
                onSave(price.copy(businessInboundCents = it))
            }
        }
        EuroField("Steuern & Gebühren (optional)", price.taxesCents) {
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
                Text("Gesamtpreis Roundtrip", color = CondorinoColors.TextTertiary, fontSize = 10.sp)
                Text(
                    "Economy: ${price.economyRoundTrip?.format() ?: "–"}   ·   " +
                        "Business: ${price.businessRoundTrip?.format() ?: "–"}",
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
    val text = cents?.let { (it / 100).toString() } ?: ""
    NumberField(
        label = label,
        value = text,
        suffix = "€",
        onValueChange = { raw -> onChange(raw.toLongOrNull()?.times(100)) },
        modifier = Modifier.padding(vertical = 3.dp),
    )
}
