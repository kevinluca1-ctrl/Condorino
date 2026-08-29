package com.condorino.weekend.data.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.condorino.weekend.MainActivity
import com.condorino.weekend.R
import com.condorino.weekend.domain.model.AppUpdate

/**
 * The one system notification this feature posts: "a new release exists". It never posts a second
 * one once the download finishes — by the time a user acts on the first, the small APK has usually
 * already finished downloading in the background, and Settings → Updates shows it as ready to
 * install immediately.
 *
 * Posting is always best-effort: on Android 13+ it silently does nothing without the
 * `POST_NOTIFICATIONS` permission, exactly like a source that cannot reach the network silently
 * returns a [UpdateCheckResult.Failure] instead of crashing — the app must keep working either way.
 */
class UpdateNotifier(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.update_notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyAvailable(update: AppUpdate) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val openApp = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.update_notification_title, update.releaseName))
            .setContentText(context.getString(R.string.update_notification_body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "condorino-updates"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CODE = 1001
    }
}
