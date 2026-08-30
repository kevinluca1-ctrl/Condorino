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
}
