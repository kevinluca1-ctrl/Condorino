package com.condorino.weekend.data

import com.condorino.weekend.data.source.FeedParser
import com.condorino.weekend.data.source.SkippedRow
import com.condorino.weekend.domain.model.DataProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedParserTest {

    private val parser = FeedParser()

    private fun feed(flights: String, isLive: Boolean = false) = """
        {
          "schema_version": 1,
          "source": "Testfeed",
          "is_live": $isLive,
          "airports": [
            {"iata":"FRA","name":"Frankfurt","city":"Frankfurt","country":"DE","country_code":"DE","time_zone":"Europe/Berlin"},
            {"iata":"LGW","name":"Gatwick","city":"London","country":"GB","country_code":"GB","time_zone":"Europe/London"}
          ],
          "flights": [$flights]
        }
    """.trimIndent()

    @Test
    fun `parses offset date-times and keeps the airport time zone for display`() {
        val parsed = parser.parse(
            feed(
                """{"flight_number":"DE 1","origin":"FRA","destination":"LGW",
                    "departure":"2026-09-04T18:15:00+02:00","arrival":"2026-09-04T18:35:00+01:00"}""",
            ),
        )
        val flight = parsed.flights.single()
        assertEquals(18, flight.departureLocal.hour)
        assertEquals(15, flight.departureLocal.minute)
        assertEquals(18, flight.arrivalLocal.hour)
        assertEquals(35, flight.arrivalLocal.minute)
        // 18:15+02:00 -> 18:35+01:00 is 80 minutes of flying, even though the wall clocks
        // only differ by 20 minutes. This is exactly the trap the app has to avoid.
        assertEquals(80L, flight.duration.toMinutes())
    }

    @Test
    fun `parses plain UTC instants too`() {
        val parsed = parser.parse(
            feed(
                """{"origin":"FRA","destination":"LGW",
                    "departure":"2026-09-04T16:15:00Z","arrival":"2026-09-04T17:35:00Z"}""",
            ),
        )
        assertEquals(1, parsed.flights.size)
        assertEquals(18, parsed.flights.single().departureLocal.hour)
    }

    @Test
    fun `a feed that does not claim to be live is treated as a timetable`() {
        val parsed = parser.parse(
            feed("""{"origin":"FRA","destination":"LGW","departure":"2026-09-04T16:15:00Z","arrival":"2026-09-04T17:35:00Z"}"""),
        )
        assertEquals(DataProvenance.SCHEDULE, parsed.provenance)
    }

    @Test
    fun `a feed that declares itself live is trusted as live`() {
        val parsed = parser.parse(
            feed(
                """{"origin":"FRA","destination":"LGW","departure":"2026-09-04T16:15:00Z","arrival":"2026-09-04T17:35:00Z"}""",
                isLive = true,
            ),
        )
        assertEquals(DataProvenance.LIVE, parsed.provenance)
    }

    @Test
    fun `a forced provenance always wins so demo data can never claim to be live`() {
        val parsed = parser.parse(
            feed(
                """{"origin":"FRA","destination":"LGW","departure":"2026-09-04T16:15:00Z","arrival":"2026-09-04T17:35:00Z"}""",
                isLive = true,
            ),
            forcedProvenance = DataProvenance.DEMO,
        )
        assertEquals(DataProvenance.DEMO, parsed.provenance)
        assertEquals(DataProvenance.DEMO, parsed.flights.single().provenance)
    }

    @Test
    fun `bad rows are skipped and reported rather than silently dropped`() {
        val parsed = parser.parse(
            feed(
                """
                {"origin":"FRA","destination":"LGW","departure":"2026-09-04T16:15:00Z","arrival":"2026-09-04T17:35:00Z"},
                {"origin":"FRA","destination":"ZZZ","departure":"2026-09-04T16:15:00Z","arrival":"2026-09-04T17:35:00Z"},
                {"origin":"FRA","destination":"LGW","departure":"nonsense","arrival":"2026-09-04T17:35:00Z"},
                {"origin":"FRA","destination":"LGW","departure":"2026-09-04T18:00:00Z","arrival":"2026-09-04T17:35:00Z"}
                """.trimIndent(),
            ),
        )
        assertEquals(1, parsed.flights.size)
        assertEquals(3, parsed.skipped.size)
        assertTrue(parsed.skipped.any { it is SkippedRow.UnknownDestination && it.code == "ZZZ" })
        assertTrue(parsed.skipped.any { it is SkippedRow.UnreadableTime })
        assertTrue(parsed.skipped.any { it is SkippedRow.ArrivalNotAfterDeparture })
    }

    @Test
    fun `an airport with an invalid time zone is rejected rather than guessed`() {
        val raw = """
            {"schema_version":1,"source":"t","airports":[
              {"iata":"XXX","name":"x","city":"x","country":"x","country_code":"XX","time_zone":"Nope/Nope"}
            ],"flights":[]}
        """.trimIndent()
        val parsed = parser.parse(raw)
        assertTrue(
            parsed.skipped.any {
                it is SkippedRow.UnknownTimeZone && it.iata == "XXX" && it.timeZone == "Nope/Nope"
            },
        )
        // FRA is always available as the home airport.
        assertEquals(setOf("FRA"), parsed.airports.keys)
    }

    @Test
    fun `unknown fields do not break parsing`() {
        val parsed = parser.parse(
            feed(
                """{"origin":"FRA","destination":"LGW","departure":"2026-09-04T16:15:00Z",
                    "arrival":"2026-09-04T17:35:00Z","some_future_field":42}""",
            ),
        )
        assertEquals(1, parsed.flights.size)
    }

    @Test
    fun `an unknown fare stays null and never becomes zero`() {
        val parsed = parser.parse(
            feed("""{"origin":"FRA","destination":"LGW","departure":"2026-09-04T16:15:00Z","arrival":"2026-09-04T17:35:00Z"}"""),
        )
        assertEquals(null, parsed.flights.single().cashFareCents)
    }
}
