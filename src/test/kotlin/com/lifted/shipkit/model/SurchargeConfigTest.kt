package com.lifted.shipkit.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The tier-2 buyer surcharge is money-touching, so its math is unit-tested
 * exactly to the cent — the surcharged total must equal the amount authorized at
 * the gateway or the 3-D Secure amount reconciliation breaks.
 */
class SurchargeConfigTest {
    @Test
    fun `standard surcharge is three point seventy-five percent plus fifteen cents`() {
        assertEquals(BigDecimal("3.75"), SurchargeConfig.STANDARD.percentage)
        assertEquals(15, SurchargeConfig.STANDARD.fixedCents)
        assertEquals(true, SurchargeConfig.STANDARD.enabled)
        assertEquals(false, SurchargeConfig.DISABLED.enabled)
    }

    @Test
    fun `feeOn computes the fee exactly to two decimals (HALF_UP)`() {
        val s = SurchargeConfig.STANDARD
        // 8.74 * 3.75% = 0.32775; + 0.15 = 0.47775 -> 0.48
        assertEquals(BigDecimal("0.48"), s.feeOn(BigDecimal("8.74")))
        // 100.00 * 3.75% = 3.75; + 0.15 = 3.90
        assertEquals(BigDecimal("3.90"), s.feeOn(BigDecimal("100.00")))
        // 10.00 * 3.75% = 0.375; + 0.15 = 0.525 -> 0.53 (HALF_UP)
        assertEquals(BigDecimal("0.53"), s.feeOn(BigDecimal("10.00")))
        // zero base still owes the flat fee
        assertEquals(BigDecimal("0.15"), s.feeOn(BigDecimal("0")))
    }

    @Test
    fun `applyTo adds the fee when enabled and returns the total to the cent`() {
        val s = SurchargeConfig.STANDARD
        assertEquals(BigDecimal("9.22"), s.applyTo(BigDecimal("8.74")))
        assertEquals(BigDecimal("103.90"), s.applyTo(BigDecimal("100.00")))
        assertEquals(BigDecimal("10.53"), s.applyTo(BigDecimal("10.00")))
    }

    @Test
    fun `applyTo is a no-op (2dp) when the surcharge is disabled`() {
        val s = SurchargeConfig.DISABLED
        assertEquals(BigDecimal("8.74"), s.applyTo(BigDecimal("8.74")))
        // Still normalizes scale to two decimals.
        assertEquals(BigDecimal("8.70"), s.applyTo(BigDecimal("8.7")))
    }

    @Test
    fun `a custom rate is applied exactly`() {
        val s = SurchargeConfig(enabled = true, percentage = BigDecimal("2.9"), fixedCents = 30)
        // 100 * 2.9% = 2.90; + 0.30 = 3.20
        assertEquals(BigDecimal("3.20"), s.feeOn(BigDecimal("100")))
        assertEquals(BigDecimal("103.20"), s.applyTo(BigDecimal("100")))
    }

    @Test
    fun `negative configuration and negative amounts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SurchargeConfig(enabled = true, percentage = BigDecimal("-1"), fixedCents = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SurchargeConfig(enabled = true, percentage = BigDecimal("1"), fixedCents = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SurchargeConfig.STANDARD.feeOn(BigDecimal("-0.01"))
        }
    }
}
