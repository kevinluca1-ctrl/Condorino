package com.condorino.weekend.data.update

import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

/**
 * Orchestrates the update flow: checking GitHub, starting and tracking a download, and handing off
 * to the system installer. Kept as an interface mainly so [com.condorino.weekend.ui.settings.SettingsViewModel]
 * depends on a contract rather than the download-manager plumbing directly.
 */
interface UpdateRepository {

    val state: StateFlow<UpdateUiState>

    /** Restores in-flight state from prefs (a pending or completed download) after process start. */
    suspend fun initialize()

    /** User- or worker-initiated check. Always updates [state], never throws. */
    suspend fun checkNow()

    /** Starts (or restarts) the download for whatever [UpdateUiState.Phase.Available] currently holds. */
    suspend fun startDownload()

    /**
     * Launches the system installer for a completed download. Returns false if the system's
     * "install unknown apps" special access has not been granted for this app yet — the caller
     * should send the user to [openUnknownSourcesSettings] first in that case.
     */
    fun install(): Boolean

    fun canInstallPackages(): Boolean
    fun openUnknownSourcesSettings(): Intent

    suspend fun setAutoCheckEnabled(enabled: Boolean)
    suspend fun setWifiOnly(wifiOnly: Boolean)

    /** Runs a full check-and-download cycle for the background worker; posts a notification if new. */
    suspend fun backgroundCheckAndDownload()

    /** Called by [com.condorino.weekend.data.update.UpdateDownloadReceiver] when a tracked download finishes. */
    suspend fun onDownloadFinished(downloadId: Long)
}
