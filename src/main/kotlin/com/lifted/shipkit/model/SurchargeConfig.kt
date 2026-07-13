package com.lifted.shipkit.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The tier-2 **buyer surcharge** framework: a built-in, toggleable pass-through
 * of the Lifted 3-D Secure merchant-account processing cost —
 * **3.75% + $0.15 per transaction** — onto the buyer at checkout.
 *
 * When a merchant runs on a Lifted 3DS merchant account ([TierMode.MERCHANT])
 * the account costs 3.75% + 15¢ per transaction. Rather than absorb it, the
 * merchant can flip [enabled] on and ShipKit adds that same fee to the amount
 * the card is charged, so the merchant nets the shipping price. This is the
 * "surcharge to the buyer via a built-in framework toggle" from the pricing
 * model. It is **off by default** — surcharging is a deliberate, jurisdiction-
 * aware choice the merchant opts into.
 *
 * All math is [BigDecimal] and rounds HALF_UP to the cent so the surcharged
 * total equals the amount authorized at the gateway to the penny — a mismatch
 * would break the 3-D Secure amount reconciliation and the liability shift.
 *
 * @param enabled    whether the surcharge is added to the buyer's charge.
 * @param percentage percentage rate applied to the pre-surcharge amount (e.g.
 *                   `3.75` = 3.75%).
 * @param fixedCents flat per-transaction fee in whole cents (e.g. `15` = $0.15).
 */
data class SurchargeConfig(
    val enabled: Boolean,
    val percentage: BigDecimal,
    val fixedCents: Int,
) {
    init {
        require(percentage.signum() >= 0) { "surcharge percentage must be >= 0" }
        require(percentage <= MAX_PERCENTAGE) { "surcharge percentage must be <= $MAX_PERCENTAGE" }
        require(fixedCents >= 0) { "surcharge fixed fee must be >= 0" }
        require(fixedCents <= MAX_FIXED_CENTS) { "surcharge fixed fee must be <= $MAX_FIXED_CENTS" }
    }

    /**
     * The surcharge **fee** for a pre-surcharge [amount], as an exact 2-decimal
     * value: `round2(amount * percentage/100 + fixedCents/100)`. Independent of
     * [enabled] — callers use [applyTo] for the enabled-aware total; this is
     * exposed so the fee can be itemized to the buyer.
     */
    fun feeOn(amount: BigDecimal): BigDecimal {
        require(amount.signum() >= 0) { "amount must be >= 0" }
        val percentageFee = amount.multiply(percentage).divide(HUNDRED)
        val fixedFee = BigDecimal(fixedCents).divide(HUNDRED)
        return percentageFee.add(fixedFee).setScale(2, RoundingMode.HALF_UP)
    }

    /**
     * The buyer-facing **total** for a pre-surcharge [amount]: `amount + feeOn`
     * when [enabled], otherwise [amount] unchanged (always scaled to 2 decimals).
     */
    fun applyTo(amount: BigDecimal): BigDecimal {
        val base = amount.setScale(2, RoundingMode.HALF_UP)
        return if (enabled) base.add(feeOn(amount)) else base
    }

    companion object {
        private val HUNDRED = BigDecimal(100)

        /** Bounds against fat-finger / abusive config (surcharge caps well above any real rate). */
        val MAX_PERCENTAGE: BigDecimal = BigDecimal(100)
        const val MAX_FIXED_CENTS = 100_000

        /** The Lifted 3DS merchant-account per-transaction cost: 3.75% + $0.15. */
        val STANDARD_PERCENTAGE: BigDecimal = BigDecimal("3.75")
        const val STANDARD_FIXED_CENTS = 15

        /** Surcharge OFF (default) — the merchant absorbs the processing fee. */
        val DISABLED =
            SurchargeConfig(
                enabled = false,
                percentage = STANDARD_PERCENTAGE,
                fixedCents = STANDARD_FIXED_CENTS,
            )

        /** Surcharge ON at the standard 3.75% + $0.15 — passed to the buyer. */
        val STANDARD =
            SurchargeConfig(
                enabled = true,
                percentage = STANDARD_PERCENTAGE,
                fixedCents = STANDARD_FIXED_CENTS,
            )
    }
}
