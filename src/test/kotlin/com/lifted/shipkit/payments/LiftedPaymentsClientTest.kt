package com.lifted.shipkit.payments

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The 3-D Secure liability-shift and status derivation is the security core of
 * the payment layer, so it is unit-tested in isolation from any live gateway.
 * A label may only be bought when the charge is Approved AND a shift occurred.
 */
class LiftedPaymentsClientTest {
    private val client =
        LiftedPaymentsClient(
            LiftedPaymentsConfig(bearerToken = "test", terminalId = 1, dbaId = 1),
        )

    @Test
    fun `Visa fully-authenticated (ECI 05 + cavv) shifts liability`() {
        assertTrue(LiftedPaymentsClient.deriveLiabilityShift(eci = "05", cavv = "abc123"))
    }

    @Test
    fun `Mastercard attempted (ECI 01 + cavv) shifts liability`() {
        assertTrue(LiftedPaymentsClient.deriveLiabilityShift(eci = "01", cavv = "aav123"))
    }

    @Test
    fun `no cryptogram means no shift regardless of ECI`() {
        assertFalse(LiftedPaymentsClient.deriveLiabilityShift(eci = "05", cavv = null))
        assertFalse(LiftedPaymentsClient.deriveLiabilityShift(eci = "05", cavv = ""))
    }

    @Test
    fun `not-authenticated ECI (07) never shifts`() {
        assertFalse(LiftedPaymentsClient.deriveLiabilityShift(eci = "07", cavv = "abc123"))
    }

    @Test
    fun `Visa attempted (ECI 06 + cavv) still shifts`() {
        // Issuer/ACS unavailable but a valid cryptogram was produced -> shift applies.
        assertTrue(LiftedPaymentsClient.deriveLiabilityShift(eci = "06", cavv = "abc123"))
    }

    @Test
    fun `issuer-reject (R, no cryptogram) never shifts and is never approved`() {
        // Status R = issuer rejected the authentication: no ECI, no CAVV. Per the
        // forced-3DS rule this must never authorize a charge.
        assertFalse(LiftedPaymentsClient.deriveLiabilityShift(eci = null, cavv = null))
        val txn =
            mapOf(
                "status" to mapOf("status" to "Approved"),
                // An issuer-reject leaves no authenticated 3DS cryptogram behind.
                "threeds" to mapOf<String, Any?>("eci" to null, "cavv" to null),
            )
        val v = client.interpretTransaction(txn)
        assertEquals(
            "declined",
            v.status,
            "R (issuer reject) must be refused even if the gateway says Approved",
        )
        assertFalse(v.liabilityShift)
    }

    @Test
    fun `approved with a liability shift maps to approved`() {
        val txn =
            mapOf(
                "status" to mapOf("status" to "Approved"),
                "threeds" to mapOf("eci" to "05", "cavv" to "abc123"),
            )
        val v = client.interpretTransaction(txn)
        assertEquals("approved", v.status)
        assertTrue(v.liabilityShift)
        assertEquals("05", v.eci)
    }

    @Test
    fun `approved WITHOUT a shift is refused (forced 3DS) — reported as declined`() {
        val txn =
            mapOf(
                "status" to mapOf("status" to "Approved"),
                "threeds" to mapOf("eci" to "07"),
            )
        val v = client.interpretTransaction(txn)
        assertEquals("declined", v.status)
        assertFalse(v.liabilityShift)
    }

    @Test
    fun `declined maps to declined and error maps to failed`() {
        assertEquals(
            "declined",
            client.interpretTransaction(mapOf("status" to mapOf("status" to "Declined"))).status,
        )
        assertEquals(
            "failed",
            client.interpretTransaction(mapOf("status" to mapOf("status" to "Error"))).status,
        )
    }
}
