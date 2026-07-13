package com.lifted.shipkit.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Pricing is the only money-touching math in ShipKit, so it is unit-tested
 * exhaustively: percentage + fixed fee, rounding to the cent, and edge cases.
 */
class MarkupConfigTest {
    @Test
    fun `applies percentage and fixed fee then rounds to cents`() {
        // 12% of 10.00 = 1.20; + $0.50 fixed => 11.70
        val markup = MarkupConfig(percentageMarkup = 12.0, fixedFeeCents = 50)
        assertEquals(11.70, markup.applyTo(10.0), 1e-9)
    }

    @Test
    fun `rounds half-cent results to the nearest cent`() {
        // 10% of 9.99 = 0.999 => 9.99 + 0.999 = 10.989 -> 10.99
        val markup = MarkupConfig(percentageMarkup = 10.0, fixedFeeCents = 0)
        assertEquals(10.99, markup.applyTo(9.99), 1e-9)
    }

    @Test
    fun `zero markup returns the base rate unchanged`() {
        val markup = MarkupConfig(percentageMarkup = 0.0, fixedFeeCents = 0)
        assertEquals(7.35, markup.applyTo(7.35), 1e-9)
    }

    @Test
    fun `fixed fee only adds a flat amount`() {
        val markup = MarkupConfig(percentageMarkup = 0.0, fixedFeeCents = 99)
        assertEquals(5.99, markup.applyTo(5.0), 1e-9)
    }

    @Test
    fun `default markup is twelve percent plus fifty cents`() {
        assertEquals(12.0, MarkupConfig.DEFAULT.percentageMarkup, 1e-9)
        assertEquals(50, MarkupConfig.DEFAULT.fixedFeeCents)
        assertEquals(6.10, MarkupConfig.DEFAULT.applyTo(5.0), 1e-9)
    }

    @Test
    fun `negative configuration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { MarkupConfig(-1.0, 0) }
        assertThrows(IllegalArgumentException::class.java) { MarkupConfig(0.0, -1) }
    }

    @Test
    fun `negative base rate is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { MarkupConfig.DEFAULT.applyTo(-0.01) }
    }

    @Test
    fun `applyToAmount computes money exactly to two decimals`() {
        // 48.90 + 12% (5.868) + 0.50 = 55.268 -> 55.27
        val markup = MarkupConfig(percentageMarkup = 12.0, fixedFeeCents = 50)
        assertEquals(BigDecimal("55.27"), markup.applyToAmount(BigDecimal("48.90")))
        // Parses the raw carrier string exactly, always 2dp scale.
        assertEquals(BigDecimal("8.74"), markup.applyToAmount(BigDecimal("7.36")))
    }

    @Test
    fun `applyToAmount rejects a negative base`() {
        assertThrows(IllegalArgumentException::class.java) {
            MarkupConfig.DEFAULT.applyToAmount(BigDecimal("-0.01"))
        }
    }
}
