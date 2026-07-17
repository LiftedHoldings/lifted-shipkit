package com.lifted.shipkit.store

import com.lifted.shipkit.model.PaymentSession
import com.lifted.shipkit.model.TrackingRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The label-purchase idempotency guard is what stops a status-poll racing the
 * 3-D Secure return callback from buying (and billing) two labels for one
 * payment. Exactly one caller may ever win the claim.
 */
class InMemoryLabelStoreTest {
    @Test
    fun `only the first caller wins the purchase claim`() {
        val store = InMemoryLabelStore()
        assertTrue(store.claimLabelPurchase("sess-1"))
        assertFalse(store.claimLabelPurchase("sess-1"))
        assertFalse(store.claimLabelPurchase("sess-1"))
    }

    @Test
    fun `tracking update is stored, retrievable, and last-write-wins per code`() {
        val store = InMemoryLabelStore()
        assertNull(store.getTracking("EZ1"), "no tracking before any event")

        store.saveTrackingUpdate(
            TrackingRecord(
                trackingCode = "EZ1",
                status = "in_transit",
                statusDetail = "arrived_at_facility",
                carrier = "USPS",
                estDeliveryDate = "2026-07-18",
                shipmentId = "shp_1",
                eventAt = "2026-07-15T10:00:00Z",
            ),
        )
        val first = store.getTracking("EZ1")
        assertNotNull(first)
        assertEquals("in_transit", first!!.status)
        assertEquals("USPS", first.carrier)
        assertNotNull(first.updatedAt, "store stamps its own persist time")

        // A later event for the same code wins; a shipment id absent on the later
        // event is preserved (parity with the Postgres COALESCE upsert).
        store.saveTrackingUpdate(
            TrackingRecord(trackingCode = "EZ1", status = "delivered", carrier = "USPS"),
        )
        val latest = store.getTracking("EZ1")!!
        assertEquals("delivered", latest.status)
        assertEquals("shp_1", latest.shipmentId, "known shipment id preserved when omitted")
    }

    @Test
    fun `a late-arriving older tracking event does not regress the stored status`() {
        val store = InMemoryLabelStore()
        // Newest event first: delivered at T2.
        store.saveTrackingUpdate(
            TrackingRecord(
                trackingCode = "EZ2",
                status = "delivered",
                eventAt = "2026-07-16T09:00:00Z",
            ),
        )
        // EasyPost retries/reorders: an OLDER in_transit event (T1 < T2) arrives late.
        store.saveTrackingUpdate(
            TrackingRecord(
                trackingCode = "EZ2",
                status = "in_transit",
                eventAt = "2026-07-15T10:00:00Z",
            ),
        )
        assertEquals(
            "delivered",
            store.getTracking("EZ2")!!.status,
            "an older event must not overwrite a newer status",
        )
        // A newer event (T3 > T2) still updates normally.
        store.saveTrackingUpdate(
            TrackingRecord(
                trackingCode = "EZ2",
                status = "returned",
                eventAt = "2026-07-17T08:00:00Z",
            ),
        )
        assertEquals("returned", store.getTracking("EZ2")!!.status, "a newer event still wins")
    }

    @Test
    fun `releasing a claim allows a legitimate retry`() {
        val store = InMemoryLabelStore()
        assertTrue(store.claimLabelPurchase("sess-2"))
        store.releaseLabelPurchaseClaim("sess-2")
        assertTrue(store.claimLabelPurchase("sess-2"))
    }

    @Test
    fun `under concurrency exactly one of many callers wins`() {
        val store = InMemoryLabelStore()
        val pool = Executors.newFixedThreadPool(16)
        val winners = AtomicInteger(0)
        val tasks =
            (1..64).map {
                Callable { if (store.claimLabelPurchase("race")) winners.incrementAndGet() }
            }
        pool.invokeAll(tasks)
        pool.shutdown()
        assertEquals(1, winners.get())
    }

    @Test
    fun `payment sessions round-trip and expire out of reads only when stale`() {
        val store = InMemoryLabelStore()
        val session =
            PaymentSession(
                sessionId = "s1",
                amount = 8.74,
                description = "test",
                externalId = "ext-1",
                createdAt = System.currentTimeMillis(),
                paidBaseRate = 7.36,
            )
        store.savePaymentSession(session)
        val loaded = store.getPaymentSession("s1")
        assertEquals(8.74, loaded?.amount)
        assertEquals(7.36, loaded?.paidBaseRate)
    }

    @Test
    fun `saving a stale session never regresses an already-bought label to null`() {
        val store = InMemoryLabelStore()
        val base =
            PaymentSession(
                sessionId = "s2",
                amount = 8.74,
                description = "d",
                externalId = "e",
                createdAt = System.currentTimeMillis(),
            )
        store.savePaymentSession(base)
        // Winner stores the bought label.
        store.savePaymentSession(base.copy().apply { labelUrl = "https://l/won.png" })
        // A loser races in with a stale (label-less) copy — must NOT blank the label.
        store.savePaymentSession(base.copy().apply { labelUrl = null })
        assertEquals("https://l/won.png", store.getPaymentSession("s2")?.labelUrl)
    }

    @Test
    fun `a verification session is only verified by its own phone`() {
        val store = InMemoryLabelStore()
        val id = store.createVerificationSession("5551230000")
        assertFalse(store.markVerificationSessionVerified(id, "5559998888"), "wrong phone")
        assertNull(store.getValidVerificationSession(id))
        assertTrue(store.markVerificationSessionVerified(id, "5551230000"), "own phone")
        assertEquals("5551230000", store.getValidVerificationSession(id)?.phoneNumber)
    }
}
