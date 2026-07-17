package com.lifted.shipkit.http

import com.google.gson.JsonParser
import com.lifted.shipkit.buildApp
import com.lifted.shipkit.config.ShipKitConfig
import com.lifted.shipkit.events.UsageEventEmitter
import com.lifted.shipkit.events.UsageEventsConfig
import com.lifted.shipkit.model.BoughtLabel
import com.lifted.shipkit.model.PaymentSession
import com.lifted.shipkit.model.TierMode
import com.lifted.shipkit.payments.GatewayResult
import com.lifted.shipkit.payments.LiftedPaymentsClient
import com.lifted.shipkit.payments.PaymentVerification
import com.lifted.shipkit.security.InMemoryApiKeyStore
import com.lifted.shipkit.security.KeyGenerator
import com.lifted.shipkit.shipping.EasyPostService
import com.lifted.shipkit.shipping.PaymentPricing
import com.lifted.shipkit.sms.DisabledSmsVerifier
import com.lifted.shipkit.sms.SmsConfig
import com.lifted.shipkit.store.InMemoryLabelStore
import com.lifted.shipkit.store.StoreBackend
import io.javalin.Javalin
import io.javalin.testtools.JavalinTest
import io.mockk.every
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The managed-deployment hooks, end-to-end over the real route graph:
 *
 *  - the `SHIPKIT_MANAGED_CONFIG_TOKEN` bearer alternative on
 *    `POST /api/config/markup` (fail-closed when unset, constant-time compare,
 *    scoped to that one route), including the `{markup_pct, fixed_fee}`
 *    percent+dollars body shape and its validation/mapping; and
 *  - the `label.purchased` usage-event webhook fired by both purchase paths.
 */
class ManagedOpsIntegrationTest {
    private val jsonMedia = "application/json".toMediaType()
    private val ok = OkHttpClient()

    private fun config(managedToken: String? = null) =
        ShipKitConfig(
            port = 0,
            baseUrl = "http://localhost",
            corsOrigins = "*",
            easyPostApiKey = null,
            easyPostWebhookSecret = null,
            payments = null,
            adminPhoneWhitelist = emptyList(),
            sms = SmsConfig(),
            storeBackend = StoreBackend.MEMORY,
            db = null,
            managedConfigToken = managedToken,
        )

    private class Wired(
        val app: Javalin,
        val key: String,
        val keyPrefix: String,
        val store: InMemoryLabelStore,
    )

    private fun wire(
        config: ShipKitConfig,
        easyPost: EasyPostService? = null,
        payments: LiftedPaymentsClient? = null,
        store: InMemoryLabelStore = InMemoryLabelStore(),
        events: UsageEventEmitter = UsageEventEmitter.DISABLED,
    ): Wired {
        val keys = InMemoryApiKeyStore()
        val minted = KeyGenerator.mint("managed-ops", KeyGenerator.Mode.TEST)
        keys.add(minted.record)
        val handlers =
            Handlers(config, store, easyPost, payments, DisabledSmsVerifier, keys, events)
        return Wired(buildApp(config, handlers), minted.plaintext, minted.record.prefix, store)
    }

    private fun request(
        origin: String,
        path: String,
        method: String = "POST",
        body: String? = "{}",
        apiKey: String? = null,
        bearer: String? = null,
    ): Response {
        val b = Request.Builder().url(origin + path)
        when (method) {
            "POST" -> b.post((body ?: "{}").toRequestBody(jsonMedia))
            else -> b.get()
        }
        if (apiKey != null) b.header("ShipKit-Api-Key", apiKey)
        if (bearer != null) b.header("Authorization", "Bearer $bearer")
        return ok.newCall(b.build()).execute()
    }

    // ---- Managed markup-config endpoint --------------------------------------

    @Test
    fun `the managed bearer token updates markup with the percent-and-dollars shape`() {
        val w = wire(config(managedToken = "mct_secret_1"))
        JavalinTest.test(w.app) { _, client ->
            request(
                client.origin,
                "/api/config/markup",
                body = """{"markup_pct":10.0,"fixed_fee":0.75,"card_fee_pct":3.75}""",
                bearer = "mct_secret_1",
            ).use {
                val body = it.body!!.string()
                assertEquals(200, it.code, body)
                assertTrue(body.contains(""""success":true"""), body)
                assertTrue(body.contains(""""card_fee_pct":3.75"""), body)
            }
        }
        // Percent + dollars mapped into the store's canonical MarkupConfig.
        val stored = w.store.getMarkupConfig()
        assertEquals(10.0, stored.percentageMarkup)
        assertEquals(75, stored.fixedFeeCents)
    }

