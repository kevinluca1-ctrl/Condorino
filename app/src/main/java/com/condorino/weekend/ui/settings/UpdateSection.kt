package com.condorino.weekend.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.condorino.weekend.BuildConfig
import com.condorino.weekend.R
import com.condorino.weekend.core.Formatting
import com.condorino.weekend.data.update.UpdateUiState
import com.condorino.weekend.domain.model.AppUpdate
import com.condorino.weekend.ui.text.relativeAge
import com.condorino.weekend.ui.theme.CondorinoColors

/**
 * The one place the user checks "am I on the latest build" and acts on it. See
 * [com.condorino.weekend.data.update.DefaultUpdateRepository] for what drives [state].
 */
@Composable
fun UpdateSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val update = state.updateState

    SettingsSection(
        stringResource(R.string.settings_updates),
        stringResource(
            if (BuildConfig.RELEASE_TAG.isNotBlank()) R.string.settings_updates_body
            else R.string.settings_updates_body_dev,
            BuildConfig.VERSION_NAME,
        ),
    ) {
        SwitchRow(
            label = stringResource(R.string.update_auto_check),
            description = stringResource(R.string.update_auto_check_body),
            checked = update.autoCheckEnabled,
            onCheckedChange = viewModel::setUpdateAutoCheckEnabled,
        )
        SwitchRow(
            label = stringResource(R.string.update_wifi_only),
            checked = update.wifiOnly,
            onCheckedChange = viewModel::setUpdateWifiOnly,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.update_last_checked, relativeAge(update.lastCheckedAt)),
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
            )
            TextButton(onClick = viewModel::checkForUpdate, enabled = update.phase != UpdateUiState.Phase.Checking) {
                Text(stringResource(R.string.update_check_now), color = CondorinoColors.Amber, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(2.dp))

        when (val phase = update.phase) {
            UpdateUiState.Phase.Idle -> Unit
            UpdateUiState.Phase.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = CondorinoColors.Amber)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.update_checking), color = CondorinoColors.TextTertiary, fontSize = 12.sp)
            }
            UpdateUiState.Phase.UpToDate -> Text(
                stringResource(R.string.update_up_to_date),
                color = CondorinoColors.Mint,
                fontSize = 12.sp,
            )
            is UpdateUiState.Phase.NotConfigured -> Text(
                phase.reason,
                color = CondorinoColors.TextTertiary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            is UpdateUiState.Phase.Failure -> Column {
                Text(phase.reason, color = CondorinoColors.Warning, fontSize = 12.sp, lineHeight = 16.sp)
                phase.detail?.let {
                    Text(it, color = CondorinoColors.TextTertiary, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
            is UpdateUiState.Phase.Available -> AvailableCard(phase.update, onDownload = viewModel::downloadUpdate)
            is UpdateUiState.Phase.Downloading -> DownloadingRow(
                title = phase.update?.releaseName ?: phase.tag,
                percent = phase.percent,
            )
            is UpdateUiState.Phase.Ready -> ReadyCard(
                title = phase.update?.releaseName ?: phase.tag,
                canInstall = viewModel.canInstallPackages(),
                onInstall = { viewModel.installUpdate() },
                onGrantAccess = { context.startActivity(viewModel.openUnknownSourcesSettingsIntent()) },
            )
            is UpdateUiState.Phase.DownloadFailed -> Column {
                Text(stringResource(R.string.update_download_failed), color = CondorinoColors.Warning, fontSize = 12.sp)
                OutlinedButton(onClick = viewModel::downloadUpdate) {
                    Text(stringResource(R.string.update_retry), color = CondorinoColors.Amber, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AvailableCard(update: AppUpdate, onDownload: () -> Unit) {
    val context = LocalContext.current
    Column {
        Text(
            stringResource(R.string.update_available_title, update.releaseName),
            color = CondorinoColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        update.notes?.let {
            Text(
                it,
                color = CondorinoColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (update.apkSizeBytes > 0) {
            Text(Formatting.megabytes(update.apkSizeBytes), color = CondorinoColors.TextTertiary, fontSize = 11.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDownload) {
                Text(stringResource(R.string.update_download), fontSize = 12.sp)
            }
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, update.htmlUrl.toUri()))
            }) {
                Text(stringResource(R.string.update_view_on_github), color = CondorinoColors.Amber, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DownloadingRow(title: String, percent: Int?) {
    Column {
        Text(
            stringResource(R.string.update_downloading, title),
            color = CondorinoColors.TextSecondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        if (percent != null) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = CondorinoColors.Amber,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CondorinoColors.Amber)
        }
    }
}

@Composable
private fun ReadyCard(title: String, canInstall: Boolean, onInstall: () -> Unit, onGrantAccess: () -> Unit) {
    Column {
        Text(
            stringResource(R.string.update_ready_title, title),
            color = CondorinoColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        if (canInstall) {
            Button(onClick = onInstall) {
                Text(stringResource(R.string.update_install), fontSize = 12.sp)
            }
        } else {
            Column {
                Text(
                    stringResource(R.string.update_grant_install_access_body),
                    color = CondorinoColors.TextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                OutlinedButton(onClick = onGrantAccess) {
                    Text(
                        stringResource(R.string.update_grant_install_access),
                        color = CondorinoColors.Amber,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
