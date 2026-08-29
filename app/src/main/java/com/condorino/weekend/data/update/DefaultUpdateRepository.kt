package com.condorino.weekend.data.update

import android.content.Intent
import com.condorino.weekend.data.prefs.PreferencesStore
import com.condorino.weekend.domain.model.AppUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * See [UpdateRepository]. The only piece of state this class does not persist is the fetched
 * [AppUpdate] itself (notes, size, download URL) — only its tag and file name survive a process
 * restart, in [cachedUpdate] the richer object is kept for as long as the process holding it stays
 * alive, and the UI falls back to the plainer tag-only view once it is gone.
 */
class DefaultUpdateRepository(
    private val source: GitHubReleaseUpdateSource,
    private val downloader: UpdateDownloader,
    private val notifier: UpdateNotifier,
    private val preferencesStore: PreferencesStore,
) : UpdateRepository {

    private val _state = MutableStateFlow(UpdateUiState())
    override val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    @Volatile
    private var cachedUpdate: AppUpdate? = null

    override suspend fun initialize() {
        val prefs = preferencesStore.currentUpdatePrefs()
        _state.value = _state.value.copy(
            autoCheckEnabled = prefs.autoCheckEnabled,
            wifiOnly = prefs.wifiOnly,
            lastCheckedAt = prefs.lastCheckedAt,
        )

        val readyTag = prefs.readyTag
        val readyAsset = prefs.readyApkAssetName
        if (readyTag != null && readyAsset != null) {
            if (downloader.apkFile(readyAsset).exists()) {
                setPhase(UpdateUiState.Phase.Ready(readyTag, readyAsset, cachedUpdate.forTag(readyTag)))
                return
            }
            // The file is gone (cache cleared, storage wiped) — the prefs entry is stale.
            preferencesStore.clearReadyDownload()
        }

        val pendingId = prefs.pendingDownloadId
        val pendingTag = prefs.pendingDownloadTag
        val pendingAsset = prefs.pendingDownloadApkAssetName
        if (pendingId == null || pendingTag == null || pendingAsset == null) return

        when (val status = downloader.queryStatus(pendingId)) {
            is UpdateDownloader.DownloadStatus.Successful -> finishDownload(pendingTag, pendingAsset)
            is UpdateDownloader.DownloadStatus.Failed -> {
                preferencesStore.clearPendingDownload()
                setPhase(UpdateUiState.Phase.DownloadFailed(pendingTag, status.reasonCode))
            }
            is UpdateDownloader.DownloadStatus.Running ->
                setPhase(UpdateUiState.Phase.Downloading(pendingTag, status.percent, cachedUpdate.forTag(pendingTag)))
            is UpdateDownloader.DownloadStatus.Pending ->
                setPhase(UpdateUiState.Phase.Downloading(pendingTag, null, cachedUpdate.forTag(pendingTag)))
            is UpdateDownloader.DownloadStatus.Unknown -> preferencesStore.clearPendingDownload()
        }
    }

    override suspend fun checkNow() {
        setPhase(UpdateUiState.Phase.Checking)
        val now = Instant.now()
        preferencesStore.recordUpdateCheckedAt(now)
        _state.value = _state.value.copy(lastCheckedAt = now)

        when (val result = source.checkForUpdate()) {
            is UpdateCheckResult.Available -> {
                cachedUpdate = result.update
                setPhase(UpdateUiState.Phase.Available(result.update))
            }
            UpdateCheckResult.UpToDate -> setPhase(UpdateUiState.Phase.UpToDate)
            is UpdateCheckResult.NotConfigured -> setPhase(UpdateUiState.Phase.NotConfigured(result.reason))
            is UpdateCheckResult.Failure -> setPhase(UpdateUiState.Phase.Failure(result.reason, result.detail))
        }
    }

    override suspend fun startDownload() {
        val update = cachedUpdate ?: return
        val id = downloader.enqueue(update, allowMetered = !_state.value.wifiOnly)
        preferencesStore.recordPendingDownload(id, update.tagName, update.apkAssetName)
        setPhase(UpdateUiState.Phase.Downloading(update.tagName, percent = 0, update = update))
    }

    override fun install(): Boolean {
        val ready = _state.value.phase as? UpdateUiState.Phase.Ready ?: return false
        if (!downloader.canInstallPackages()) return false
        return downloader.installFile(ready.apkAssetName)
    }

    override fun canInstallPackages(): Boolean = downloader.canInstallPackages()

    override fun openUnknownSourcesSettings(): Intent = downloader.unknownSourcesSettingsIntent()

    override suspend fun setAutoCheckEnabled(enabled: Boolean) {
        preferencesStore.setUpdateAutoCheckEnabled(enabled)
        _state.value = _state.value.copy(autoCheckEnabled = enabled)
    }

    override suspend fun setWifiOnly(wifiOnly: Boolean) {
        preferencesStore.setUpdateWifiOnly(wifiOnly)
        _state.value = _state.value.copy(wifiOnly = wifiOnly)
    }

    override suspend fun backgroundCheckAndDownload() {
        val prefs = preferencesStore.currentUpdatePrefs()
        if (!prefs.autoCheckEnabled) return

        checkNow()
        val update = (_state.value.phase as? UpdateUiState.Phase.Available)?.update ?: return
        if (prefs.lastNotifiedTag == update.tagName) return // this exact release was already handled

        notifier.ensureChannel()
        notifier.notifyAvailable(update)
        preferencesStore.recordUpdateNotified(update.tagName)
        startDownload()
    }

    override suspend fun onDownloadFinished(downloadId: Long) {
        val prefs = preferencesStore.currentUpdatePrefs()
        val tag = prefs.pendingDownloadTag
        val asset = prefs.pendingDownloadApkAssetName
        if (prefs.pendingDownloadId != downloadId || tag == null || asset == null) return // stale/unrelated broadcast

        when (val status = downloader.queryStatus(downloadId)) {
            is UpdateDownloader.DownloadStatus.Successful -> finishDownload(tag, asset)
            is UpdateDownloader.DownloadStatus.Failed -> {
                preferencesStore.clearPendingDownload()
                setPhase(UpdateUiState.Phase.DownloadFailed(tag, status.reasonCode))
            }
            else -> Unit // the broadcast can race the cursor briefly settling; nothing to update yet
        }
    }

    private suspend fun finishDownload(tag: String, apkAssetName: String) {
        preferencesStore.recordDownloadReady(tag, apkAssetName)
        setPhase(UpdateUiState.Phase.Ready(tag, apkAssetName, cachedUpdate.forTag(tag)))
    }

    private fun AppUpdate?.forTag(tag: String) = this?.takeIf { it.tagName == tag }

    private fun setPhase(phase: UpdateUiState.Phase) {
        _state.value = _state.value.copy(phase = phase)
    }
}