    @Test
    fun `the bearer path also accepts the native markup shape`() {
        val w = wire(config(managedToken = "mct_secret_1"))
        JavalinTest.test(w.app) { _, client ->
            request(
                client.origin,
                "/api/config/markup",
                body = """{"percentage_markup":15.0,"fixed_fee_cents":25}""",
                bearer = "mct_secret_1",
            ).use { assertEquals(200, it.code) }
        }
        assertEquals(15.0, w.store.getMarkupConfig().percentageMarkup)
        assertEquals(25, w.store.getMarkupConfig().fixedFeeCents)
    }

    @Test
    fun `a wrong or missing bearer token is refused`() {
        val w = wire(config(managedToken = "mct_secret_1"))
        JavalinTest.test(w.app) { _, client ->
            request(
                client.origin,
                "/api/config/markup",
                body = """{"markup_pct":10.0,"fixed_fee":0.75}""",
                bearer = "mct_WRONG",
            ).use { assertEquals(401, it.code, "wrong token -> 401") }
            request(
                client.origin,
                "/api/config/markup",
                body = """{"markup_pct":10.0,"fixed_fee":0.75}""",
            ).use { assertEquals(401, it.code, "no credentials -> 401") }
        }
        // Nothing was written.
        assertEquals(12.0, w.store.getMarkupConfig().percentageMarkup)
    }

    @Test
    fun `with no managed token configured the bearer path is disabled - fail closed`() {
        val w = wire(config(managedToken = null))
        JavalinTest.test(w.app) { _, client ->
            // Any bearer value is refused when the env is unset...
            request(
                client.origin,
                "/api/config/markup",
                body = """{"markup_pct":10.0,"fixed_fee":0.75}""",
                bearer = "",
            ).use { assertEquals(401, it.code, "empty bearer refused") }
            request(
                client.origin,
                "/api/config/markup",
                body = """{"markup_pct":10.0,"fixed_fee":0.75}""",
                bearer = "anything",
            ).use { assertEquals(401, it.code, "unset env -> bearer path disabled") }
            // ...while the existing sk_ path still works (no duplicated logic).
            request(
                client.origin,
                "/api/config/markup",
                body = """{"markup_pct":10.0,"fixed_fee":0.75}""",
                apiKey = w.key,
            ).use { assertEquals(200, it.code, "sk_ key still writes markup") }
        }
        assertEquals(10.0, w.store.getMarkupConfig().percentageMarkup)
        assertEquals(75, w.store.getMarkupConfig().fixedFeeCents)
    }

    @Test
    fun `the bearer scheme name is case-insensitive per RFC 7235`() {
        val w = wire(config(managedToken = "mct_secret_1"))
        JavalinTest.test(w.app) { _, client ->
            val b =
                Request
                    .Builder()
                    .url("${client.origin}/api/config/markup")
                    .post("""{"markup_pct":9.0,"fixed_fee":0.10}""".toRequestBody(jsonMedia))
                    .header("Authorization", "bearer mct_secret_1")
            ok.newCall(b.build()).execute().use { assertEquals(200, it.code) }
        }
        assertEquals(9.0, w.store.getMarkupConfig().percentageMarkup)
        assertEquals(10, w.store.getMarkupConfig().fixedFeeCents)
    }

    @Test
    fun `the managed token opens ONLY the markup write - no other route`() {
        val w = wire(config(managedToken = "mct_secret_1"))
        JavalinTest.test(w.app) { _, client ->
            request(
                client.origin,
                "/api/config/markup",
                method = "GET",
                body = null,
                bearer = "mct_secret_1",
            ).use { assertEquals(401, it.code, "GET markup still needs an API key") }
            request(
                client.origin,
                "/api/shipment/buy",
                bearer = "mct_secret_1",
            ).use { assertEquals(401, it.code, "privileged routes stay closed") }
            request(
                client.origin,
                "/api/keys",
                bearer = "mct_secret_1",
            ).use { assertEquals(401, it.code, "key management stays closed") }
        }
    }

