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
    version = 1,
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
