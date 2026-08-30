package com.condorino.weekend.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FlightDao {

    @Upsert
    suspend fun upsertAll(flights: List<CachedFlightEntity>)

    @Query("SELECT * FROM cached_flights WHERE departureLocalDate BETWEEN :from AND :to")
    suspend fun inRange(from: String, to: String): List<CachedFlightEntity>

    @Query("SELECT DISTINCT destinationIata FROM cached_flights WHERE originIata = :origin")
    suspend fun destinationsFrom(origin: String): List<String>

    @Query("DELETE FROM cached_flights WHERE departureLocalDate BETWEEN :from AND :to AND sourceId = :sourceId")
    suspend fun clearRange(from: String, to: String, sourceId: String)

    @Query("DELETE FROM cached_flights WHERE departureEpochMillis < :beforeEpochMillis")
    suspend fun purgeOlderThan(beforeEpochMillis: Long)

    /** Used when the user turns "Allow demo data" off, so switching it off actually clears it. */
    @Query("DELETE FROM cached_flights WHERE provenance = :provenance")
    suspend fun purgeByProvenance(provenance: String)

    @Query("SELECT COUNT(*) FROM cached_flights")
    suspend fun count(): Int

    @Query("DELETE FROM cached_flights")
    suspend fun clearAll()
}

@Dao
interface AirportDao {

    @Upsert
    suspend fun upsertAll(airports: List<AirportEntity>)

    @Query("SELECT * FROM airports")
    suspend fun all(): List<AirportEntity>

    @Query("SELECT * FROM airports")
    fun observeAll(): Flow<List<AirportEntity>>
}

@Dao
interface StandbyPriceDao {

    @Upsert
    suspend fun upsert(price: StandbyPriceEntity)

    @Query("SELECT * FROM standby_prices")
    fun observeAll(): Flow<List<StandbyPriceEntity>>

    @Query("SELECT * FROM standby_prices")
    suspend fun all(): List<StandbyPriceEntity>

    /** Every price entered for this destination — up to one per airline, see the entity doc. */
    @Query("SELECT * FROM standby_prices WHERE destinationIata = :iata")
    suspend fun byIata(iata: String): List<StandbyPriceEntity>

    @Query("DELETE FROM standby_prices WHERE destinationIata = :iata AND airlineIcao = :airlineIcao")
    suspend fun delete(iata: String, airlineIcao: String)
}

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE destinationIata = :iata")
    suspend fun remove(iata: String)

    @Query("SELECT * FROM favorites ORDER BY addedAtEpochMillis DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT destinationIata FROM favorites")
    suspend fun allIata(): List<String>
}

@Dao
interface RefreshStateDao {

    @Upsert
    suspend fun upsert(state: RefreshStateEntity)

    @Query("SELECT * FROM refresh_state WHERE id = 1")
    fun observe(): Flow<RefreshStateEntity?>

    @Query("SELECT * FROM refresh_state WHERE id = 1")
    suspend fun get(): RefreshStateEntity?
}
