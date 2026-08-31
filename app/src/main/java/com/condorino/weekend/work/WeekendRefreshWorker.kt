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
import com.condorino.weekend.scoring.WeekendCalendar
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Keeps the local cache warm so the app has something to show the moment it is opened, and so an
 * offline launch still shows recent data rather than nothing.
 *
 * Deliberately modest: one refresh a day, only on an unmetered network, covering the next few
 * weekends. It never notifies and never acts on the data — it only fills the cache.
 */
class WeekendRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? CondorinoApp)?.container ?: return Result.failure()
        val today = LocalDate.now()
        val from = WeekendCalendar.anchorFriday(today).minusDays(1)
        val to = from.plusWeeks(LOOKAHEAD_WEEKS)

        return try {
            // An empty result is not a failure worth retrying: the usual cause is "no data source
            // configured", and retrying on a schedule will not fix that. Only a thrown error —
            // a dropped connection mid-refresh — earns a retry.
            container.tripRepository.refreshRange(from, to)
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
        private const val UNIQUE_NAME = "condorino-weekend-refresh"
        private const val LOOKAHEAD_WEEKS = 8L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WeekendRefreshWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
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
