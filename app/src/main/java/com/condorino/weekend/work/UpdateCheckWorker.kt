package com.condorino.weekend.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.condorino.weekend.CondorinoApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Checks this app's own GitHub Releases once a day and, when a newer build exists, downloads it in
 * the background and notifies the user (spec follow-up: "auto update ... pulls the latest APK ...
 * user should be notified once a new release is available").
 *
 * The metadata check itself needs only a few kilobytes, so it runs on any network; the APK download
 * it may then start honours the user's Wi-Fi-only setting inside [com.condorino.weekend.data.update.DefaultUpdateRepository],
 * not the worker's own network constraint.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? CondorinoApp)?.container ?: return Result.failure()
        return try {
            container.updateRepository.backgroundCheckAndDownload()
            Result.success()
        } catch (e: CancellationException) {
            // Cancellation is not a failure: it means the caller went away (a new search
            // superseded this one, or the screen was left). Reporting it as an error would
            // put a spurious message on screen and hide the cancellation from the caller.
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "condorino-update-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
