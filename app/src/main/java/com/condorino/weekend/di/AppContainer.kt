package com.condorino.weekend.di

import android.content.Context
import com.condorino.weekend.BuildConfig
import com.condorino.weekend.data.DefaultFavoriteRepository
import com.condorino.weekend.data.DefaultStandbyPriceRepository
import com.condorino.weekend.data.DefaultTripRepository
import com.condorino.weekend.data.DestinationCatalog
import com.condorino.weekend.data.local.CondorinoDatabase
import com.condorino.weekend.data.mapper.toDomain
import com.condorino.weekend.data.prefs.PreferencesStore
import com.condorino.weekend.data.source.AssetDemoFlightDataSource
import com.condorino.weekend.data.source.CondorDeveloperApiDataSource
import com.condorino.weekend.data.source.FlightDataSource
import com.condorino.weekend.data.source.HttpFeedFlightDataSource
import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.repository.FavoriteRepository
import com.condorino.weekend.domain.repository.StandbyPriceRepository
import com.condorino.weekend.domain.repository.TripRepository
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container.
 *
 * A single-module app with no test doubles to inject at runtime does not need a DI framework;
 * keeping the graph explicit here makes the data-source priority order — the thing this app is
 * most opinionated about — readable in one place.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database: CondorinoDatabase by lazy { CondorinoDatabase.create(appContext) }

    val preferencesStore: PreferencesStore by lazy { PreferencesStore(appContext) }

    private val destinationCatalog: DestinationCatalog by lazy { DestinationCatalog(appContext) }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
                    )
                }
            }
            .build()
    }

    val standbyPriceRepository: StandbyPriceRepository by lazy {
        DefaultStandbyPriceRepository(database.standbyPriceDao())
    }

    val favoriteRepository: FavoriteRepository by lazy {
        DefaultFavoriteRepository(database.favoriteDao())
    }

    private val demoSource: FlightDataSource by lazy { AssetDemoFlightDataSource(appContext) }

    /**
     * Real sources in descending order of trust. The first one that returns flights wins; the
     * demo source is handled separately by the repository so it can never be mistaken for one of
     * these.
     */
    private val liveSources: List<FlightDataSource> by lazy {
        listOf(
            CondorDeveloperApiDataSource(
                client = httpClient,
                configProvider = { preferencesStore.condorApiConfig.first() },
                airportCatalog = {
                    database.airportDao().all()
                        .associate { it.iata to it.toDomain() }
                        .ifEmpty { mapOf(Airport.HOME_IATA to Airport.FRANKFURT) }
                },
            ),
            HttpFeedFlightDataSource(
                client = httpClient,
                configProvider = { preferencesStore.feedConfig.first() },
            ),
        )
    }

    val tripRepository: TripRepository by lazy {
        DefaultTripRepository(
            sources = liveSources,
            demoSource = demoSource,
            flightDao = database.flightDao(),
            airportDao = database.airportDao(),
            refreshStateDao = database.refreshStateDao(),
            preferencesStore = preferencesStore,
            destinationCatalog = destinationCatalog,
            standbyPriceRepository = standbyPriceRepository,
            favoriteRepository = favoriteRepository,
        )
    }

    /** Exposed for the settings screen so it can show each source's configuration status. */
    val allSources: List<FlightDataSource> get() = liveSources + demoSource
}
