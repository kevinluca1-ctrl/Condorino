package com.condorino.weekend.domain.model

/**
 * Where a piece of flight information came from. This is deliberately part of the domain model
 * and is carried all the way into the UI: the app must never let sample data look like live data
 * (see docs/CONDOR_DATA_SOURCES.md).
 */
enum class DataProvenance {
    /** Fetched from a configured live flight data source within the freshness window. */
    LIVE,

    /** Fetched live, but longer ago than the freshness window. */
    RECENTLY_UPDATED,

    /** Published timetable ("this route flies on Fridays at 18:15"), not a bookable availability. */
    SCHEDULE,

    /** Served from the local Room cache while offline / while a refresh is in flight. */
    CACHED,

    /** Entered by the user (currently: standby prices). */
    MANUAL,

    /** Bundled demo data. Must always be visibly flagged in the UI. */
    DEMO,
    ;

    val isTrustworthyLive: Boolean get() = this == LIVE || this == RECENTLY_UPDATED
    val isSample: Boolean get() = this == DEMO
}