    @Test
    fun `managed shape validation rejects out-of-range and missing fields`() {
        val w = wire(config(managedToken = "mct_secret_1"))
        val bad =
            listOf(
                """{"markup_pct":150.0,"fixed_fee":0.75}""" to "markup_pct above 100",
                """{"markup_pct":-1.0,"fixed_fee":0.75}""" to "negative markup_pct",
                """{"markup_pct":10.0,"fixed_fee":2000.0}""" to "fixed_fee above cap",
                """{"markup_pct":10.0,"fixed_fee":-0.01}""" to "negative fixed_fee",
                """{"markup_pct":10.0}""" to "missing fixed_fee",
                """{"fixed_fee":0.75}""" to "missing markup_pct",
                """{"markup_pct":10.0,"fixed_fee":0.75,"card_fee_pct":101}""" to
                    "card_fee_pct above 100",
                """{"percentage_markup":12.0,"fixed_fee_cents":50,"card_fee_pct":500}""" to
                    "card_fee_pct validated with the native shape too",
                """{"markup_pct":10.0,"fixed_fee":0.75,"percentage_markup":12.0,"fixed_fee_cents":50}""" to
                    "mixing both shapes is ambiguous",
            )
        JavalinTest.test(w.app) { _, client ->
            bad.forEach { (body, why) ->
                request(
                    client.origin,
                    "/api/config/markup",
                    body = body,
                    bearer = "mct_secret_1",
                ).use { assertEquals(400, it.code, why) }
            }
        }
        // Defaults untouched after every rejected write.
        assertEquals(12.0, w.store.getMarkupConfig().percentageMarkup)
        assertEquals(50, w.store.getMarkupConfig().fixedFeeCents)
    }

    // ---- Usage-event emission from the purchase paths ------------------------

    /** Start a webhook-capture server; returns (app, url, bodies, latch). */
    private fun captureHook(
        latch: CountDownLatch,
    ): Triple<Javalin, String, ConcurrentLinkedQueue<String>> {
        val bodies = ConcurrentLinkedQueue<String>()
        val app =
            Javalin
                .create()
                .post("/hook") { ctx ->
                    bodies.add(ctx.body())
                    ctx.status(200)
                    latch.countDown()
                }.start(0)
        return Triple(app, "http://localhost:${app.port()}/hook", bodies)
    }

    @Test
    fun `a 3DS session purchase emits label_purchased with the key prefix as tenant`() {
        val latch = CountDownLatch(1)
        val (hookApp, hookUrl, bodies) = captureHook(latch)
        try {
            val easyPost = mockk<EasyPostService>()
            val payments = mockk<LiftedPaymentsClient>()
            every { payments.verifyPayment(any()) } returns
                PaymentVerification("approved", eci = "05", cavv = "c", liabilityShift = true)
            every { easyPost.buyLabel(any(), any(), any(), any()) } returns
                BoughtLabel(
                    shipmentId = "shp_1",
                    trackingCode = "1Z999",
                    labelUrl = "https://label.example/1.png",
                    carrier = "USPS",
                    service = "Priority",
                    baseRate = 7.36,
                )
            val store =
                InMemoryLabelStore().apply {
                    savePaymentSession(
                        PaymentSession(
                            sessionId = "s1",
                            amount = 8.74,
                            description = "Shipping Label Purchase",
                            externalId = "ext-1",
                            createdAt = System.currentTimeMillis(),
                            shipmentId = "shp_1",
                            rateId = "rate_1",
                            paidBaseRate = 7.36,
                            currency = "USD",
                        ),
                    )
                }
            UsageEventEmitter(UsageEventsConfig(hookUrl, "whsec_1"), retryDelayMs = 10)
                .use { emitter ->
                    val w =
                        wire(
                            config(),
                            easyPost = easyPost,
                            payments = payments,
                            store = store,
                            events = emitter,
                        )
                    JavalinTest.test(w.app) { _, client ->
                        request(
                            client.origin,
                            "/api/payment/purchase-label/s1",
                            apiKey = w.key,
                        ).use { assertEquals(200, it.code) }
                        assertTrue(latch.await(5, TimeUnit.SECONDS), "event delivered")
                    }
                    val root = JsonParser.parseString(bodies.first()).asJsonObject
                    assertEquals("label.purchased", root["event"].asString)
                    // The tenant is the key's short display prefix, never the full key.
                    assertEquals(w.keyPrefix, root["tenantKey"].asString)
                    assertTrue(!bodies.first().contains(w.key), "full key never leaves")
                    val data = root["data"].asJsonObject
                    assertEquals("s1", data["session_id"].asString)
                    assertEquals(736, data["carrier_cost_cents"].asInt)
                    assertEquals(874, data["buyer_charge_cents"].asInt)
                    assertEquals(12.0, data["markup_pct"].asDouble)
                    assertEquals(50, data["fixed_fee_cents"].asInt)
                    assertEquals("shp_1", data["easypost_shipment_id"].asString)
                    assertEquals("ext-1", data["payment_txn_id"].asString)
                    assertEquals("card_3ds", data["payment_rail"].asString)
                }
        } finally {
            hookApp.stop()
        }
    }

