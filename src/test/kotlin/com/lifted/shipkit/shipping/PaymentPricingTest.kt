package com.lifted.shipkit.shipping

import com.lifted.shipkit.model.MarkupConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The charged amount is server-authoritative: it is derived only from the
 * selected rate id plus the merchant markup, and there is no channel through
 * which a client can influence it. These tests lock that contract in.
 */
class PaymentPricingTest {
    private data class FakeRate(
        override val id: String,
        override val amount: String,
        override val carrier: String? = "USPS",
        override val service: String? = "Priority",
        override val currency: String? = "USD",
    ) : RateView

    private val rates =
        listOf(
            FakeRate(id = "rate_ground", amount = "7.36"),
            FakeRate(id = "rate_express", amount = "48.90"),
        )

    @Test
    fun `amount is computed from the selected rate plus markup, never from any client input`() {
        val markup = MarkupConfig(percentageMarkup = 12.0, fixedFeeCents = 50)

        // Express: 48.90 + 12% (5.868) + 0.50 = 55.268 -> 55.27
        val express = PaymentPricing.quoteFor("rate_express", rates, markup)
        assertEquals("55.27", express.amount)
        assertEquals(BigDecimal("48.90"), express.baseRate)
        assertEquals("USD", express.currency)

        // Ground: 7.36 + 12% (0.8832) + 0.50 = 8.7432 -> 8.74
        val ground = PaymentPricing.quoteFor("rate_ground", rates, markup)
        assertEquals("8.74", ground.amount)
    }

    @Test
    fun `selecting the cheap rate can never yield the expensive amount (no substitution)`() {
        val markup = MarkupConfig(percentageMarkup = 0.0, fixedFeeCents = 0)
        assertEquals("7.36", PaymentPricing.quoteFor("rate_ground", rates, markup).amount)
    }

    @Test
    fun `an unknown rate id is rejected — a client cannot smuggle in an arbitrary price`() {
        assertThrows(IllegalArgumentException::class.java) {
            PaymentPricing.quoteFor("rate_forged", rates, MarkupConfig.DEFAULT)
        }
    }
}
