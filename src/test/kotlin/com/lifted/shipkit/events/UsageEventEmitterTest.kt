package com.lifted.shipkit.events

import com.google.gson.JsonParser
import io.javalin.Javalin
import io.javalin.testtools.JavalinTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The fire-and-forget guarantees of [UsageEventEmitter]: exact envelope shape
 * and auth header, strict no-op when unconfigured, one retry, and every
 * delivery failure swallowed (an unreachable webhook can never surface).
 */
class UsageEventEmitterTest {
    /** One delivered webhook request, as the receiving platform saw it. */
    private data class Received(
        val authorization: String?,
        val body: String,
    )

    private fun event() =
        LabelPurchasedEvent(
            tenantKey = "pk_test_abc123",
            sessionId = "s1",
            carrier = "USPS",
            service = "Priority",
            carrierCostCents = 736,
            markupPct = 12.0,
            fixedFeeCents = 50,
            buyerChargeCents = 874,
            currency = "USD",
            easyPostShipmentId = "shp_1",
            paymentTxnId = "txn_1",
            paymentRail = "card_3ds",
        )

    /** A capture server: records each request and answers from [statuses]. */
    private fun captureApp(
        received: ConcurrentLinkedQueue<Received>,
        latch: CountDownLatch,
        statuses: List<Int> = listOf(200),
    ): Javalin {
        val calls = AtomicInteger()
        return Javalin.create().post("/hook") { ctx ->
            received.add(Received(ctx.header("Authorization"), ctx.body()))
            val i = calls.getAndIncrement()
            ctx.status(statuses.getOrElse(i) { statuses.last() })
            latch.countDown()
        }
    }

    @Test
    fun `posts the versioned envelope with the bearer token`() {
        val received = ConcurrentLinkedQueue<Received>()
        val latch = CountDownLatch(1)
        JavalinTest.test(captureApp(received, latch)) { _, client ->
            UsageEventEmitter(
                UsageEventsConfig("${client.origin}/hook", bearerToken = "whsec_1"),
                retryDelayMs = 10,
            ).use { emitter ->
                emitter.emitLabelPurchased(event())
                assertTrue(latch.await(5, TimeUnit.SECONDS), "webhook was called")
            }
        }
        assertEquals(1, received.size, "delivered exactly once on success")
        val hit = received.first()
        assertEquals("Bearer whsec_1", hit.authorization)

        val root = JsonParser.parseString(hit.body).asJsonObject
        assertEquals("label.purchased", root["event"].asString)
        assertEquals(1, root["version"].asInt)
        assertEquals("pk_test_abc123", root["tenantKey"].asString)
        assertEquals("shipkit", root["source"].asString)
        // occurredAt is ISO-8601 UTC and parses back to an Instant.
        Instant.parse(root["occurredAt"].asString)

        val data = root["data"].asJsonObject
        assertEquals("s1", data["session_id"].asString)
        assertEquals("USPS", data["carrier"].asString)
        assertEquals("Priority", data["service"].asString)
        assertEquals(736, data["carrier_cost_cents"].asInt)
        assertEquals(12.0, data["markup_pct"].asDouble)
        assertEquals(50, data["fixed_fee_cents"].asInt)
        assertEquals(874, data["buyer_charge_cents"].asInt)
        assertEquals("USD", data["currency"].asString)
        assertEquals("shp_1", data["easypost_shipment_id"].asString)
        assertEquals("txn_1", data["payment_txn_id"].asString)
        assertEquals("card_3ds", data["payment_rail"].asString)
    }

    @Test
    fun `null optional fields are serialized as JSON null, not omitted`() {
        val received = ConcurrentLinkedQueue<Received>()
        val latch = CountDownLatch(1)
        JavalinTest.test(captureApp(received, latch)) { _, client ->
            UsageEventEmitter(UsageEventsConfig("${client.origin}/hook")).use { emitter ->
                emitter.emitLabelPurchased(
                    event().copy(tenantKey = null, carrier = null, carrierCostCents = null),
                )
                assertTrue(latch.await(5, TimeUnit.SECONDS))
            }
        }
        val root = JsonParser.parseString(received.first().body).asJsonObject
        assertTrue(root.has("tenantKey") && root["tenantKey"].isJsonNull)
        val data = root["data"].asJsonObject
        assertTrue(data.has("carrier") && data["carrier"].isJsonNull)
        assertTrue(data.has("carrier_cost_cents") && data["carrier_cost_cents"].isJsonNull)
        // No token configured -> no Authorization header at all.
        assertNull(received.first().authorization)
    }

    @Test
    fun `unset config is a strict no-op`() {
        val emitter = UsageEventEmitter(config = null)
        assertFalse(emitter.enabled)
        // Must not throw, block, or start anything.
        emitter.emitLabelPurchased(event())
        emitter.close()
    }

