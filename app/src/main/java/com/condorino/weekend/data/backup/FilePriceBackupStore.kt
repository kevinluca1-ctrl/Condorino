package com.condorino.weekend.data.backup

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the backup in the app's own private files directory, which is included in Android's
 * auto-backup rules alongside the database — so the copy also rides along to a new device.
 *
 * Written via a temporary file and an atomic rename, so an interrupted write (process death,
 * battery) leaves the previous good backup in place rather than a half-written one.
 */
class FilePriceBackupStore(context: Context) : PriceBackupStore {

    private val appContext = context.applicationContext
    private val file: File get() = File(appContext.filesDir, FILE_NAME)

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        val f = file
        if (f.exists()) f.readText() else null
    }

    override suspend fun write(document: String) = withContext(Dispatchers.IO) {
        val target = file
        val temp = File(appContext.filesDir, "$FILE_NAME.tmp")
        try {
            temp.writeText(document)
            if (!temp.renameTo(target)) {
                // Some filesystems refuse a rename over an existing file.
                target.delete()
                temp.renameTo(target)
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            temp.delete()
        }
    }

    private companion object {
        const val FILE_NAME = "standby-prices-backup.json"
    }
}
