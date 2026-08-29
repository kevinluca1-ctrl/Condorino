package com.condorino.weekend.data.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.condorino.weekend.CondorinoApp
import kotlinx.coroutines.launch

/**
 * Reacts to `ACTION_DOWNLOAD_COMPLETE` for a download this app started. Dynamically registered
 * (in [CondorinoApp.onCreate]) rather than declared in the manifest: this broadcast is delivered
 * to any receiver alive in the process, static registration buys nothing extra here, and it avoids
 * the receiver firing before [CondorinoApp] has finished building its container.
 *
 * If the app process was killed before this ever runs, nothing is lost: [UpdateRepository.initialize]
 * re-queries the pending download's status the next time the app (or the background worker) starts.
 */
class UpdateDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId < 0) return

        val app = context.applicationContext as? CondorinoApp ?: return
        app.appScope.launch {
            app.container.updateRepository.onDownloadFinished(downloadId)
        }
    }
}
