package com.condorino.weekend.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedFlightEntity::class,
        AirportEntity::class,
        StandbyPriceEntity::class,
        FavoriteEntity::class,
        RefreshStateEntity::class,
    ],
    // Bumped for standby_prices' new composite (destination, airline) primary key — see
    // StandbyPriceEntity. Standby prices are the user's own hand-entered bookkeeping, not
    // disposable cache, but this database has no real migrations yet (fallbackToDestructiveMigration
    // below): anyone upgrading across this bump who wants to keep their prices should export them
    // first (Settings → standby prices → export) and re-import afterwards.
    version = 2,
    exportSchema = true,
)
abstract class CondorinoDatabase : RoomDatabase() {

    abstract fun flightDao(): FlightDao
    abstract fun airportDao(): AirportDao
    abstract fun standbyPriceDao(): StandbyPriceDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun refreshStateDao(): RefreshStateDao

    companion object {
        fun create(context: Context): CondorinoDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                CondorinoDatabase::class.java,
                "condorino.db",
            )
                // The cache is disposable; a schema change may simply drop it.
                .fallbackToDestructiveMigration()
                .build()
    }
}
