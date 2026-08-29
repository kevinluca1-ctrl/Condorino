package com.condorino.weekend.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.condorino.weekend.R
import com.condorino.weekend.domain.model.DataProvenance
import com.condorino.weekend.ui.theme.CondorinoColors
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
