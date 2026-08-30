package com.condorino.weekend

import com.condorino.weekend.data.export.PriceExport
import com.condorino.weekend.domain.model.Airlines
import com.condorino.weekend.domain.model.Destination
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.domain.model.UserPreferences
import com.condorino.weekend.domain.model.key
import com.condorino.weekend.scoring.TripBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole path a real user actually walks: export a price file, import it, then look at a trip
 * and expect the price to be there. Each layer had its own passing tests while this path was
 * broken end to end in `alpha-08` — an imported file stored prices under Condor's ICAO code while
 * flights arrived carrying a different designator, so every trip read "not set".
 *
 * The file shape here mirrors a genuine export written before per-airline pricing existed: no
 * `airline_icao` field anywhere, `ROUND_TRIP` mode, a mix of economy and business.
 */
class PriceMatchingEndToEndTest {

    /** A pre-per-airline export: no airline field on any row, exactly as such a file looks. */
    private val legacyExport = """
        {
          "exported_at": "2026-08-30T21:22:03.827120Z",
          "prices": [
            {"iata":"BUD","mode":"ROUND_TRIP","economy_outbound_cents":9764,
             "business_outbound_cents":16764,"updated_at_epoch_millis":1788123907607},
            {"iata":"LGW","mode":"ROUND_TRIP","economy_outbound_cents":7980,
             "business_outbound_cents":14980,"updated_at_epoch_millis":1788123862451}
          ]
        }
    """.trimIndent()

    private fun importedPrices(): Map<String, StandbyPrice> =
        PriceExport.read(legacyExport).associateBy { it.key }

    private val destinations = mapOf(
        "LGW" to Fixtures.destination(Fixtures.LGW),
        "BUD" to Fixtures.destination(Fixtures.BUD),
    )

    private val builder = TripBuilder(
        // A real staff fare is far above the stock budget; raise it so the assertion under test is
        // "was the price found", not "was it affordable".
        UserPreferences.DEFAULT.copy(maxBudgetCents = 100_000),
    )

    @Test
    fun `an export with no airline field imports as Condor`() {
        assertTrue(importedPrices().values.all { it.airlineIcao == Airlines.CONDOR.icaoCode })
    }

    @Test
    fun `an imported price shows against a demo flight, the case reported from the app`() {
        // The bundled demo schedule flies as "XX". This is the exact screen that read "not set".
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.BUD, Fixtures.FRIDAY, "20:10", 90, airlineCode = "XX"),
            Fixtures.flight(Fixtures.BUD, Fixtures.FRA, Fixtures.SUNDAY, "17:05", 90, airlineCode = "XX"),
        )
        val trip = builder.build(flights, Fixtures.FRIDAY, destinations, importedPrices())
            .trips.firstOrNull { it.iata == "BUD" }

        assertNotNull("no trip was built for BUD at all", trip)
        assertEquals(9764L, trip!!.standbyPrice?.economyOutboundCents)
        assertEquals(16764L, trip.standbyPrice?.businessOutboundCents)
    }

    @Test
    fun `the same imported price shows against a flight from the official Condor API`() {
        // That source reports the IATA designator "DE" where the price is stored as "CFG".
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80, airlineCode = "DE"),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80, airlineCode = "DE"),
        )
        val trip = builder.build(flights, Fixtures.FRIDAY, destinations, importedPrices())
            .trips.firstOrNull { it.iata == "LGW" }

        assertNotNull("no trip was built for LGW at all", trip)
        assertEquals(7980L, trip!!.standbyPrice?.economyOutboundCents)
    }

    @Test
    fun `and against a flight reported with the ICAO designator`() {
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80, airlineCode = "CFG"),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80, airlineCode = "CFG"),
        )
        val trip = builder.build(flights, Fixtures.FRIDAY, destinations, importedPrices())
            .trips.firstOrNull { it.iata == "LGW" }

        assertEquals(7980L, trip?.standbyPrice?.economyOutboundCents)
    }

    @Test
    fun `a Lufthansa flight still shows nothing, since only a Condor fare was imported`() {
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80, airlineCode = "LH"),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80, airlineCode = "LH"),
        )
        val trip = builder.build(flights, Fixtures.FRIDAY, destinations, importedPrices())
            .trips.firstOrNull { it.iata == "LGW" }

        assertNotNull(trip)
        assertEquals(null, trip!!.standbyPrice)
    }

    @Test
    fun `a full export-import round trip keeps every price matchable`() {
        val reExported = PriceExport.write(importedPrices().values, "2026-08-31T00:00:00Z")
        val reImported = PriceExport.read(reExported).associateBy { it.key }

        assertEquals(importedPrices().keys, reImported.keys)
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.BUD, Fixtures.FRIDAY, "20:10", 90, airlineCode = "DE"),
            Fixtures.flight(Fixtures.BUD, Fixtures.FRA, Fixtures.SUNDAY, "17:05", 90, airlineCode = "DE"),
        )
        val trip = builder.build(flights, Fixtures.FRIDAY, destinations, reImported)
            .trips.firstOrNull { it.iata == "BUD" }
        assertEquals(9764L, trip?.standbyPrice?.economyOutboundCents)
    }
}
