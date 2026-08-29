package com.condorino.weekend.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.condorino.weekend.BuildConfig
import com.condorino.weekend.R
import com.condorino.weekend.ui.theme.CondorinoColors

/** App identity, version and the links a user actually looks for at the bottom of Settings. */
@Composable
fun AboutSection() {
    val context = LocalContext.current
    val repoUrl = "https://github.com/${BuildConfig.UPDATE_REPO_OWNER}/${BuildConfig.UPDATE_REPO_NAME}"

    SettingsSection(stringResource(R.string.settings_about)) {
        Text(stringResource(R.string.app_name), color = CondorinoColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.about_tagline), color = CondorinoColors.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        Text(
            stringResource(
                R.string.about_version,
                BuildConfig.VERSION_NAME,
                BuildConfig.RELEASE_TAG.ifBlank { stringResource(R.string.about_version_dev) },
            ),
            color = CondorinoColors.TextTertiary,
            fontSize = 11.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, repoUrl.toUri())) }) {
                Text(stringResource(R.string.about_view_source), color = CondorinoColors.Amber, fontSize = 12.sp)
            }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, "$repoUrl/releases".toUri())) }) {
                Text(stringResource(R.string.about_view_releases), color = CondorinoColors.Amber, fontSize = 12.sp)
            }
        }
        Text(stringResource(R.string.about_credits), color = CondorinoColors.TextTertiary, fontSize = 10.sp, lineHeight = 14.sp)
    }
}
