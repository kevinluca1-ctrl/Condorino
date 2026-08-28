package com.condorino.weekend.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.condorino.weekend.R
import com.condorino.weekend.domain.model.Cabin
import com.condorino.weekend.domain.model.DestinationType
import com.condorino.weekend.domain.model.Money
import com.condorino.weekend.domain.model.WeekendPattern
import com.condorino.weekend.ui.planner.TripFilters
import com.condorino.weekend.ui.text.label
import com.condorino.weekend.ui.theme.CondorinoColors
import kotlin.math.roundToInt

/** Filters from spec §13: patterns, cabin, max price, min score, destination types. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    filters: TripFilters,
    onDismiss: () -> Unit,
    onChange: (TripFilters) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CondorinoColors.Surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.filter_title),
                    color = CondorinoColors.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                )
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onChange(TripFilters()) }) {
                        Text(stringResource(R.string.filter_reset), color = CondorinoColors.Amber, fontSize = 13.sp)
                    }
                }
            }

            SectionTitle(stringResource(R.string.filter_days))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WeekendPattern.byPriority.forEach { pattern ->
                    val selected = pattern in filters.patterns
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = if (selected) filters.patterns - pattern else filters.patterns + pattern
                            onChange(filters.copy(patterns = next.ifEmpty { WeekendPattern.entries.toSet() }))
                        },
                        label = { Text(pattern.label(), fontSize = 13.sp) },
                        colors = chipColors(),
                    )
                }
                FilterChip(
                    selected = filters.patterns.size == WeekendPattern.entries.size,
                    onClick = { onChange(filters.copy(patterns = WeekendPattern.entries.toSet())) },
                    label = { Text(stringResource(R.string.filter_all), fontSize = 13.sp) },
                    colors = chipColors(),
                )
            }

            SectionTitle(stringResource(R.string.filter_cabin))
            Row {
                Cabin.entries.forEach { cabin ->
                    val checked = cabin in filters.cabins
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                val next = if (checked) filters.cabins - cabin else filters.cabins + cabin
                                onChange(filters.copy(cabins = next.ifEmpty { Cabin.entries.toSet() }))
                            },
                            colors = CheckboxDefaults.colors(checkedColor = CondorinoColors.Amber),
                        )
                        Text(cabin.label(), color = CondorinoColors.TextSecondary, fontSize = 13.sp)
                    }
                }
            }

            SectionTitle(
                stringResource(
                    R.string.filter_max_price,
                    filters.maxPriceCents?.let { Money(it).format() }
                        ?: stringResource(R.string.filter_unlimited),
                ),
            )
            Slider(
                value = (filters.maxPriceCents ?: MAX_PRICE_CENTS).toFloat(),
                onValueChange = {
                    val cents = it.roundToInt().toLong()
                    onChange(filters.copy(maxPriceCents = if (cents >= MAX_PRICE_CENTS) null else cents))
                },
                valueRange = 0f..MAX_PRICE_CENTS.toFloat(),
                steps = 19,
                colors = SliderDefaults.colors(
                    thumbColor = CondorinoColors.Amber,
                    activeTrackColor = CondorinoColors.Amber,
                    inactiveTrackColor = CondorinoColors.SurfaceHigh,
                ),
            )

            SectionTitle(stringResource(R.string.filter_min_score, filters.minScore))
            Slider(
                value = filters.minScore.toFloat(),
                onValueChange = { onChange(filters.copy(minScore = it.roundToInt())) },
                valueRange = 0f..100f,
                steps = 19,
                colors = SliderDefaults.colors(
                    thumbColor = CondorinoColors.Amber,
                    activeTrackColor = CondorinoColors.Amber,
                    inactiveTrackColor = CondorinoColors.SurfaceHigh,
                ),
            )

            SectionTitle(stringResource(R.string.filter_destination_type))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DestinationType.entries.forEach { type ->
                    val selected = type in filters.destinationTypes
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = if (selected) filters.destinationTypes - type
                            else filters.destinationTypes + type
                            onChange(
                                filters.copy(
                                    destinationTypes = next.ifEmpty { DestinationType.entries.toSet() },
                                ),
                            )
                        },
                        label = { Text(type.label(), fontSize = 13.sp) },
                        colors = chipColors(),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Checkbox(
                    checked = filters.favoritesOnly,
                    onCheckedChange = { onChange(filters.copy(favoritesOnly = it)) },
                    colors = CheckboxDefaults.colors(checkedColor = CondorinoColors.Amber),
                )
                Text(stringResource(R.string.filter_favorites_only), color = CondorinoColors.TextSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = CondorinoColors.TextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    containerColor = CondorinoColors.SurfaceElevated,
    labelColor = CondorinoColors.TextSecondary,
    selectedContainerColor = CondorinoColors.Amber,
    selectedLabelColor = CondorinoColors.Background,
)

private const val MAX_PRICE_CENTS = 50_000L
