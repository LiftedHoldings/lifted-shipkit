package com.lifted.shipkit.model

/**
 * The server-side gate for ShipKit's **frictionless / card-on-file** capability
 * (SPEC_R3 §5). Frictionless mode lets a checkout charge with `3ds:false` for a
 * speedier repeat purchase AND save cards on file (Maverick customer vault →
 * one-tap charges of a stored card).
 *
 * This capability is **never** a client/widget toggle — it is derived once, on the
 * server, from the deployment's tier/account:
 *
 *  - **Default & self-host / bring-your-own-payments: forced 3-D Secure.** Forced
 *    3-D Secure is the whole fraud/chargeback pitch of ShipKit's payment layer, so
 *    a self-host / BYO deployment can NOT disable it. A self-host deployment that
 *    nevertheless *requests* frictionless is **refused** — [resolve] throws
 *    [ForcedThreeDsViolation] rather than silently downgrading its own security.
 *  - **Lifted Payments (tier 2 [TierMode.MERCHANT] or tier 3 [TierMode.MANAGED])**
 *    — and enterprise custom-dev accounts, handled out of band — **may** opt in.
 *
 * The gate has no other state: given the tier and the deployment's requested
 * opt-in, it returns whether frictionless is allowed, or refuses. It is a pure
 * function so the both-ways behaviour is unit-testable without any gateway.
 */
object FrictionlessGate {
    /**
     * Resolve whether frictionless mode is allowed for [tier] given the
     * deployment's [requested] opt-in (`SHIPKIT_FRICTIONLESS_ENABLED`).
     *
     *  - `requested == false` → `false` (forced 3-D Secure — the default).
     *  - `requested == true` on a [TierMode.frictionlessEligible] tier → `true`.
     *  - `requested == true` on an ineligible tier (self-host / BYO) → **refused**
     *    with [ForcedThreeDsViolation]; a BYO deployment must not be able to turn
     *    off 3-D Secure.
     */
    fun resolve(
        tier: TierMode,
        requested: Boolean,
    ): Boolean {
        if (!requested) return false
        if (!tier.frictionlessEligible) throw ForcedThreeDsViolation(tier)
        return true
    }
}

/**
 * Raised when a deployment that is not on Lifted's rails
 * (self-host / bring-your-own-payments) attempts to enable frictionless mode —
 * i.e. attempts to turn OFF forced 3-D Secure. The attempt is refused, not
 * honoured: 3-D Secure cannot be disabled off Lifted's rails.
 */
class ForcedThreeDsViolation(
    tier: TierMode,
) : IllegalStateException(
        "Frictionless mode (3-D Secure off / card-on-file) is not available on the " +
            "'${tier.slug}' tier. Forced 3-D Secure cannot be disabled for self-host / " +
            "bring-your-own-payments deployments. Frictionless is available only on a " +
            "Lifted Payments merchant/managed account or an enterprise custom-dev account " +
            "(support@liftedholdings.com).",
    )
