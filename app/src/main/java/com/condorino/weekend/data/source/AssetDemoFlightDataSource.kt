package com.condorino.weekend.data.source

import android.content.Context
import com.condorino.weekend.domain.model.DataProvenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The bundled fallback so the app is usable on a fresh install with no configuration.
 *
 * ⚠️ **This is demo data.** The flight numbers and times in `assets/demo_schedule.json` are
 * illustrative and are NOT a Condor timetable. Everything it produces is tagged
 * [DataProvenance.DEMO], which the UI renders with a permanent warning banner (spec §29).
 *
 * The demo file describes a *weekly pattern* (weekday + local time). This source projects that
 * pattern onto the requested calendar dates so the app can be exercised end to end, and it
 * refuses to claim any provenance better than DEMO.
 */
class AssetDemoFlightDataSource(
    private val context: Context,
    private val parser: FeedParser = FeedParser(),
    private val assetName: String = ASSET,
) : FlightDataSource {

    override val id: String = "demo-asset"
    override val displayName: String = "Beispieldaten (kein echter Condor-Flugplan)"
    override val bestProvenance: DataProvenance = DataProvenance.DEMO

    override suspend fun status(): SourceStatus = SourceStatus.Ready

    override suspend fun search(query: FlightSearchQuery): FlightSearchResult =
        withContext(Dispatchers.IO) {
            try {
                val raw = context.assets.open(assetName).bufferedReader().use { it.readText() }
                val parsed = parser.parse(raw, forcedProvenance = DataProvenance.DEMO)

                // The demo feed is authored around a single reference weekend; shift it onto the
                // weekend actually being searched so every date the user picks has candidates.
                val shifted = shiftToWeek(parsed, query.from)

                val filtered = shifted.filter { f ->
                    val date = f.departureLocal.toLocalDate()
                    !date.isBefore(query.from) && !date.isAfter(query.to) &&
                        (query.destinationIata == null ||
                            f.destination.iata == query.destinationIata ||
                            f.origin.iata == query.destinationIata)
                }

                FlightSearchResult.Success(
                    flights = filtered,
                    provenance = DataProvenance.DEMO,
                    retrievedAt = Instant.now(),
                    note = "Beispieldaten – keine echten Condor-Flugzeiten.",
                )
            } catch (e: Exception) {
                FlightSearchResult.Failure(
                    userMessage = "Beispieldaten konnten nicht gelesen werden.",
                    technicalDetail = e.message,
                )
            }
        }

    /**
     * Moves every flight forward/backward by whole weeks so that the feed's reference week lines
     * up with the week containing [target]. Weekday and local time-of-day are preserved, which is
     * exactly how a repeating timetable behaves.
     */
    private fun shiftToWeek(parsed: ParsedFeed, target: LocalDate) =
        parsed.flights.map { flight ->
            val depLocal = flight.departureLocal
            val arrLocal = flight.arrivalLocal
            val weeks = ChronoUnit.WEEKS.between(
                depLocal.toLocalDate().with(java.time.DayOfWeek.MONDAY),
                target.with(java.time.DayOfWeek.MONDAY),
            )
            if (weeks == 0L) {
                flight
            } else {
                // Shift in *local* time so the wall-clock departure stays 18:15 even when the
                // shift crosses a daylight-saving boundary.
                flight.copy(
                    departure = depLocal.plusWeeks(weeks).toInstant(),
                    arrival = arrLocal.plusWeeks(weeks).toInstant(),
                )
            }
        }

    companion object {
        const val ASSET = "demo_schedule.json"
        val REFERENCE_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
