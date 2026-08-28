package com.condorino.weekend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The price fields are the one place in the app where the user types data the scoring depends on,
 * so the text handling gets tested rather than eyeballed.
 */
class MoneyInputTest {

    @Test
    fun `a half-typed decimal survives so the user can keep typing`() {
        assertEquals("12,", MoneyInput.sanitizeDecimal("12,"))
        assertEquals("12.", MoneyInput.sanitizeDecimal("12."))
        assertEquals("12,5", MoneyInput.sanitizeDecimal("12,5"))
        assertEquals("12,50", MoneyInput.sanitizeDecimal("12,50"))
    }

    @Test
    fun `both separators are accepted because keyboards differ by locale`() {
        assertEquals(4550L, MoneyInput.parseEuroToCents("45,50"))
        assertEquals(4550L, MoneyInput.parseEuroToCents("45.50"))
    }

    @Test
    fun `only the first separator counts and the fraction stops at two digits`() {
        assertEquals("12.34", MoneyInput.sanitizeDecimal("12.3.4"))
        assertEquals("12.34", MoneyInput.sanitizeDecimal("12.3456"))
        assertEquals("12,34", MoneyInput.sanitizeDecimal("12,34,99"))
    }

    @Test
    fun `letters and currency symbols are dropped as they are typed`() {
        assertEquals("45", MoneyInput.sanitizeDecimal("45 €"))
        assertEquals("45", MoneyInput.sanitizeDecimal("EUR45"))
        assertEquals("120", MoneyInput.sanitizeInteger("120 min"))
    }

    @Test
    fun `an empty or half-typed amount is not set rather than zero`() {
        // A standby price of 0 would score as a free flight; "not entered" has to stay null.
        assertNull(MoneyInput.parseEuroToCents(""))
        assertNull(MoneyInput.parseEuroToCents("   "))
        assertNull(MoneyInput.parseEuroToCents(","))
        assertNull(MoneyInput.parseEuroToCents("."))
        assertNull(MoneyInput.parseEuroToCents("abc"))
        assertNull(MoneyInput.parseEuroToCents("-5"))
    }

    @Test
    fun `zero entered on purpose stays zero`() {
        assertEquals(0L, MoneyInput.parseEuroToCents("0"))
    }

    @Test
    fun `cents round rather than truncate`() {
        assertEquals(4599L, MoneyInput.parseEuroToCents("45.99"))
        assertEquals(4550L, MoneyInput.parseEuroToCents("45.5"))
    }

    @Test
    fun `a stored amount comes back in the shape it will be edited in`() {
        assertEquals("", MoneyInput.formatCentsForEditing(null))
        assertEquals("45", MoneyInput.formatCentsForEditing(4500))
        assertEquals("45.50", MoneyInput.formatCentsForEditing(4550))
        assertEquals("0", MoneyInput.formatCentsForEditing(0))
    }

    @Test
    fun `editing round-trips without drifting`() {
        listOf(0L, 1L, 99L, 4500L, 4550L, 123456L).forEach { cents ->
            assertEquals(
                cents,
                MoneyInput.parseEuroToCents(MoneyInput.formatCentsForEditing(cents)),
            )
        }
    }
}
