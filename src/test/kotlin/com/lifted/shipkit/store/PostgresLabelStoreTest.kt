package com.lifted.shipkit.store

import com.lifted.shipkit.model.LabelRecord
import com.lifted.shipkit.model.MarkupConfig
import com.lifted.shipkit.model.PaymentSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * [PostgresLabelStore] against a real **postgres:16** (Testcontainers, or an
 * external DB via `-Dshipkit.test.pg.url`). When neither is available the whole
 * class is *skipped* (not failed) — matching CONTRACTS §8 ("skip gracefully if
 * Docker absent"). See [PgTestSupport].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresLabelStoreTest {
    private lateinit var handle: PgTestSupport.Handle
    private lateinit var store: PostgresLabelStore

    @BeforeAll
    fun setUp() {
        handle = PgTestSupport.acquire()
        store = PostgresLabelStore(handle.db)
        store.start()
    }

    @AfterAll
    fun tearDown() {
        if (this::store.isInitialized) store.close()
        if (this::handle.isInitialized) handle.close()
    }

    @Test
    fun `markup config seeds the default and round-trips an update`() {
        // start() seeds DEFAULT.
        assertEquals(
            MarkupConfig.DEFAULT.percentageMarkup,
            store.getMarkupConfig().percentageMarkup,
            1e-9,
        )
        store.updateMarkupConfig(MarkupConfig(percentageMarkup = 20.0, fixedFeeCents = 99))
        assertEquals(20.0, store.getMarkupConfig().percentageMarkup, 1e-9)
        assertEquals(99, store.getMarkupConfig().fixedFeeCents)
        // Restore so other tests see the default.
        store.updateMarkupConfig(MarkupConfig.DEFAULT)
    }

    @Test
    fun `end shipper id persists`() {
        assertNull(store.getEndShipperId())
        store.setEndShipperId("es_123")
        assertEquals("es_123", store.getEndShipperId())
    }

    @Test
    fun `labels save and are retrievable by id, session, and phone`() {
        val label =
            LabelRecord(
                id = "lbl_1",
                sessionId = "sess_A",
                labelUrl = "https://label/a.png",
                trackingCode = "1Z",
                carrier = "USPS",
                service = "Priority",
                amount = 8.74,
                baseRate = 7.36,
                percentageMarkup = 12.0,
                fixedFeeCents = 50,
                shipmentId = "shp_A",
                senderPhone = "+1 (555) 111-2222",
            )
        store.saveLabel(label)

        val byId = store.getLabel("lbl_1")
        assertNotNull(byId)
        assertEquals("https://label/a.png", byId!!.labelUrl)
        // amount/baseRate are NUMERIC(12,2) and come back exact.
        assertEquals(8.74, byId.amount!!, 1e-9)
        assertEquals(1.38, byId.profit!!, 1e-9)

        assertNotNull(store.getLabelBySession("sess_A"))
        // Phone is normalized to 10 digits on write and on query.
        assertEquals(1, store.getLabelsByPhone("5551112222").size)
    }

    @Test
    fun `payment session round-trips including the three_ds JSONB`() {
        val s =
            PaymentSession(
                sessionId = "psess_1",
                amount = 8.74,
                description = "Shipping",
                externalId = "ext_1",
                createdAt = System.currentTimeMillis(),
                shipmentId = "shp_1",
                rateId = "rate_1",
                paidBaseRate = 7.36,
                currency = "USD",
            ).apply {
                status = "approved"
                threeDsEci = "05"
                threeDsCavv = "cavv3ds01"
                liabilityShift = true
            }
        store.savePaymentSession(s)

        val loaded = store.getPaymentSession("psess_1")
        assertNotNull(loaded)
        assertEquals("approved", loaded!!.status)
        assertEquals(8.74, loaded.amount, 1e-9)
        assertEquals(7.36, loaded.paidBaseRate!!, 1e-9)
        assertEquals("05", loaded.threeDsEci)
        assertEquals("cavv3ds01", loaded.threeDsCavv)
        assertTrue(loaded.liabilityShift)
    }

    @Test
    fun `claimLabelPurchase is atomic — exactly one concurrent caller wins`() {
        store.savePaymentSession(
            PaymentSession(
                sessionId = "race_pg",
                amount = 1.0,
                description = "d",
                externalId = "e",
                createdAt = System.currentTimeMillis(),
            ),
        )
        val pool = Executors.newFixedThreadPool(8)
        val winners = AtomicInteger(0)
        val tasks =
            (1..16).map {
                Callable { if (store.claimLabelPurchase("race_pg")) winners.incrementAndGet() }
            }
        pool.invokeAll(tasks)
        pool.shutdown()
        assertEquals(1, winners.get(), "the DB claim must admit exactly one buyer")

        // Releasing lets a legitimate retry re-claim.
        store.releaseLabelPurchaseClaim("race_pg")
        assertTrue(store.claimLabelPurchase("race_pg"))
    }

    @Test
    fun `verification sessions are only valid once marked verified`() {
        val id = store.createVerificationSession("5551234567")
        assertNull(store.getValidVerificationSession(id), "unverified session is not valid")
        // A DIFFERENT phone must not verify this session (phone binding).
        assertFalse(store.markVerificationSessionVerified(id, "5559998888"))
        assertNull(store.getValidVerificationSession(id), "mismatched-phone check did not verify")
        // The session's own phone verifies it.
        assertTrue(store.markVerificationSessionVerified(id, "5551234567"))
        val v = store.getValidVerificationSession(id)
        assertNotNull(v)
        assertEquals("5551234567", v!!.phoneNumber)
    }

    @Test
    fun `deleting a label by session removes it`() {
        store.saveLabel(
            LabelRecord(id = "lbl_del", sessionId = "sess_del", labelUrl = "https://l/x.png"),
        )
        assertNotNull(store.getLabelBySession("sess_del"))
        assertTrue(store.deleteLabelBySession("sess_del"))
        assertNull(store.getLabelBySession("sess_del"))
        assertFalse(store.deleteLabelBySession("sess_del"), "second delete is a no-op")
    }
}
