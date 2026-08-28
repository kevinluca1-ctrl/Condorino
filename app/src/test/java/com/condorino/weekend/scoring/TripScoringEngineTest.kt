package com.condorino.weekend.scoring

import com.condorino.weekend.Fixtures
import com.condorino.weekend.domain.model.Cabin
import com.condorino.weekend.domain.model.Destination
import com.condorino.weekend.domain.model.Flight
import com.condorino.weekend.domain.model.PriceEntryMode
import com.condorino.weekend.domain.model.ScoreComponent
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.domain.model.TripScore
import com.condorino.weekend.domain.model.UserPreferences
import com.condorino.weekend.domain.model.WeekendPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripScoringEngineTest {

    private val prefs = UserPreferences.DEFAULT
    private val time = TimeCompatibilityCalculator(prefs)
    private val engine = TripScoringEngine(prefs, time)

    private fun scoreTrip(
        outbound: Flight,
        inbound: Flight,
        pattern: WeekendPattern,
        destination: Destination = Fixtures.destination(outbound.destination),
        price: StandbyPrice? = null,
    ): TripScore {
        val effective = time.effectiveTime(outbound, inbound, destination)
        return engine.score(
            outbound = outbound,
            inbound = inbound,
            destination = destination,
            pattern = pattern,
            standbyPrice = price,
            effectiveTime = effective,
            nights = time.nights(outbound, inbound),
        )
    }

    // ------------------------------------------------------------------ the brief's own cases

    @Test
    fun `Friday 1815 to Sunday 1935 scores far better than Friday 1300 to Sunday 1500`() {
        val good = scoreTrip(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80),
            WeekendPattern.FRI_SUN,
        )
        val bad = scoreTrip(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "13:00", 80),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "15:00", 80),
            WeekendPattern.FRI_SUN,
        )
        assertTrue(
            "18:15/19:35 (${good.total}) must clearly beat 13:00/15:00 (${bad.total})",
            good.total > bad.total + 15,
        )
    }

    @Test
    fun `Thursday 2000 to Sunday 1900 usually beats Friday 2000 to Sunday 1400`() {
        val thursday = scoreTrip(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.THURSDAY, "20:00", 80),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:00", 80),
            WeekendPattern.THU_SUN,
        )
        val friday = scoreTrip(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "20:00", 80),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "14:00", 80),
            WeekendPattern.FRI_SUN,
        )
        assertTrue(
            "Do 20:00 → So 19:00 (${thursday.total}) should beat Fr 20:00 → So 14:00 (${friday.total})",
            thursday.total > friday.total,
        )
    }

    // ------------------------------------------------------------------ component behaviour

    @Test
    fun `outbound score rises monotonically until the earliest reachable departure`() {
        val times = listOf("12:00", "14:00", "16:00", "17:30", "19:15")
        val scores = times.map { t ->
            engine.outboundTimeScore(
                Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, t, 80),
                WeekendPattern.FRI_SUN,
            )
        }
        scores.zipWithNext().forEach { (earlier, later) ->
            assertTrue("scores must not decrease: $scores", later >= earlier)
        }
    }

    @Test
    fun `later return departure always scores at least as well`() {
        val times = listOf("09:00", "12:00", "15:00", "18:00", "20:30")
        val scores = times.map { t ->
            engine.inboundTimeScore(
                Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, t, 80),
                WeekendPattern.FRI_SUN,
            )
        }
        scores.zipWithNext().forEach { (earlier, later) ->
            assertTrue("return scores must not decrease: $scores", later >= earlier)
        }
    }

    @Test
    fun `pattern priority follows the order in the brief`() {
        val reasons = mutableListOf<String>()
        val ordered = WeekendPattern.byPriority.map {
            engine.weekendCompatibility(it, outboundWorkdayPenalty = 0.0, reasons = reasons).first
        }
        ordered.zipWithNext().forEach { (higher, lower) ->
            assertTrue("higher-priority patterns must score higher: $ordered", higher > lower)
        }
    }

    @Test
    fun `cost score is neutral when no standby price is known and warns about it`() {
        val warnings = mutableListOf<String>()
        val (value, explanation) = engine.cost(null, warnings)
        assertEquals(TripScoringEngine.NEUTRAL_SCORE, value, 1e-9)
        assertTrue(warnings.single().contains("fehlt"))
        assertTrue(explanation.contains("kein Standby-Preis"))
    }

    @Test
    fun `cheaper standby prices score higher`() {
        val cheap = StandbyPrice("LGW", PriceEntryMode.PER_SEGMENT, economyOutboundCents = 4_500, economyInboundCents = 4_500)
        val pricey = StandbyPrice("LGW", PriceEntryMode.PER_SEGMENT, economyOutboundCents = 13_000, economyInboundCents = 13_000)
        val cheapScore = engine.cost(cheap, mutableListOf()).first
        val priceyScore = engine.cost(pricey, mutableListOf()).first
        assertTrue("$cheapScore should beat $priceyScore", cheapScore > priceyScore)
    }

    @Test
    fun `over-budget prices score zero and produce a warning`() {
        val warnings = mutableListOf<String>()
        val tooMuch = StandbyPrice("LGW", PriceEntryMode.ROUND_TRIP, economyOutboundCents = 60_000)
        val (value, _) = engine.cost(tooMuch, warnings)
        assertEquals(0.0, value, 1e-9)
        assertTrue(warnings.any { it.contains("Budget") })
    }

    @Test
    fun `a negative stay scores zero and warns`() {
        val warnings = mutableListOf<String>()
        val (value, _) = engine.stayQuality(java.time.Duration.ofHours(-2), 0, mutableListOf(), warnings)
        assertEquals(0.0, value, 1e-9)
        assertTrue(warnings.any { it.contains("Rückflug zu früh") })
    }

    @Test
    fun `stay quality peaks in the classic weekend band`() {
        val short = engine.stayQuality(java.time.Duration.ofHours(14), 1, mutableListOf(), mutableListOf()).first
        val ideal = engine.stayQuality(java.time.Duration.ofHours(46), 2, mutableListOf(), mutableListOf()).first
        val long = engine.stayQuality(java.time.Duration.ofHours(96), 4, mutableListOf(), mutableListOf()).first
        assertTrue(ideal > short)
        assertTrue(ideal > long)
    }

    @Test
    fun `a connecting flight is penalised against an otherwise identical nonstop`() {
        val nonstop = scoreTrip(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80),
            WeekendPattern.FRI_SUN,
        )
        val connecting = scoreTrip(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80, isDirect = false),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80, isDirect = false),
            WeekendPattern.FRI_SUN,
        )
        assertTrue(nonstop.total > connecting.total)
        assertTrue(connecting.warnings.any { it.contains("Nonstop") })
    }

    @Test
    fun `score is always within 0 and 100 and reproducible from its components`() {
        val score = scoreTrip(
            Fixtures.flight(Fixtures.FRA, Fixtures.BUD, Fixtures.FRIDAY, "20:10", 90),
            Fixtures.flight(Fixtures.BUD, Fixtures.FRA, Fixtures.SUNDAY, "17:05", 90),
            WeekendPattern.FRI_SUN,
            price = StandbyPrice("BUD", PriceEntryMode.PER_SEGMENT, economyOutboundCents = 3_800, economyInboundCents = 3_800),
        )
        assertTrue(score.total in 0.0..100.0)
        assertEquals(ScoreComponent.entries.size, score.components.size)

        val weightSum = score.components.sumOf { it.weight }
        val recomputed = score.components.sumOf { it.value * it.weight } / weightSum
        assertEquals(recomputed, score.total, 1e-9)
    }

    @Test
    fun `weights are normalised so a single slider does not change the scale`() {
        val doubled = prefs.copy(weights = prefs.weights.copy(cost = prefs.weights.cost * 2))
        val engineB = TripScoringEngine(doubled, TimeCompatibilityCalculator(doubled))
        val out = Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80)
        val back = Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80)
        val destination = Fixtures.destination(Fixtures.LGW)
        val score = engineB.score(
            out, back, destination, WeekendPattern.FRI_SUN, null,
            time.effectiveTime(out, back, destination), 2,
        )
        assertTrue(score.total in 0.0..100.0)
    }

    @Test
    fun `preferred cabin decides which price the cost component uses`() {
        val businessPrefs = prefs.copy(preferredCabin = Cabin.BUSINESS)
        val businessEngine = TripScoringEngine(businessPrefs)
        val price = StandbyPrice(
            "LGW", PriceEntryMode.PER_SEGMENT,
            economyOutboundCents = 4_500, economyInboundCents = 4_500,
            businessOutboundCents = 11_000, businessInboundCents = 11_000,
        )
        val economyScore = engine.cost(price, mutableListOf()).first
        val businessScore = businessEngine.cost(price, mutableListOf()).first
        assertTrue("economy ($economyScore) is cheaper than business ($businessScore)", economyScore > businessScore)
    }
}
