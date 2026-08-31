package com.condorino.weekend.data.backup

import com.condorino.weekend.data.export.PriceExport
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.domain.model.key
import java.time.Instant

/**
 * Where a backup copy of the standby prices lives. Split out behind an interface because the
 * recovery decision below is worth testing on its own, and file I/O is the one part of it that
 * needs Android.
 */
interface PriceBackupStore {
    /** The stored backup document, or null if there has never been one. */
    suspend fun read(): String?

    /** Replaces the stored backup. Failing to write must never break saving a price. */
    suspend fun write(document: String)
}

/**
 * A second copy of the standby prices, kept beside the database.
 *
 * Every other table in this app is a cache that can be refetched, but standby prices are typed in
 * by hand from MyID Travel and exist nowhere else — losing them costs the user real work. The
 * database is not a safe enough single home for them:
 *
 * * it is opened with `fallbackToDestructiveMigration`, so any future schema change drops every
 *   row rather than failing the upgrade;
 * * "clear storage" and an uninstall/reinstall take it with them;
 * * corruption is rare but terminal.
 *
 * So each write also refreshes a plain JSON copy — deliberately the *same* format the user-facing
 * export writes, so the safety net and the export file are interchangeable and neither can rot
 * separately from the other. [restoreIfEmpty] then puts the prices back the first time the app
 * finds the table empty with a backup present, which turns each of the cases above from "the work
 * is gone" into "it comes back on next launch".
 */
class StandbyPriceBackup(
    private val store: PriceBackupStore,
    private val now: () -> Instant = { Instant.now() },
) {

    /** Best-effort by design: a backup that cannot be written must not fail the save it shadows. */
    suspend fun backup(prices: Collection<StandbyPrice>) {
        runCatching { store.write(PriceExport.write(prices, now().toString())) }
    }

    /**
     * Restores prices from the backup **only** when the database has none, and returns what should
     * now be saved (empty when there is nothing to do).
     *
     * The empty-table condition is the whole safeguard against this fighting the user: it can only
     * ever add prices back to a database that has lost all of them, and can never overwrite, revive
     * or duplicate a price that still exists — including one the user deliberately deleted, since
     * deleting the last price leaves a backup that is itself empty.
     */
    suspend fun restoreIfEmpty(currentPrices: Map<String, StandbyPrice>): List<StandbyPrice> {
        if (currentPrices.isNotEmpty()) return emptyList()
        val document = runCatching { store.read() }.getOrNull() ?: return emptyList()
        if (document.isBlank()) return emptyList()
        return runCatching { PriceExport.read(document) }.getOrDefault(emptyList())
            .distinctBy { it.key }
    }
}
