package com.lifted.shipkit.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The account-gated frictionless capability (SPEC_R3 §5) tested BOTH ways: a
 * self-host / bring-your-own-payments deployment can NOT disable forced 3-D Secure
 * (any attempt is refused), while a Lifted merchant/managed account MAY opt in.
 * Pure logic — no gateway, no HTTP.
 */
class FrictionlessGateTest {
    @Test
    fun `not requested is always forced-3DS (false) on every tier`() {
        assertFalse(FrictionlessGate.resolve(TierMode.SELF_HOST, requested = false))
        assertFalse(FrictionlessGate.resolve(TierMode.MERCHANT, requested = false))
        assertFalse(FrictionlessGate.resolve(TierMode.MANAGED, requested = false))
    }

    @Test
    fun `self-host requesting frictionless is REFUSED — 3DS cannot be disabled off the rails`() {
        val ex =
            assertThrows(ForcedThreeDsViolation::class.java) {
                FrictionlessGate.resolve(TierMode.SELF_HOST, requested = true)
            }
        // The refusal names the tier and points at the real upgrade path.
        assertTrue(ex.message!!.contains("selfhost"))
        assertTrue(ex.message!!.contains("cannot be disabled"))
    }

    @Test
    fun `a Lifted merchant or managed account MAY enable frictionless`() {
        assertTrue(FrictionlessGate.resolve(TierMode.MERCHANT, requested = true))
        assertTrue(FrictionlessGate.resolve(TierMode.MANAGED, requested = true))
    }

    @Test
    fun `only merchant and managed tiers are frictionless-eligible`() {
        assertFalse(TierMode.SELF_HOST.frictionlessEligible)
        assertTrue(TierMode.MERCHANT.frictionlessEligible)
        assertTrue(TierMode.MANAGED.frictionlessEligible)
    }
}
