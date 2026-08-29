package com.condorino.weekend.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.condorino.weekend.domain.model.AppUpdate
import java.io.File

/**
 * The Android-specific half of the update flow: everything [DownloadManager] and the package
 * installer need. Kept separate from [UpdateRepository] so the repository's own logic — what to
 * persist, when to notify — stays free of `Context`.
 *
 * The APK is written to this app's own external-files "Download" directory rather than the shared
 * Downloads folder, so no storage permission is needed on any supported Android version, and it is
 * served back to the system installer through a [FileProvider] content URI, which `targetSdk 24+`
 * requires in place of a bare `file://` path.
 */
class UpdateDownloader(private val context: Context) {

    private val downloadManager: DownloadManager
        get() = context.getSystemService(DownloadManager::class.java)

    /** Where [enqueue] will write the file, and where [installFile] will look for it afterwards. */
    fun apkFile(apkAssetName: String): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkAssetName)

    /**
     * Starts the download and returns the [DownloadManager] request id, which the caller persists
     * to recognise the matching `ACTION_DOWNLOAD_COMPLETE` broadcast later — a download outlives
     * both the enqueuing coroutine and, if the app process is killed, the app itself.
     */
    fun enqueue(update: AppUpdate, allowMetered: Boolean): Long {
        // A stale partial download from an older, dismissed update must not linger and confuse a
        // later "is my file ready" check.
        apkFile(update.apkAssetName).delete()

        val request = DownloadManager.Request(update.apkDownloadUrl.toUri())
            .setTitle(update.releaseName)
            .setDescription(update.apkAssetName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, update.apkAssetName)
            .setAllowedOverMetered(allowMetered)
            .setAllowedOverRoaming(false)
            .setMimeType("application/vnd.android.package-archive")

        return downloadManager.enqueue(request)
    }

    /** Result of asking [DownloadManager] about a request it is still tracking. */
    sealed interface DownloadStatus {
        data object Pending : DownloadStatus
        data class Running(val percent: Int?) : DownloadStatus
        data object Successful : DownloadStatus
        data class Failed(val reasonCode: Int) : DownloadStatus
        /** [DownloadManager] has already forgotten this id — neither still running nor a known result. */
        data object Unknown : DownloadStatus
    }

    fun queryStatus(downloadId: Long): DownloadStatus {
        val cursor: Cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
            ?: return DownloadStatus.Unknown
        cursor.use {
            if (!it.moveToFirst()) return DownloadStatus.Unknown
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.Successful
                DownloadManager.STATUS_FAILED -> DownloadStatus.Failed(
                    it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                )
                DownloadManager.STATUS_RUNNING -> {
                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val soFar = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    DownloadStatus.Running(if (total > 0) ((soFar * 100) / total).toInt() else null)
                }
                else -> DownloadStatus.Pending
            }
        }
    }

    /** Whether the system will let this app launch the installer without sending the user to Settings first. */
    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** "Allow app installs from Condorino" — the one-time special-access screen for this app. */
    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    /**
     * Launches the system package installer for the previously downloaded file.
     *
     * Returns false without throwing if the file is missing (a download that never actually
     * finished, or one whose file was cleaned up) — the caller falls back to offering a re-download
     * rather than crashing on a stale state.
     */
    fun installFile(apkAssetName: String): Boolean {
        val file = apkFile(apkAssetName)
        if (!file.exists()) return false

        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return true
    }
}