    @Test
    fun `a failed delivery is retried exactly once`() {
        val received = ConcurrentLinkedQueue<Received>()
        val latch = CountDownLatch(2)
        val app = captureApp(received, latch, statuses = listOf(500, 200))
        JavalinTest.test(app) { _, client ->
            UsageEventEmitter(
                UsageEventsConfig("${client.origin}/hook"),
                retryDelayMs = 10,
            ).use { emitter ->
                emitter.emitLabelPurchased(event())
                assertTrue(latch.await(5, TimeUnit.SECONDS), "retried after the 500")
            }
        }
        assertEquals(2, received.size, "original attempt + one retry")
        assertEquals(received.first().body, received.last().body, "same payload resent")
    }

    @Test
    fun `a 4xx from the receiver is NOT retried`() {
        val received = ConcurrentLinkedQueue<Received>()
        val latch = CountDownLatch(1)
        val app = captureApp(received, latch, statuses = listOf(400))
        JavalinTest.test(app) { _, client ->
            UsageEventEmitter(
                UsageEventsConfig("${client.origin}/hook"),
                retryDelayMs = 10,
            ).use { emitter ->
                emitter.emitLabelPurchased(event())
                assertTrue(latch.await(5, TimeUnit.SECONDS))
                // Give a would-be retry ample time to land before asserting.
                Thread.sleep(200)
            }
        }
        assertEquals(1, received.size, "a rejected payload is dropped, not resent")
    }

    @Test
    fun `an unreachable webhook is swallowed and never propagates`() {
        // Port 1 refuses connections; both attempts fail. emit + close must
        // still return normally — the purchase path can never be failed by this.
        val emitter =
            UsageEventEmitter(
                UsageEventsConfig("http://127.0.0.1:1/hook", bearerToken = "t"),
                retryDelayMs = 10,
            )
        emitter.emitLabelPurchased(event())
        emitter.close()
    }

    @Test
    fun `a failed delivery is surfaced on the observable failed-delivery counter`() {
        // Undercount must not be silent: a delivery that never succeeds increments
        // an observable counter (instead of being swallowed with no signal).
        val emitter =
            UsageEventEmitter(
                UsageEventsConfig("http://127.0.0.1:1/hook", bearerToken = "t"),
                retryDelayMs = 10,
            )
        emitter.emitLabelPurchased(event())
        // close() drains the worker (awaitTermination) so the delivery has run.
        emitter.close()
        assertEquals(
            1,
            emitter.failedDeliveryCount,
            "the failed delivery is counted, not swallowed silently",
        )
        assertEquals(
            0,
            emitter.droppedEventCount,
            "it reached delivery, so it is not a queue-full drop",
        )
    }

    @Test
    fun `a 4xx rejection increments the observable failed-delivery counter`() {
        val received = ConcurrentLinkedQueue<Received>()
        val latch = CountDownLatch(1)
        val app = captureApp(received, latch, statuses = listOf(400))
        JavalinTest.test(app) { _, client ->
            UsageEventEmitter(UsageEventsConfig("${client.origin}/hook"), retryDelayMs = 10).use { e ->
                e.emitLabelPurchased(event())
                assertTrue(latch.await(5, TimeUnit.SECONDS))
                e.close()
                assertEquals(
                    1,
                    e.failedDeliveryCount,
                    "a 4xx-rejected billing event is counted for reconciliation",
                )
            }
        }
    }

    @Test
    fun `queue-full drops increment the observable dropped-event counter`() {
        // Wedge the single worker inside a held webhook, then over-fill the tiny
        // bounded queue. The overflow events must be counted as dropped — an
        // observable signal that money_in/label_spend may under-count — not lost
        // silently the way DiscardOldestPolicy did.
        val releaseServer = CountDownLatch(1)
        val serverHit = CountDownLatch(1)
        val app =
            Javalin.create().post("/hook") { ctx ->
                serverHit.countDown()
                releaseServer.await(5, TimeUnit.SECONDS)
                ctx.status(200)
            }
        JavalinTest.test(app) { _, client ->
            UsageEventEmitter(
                UsageEventsConfig("${client.origin}/hook"),
                retryDelayMs = 10,
                maxQueuedEvents = 1,
            ).use { emitter ->
                // 1) Occupies the single worker, which blocks in the held webhook.
                emitter.emitLabelPurchased(event())
                assertTrue(serverHit.await(5, TimeUnit.SECONDS), "worker began delivering")
                // 2) Fills the 1-slot queue.
                emitter.emitLabelPurchased(event())
                // 3) & 4) Queue full -> rejected -> counted as dropped.
                emitter.emitLabelPurchased(event())
                emitter.emitLabelPurchased(event())
                val dropped = emitter.droppedEventCount
                releaseServer.countDown()
                assertTrue(dropped >= 1, "queue-full drops are counted, not silent (was $dropped)")
            }
        }
    }

    @Test
    fun `dollarsToCents rounds half-up to exact cents`() {
        assertEquals(874, LabelPurchasedEvent.dollarsToCents(8.74))
        assertEquals(736, LabelPurchasedEvent.dollarsToCents(7.36))
        assertEquals(1, LabelPurchasedEvent.dollarsToCents(0.005))
        assertEquals(0, LabelPurchasedEvent.dollarsToCents(0.0))
    }
}
