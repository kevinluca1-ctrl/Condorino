package com.condorino.weekend.data.source

import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.DataProvenance
import com.condorino.weekend.domain.model.Flight
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Result of turning a raw feed document into domain objects. */
data class ParsedFeed(
    val flights: List<Flight>,
    val airports: Map<String, Airport>,
    val source: String,
    val provenance: DataProvenance,
    val generatedAt: Instant?,
    /** Rows that had to be dropped, with the reason — surfaced as a warning, never swallowed. */
    val skipped: List<String>,
)

/**
 * Parses [FlightFeed] documents. Tolerant by design: one malformed row must not lose the rest of
 * the feed, but every dropped row is reported so the user can see that data is incomplete.
 */
class FeedParser(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {

    /**
     * @param referenceAirports the bundled public airport reference, used for any airport a feed
     *   references but does not declare. A feed may therefore be a bare list of IATA codes and
     *   times; it no longer has to repeat name, country and time zone for every airport.
     */
    fun parse(
        raw: String,
        forcedProvenance: DataProvenance? = null,
        referenceAirports: Map<String, Airport> = emptyMap(),
    ): ParsedFeed {
        val feed = json.decodeFromString(FlightFeed.serializer(), raw)
        val skipped = mutableListOf<String>()

        val airports = feed.airports.mapNotNull { fa ->
            val zone = runCatching { ZoneId.of(fa.timeZone) }.getOrNull()
            if (zone == null) {
                skipped += "Flughafen ${fa.iata}: unbekannte Zeitzone '${fa.timeZone}'"
                null
            } else {
                fa.iata.uppercase() to Airport(
                    iata = fa.iata.uppercase(),
                    name = fa.name,
                    city = fa.city,
                    country = fa.country,
                    countryCode = fa.countryCode,
                    timeZoneId = fa.timeZone,
                )
            }
        }.toMap().toMutableMap()

        // FRA is always known, even if a feed forgets to declare it.
        airports.putIfAbsent(Airport.HOME_IATA, Airport.FRANKFURT)

        val provenance = forcedProvenance
            ?: if (feed.isLive) DataProvenance.LIVE else DataProvenance.SCHEDULE

        val retrievedAt = feed.generatedAt?.let { parseInstantOrNull(it) }

        // A feed's own declaration wins; anything it leaves out is looked up in the reference.
        fun resolve(code: String): Airport? {
            val key = code.uppercase()
            return airports[key] ?: referenceAirports[key]?.also { airports[key] = it }
        }

        val flights = feed.flights.mapNotNull { ff ->
            val origin = resolve(ff.origin)
            val destination = resolve(ff.destination)
            when {
                origin == null -> {
                    skipped += "Flug ${ff.flightNumber ?: "?"}: Abflughafen ${ff.origin} " +
                        "weder im Feed deklariert noch im Referenzdatensatz gefunden"
                    null
                }
                destination == null -> {
                    skipped += "Flug ${ff.flightNumber ?: "?"}: Zielflughafen ${ff.destination} " +
                        "weder im Feed deklariert noch im Referenzdatensatz gefunden"
                    null
                }
                else -> {
                    val dep = parseInstantOrNull(ff.departure)
                    val arr = parseInstantOrNull(ff.arrival)
                    if (dep == null || arr == null) {
                        skipped += "Flug ${ff.flightNumber ?: "?"}: ungültiges Zeitformat"
                        null
                    } else if (!arr.isAfter(dep)) {
                        skipped += "Flug ${ff.flightNumber ?: "?"}: Ankunft liegt nicht nach dem Abflug"
                        null
                    } else {
                        Flight(
                            flightNumber = ff.flightNumber,
                            airline = ff.airline,
                            airlineCode = ff.airlineCode,
                            origin = origin,
                            destination = destination,
                            departure = dep,
                            arrival = arr,
                            isDirect = ff.isDirect,
                            provenance = provenance,
                            retrievedAt = retrievedAt,
                            cashFareCents = ff.fareCents,
                            availabilityNote = ff.availabilityNote,
                        )
                    }
                }
            }
        }

        return ParsedFeed(
            flights = flights,
            airports = airports,
            source = feed.source,
            provenance = provenance,
            generatedAt = retrievedAt,
            skipped = skipped,
        )
    }

    /** Accepts both `…Z` instants and offset date-times such as `2026-09-04T18:15:00+02:00`. */
    private fun parseInstantOrNull(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrElse {
            try {
                OffsetDateTime.parse(value).toInstant()
            } catch (e: DateTimeParseException) {
                null
            }
        }
}
