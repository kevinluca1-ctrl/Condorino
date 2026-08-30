package com.condorino.weekend.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.condorino.weekend.domain.model.DataProvenance
import com.condorino.weekend.domain.repository.DataStatus
import com.condorino.weekend.ui.text.relativeAge
import com.condorino.weekend.ui.theme.CondorinoColors

/**
 * The persistent "how much can I trust this?" strip.
 *
 * Always renders the provenance and the last successful update time. When the data is demo data,
 * an unmissable red banner is shown above it — the app must never look like it is presenting
 * real live flights when it is not.
 */
@Composable
fun DataStatusBar(
    status: DataStatus,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    /** Flips OpenSky on with its shipped defaults — the one data source that needs no typing. */
    onEnableFreeSource: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {

        if (status.isDemo) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CondorinoColors.DemoBanner)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    stringResource(R.string.status_demo_banner_title),
                    color = CondorinoColors.DemoBannerText,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 0.6.sp,
                )
                Text(
                    stringResource(R.string.status_demo_banner_body),
                    color = CondorinoColors.DemoBannerText.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onEnableFreeSource != null) {
                        TextButton(
                            onClick = onEnableFreeSource,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        ) {
                            Text(
                                stringResource(R.string.status_enable_free_source),
                                color = CondorinoColors.Amber,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    if (onOpenSettings != null) {
                        if (onEnableFreeSource != null) {
                            Text(
                                "·",
                                color = CondorinoColors.DemoBannerText.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }
                        TextButton(onClick = onOpenSettings, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text(
                                stringResource(R.string.status_demo_setup),
                                color = CondorinoColors.Amber,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = if (status.isDemo) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProvenancePill(status.provenance)

            Column(Modifier.weight(1f)) {
                Text(
                    text = if (status.isOffline) {
                        stringResource(R.string.status_offline_last_updated, lastUpdate(status))
                    } else {
                        stringResource(R.string.status_last_updated, lastUpdate(status))
                    },
                    color = CondorinoColors.TextTertiary,
                    fontSize = 11.sp,
                )
                status.sourceLabel?.let {
                    Text(
                        text = it,
                        color = CondorinoColors.TextTertiary.copy(alpha = 0.75f),
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }

            if (status.isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = CondorinoColors.Amber,
                )
            } else {
                TextButton(onClick = onRefresh) {
                    Text(
                        stringResource(R.string.action_refresh_now),
                        color = CondorinoColors.Amber,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        status.errorMessage?.let { message ->
            Text(
                text = message,
                color = CondorinoColors.Warning,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun lastUpdate(status: DataStatus): String {
    val success = status.lastSuccess ?: return stringResource(R.string.status_never)
    return "${Formatting.clock(success)} (${relativeAge(success)})"
}

/** Compact inline variant used on secondary screens. */
@Composable
fun ProvenanceInline(provenance: DataProvenance?, modifier: Modifier = Modifier) {
    ProvenancePill(provenance, modifier)
}
