package com.condorino.weekend.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.condorino.weekend.data.source.SourceStatus
import com.condorino.weekend.domain.model.Cabin
import com.condorino.weekend.domain.model.DestinationType
import com.condorino.weekend.domain.model.ScoreComponent
import com.condorino.weekend.domain.model.WeekendPattern
import com.condorino.weekend.ui.theme.CondorinoColors
import java.time.LocalTime

/** Everything from spec §7 (work times, buffers, limits), §8 (weights) and §3 (data sources). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenPrices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = state.preferences

    Column(
        modifier
            .fillMaxSize()
            .background(CondorinoColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 96.dp),
    ) {
        Text(
            "Einstellungen",
            color = CondorinoColors.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1).sp,
        )

        // ---------------------------------------------------------------- data sources
        SettingsSection(
            "Datenquellen",
            "Die App erfindet keine Flugdaten. Ohne konfigurierte Quelle zeigt sie klar " +
                "gekennzeichnete Beispieldaten.",
        ) {
            state.sources.forEach { source ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, color = CondorinoColors.TextPrimary, fontSize = 13.sp)
                        Text(
                            when (val s = source.status) {
                                is SourceStatus.Ready -> "Bereit"
                                is SourceStatus.NotConfigured -> "${s.reason} ${s.howToFix}"
                                is SourceStatus.Unavailable -> s.reason
                            },
                            color = when (source.status) {
                                is SourceStatus.Ready -> CondorinoColors.Mint
                                else -> CondorinoColors.TextTertiary
                            },
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            SwitchRow(
                label = "Beispieldaten erlauben",
                description = "Wenn keine echte Quelle liefert, werden erfundene Musterflüge " +
                    "angezeigt – immer rot als Beispieldaten markiert.",
                checked = state.allowDemoData,
                onCheckedChange = viewModel::setAllowDemoData,
            )
        }

        SettingsSection(
            "Eigener Flight-Feed",
            "JSON nach dem Condorino-Feed-Schema (siehe docs/CONDOR_DATA_SOURCES.md). " +
                "Funktioniert mit jeder HTTPS-URL, die dieses Format liefert.",
        ) {
            SwitchRow(
                label = "Feed aktiv",
                checked = state.feedConfig.enabled,
                onCheckedChange = { viewModel.updateFeedConfig(state.feedConfig.copy(enabled = it)) },
            )
            TextField(
                label = "Feed-URL",
                value = state.feedConfig.url,
                placeholder = "https://…/condor-feed.json",
                onValueChange = { viewModel.updateFeedConfig(state.feedConfig.copy(url = it.trim())) },
            )
            TextField(
                label = "Auth-Header (optional)",
                value = state.feedConfig.headerName,
                placeholder = "X-API-Key",
                onValueChange = { viewModel.updateFeedConfig(state.feedConfig.copy(headerName = it.trim())) },
            )
            TextField(
                label = "Auth-Wert (optional)",
                value = state.feedConfig.headerValue,
                onValueChange = { viewModel.updateFeedConfig(state.feedConfig.copy(headerValue = it.trim())) },
            )
        }

        SettingsSection(
            "Condor Developer API",
            "Condor betreibt ein Entwicklerportal (developer.condor.com). Der genaue Vertrag ist " +
                "registrierungspflichtig – trage ihn hier ein, sobald du Zugang hast. Die App rät " +
                "bewusst keine Endpunkte.",
        ) {
            SwitchRow(
                label = "API aktiv",
                checked = state.condorApiConfig.enabled,
                onCheckedChange = { viewModel.updateCondorApiConfig(state.condorApiConfig.copy(enabled = it)) },
            )
            TextField("Basis-URL", state.condorApiConfig.baseUrl, placeholder = "https://api.condor.com") {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(baseUrl = it.trim()))
            }
            TextField("Endpunkt-Pfad", state.condorApiConfig.path, placeholder = "z. B. /v1/flights") {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(path = it.trim()))
            }
            TextField("API-Key-Header", state.condorApiConfig.apiKeyHeader, placeholder = "Ocp-Apim-Subscription-Key") {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(apiKeyHeader = it.trim()))
            }
            TextField("API-Key", state.condorApiConfig.apiKey) {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(apiKey = it.trim()))
            }
            TextField("Pfad zur Flugliste in der Antwort", state.condorApiConfig.itemsPath, placeholder = "data.flights") {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(itemsPath = it.trim()))
            }
            Text(
                "Feldnamen der Antwort",
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            TextField("Feld: Abflughafen", state.condorApiConfig.fieldOrigin) {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(fieldOrigin = it.trim()))
            }
            TextField("Feld: Zielflughafen", state.condorApiConfig.fieldDestination) {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(fieldDestination = it.trim()))
            }
            TextField("Feld: Abflugzeit (ISO-8601)", state.condorApiConfig.fieldDeparture) {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(fieldDeparture = it.trim()))
            }
            TextField("Feld: Ankunftszeit (ISO-8601)", state.condorApiConfig.fieldArrival) {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(fieldArrival = it.trim()))
            }
            TextField("Feld: Flugnummer", state.condorApiConfig.fieldFlightNumber) {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(fieldFlightNumber = it.trim()))
            }
        }

        // ---------------------------------------------------------------- work / travel times
        SettingsSection(
            "Arbeitszeiten & Anfahrt",
            "Daraus berechnet die App den frühesten realistisch erreichbaren Flug.",
        ) {
            NumberField(
                "Arbeitsende (Stunde)",
                prefs.workEndTime.hour.toString(),
                { v -> v.toIntOrNull()?.coerceIn(0, 23)?.let { h ->
                    viewModel.updatePreferences { it.copy(workEndTime = LocalTime.of(h, prefs.workEndTime.minute)) }
                } },
            )
            NumberField(
                "Arbeitsende (Minute)",
                prefs.workEndTime.minute.toString(),
                { v -> v.toIntOrNull()?.coerceIn(0, 59)?.let { m ->
                    viewModel.updatePreferences { it.copy(workEndTime = LocalTime.of(prefs.workEndTime.hour, m)) }
                } },
            )
            TextField("Heimatort", prefs.homeCity) { v ->
                viewModel.updatePreferences { it.copy(homeCity = v) }
            }
            NumberField("Fahrzeit zum FRA", prefs.homeToAirportMinutes.toString(), { v ->
                v.toIntOrNull()?.let { m -> viewModel.updatePreferences { it.copy(homeToAirportMinutes = m) } }
            }, suffix = "min")
            NumberField("Puffer am FRA", prefs.airportBufferMinutes.toString(), { v ->
                v.toIntOrNull()?.let { m -> viewModel.updatePreferences { it.copy(airportBufferMinutes = m) } }
            }, suffix = "min")
            NumberField("Puffer am Zielflughafen (Rückflug)", prefs.returnAirportBufferMinutes.toString(), { v ->
                v.toIntOrNull()?.let { m -> viewModel.updatePreferences { it.copy(returnAirportBufferMinutes = m) } }
            }, suffix = "min")
            NumberField("Rückfahrt FRA → Zuhause", prefs.airportToHomeMinutes.toString(), { v ->
                v.toIntOrNull()?.let { m -> viewModel.updatePreferences { it.copy(airportToHomeMinutes = m) } }
            }, suffix = "min")

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CondorinoColors.SurfaceElevated)
                    .padding(12.dp),
            ) {
                Column {
                    Text(
                        "Frühester sinnvoller Abflug ab FRA",
                        color = CondorinoColors.TextTertiary,
                        fontSize = 11.sp,
                    )
                    Text(
                        "%02d:%02d".format(
                            prefs.earliestReachableDeparture.hour,
                            prefs.earliestReachableDeparture.minute,
                        ),
                        color = CondorinoColors.Amber,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        "= Arbeitsende + ${prefs.homeToAirportMinutes} min Anfahrt + " +
                            "${prefs.airportBufferMinutes} min Puffer",
                        color = CondorinoColors.TextTertiary,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- trip constraints
        SettingsSection("Reisevorgaben") {
            NumberField("Max. Flugzeit", prefs.maxFlightMinutes.toString(), { v ->
                v.toIntOrNull()?.let { m -> viewModel.updatePreferences { it.copy(maxFlightMinutes = m) } }
            }, suffix = "min")
            NumberField("Mindestanzahl Nächte", prefs.minNights.toString(), { v ->
                v.toIntOrNull()?.let { n -> viewModel.updatePreferences { it.copy(minNights = n) } }
            })
            NumberField("Maximale Anzahl Nächte", prefs.maxNights.toString(), { v ->
                v.toIntOrNull()?.let { n -> viewModel.updatePreferences { it.copy(maxNights = n) } }
            })
            NumberField("Maximales Budget", (prefs.maxBudgetCents / 100).toString(), { v ->
                v.toLongOrNull()?.let { e -> viewModel.updatePreferences { it.copy(maxBudgetCents = e * 100) } }
            }, suffix = "€")

            Text("Bevorzugte Reiseklasse", color = CondorinoColors.TextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Cabin.entries.forEach { cabin ->
                    FilterChip(
                        selected = prefs.preferredCabin == cabin,
                        onClick = { viewModel.updatePreferences { it.copy(preferredCabin = cabin) } },
                        label = { Text(cabin.label, fontSize = 12.sp) },
                        colors = chipColors(),
                    )
                }
            }

            Text("Bevorzugte Reisemuster", color = CondorinoColors.TextSecondary, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WeekendPattern.byPriority.forEach { pattern ->
                    val selected = pattern in prefs.enabledPatterns
                    FilterChip(
                        selected = selected,
                        onClick = {
                            viewModel.updatePreferences {
                                val next = if (selected) it.enabledPatterns - pattern
                                else it.enabledPatterns + pattern
                                it.copy(enabledPatterns = next.ifEmpty { setOf(pattern) })
                            }
                        },
                        label = { Text(pattern.label, fontSize = 12.sp) },
                        colors = chipColors(),
                    )
                }
            }

            Text("Bevorzugte Zieltypen", color = CondorinoColors.TextSecondary, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DestinationType.entries.forEach { type ->
                    val selected = type in prefs.enabledDestinationTypes
                    FilterChip(
                        selected = selected,
                        onClick = {
                            viewModel.updatePreferences {
                                val next = if (selected) it.enabledDestinationTypes - type
                                else it.enabledDestinationTypes + type
                                it.copy(enabledDestinationTypes = next.ifEmpty { setOf(type) })
                            }
                        },
                        label = { Text(type.label, fontSize = 12.sp) },
                        colors = chipColors(),
                    )
                }
            }
        }

        // ---------------------------------------------------------------- scoring weights
        SettingsSection(
            "Score-Gewichtung",
            "Die Gewichte werden normalisiert – du kannst jeden Regler einzeln verschieben.",
        ) {
            ScoreComponent.entries.forEach { component ->
                WeightSlider(
                    label = component.label,
                    value = prefs.weights.forComponent(component),
                ) { value ->
                    viewModel.updatePreferences {
                        it.copy(weights = it.weights.withComponent(component, value))
                    }
                }
            }
            Text(
                "Summe: ${(prefs.weights.total * 100).toInt()} % (wird auf 100 % normalisiert)",
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
            )
            OutlinedButton(onClick = {
                viewModel.updatePreferences {
                    it.copy(weights = com.condorino.weekend.domain.model.ScoreWeights.DEFAULT)
                }
            }) {
                Text("Standardgewichtung", color = CondorinoColors.Amber, fontSize = 12.sp)
            }
        }

        // ---------------------------------------------------------------- prices
        SettingsSection(
            "Standby-Preise (MyID Travel)",
            "Die App ruft keine MyID-Travel-Daten ab und speichert keine Zugangsdaten. " +
                "Preise trägst du selbst ein.",
        ) {
            Text(
                "${state.prices.count { it.value.hasAnyPrice }} Ziele mit hinterlegten Preisen",
                color = CondorinoColors.TextSecondary,
                fontSize = 13.sp,
            )
            OutlinedButton(onClick = onOpenPrices, modifier = Modifier.fillMaxWidth()) {
                Text("Standby-Preise verwalten", color = CondorinoColors.Amber, fontSize = 13.sp)
            }
        }

        SettingsSection("Datenschutz") {
            Text(
                "Condorino speichert ausschließlich lokal: deine Einstellungen, deine manuell " +
                    "eingegebenen Standby-Preise, Favoriten und einen Flugdaten-Cache. Es gibt " +
                    "keine Tracking-SDKs, keine Konten und keine Weitergabe an Dritte. " +
                    "Netzwerkzugriffe erfolgen nur auf die von dir konfigurierten Datenquellen.",
                color = CondorinoColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }

        Spacer(Modifier.height(40.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    containerColor = CondorinoColors.SurfaceElevated,
    labelColor = CondorinoColors.TextSecondary,
    selectedContainerColor = CondorinoColors.Amber,
    selectedLabelColor = CondorinoColors.Background,
)