    @Test
    fun `a saved-card purchase emits label_purchased on the card_saved rail`() {
        val latch = CountDownLatch(1)
        val (hookApp, hookUrl, bodies) = captureHook(latch)
        try {
            val easyPost = mockk<EasyPostService>()
            val payments = mockk<LiftedPaymentsClient>()
            every { easyPost.priceRate(any(), any(), any()) } returns
                PaymentPricing.Quote(
                    amount = "8.74",
                    baseRate = BigDecimal("7.36"),
                    currency = "USD",
                )
            every { payments.chargeSavedCard(any(), any(), any()) } returns
                GatewayResult(transactionId = "txn_s", status = "approved", approved = true)
            every { easyPost.buyLabel(any(), any(), any(), any()) } returns
                BoughtLabel(
                    shipmentId = "shp_1",
                    trackingCode = "1Z999",
                    labelUrl = "https://label.example/1.png",
                    carrier = "USPS",
                    service = "Priority",
                    baseRate = 7.36,
                )
            val cfg = config().copy(tier = TierMode.MANAGED, frictionlessAllowed = true)
            UsageEventEmitter(UsageEventsConfig(hookUrl), retryDelayMs = 10).use { emitter ->
                val w = wire(cfg, easyPost = easyPost, payments = payments, events = emitter)
                JavalinTest.test(w.app) { _, client ->
                    request(
                        client.origin,
                        "/api/payment/charge-saved-card",
                        body =
                            """{"vault_id":"cust_1:card_1","shipment_id":"shp_1",""" +
                                """"rate_id":"rate_1","idempotency_key":"k-ev"}""",
                        apiKey = w.key,
                    ).use { assertEquals(200, it.code) }
                    assertTrue(latch.await(5, TimeUnit.SECONDS), "event delivered")
                }
                val root = JsonParser.parseString(bodies.first()).asJsonObject
                val data = root["data"].asJsonObject
                assertEquals("card_saved", data["payment_rail"].asString)
                assertEquals("saved-k-ev", data["session_id"].asString)
                assertEquals(874, data["buyer_charge_cents"].asInt)
                assertEquals("txn_s", data["payment_txn_id"].asString)
            }
        } finally {
            hookApp.stop()
        }
    }

    @Test
    fun `with no webhook configured a purchase succeeds and sends nothing`() {
        val easyPost = mockk<EasyPostService>()
        val payments = mockk<LiftedPaymentsClient>()
        every { payments.verifyPayment(any()) } returns
            PaymentVerification("approved", eci = "05", cavv = "c", liabilityShift = true)
        every { easyPost.buyLabel(any(), any(), any(), any()) } returns
            BoughtLabel(shipmentId = "shp_1", labelUrl = "https://label.example/1.png")
        val store =
            InMemoryLabelStore().apply {
                savePaymentSession(
                    PaymentSession(
                        sessionId = "s1",
                        amount = 8.74,
                        description = "d",
                        externalId = "ext-1",
                        createdAt = System.currentTimeMillis(),
                        shipmentId = "shp_1",
                        rateId = "rate_1",
                        paidBaseRate = 7.36,
                    ),
                )
            }
        // Default emitter (DISABLED): the purchase path must be unaffected.
        val w = wire(config(), easyPost = easyPost, payments = payments, store = store)
        JavalinTest.test(w.app) { _, client ->
            request(client.origin, "/api/payment/purchase-label/s1", apiKey = w.key).use {
                assertEquals(200, it.code)
            }
        }
    }
}
