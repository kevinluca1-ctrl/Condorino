package com.condorino.weekend.data

import com.condorino.weekend.data.backup.StandbyPriceBackup
import com.condorino.weekend.data.local.FavoriteDao
import com.condorino.weekend.data.local.FavoriteEntity
import com.condorino.weekend.data.local.StandbyPriceDao
import com.condorino.weekend.data.mapper.toDomain
import com.condorino.weekend.data.mapper.toEntity
import com.condorino.weekend.domain.model.Airlines
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.domain.model.key
import com.condorino.weekend.domain.repository.FavoriteRepository
import com.condorino.weekend.domain.repository.StandbyPriceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * Manually maintained MyID Travel / staff-travel prices. The app never talks to MyID Travel and
 * stores no credentials (spec §26) — this is purely the user's own bookkeeping.
 */
class DefaultStandbyPriceRepository(
    private val dao: StandbyPriceDao,
    /** Shadow copy of every price, so hand-typed work survives losing the database — see
     *  [StandbyPriceBackup]. Null disables the safety net (used by tests). */
    private val backup: StandbyPriceBackup? = null,
) : StandbyPriceRepository {

    override val prices: Flow<Map<String, StandbyPrice>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() }.associateBy { it.key } }

    override suspend fun current(): Map<String, StandbyPrice> =
        dao.all().map { it.toDomain() }.associateBy { it.key }

    override suspend fun save(price: StandbyPrice) {
        // The one write chokepoint for every path (the price screen, and importing a file), so
        // canonicalising the airline code here is what guarantees a stored price is always keyed
        // by the ICAO designator flights are matched against — see Airlines.canonicalIcao.
        val normalised = price.copy(
            airlineIcao = Airlines.canonicalIcao(price.airlineIcao),
            updatedAtEpochMillis = Instant.now().toEpochMilli(),
        )
        dao.upsert(normalised.toEntity())
        refreshBackup()
    }

    override suspend fun delete(iata: String, airlineIcao: String) {
        dao.delete(iata, airlineIcao)
        refreshBackup()
    }

    /**
     * Puts the prices back if the database has lost them all and a backup survives — the whole
     * point of the safety net. Restoring writes through [save], so a recovered price is normalised
     * and re-backed-up exactly like a typed one; running it when nothing was lost does nothing.
     *
     * @return how many prices were restored, for the caller to report.
     */
    override suspend fun restoreFromBackupIfEmpty(): Int {
        val recovered = backup?.restoreIfEmpty(current()).orEmpty()
        recovered.forEach { save(it) }
        return recovered.size
    }

    private suspend fun refreshBackup() {
        backup?.backup(dao.all().map { it.toDomain() })
    }
}

class DefaultFavoriteRepository(
    private val dao: FavoriteDao,
) : FavoriteRepository {

    override val favorites: Flow<Set<String>> =
        dao.observeAll().map { rows -> rows.map { it.destinationIata }.toSet() }

    override suspend fun toggle(iata: String) {
        if (isFavorite(iata)) dao.remove(iata)
        else dao.add(FavoriteEntity(iata, Instant.now().toEpochMilli()))
    }

    override suspend fun isFavorite(iata: String): Boolean = iata in dao.allIata()
}
