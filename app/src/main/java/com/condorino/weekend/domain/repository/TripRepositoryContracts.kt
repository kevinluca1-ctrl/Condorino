package com.condorino.weekend.domain.repository

import com.condorino.weekend.domain.model.DataProvenance
import com.condorino.weekend.domain.model.Destination
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.domain.model.WeekendTrip
import com.condorino.weekend.scoring.RejectionReason
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/** Freshness/authenticity banner state shown at the top of every data-bearing screen. */
data class DataStatus(
    val provenance: DataProvenance?,
    val sourceLabel: String?,
    val lastSuccess: Instant?,
    val lastAttempt: Instant?,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val notConfiguredHint: String? = null,
    val isOffline: Boolean = false,
) {
    val hasEverLoaded: Boolean get() = lastSuccess != null
    val isDemo: Boolean get() = provenance == DataProvenance.DEMO

    companion object {
        val EMPTY = DataStatus(null, null, null, null)
    }
}

/** Result of searching one weekend. */
data class WeekendSearchResult(
    val friday: LocalDate,
    val trips: List<WeekendTrip>,
    val rejections: Map<RejectionReason, Int>,
    val status: DataStatus,
) {
    val best: WeekendTrip? get() = trips.firstOrNull()
    val topScore: Double get() = best?.score?.total ?: 0.0
}

interface TripRepository {

    val dataStatus: Flow<DataStatus>

    /** Cached-first search for one weekend; does not hit the network. */
    suspend fun searchWeekend(friday: LocalDate): WeekendSearchResult

    /** Forces a refresh from the highest-priority configured source, then re-searches. */
    suspend fun refresh(friday: LocalDate): WeekendSearchResult

    /**
     * Scores every weekend in the range from cached data only. Used by the calendar and
     * multi-weekend screens for their instant first paint.
     */
    suspend fun searchRange(from: LocalDate, to: LocalDate): List<WeekendSearchResult>

    /**
     * Fetches the whole range from the configured source in one request, then re-scores it.
     * The calendar needs this: without it, only the weekend the user happened to open would ever
     * have data, and a three-month overview would be empty by construction.
     */
    suspend fun refreshRange(from: LocalDate, to: LocalDate): List<WeekendSearchResult>

    suspend fun destinations(): List<Destination>

    /** Drops every cached flight. The next load re-fetches from the configured sources. */
    suspend fun clearCache()

    /**
     * Drops every cached flight sourced from the bundled demo data, and clears any refresh-status
     * banner still pointing at it. Called when the user turns "Allow demo data" off — without this,
     * previously cached demo flights (persisted before the toggle was flipped) would keep showing up
     * in [searchWeekend] indefinitely, since demo provenance never silently downgrades to CACHED.
     */
    suspend fun purgeDemoData()
}

/** Both maps are keyed by [com.condorino.weekend.domain.model.standbyPriceKey] (destination +
 *  airline), not destination alone — a route can carry a different price per airline. */
interface StandbyPriceRepository {
    val prices: Flow<Map<String, StandbyPrice>>
    suspend fun current(): Map<String, StandbyPrice>
    suspend fun save(price: StandbyPrice)
    suspend fun delete(iata: String, airlineIcao: String)
}

interface FavoriteRepository {
    val favorites: Flow<Set<String>>
    suspend fun toggle(iata: String)
    suspend fun isFavorite(iata: String): Boolean
}
