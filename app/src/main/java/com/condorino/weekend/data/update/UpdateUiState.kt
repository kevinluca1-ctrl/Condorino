package com.condorino.weekend.data.update

import com.condorino.weekend.domain.model.AppUpdate
import java.time.Instant

/**
 * Everything the Settings screen needs to render the update flow, combining the live check result
 * with what is persisted (so the state survives navigating away and, for an in-progress or
 * completed download, an app restart).
 */
data class UpdateUiState(
    val autoCheckEnabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val lastCheckedAt: Instant? = null,
    val phase: Phase = Phase.Idle,
) {
    sealed interface Phase {
        data object Idle : Phase
        data object Checking : Phase
        data object UpToDate : Phase
        data class NotConfigured(val reason: String) : Phase
        data class Failure(val reason: String, val detail: String? = null) : Phase
        data class Available(val update: AppUpdate) : Phase

        /**
         * [update] is null only when this state was rebuilt from persisted prefs after a process
         * restart, where only the tag and file name survive — the UI falls back to a plainer label.
         */
        data class Downloading(val tag: String, val percent: Int?, val update: AppUpdate? = null) : Phase
        data class Ready(val tag: String, val apkAssetName: String, val update: AppUpdate? = null) : Phase
        data class DownloadFailed(val tag: String, val reasonCode: Int) : Phase
    }
}
