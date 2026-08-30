package com.condorino.weekend.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScoringMathTest {

    @Test
    fun `clamp holds values inside range unchanged`() {
        assertEquals(0.5, ScoringMath.clamp(0.5), 1e-9)
    }

    @Test
    fun `clamp caps values outside the given range`() {
        assertEquals(0.0, ScoringMath.clamp(-5.0), 1e-9)
        assertEquals(1.0, ScoringMath.clamp(5.0), 1e-9)
    }

    @Test
    fun `clamp maps NaN to the minimum rather than propagating it`() {
        // A NaN silently flowing through a score (e.g. a division by a budget of zero elsewhere)
        // must never reach the UI as "NaN %" — it collapses to the floor instead.
        assertEquals(0.0, ScoringMath.clamp(Double.NaN), 1e-9)
    }

    @Test
    fun `clamp100 is clamp with 0 to 100 bounds`() {
        assertEquals(100.0, ScoringMath.clamp100(150.0), 1e-9)
        assertEquals(0.0, ScoringMath.clamp100(-1.0), 1e-9)
    }

    @Test
    fun `piecewise interpolates linearly between two points`() {
        val points = listOf(0.0 to 0.0, 10.0 to 100.0)
        assertEquals(50.0, ScoringMath.piecewise(5.0, points), 1e-9)
    }

    @Test
    fun `piecewise clamps below the first point and above the last`() {
        val points = listOf(10.0 to 20.0, 20.0 to 80.0)
        assertEquals(20.0, ScoringMath.piecewise(-100.0, points), 1e-9)
        assertEquals(80.0, ScoringMath.piecewise(1000.0, points), 1e-9)
    }

    @Test
    fun `piecewise requires at least one point`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScoringMath.piecewise(5.0, emptyList())
        }
    }

    @Test
    fun `piecewise sorts out-of-order input rather than trusting caller order`() {
        // TripScoringEngine builds several of its breakpoint lists from a user-configurable
        // preference (e.g. maxFlightMinutes), so a small enough pref value can push a derived
        // breakpoint below a fixed earlier one in the literal list. piecewise must still behave
        // as if the list had been sorted, not silently misinterpret it.
        val outOfOrder = listOf(60.0 to 100.0, 45.0 to 100.0, 90.0 to 0.0)
        val sorted = listOf(45.0 to 100.0, 60.0 to 100.0, 90.0 to 0.0)
        val probe = listOf(45.0, 50.0, 60.0, 75.0, 90.0)
        probe.forEach { x ->
            assertEquals(
                "mismatch at x=$x",
                ScoringMath.piecewise(x, sorted),
                ScoringMath.piecewise(x, outOfOrder),
                1e-9,
            )
        }
    }

    @Test
    fun `piecewise resolves a duplicate x by the first point sharing that key in sorted order`() {
        // A degenerate but real case: two breakpoints that land on the exact same x (e.g. a small
        // maxFlightMinutes colliding with a fixed anchor). The segment ending at the first of the
        // tied points wins for that exact x — flat continuity into it, not the cliff after it.
        val points = listOf(0.0 to 0.0, 60.0 to 100.0, 60.0 to 30.0, 120.0 to 0.0)
        assertEquals(100.0, ScoringMath.piecewise(60.0, points), 1e-9)
        // Just past the tie, the second of the pair takes over, as the start of its own segment.
        assertEquals(30.0 - (30.0 / 60.0), ScoringMath.piecewise(61.0, points), 1e-9)
    }

    @Test
    fun `piecewise on a flat plateau returns the shared value throughout`() {
        val points = listOf(0.0 to 50.0, 10.0 to 50.0, 20.0 to 0.0)
        assertEquals(50.0, ScoringMath.piecewise(5.0, points), 1e-9)
        assertEquals(50.0, ScoringMath.piecewise(10.0, points), 1e-9)
    }
}
