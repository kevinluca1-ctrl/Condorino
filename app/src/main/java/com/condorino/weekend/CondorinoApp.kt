package com.condorino.weekend

import android.app.Application
import android.app.DownloadManager
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.condorino.weekend.data.update.UpdateDownloadReceiver
import com.condorino.weekend.di.AppContainer
import com.condorino.weekend.work.UpdateCheckWorker
import com.condorino.weekend.work.WeekendRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CondorinoApp : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * Lives as long as the process. Only used for work that must survive the composable or
     * ViewModel that triggered it — right now, exclusively [UpdateDownloadReceiver] reacting to a
     * download finishing while nothing in the UI is necessarily listening.
     */
    val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Best-effort cache warming. Failing to schedule must never prevent the app from starting.
        runCatching { WeekendRefreshWorker.schedule(this) }
        runCatching { UpdateCheckWorker.schedule(this) }
        runCatching { container.updateNotifier.ensureChannel() }
        runCatching { appScope.launch { container.updateRepository.initialize() } }

        // DownloadManager's own provider process delivers this broadcast, not this app, so it must
        // be registered EXPORTED — NOT_EXPORTED would silently never receive it. The download id it
        // carries is re-verified against DownloadManager's own status query before anything acts on
        // it (see UpdateRepository.onDownloadFinished), so a spoofed broadcast cannot lie about the
        // outcome, only trigger a harmless extra status check.
        ContextCompat.registerReceiver(
            this,
            UpdateDownloadReceiver(),
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
}
