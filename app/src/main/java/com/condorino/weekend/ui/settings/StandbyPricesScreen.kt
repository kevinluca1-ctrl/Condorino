package com.condorino.weekend.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.condorino.weekend.R
import com.condorino.weekend.core.MoneyInput
import com.condorino.weekend.domain.model.Airline
import com.condorino.weekend.domain.model.Airlines
import com.condorino.weekend.domain.model.Destination
import com.condorino.weekend.domain.model.PriceEntryMode
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.ui.components.AirportSearch
import com.condorino.weekend.ui.components.EmptyState
import com.condorino.weekend.ui.components.SearchField
import com.condorino.weekend.ui.text.label
import com.condorino.weekend.ui.theme.CondorinoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDate

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Prices only ever live in this device's local database (spec §26 — no cloud account of this
    // app's own). Export/import goes through Android's own document picker instead: the user saves
    // the file wherever they already trust — their own Google Drive, Dropbox, local storage —
    // without the app ever needing a cloud API key or asking for an account.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = viewModel.buildPricesExportJson()
            viewModel.reportPricesExportResult(writeTextToUri(context, uri, json))
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.importPrices(readTextFromUri(context, uri))
    }

    // Grouped by destination: a destination can now hold more than one price, one per airline
    // (Condor and a Lufthansa Group carrier both flying it, priced separately) — see PriceCard.
    val pricesByDestination: Map<String, Map<String, StandbyPrice>> = remember(state.prices) {
        state.prices.values.groupBy { it.destinationIata }.mapValues { (_, prices) -> prices.associateBy { it.airlineIcao } }
    }
    // Condor is always priceable; a Lufthansa Group carrier only once it's opted into search
    // (Settings → Airlines) — no point offering a price field for an airline nothing ever searches.
    val availableAirlines: List<Airline> = remember(state.selectedLufthansaGroupCodes) {
        listOf(Airlines.CONDOR) + Airlines.LUFTHANSA_GROUP.filter { it.icaoCode in state.selectedLufthansaGroupCodes }
    }

    // Reachable destinations and anything that already has a price come first; the search reaches
    // across the whole public reference, so a destination the app has not seen yet can still get a
    // price entered ahead of time.
    val entries: List<Pair<String, Destination?>> = remember(state.destinations, pricesByDestination, state.allAirports, query) {
        val known = state.destinations.associateBy { it.iata }
        val priced = pricesByDestination.filterValues { byAirline -> byAirline.values.any { it.hasAnyPrice } }.keys

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
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            exportLauncher.launch("condorino-prices-${LocalDate.now()}.json")
                        },
                    ) {
                        Text(stringResource(R.string.prices_export), color = CondorinoColors.Amber, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                        Text(stringResource(R.string.prices_import), color = CondorinoColors.Amber, fontSize = 12.sp)
                    }
                }
                val ioStatusText = when (val status = state.priceIoStatus) {
                    PriceIoStatus.Idle -> null
                    PriceIoStatus.ExportSucceeded -> stringResource(R.string.prices_export_success)
                    PriceIoStatus.ExportFailed -> stringResource(R.string.prices_export_failed)
                    is PriceIoStatus.ImportSucceeded -> stringResource(R.string.prices_import_success, status.count)
                    PriceIoStatus.ImportFailed -> stringResource(R.string.prices_import_failed)
                }
                if (ioStatusText != null) {
                    LaunchedEffect(state.priceIoStatus) {
                        delay(4000)
                        viewModel.clearPriceIoStatus()
                    }
                    Text(
                        ioStatusText,
                        color = if (state.priceIoStatus is PriceIoStatus.ExportFailed ||
                            state.priceIoStatus is PriceIoStatus.ImportFailed
                        ) {
                            CondorinoColors.Warning
                        } else {
                            CondorinoColors.Mint
                        },
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
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
                    pricesByAirline = pricesByDestination[iata].orEmpty(),
                    availableAirlines = availableAirlines,
                    // Only the card the user was sent to opens on a specific airline; every other
                    // card keeps its own default.
                    initialAirline = state.focusPriceAirlineIcao.takeIf { iata == state.focusPriceIata },
                    expanded = expanded == iata,
                    onToggle = { expanded = if (expanded == iata) null else iata },
                    onSave = viewModel::savePrice,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PriceCard(
    iata: String,
    destination: Destination?,
    /** Keyed by [Airline.icaoCode] — up to one entry per airline actually flying this route. */
    pricesByAirline: Map<String, StandbyPrice>,
    /** Condor first, then whichever Lufthansa Group carriers are opted into search. */
    availableAirlines: List<Airline>,
    /** The airline to open on when the user arrived here from a specific trip — so "Add standby
     *  price" on, say, a Lufthansa trip fills in Lufthansa's fare and not Condor's, which that
     *  trip would never have used. Null when the screen was opened on its own. */
    initialAirline: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSave: (StandbyPrice) -> Unit,
) {
    // Which airline's fields this card is currently showing/editing — Condor by default, since
    // it's always available and is this app's own baseline. Reset per destination so switching
    // cards doesn't leave a stale airline selected on the next one.
    // Ignored if that airline isn't on offer here (it was deselected since the trip was cached),
    // so the card can never open on a chip the user has no way to see or change.
    val openOn = initialAirline?.takeIf { code -> availableAirlines.any { it.icaoCode == code } }
    var selectedAirline by rememberSaveable(iata, openOn) {
        mutableStateOf(openOn ?: Airlines.CONDOR.icaoCode)
    }
    val price = pricesByAirline[selectedAirline] ?: StandbyPrice.empty(iata, selectedAirline)
    // The collapsed summary always shows Condor's own price — the one entry every destination can
    // have — rather than whichever airline happened to be selected last time this card was open.
    val summary = pricesByAirline[Airlines.CONDOR.icaoCode] ?: StandbyPrice.empty(iata)

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
                    summary.economyRoundTrip?.let { stringResource(R.string.card_economy, it.format()) }
                        ?: stringResource(R.string.card_economy, stringResource(R.string.value_dash)),
                    color = if (summary.economyRoundTrip != null) CondorinoColors.Mint else CondorinoColors.TextTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    summary.businessRoundTrip?.let { stringResource(R.string.card_business, it.format()) }
                        ?: stringResource(R.string.card_business, stringResource(R.string.value_dash)),
                    color = if (summary.businessRoundTrip != null) CondorinoColors.Sky else CondorinoColors.TextTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (!expanded) return@Column

        Spacer(Modifier.height(12.dp))

        // Only shown once at least one Lufthansa Group carrier is opted into search — with Condor
        // the only option there is nothing to choose between, so the chip row would just be noise.
        if (availableAirlines.size > 1) {
            Text(stringResource(R.string.prices_airline), color = CondorinoColors.TextSecondary, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                availableAirlines.forEach { airline ->
                    FilterChip(
                        selected = selectedAirline == airline.icaoCode,
                        onClick = { selectedAirline = airline.icaoCode },
                        label = { Text(airline.displayName, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = CondorinoColors.SurfaceElevated,
                            labelColor = CondorinoColors.TextSecondary,
                            selectedContainerColor = CondorinoColors.Amber,
                            selectedLabelColor = CondorinoColors.Background,
                        ),
                    )
                }
            }
        }

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

/** Writes to wherever the user pointed the system's "create document" picker. */
private fun writeTextToUri(context: Context, uri: Uri, text: String): Boolean =
    runCatching {
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: return false
        true
    }.getOrDefault(false)

/** Reads from wherever the user pointed the system's "open document" picker; null on any failure. */
private fun readTextFromUri(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
        }
    }.getOrNull()
