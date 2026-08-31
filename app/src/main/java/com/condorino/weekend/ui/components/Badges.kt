package com.condorino.weekend.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.condorino.weekend.R
import com.condorino.weekend.domain.model.Airlines
import com.condorino.weekend.domain.model.DataProvenance
import com.condorino.weekend.ui.theme.CondorinoColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Circular score chip: the single most important number on every card. */
@Composable
fun ScoreBadge(
    score: Double,
    modifier: Modifier = Modifier,
    size: Int = 56,
) {
    val color = CondorinoColors.forScore(score)
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(percent = 30))
            .background(color.copy(alpha = 0.14f))
            .border(1.5.dp, color.copy(alpha = 0.55f), RoundedCornerShape(percent = 30)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = score.roundToInt().toString(),
                color = color,
                fontWeight = FontWeight.Black,
                fontSize = (size * 0.38).sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.score_label),
                color = color.copy(alpha = 0.7f),
                fontWeight = FontWeight.Bold,
                fontSize = (size * 0.14).sp,
                letterSpacing = 0.8.sp,
            )
        }
    }
}

/** Small pill used for patterns, cabins, tags. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = CondorinoColors.TextSecondary,
    background: Color = CondorinoColors.SurfaceHigh,
) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * Renders the provenance of a piece of data. This is a required element, not decoration:
 * the app must always make clear whether the user is looking at live data, a timetable,
 * a cached copy or demo data (spec §4 / §29).
 */
@Composable
fun ProvenancePill(provenance: DataProvenance?, modifier: Modifier = Modifier) {
    val (label, color) = when (provenance) {
        DataProvenance.LIVE -> stringResource(R.string.provenance_live) to CondorinoColors.Mint
        DataProvenance.RECENTLY_UPDATED -> stringResource(R.string.provenance_recent) to CondorinoColors.Sky
        DataProvenance.SCHEDULE -> stringResource(R.string.provenance_schedule) to CondorinoColors.Amber
        DataProvenance.CACHED -> stringResource(R.string.provenance_cached) to CondorinoColors.TextTertiary
        DataProvenance.MANUAL -> stringResource(R.string.provenance_manual) to CondorinoColors.TextSecondary
        DataProvenance.DEMO -> stringResource(R.string.provenance_demo) to CondorinoColors.Danger
        null -> stringResource(R.string.provenance_none) to CondorinoColors.TextTertiary
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        Text(
            text = label,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
        )
    }
}


/**
 * The operating airline of a flight, as its code, revealing the full name on tap.
 *
 * A two- or three-letter designator is what fits on a card and what a staff traveller reads
 * fluently, but it is opaque to everyone else — and this app now searches ten airlines, so which
 * one a flight belongs to genuinely matters (it decides which standby price applies). Tapping
 * spells it out rather than making the code the only answer available.
 *
 * The name comes from the app's own airline list where the code is one it knows, so it reads
 * "Condor" whichever designator the source happened to report; [fallbackName] covers a carrier
 * outside that list, and the code itself is the last resort.
 */
@Composable
fun AirlineTag(
    airlineCode: String,
    fallbackName: String? = null,
    modifier: Modifier = Modifier,
) {
    val code = airlineCode.trim().uppercase().takeIf { it.isNotBlank() } ?: return
    val airline = Airlines.resolve(code)
    val label = airline?.icaoCode ?: code
    val fullName = airline?.displayName
        ?: fallbackName?.trim()?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
        ?: code

    var showName by remember { mutableStateOf(false) }
    // Briefly: long enough to read a name, short enough that it never has to be dismissed.
    LaunchedEffect(showName) {
        if (showName) {
            delay(2_500)
            showName = false
        }
    }

    Box(modifier) {
        Text(
            text = label,
            color = CondorinoColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(CondorinoColors.SurfaceHigh)
                .clickable { showName = !showName }
                .padding(horizontal = 8.dp, vertical = 4.dp)
                // Screen readers get the name outright — the popup is a sighted-user affordance,
                // and the code alone would be read out letter by letter.
                .semantics { contentDescription = fullName },
        )
        if (showName) {
            Popup(
                alignment = Alignment.TopCenter,
                // Sits above the tag rather than covering it.
                offset = IntOffset(0, -POPUP_LIFT_PX),
                onDismissRequest = { showName = false },
                properties = PopupProperties(focusable = true),
            ) {
                Text(
                    text = fullName,
                    color = CondorinoColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CondorinoColors.SurfaceElevated)
                        .border(1.dp, CondorinoColors.Outline, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** How far above the tag the name bubble sits, in raw pixels — roughly one tag height. */
private const val POPUP_LIFT_PX = 96
