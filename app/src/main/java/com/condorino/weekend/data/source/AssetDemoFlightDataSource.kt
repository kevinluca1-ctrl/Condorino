package com.condorino.weekend.data.source

import android.content.Context
import com.condorino.weekend.R
import com.condorino.weekend.data.reference.AirportReferenceCatalog
import com.condorino.weekend.domain.model.DataProvenance
import com.condorino.weekend.domain.model.Flight
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
    private val airportCatalog: AirportReferenceCatalog,
    override val strings: SourceStrings,
    private val parser: FeedParser = FeedParser(),
    private val assetName: String = ASSET,
) : FlightDataSource {

    override val id: String = "demo-asset"
    override val displayName: String get() = strings.get(R.string.src_demo_name)
    override val bestProvenance: DataProvenance = DataProvenance.DEMO

    override suspend fun status(): SourceStatus = SourceStatus.Ready

    override suspend fun search(query: FlightSearchQuery): FlightSearchResult =
        withContext(Dispatchers.IO) {
            try {
                val raw = context.assets.open(assetName).bufferedReader().use { it.readText() }
                val parsed = parser.parse(
                    raw,
                    forcedProvenance = DataProvenance.DEMO,
                    referenceAirports = airportCatalog.airports(),
                )

                // The demo feed is authored around a single reference week. Project that weekly
                // pattern across every week the query covers, so the calendar and the
                // multi-weekend search have candidates for the whole range — not just for the
                // one weekend the user happened to open first.
                val projected = projectOverRange(parsed, query.from, query.to)

                val filtered = projected.filter { f ->
                    query.destinationIata == null ||
                        f.destination.iata == query.destinationIata ||
                        f.origin.iata == query.destinationIata
                }

                FlightSearchResult.Success(
                    flights = filtered,
                    provenance = DataProvenance.DEMO,
                    retrievedAt = Instant.now(),
                    note = strings.get(R.string.src_demo_note),
                )
            } catch (e: Exception) {
                FlightSearchResult.Failure(
                    userMessage = strings.get(R.string.src_demo_read_failed),
                    technicalDetail = e.message,
                )
            }
        }

    /**
     * Repeats the feed's reference week across every week between [from] and [to].
     *
     * The shift happens in *local* time, so a departure stays at 18:15 on its weekday even when
     * the projection crosses a daylight-saving boundary — which is how a published timetable
     * actually behaves.
     */
    private fun projectOverRange(parsed: ParsedFeed, from: LocalDate, to: LocalDate): List<Flight> {
        if (parsed.flights.isEmpty() || to.isBefore(from)) return emptyList()

        val referenceMonday = parsed.flights
            .minOf { it.departureLocal.toLocalDate() }
            .with(java.time.DayOfWeek.MONDAY)

        // One week of slack on each side so a Thursday-to-Monday window is never clipped.
        val firstMonday = from.with(java.time.DayOfWeek.MONDAY).minusWeeks(1)
        val lastMonday = to.with(java.time.DayOfWeek.MONDAY).plusWeeks(1)

        val out = mutableListOf<Flight>()
        var monday = firstMonday
        while (!monday.isAfter(lastMonday)) {
            val weeks = ChronoUnit.WEEKS.between(referenceMonday, monday)
            parsed.flights.mapTo(out) { flight ->
                if (weeks == 0L) {
                    flight
                } else {
                    flight.copy(
                        departure = flight.departureLocal.plusWeeks(weeks).toInstant(),
                        arrival = flight.arrivalLocal.plusWeeks(weeks).toInstant(),
                    )
                }
            }
            monday = monday.plusWeeks(1)
        }

        return out.filter {
            val date = it.departureLocal.toLocalDate()
            !date.isBefore(from) && !date.isAfter(to)
        }
    }

    companion object {
        const val ASSET = "demo_schedule.json"
        val REFERENCE_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
