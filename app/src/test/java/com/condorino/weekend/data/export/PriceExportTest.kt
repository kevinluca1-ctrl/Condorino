package com.condorino.weekend.data.export

import com.condorino.weekend.domain.model.PriceEntryMode
import com.condorino.weekend.domain.model.StandbyPrice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceExportTest {

    @Test
    fun `a price round-trips through export and import unchanged`() {
        val original = StandbyPrice(
            destinationIata = "PRG",
            mode = PriceEntryMode.PER_SEGMENT,
            economyOutboundCents = 4500,
            economyInboundCents = 4700,
            businessOutboundCents = 12000,
            businessInboundCents = null,
            taxesCents = 3500,
            updatedAtEpochMillis = 1_735_000_000_000L,
        )
        val text = PriceExport.write(listOf(original), "2026-08-29T09:00:00Z")
        val restored = PriceExport.read(text)
        assertEquals(listOf(original), restored)
    }

    @Test
    fun `several prices all survive the round trip`() {
        val prices = listOf(
            StandbyPrice.empty("PRG").copy(economyOutboundCents = 4500),
            StandbyPrice.empty("BCN").copy(businessOutboundCents = 20000, mode = PriceEntryMode.ROUND_TRIP),
        )
        val restored = PriceExport.read(PriceExport.write(prices, "2026-08-29T09:00:00Z"))
        assertEquals(prices.toSet(), restored.toSet())
    }

    @Test
    fun `a row with no readable IATA code is dropped rather than corrupting the import`() {
        val text = """
            {"schema_version":1,"exported_at":"2026-08-29T09:00:00Z","prices":[
              {"iata":"","mode":"PER_SEGMENT"},
              {"iata":"PRG","mode":"PER_SEGMENT","economy_outbound_cents":4500}
            ]}
        """.trimIndent()
        val restored = PriceExport.read(text)
        assertEquals(1, restored.size)
        assertEquals("PRG", restored.single().destinationIata)
    }

    @Test
    fun `an unknown entry mode falls back to per-segment rather than failing the row`() {
        val text = """
            {"schema_version":1,"exported_at":"2026-08-29T09:00:00Z","prices":[
              {"iata":"PRG","mode":"SOMETHING_FUTURE","economy_outbound_cents":4500}
            ]}
        """.trimIndent()
        val restored = PriceExport.read(text)
        assertEquals(PriceEntryMode.PER_SEGMENT, restored.single().mode)
    }

    @Test
    fun `a missing price stays null, never becomes zero`() {
        val text = PriceExport.write(listOf(StandbyPrice.empty("PRG")), "2026-08-29T09:00:00Z")
        val restored = PriceExport.read(text).single()
        assertNull(restored.economyOutboundCents)
        assertTrue(!restored.hasAnyPrice)
    }

    @Test(expected = Exception::class)
    fun `unreadable text is reported as a failure rather than an empty import`() {
        PriceExport.read("not json at all")
    }
}
