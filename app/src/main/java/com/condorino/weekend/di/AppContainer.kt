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
import com.condorino.weekend.data.reference.AirportReferenceCatalog
import com.condorino.weekend.data.source.AssetDemoFlightDataSource
import com.condorino.weekend.data.source.CommercialPriceSource
import com.condorino.weekend.data.source.CondorDeveloperApiDataSource
import com.condorino.weekend.data.source.FlightDataSource
import com.condorino.weekend.data.source.GoogleFlightsPriceSource
import com.condorino.weekend.data.source.HttpFeedFlightDataSource
import com.condorino.weekend.data.source.OpenSkyFlightDataSource
import com.condorino.weekend.data.source.SourceStrings
import com.condorino.weekend.data.source.TravelRecommendationSource
import com.condorino.weekend.data.source.TripAdvisorRecommendationSource
import com.condorino.weekend.data.update.DefaultUpdateRepository
import com.condorino.weekend.data.update.GitHubReleaseUpdateSource
import com.condorino.weekend.data.update.UpdateDownloader
import com.condorino.weekend.data.update.UpdateNotifier
import com.condorino.weekend.data.update.UpdateRepository
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

    /**
     * Localised text for the data layer.
     *
     * Sources speak to the user ("OpenSky refused access"), so they need the resource table;
     * the domain and scoring layers deliberately do not, and emit structured values instead.
     */
    private val sourceStrings: SourceStrings by lazy { SourceStrings(appContext) }

    /** Public airport reference (OurAirports + OpenFlights + tzdata), shared by every source. */
    val airportReferenceCatalog: AirportReferenceCatalog by lazy { AirportReferenceCatalog(appContext) }

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

    private val demoSource: FlightDataSource by lazy {
        AssetDemoFlightDataSource(appContext, airportReferenceCatalog, sourceStrings)
    }

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
                    // Cached airports first, then the bundled public reference.
                    val cached = database.airportDao().all().associate { it.iata to it.toDomain() }
                    airportReferenceCatalog.airports() + cached +
                        mapOf(Airport.HOME_IATA to Airport.FRANKFURT)
                },
                strings = sourceStrings,
            ),
            HttpFeedFlightDataSource(
                client = httpClient,
                configProvider = { preferencesStore.feedConfig.first() },
                airportCatalog = airportReferenceCatalog,
                strings = sourceStrings,
            ),
            // Ranked last of the real sources: OpenSky describes flights that *were* flown, which
            // is an excellent cross-check but never a statement about availability.
            OpenSkyFlightDataSource(
                client = httpClient,
                configProvider = { preferencesStore.openSkyConfig.first() },
                airportCatalog = airportReferenceCatalog,
                strings = sourceStrings,
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
            airportReferenceCatalog = airportReferenceCatalog,
            strings = sourceStrings,
        )
    }

    /** Exposed for the settings screen so it can show each source's configuration status. */
    val allSources: List<FlightDataSource> get() = liveSources + demoSource

    /**
     * On-demand commercial (cash-fare) price lookup — queried per trip, on a button tap, never
     * automatically for every trip on screen (see [CommercialPriceSource] doc).
     */
    val commercialPriceSource: CommercialPriceSource by lazy {
        GoogleFlightsPriceSource(
            client = httpClient,
            configProvider = { preferencesStore.googleFlightsApiConfig.first() },
            apiKeyProvider = { preferencesStore.rapidApiKey.first() },
            strings = sourceStrings,
        )
    }

    /**
     * On-demand "what's worth doing here?" lookup — queried per destination, on a button tap,
     * never automatically for every trip on screen (see [TravelRecommendationSource] doc).
     */
    val travelRecommendationSource: TravelRecommendationSource by lazy {
        TripAdvisorRecommendationSource(
            client = httpClient,
            configProvider = { preferencesStore.tripAdvisorApiConfig.first() },
            apiKeyProvider = { preferencesStore.rapidApiKey.first() },
            strings = sourceStrings,
        )
    }

    val updateNotifier: UpdateNotifier by lazy { UpdateNotifier(appContext) }

    private val updateDownloader: UpdateDownloader by lazy { UpdateDownloader(appContext) }

    private val updateSource: GitHubReleaseUpdateSource by lazy {
        GitHubReleaseUpdateSource(client = httpClient, strings = sourceStrings)
    }

    val updateRepository: UpdateRepository by lazy {
        DefaultUpdateRepository(
            source = updateSource,
            downloader = updateDownloader,
            notifier = updateNotifier,
            preferencesStore = preferencesStore,
        )
    }
}
