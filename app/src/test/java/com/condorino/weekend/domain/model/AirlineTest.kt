package com.condorino.weekend.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers the two small pieces of logic on [Airlines]: ICAO lookup and the human-readable list
 *  used in status/failure messages that need to say which airlines a search covered. */
class AirlineTest {

    @Test
    fun `byIcao resolves case-insensitively`() {
        assertEquals(Airlines.LUFTHANSA, Airlines.byIcao("dlh"))
        assertEquals(Airlines.CONDOR, Airlines.byIcao("CFG"))
    }

    @Test
    fun `byIcao returns null rather than guessing for an unknown code`() {
        assertNull(Airlines.byIcao("XXX"))
    }

    @Test
    fun `CONDOR is not one of the LUFTHANSA_GROUP entries`() {
        assertEquals(false, Airlines.CONDOR in Airlines.LUFTHANSA_GROUP)
    }

    @Test
    fun `ALL lists Condor first, then every Lufthansa Group carrier`() {
        assertEquals(Airlines.CONDOR, Airlines.ALL.first())
        assertEquals(Airlines.LUFTHANSA_GROUP, Airlines.ALL.drop(1))
    }

    @Test
    fun `describe joins known display names in ALL's order`() {
        assertEquals("Condor, Lufthansa", Airlines.describe(setOf("DLH", "CFG")))
    }

    @Test
    fun `describe is case-insensitive against the stored codes`() {
        assertEquals("Condor", Airlines.describe(setOf("cfg")))
    }

    @Test
    fun `describe falls back to the raw code for one this app does not recognise`() {
        assertEquals("Condor, ZZZ", Airlines.describe(setOf("CFG", "ZZZ")))
    }

    @Test
    fun `describe of an empty set is an empty string`() {
        assertEquals("", Airlines.describe(emptySet()))
    }

    @Test
    fun `resolve accepts either the ICAO or the IATA designator`() {
        // Sources genuinely disagree: the Condor Developer API says "DE", OpenSky says "CFG".
        assertEquals(Airlines.CONDOR, Airlines.resolve("CFG"))
        assertEquals(Airlines.CONDOR, Airlines.resolve("DE"))
        assertEquals(Airlines.LUFTHANSA, Airlines.resolve("DLH"))
        assertEquals(Airlines.LUFTHANSA, Airlines.resolve("LH"))
    }

    @Test
    fun `resolve ignores case and surrounding whitespace`() {
        assertEquals(Airlines.CONDOR, Airlines.resolve(" cfg "))
        assertEquals(Airlines.CONDOR, Airlines.resolve("de"))
    }

    @Test
    fun `resolve returns null for an airline this app does not know`() {
        // "XX" is what the bundled demo schedule uses on purpose.
        assertNull(Airlines.resolve("XX"))
        assertNull(Airlines.resolve(""))
        assertNull(Airlines.resolve("   "))
    }

    @Test
    fun `an ICAO code is preferred when one airline's IATA collides with another's ICAO`() {
        // Resolution checks every ICAO before any IATA, so a real ICAO code always wins.
        Airlines.ALL.forEach { airline ->
            assertEquals(airline, Airlines.resolve(airline.icaoCode))
        }
    }

    @Test
    fun `canonicalIcao normalises a known code and passes an unknown one through`() {
        assertEquals("CFG", Airlines.canonicalIcao("DE"))
        assertEquals("CFG", Airlines.canonicalIcao("cfg"))
        assertEquals("XX", Airlines.canonicalIcao(" xx "))
        assertNull(Airlines.canonicalIcaoOrNull("XX"))
    }
}
