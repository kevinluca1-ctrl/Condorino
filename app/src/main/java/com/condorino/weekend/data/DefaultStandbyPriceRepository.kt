package com.condorino.weekend.data

import com.condorino.weekend.data.local.FavoriteDao
import com.condorino.weekend.data.local.FavoriteEntity
import com.condorino.weekend.data.local.StandbyPriceDao
import com.condorino.weekend.data.mapper.toDomain
import com.condorino.weekend.data.mapper.toEntity
import com.condorino.weekend.domain.model.StandbyPrice
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
) : StandbyPriceRepository {

    override val prices: Flow<Map<String, StandbyPrice>> =
        dao.observeAll().map { rows -> rows.associate { it.destinationIata to it.toDomain() } }

    override suspend fun current(): Map<String, StandbyPrice> =
        dao.all().associate { it.destinationIata to it.toDomain() }

    override suspend fun save(price: StandbyPrice) {
        dao.upsert(price.copy(updatedAtEpochMillis = Instant.now().toEpochMilli()).toEntity())
    }

    override suspend fun delete(iata: String) = dao.delete(iata)
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
