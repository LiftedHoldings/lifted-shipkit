package com.lifted.shipkit.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.round

/**
 * Merchant markup applied on top of the raw carrier rate.
 *
 * The final customer-facing price is:
 *
 *     base + (base * percentageMarkup / 100) + (fixedFeeCents / 100)
 *
 * rounded to the nearest cent. This is the one piece of pricing logic in
 * ShipKit, so it lives in a small, pure, well-tested unit.
 *
 * Money-authoritative callers (the payment-session amount that the card is
 * actually charged) MUST use [applyToAmount], which computes in [BigDecimal] so
 * the charged amount is exact to the cent. The rate-display path may use the
 * cheaper [applyTo] `Double` form. EasyPost returns rates as decimal *strings*
 * (e.g. `"7.36"`) precisely because binary floating point cannot represent
 * money exactly — never let a customer-charged figure round through a `Double`.
 */
data class MarkupConfig(
    val percentageMarkup: Double,
    val fixedFeeCents: Int,
) {
    init {
        require(percentageMarkup.isFinite()) { "percentageMarkup must be a finite number" }
        require(percentageMarkup >= 0.0) { "percentageMarkup must be >= 0" }
        require(percentageMarkup <= MAX_PERCENTAGE_MARKUP) {
            "percentageMarkup must be <= $MAX_PERCENTAGE_MARKUP"
        }
        require(fixedFeeCents >= 0) { "fixedFeeCents must be >= 0" }
        require(fixedFeeCents <= MAX_FIXED_FEE_CENTS) {
            "fixedFeeCents must be <= $MAX_FIXED_FEE_CENTS"
        }
    }

    /** Apply this markup to a raw carrier [baseRate] (in dollars) and round to cents. */
    fun applyTo(baseRate: Double): Double {
        require(baseRate >= 0.0) { "baseRate must be >= 0" }
        val percentageAmount = baseRate * (percentageMarkup / 100.0)
        val fixedAmount = fixedFeeCents / 100.0
        val marked = baseRate + percentageAmount + fixedAmount
        return round(marked * 100.0) / 100.0
    }

    /**
     * Money-exact markup for the amount a card is charged. Parses/works in
     * [BigDecimal] and rounds HALF_UP to two decimal places so the returned
     * value equals the amount authorized at the gateway to the cent — a
     * mismatch here would invalidate the 3-D Secure CAVV (the authenticated
     * amount must equal the captured amount) and break the liability shift.
     *
     * @param baseRate the raw carrier rate (must be `>= 0`).
     * @return the marked-up amount, scaled to exactly two decimals.
     */
    fun applyToAmount(baseRate: BigDecimal): BigDecimal {
        require(baseRate.signum() >= 0) { "baseRate must be >= 0" }
        val percentageAmount =
            baseRate.multiply(BigDecimal.valueOf(percentageMarkup)).divide(HUNDRED)
        val fixedAmount = BigDecimal(fixedFeeCents).divide(HUNDRED)
        return baseRate.add(percentageAmount).add(fixedAmount).setScale(2, RoundingMode.HALF_UP)
    }

    companion object {
        private val HUNDRED = BigDecimal(100)

        /**
         * Sane upper bounds so a misconfiguration (or an unauthenticated caller —
         * see the payment-scope hardening) cannot set an absurd 1,000,000% markup
         * or a runaway fixed fee. `1000.0` = up to a 10x markup; `100_000` cents =
         * a $1,000 flat fee ceiling. Both are far above any legitimate shipping
         * margin yet block fat-finger / abusive values.
         */
        const val MAX_PERCENTAGE_MARKUP = 1000.0
        const val MAX_FIXED_FEE_CENTS = 100_000

        /** Safe default markup used when no configuration is stored. */
        val DEFAULT = MarkupConfig(percentageMarkup = 12.0, fixedFeeCents = 50)
    }
}
