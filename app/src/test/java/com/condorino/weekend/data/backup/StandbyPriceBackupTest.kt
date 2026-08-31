package com.condorino.weekend.data.backup

import com.condorino.weekend.data.export.PriceExport
import com.condorino.weekend.domain.model.Airlines
import com.condorino.weekend.domain.model.PriceEntryMode
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.domain.model.key
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The safety net for the one thing in this app that cannot be refetched: prices typed in by hand.
 * What matters is that it restores exactly when the database has lost everything, and never
 * otherwise — reviving a price the user deliberately deleted would be its own bug.
 */
class StandbyPriceBackupTest {

    private class FakeStore(var document: String? = null) : PriceBackupStore {
        var writes = 0
        override suspend fun read(): String? = document
        override suspend fun write(document: String) {
            writes++
            this.document = document
        }
    }

    /** A store whose disk is broken both ways, to prove neither path is load-bearing. */
    private class BrokenStore : PriceBackupStore {
        override suspend fun read(): String = throw java.io.IOException("unreadable")
        override suspend fun write(document: String): Unit = throw java.io.IOException("full")
    }

    private val fixedNow = { Instant.parse("2026-08-31T00:00:00Z") }

    private fun price(iata: String, cents: Long, airline: String = Airlines.CONDOR.icaoCode) =
        StandbyPrice(iata, PriceEntryMode.ROUND_TRIP, economyOutboundCents = cents, airlineIcao = airline)

    @Test
    fun `a backup round-trips through the export format the user's own file uses`() = runBlocking {
        val store = FakeStore()
        val prices = listOf(price("BUD", 9_764), price("LGW", 7_980))
        StandbyPriceBackup(store, fixedNow).backup(prices)

        assertEquals(prices.toSet(), PriceExport.read(store.document!!).toSet())
    }

    @Test
    fun `prices come back when the database has lost every one of them`() = runBlocking {
        val prices = listOf(price("BUD", 9_764), price("LGW", 7_980))
        val store = FakeStore(PriceExport.write(prices, "2026-08-31T00:00:00Z"))

        val restored = StandbyPriceBackup(store, fixedNow).restoreIfEmpty(emptyMap())

        assertEquals(prices.toSet(), restored.toSet())
    }

    @Test
    fun `nothing is restored while the database still holds a price`() = runBlocking {
        val kept = price("BUD", 9_764)
        val store = FakeStore(PriceExport.write(listOf(price("LGW", 7_980)), "2026-08-31T00:00:00Z"))

        val restored = StandbyPriceBackup(store, fixedNow).restoreIfEmpty(mapOf(kept.key to kept))

        assertTrue("a surviving price must never be overwritten or added to", restored.isEmpty())
    }

    @Test
    fun `deleting the last price does not resurrect it on the next launch`() = runBlocking {
        // Deleting rewrites the backup from what is left, so the backup is empty too — the case
        // that would otherwise make this feature fight the user.
        val store = FakeStore()
        val backup = StandbyPriceBackup(store, fixedNow)
        backup.backup(listOf(price("BUD", 9_764)))
        backup.backup(emptyList())

        assertTrue(backup.restoreIfEmpty(emptyMap()).isEmpty())
    }

    @Test
    fun `no backup at all restores nothing`() = runBlocking {
        assertTrue(StandbyPriceBackup(FakeStore(null), fixedNow).restoreIfEmpty(emptyMap()).isEmpty())
    }

    @Test
    fun `an unreadable or corrupt backup is ignored rather than crashing the launch`() = runBlocking {
        assertTrue(StandbyPriceBackup(FakeStore("not json at all"), fixedNow).restoreIfEmpty(emptyMap()).isEmpty())
        assertTrue(StandbyPriceBackup(FakeStore("   "), fixedNow).restoreIfEmpty(emptyMap()).isEmpty())
        assertTrue(StandbyPriceBackup(BrokenStore(), fixedNow).restoreIfEmpty(emptyMap()).isEmpty())
    }

    @Test
    fun `a disk that cannot be written never fails the save it shadows`() = runBlocking {
        // backup() is called from inside save(); throwing here would lose the price being saved.
        StandbyPriceBackup(BrokenStore(), fixedNow).backup(listOf(price("BUD", 9_764)))
    }

    @Test
    fun `per-airline prices survive the round trip intact`() = runBlocking {
        val store = FakeStore()
        val prices = listOf(
            price("BUD", 9_764, Airlines.CONDOR.icaoCode),
            price("BUD", 14_900, Airlines.LUFTHANSA.icaoCode),
        )
        val backup = StandbyPriceBackup(store, fixedNow)
        backup.backup(prices)

        val restored = backup.restoreIfEmpty(emptyMap())
        assertEquals(2, restored.size)
        assertEquals(prices.map { it.key }.toSet(), restored.map { it.key }.toSet())
    }
}
