package com.lifted.shipkit.http

import com.lifted.shipkit.buildApp
import com.lifted.shipkit.config.ShipKitConfig
import com.lifted.shipkit.model.BoughtLabel
import com.lifted.shipkit.model.PaymentSession
import com.lifted.shipkit.payments.LiftedPaymentsClient
import com.lifted.shipkit.payments.PaymentVerification
import com.lifted.shipkit.security.ApiKeyStore
import com.lifted.shipkit.security.InMemoryApiKeyStore
import com.lifted.shipkit.security.KeyGenerator
import com.lifted.shipkit.shipping.EasyPostService
import com.lifted.shipkit.sms.DisabledSmsVerifier
import com.lifted.shipkit.sms.SmsConfig
import com.lifted.shipkit.sms.SmsVerifier
import com.lifted.shipkit.store.InMemoryLabelStore
import com.lifted.shipkit.store.LabelStore
import com.lifted.shipkit.store.StoreBackend
import io.javalin.Javalin
import io.javalin.testtools.JavalinTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.text.Normalizer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end HTTP tests over the real Javalin route graph and API-key filter,
 * with the EasyPost + Lifted Payments clients faked (MockK). These lock the
 * Fortune-500 platform guarantees: no `/api` call without a key, every
 * unconfigured feature degrades to `503`, forced-3DS is enforced at the edge,
 * the label purchase is idempotent under concurrency, and the EasyPost webhook
 * is HMAC-verified.
 */
class ApiIntegrationTest {
    private val jsonMedia = "application/json".toMediaType()
    private val ok = OkHttpClient()

    private fun config(webhookSecret: String? = null) =
        ShipKitConfig(
            port = 0,
            baseUrl = "http://localhost",
            corsOrigins = "*",
            easyPostApiKey = null,
            easyPostWebhookSecret = webhookSecret,
            payments = null,
            adminPhoneWhitelist = emptyList(),
            sms = SmsConfig(),
            storeBackend = StoreBackend.MEMORY,
            db = null,
        )

    private class Wired(
        val app: Javalin,
        val key: String,
        val store: LabelStore,
        val keys: ApiKeyStore,
    )

    private fun wire(
        easyPost: EasyPostService? = null,
        payments: LiftedPaymentsClient? = null,
        sms: SmsVerifier = DisabledSmsVerifier,
        store: LabelStore = InMemoryLabelStore(),
        config: ShipKitConfig = config(),
    ): Wired {
        val keys = InMemoryApiKeyStore()
        val minted = KeyGenerator.mint("integration", KeyGenerator.Mode.TEST)
        keys.add(minted.record)
        val handlers = Handlers(config, store, easyPost, payments, sms, keys)
        return Wired(buildApp(config, handlers), minted.plaintext, store, keys)
    }

    private fun post(
        origin: String,
        path: String,
        key: String?,
        body: String = "{}",
    ): Response {
        val b =
            Request
                .Builder()
                .url(origin + path)
                .post(body.toRequestBody(jsonMedia))
        if (key != null) b.header("ShipKit-Api-Key", key)
        return ok.newCall(b.build()).execute()
    }

    private fun get(
        origin: String,
        path: String,
        key: String?,
    ): Response {
        val b = Request.Builder().url(origin + path).get()
        if (key != null) b.header("ShipKit-Api-Key", key)
        return ok.newCall(b.build()).execute()
    }

    private fun session() =
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
        )

    private fun boughtLabel() =
        BoughtLabel(
            shipmentId = "shp_1",
            trackingCode = "1Z999",
            labelUrl = "https://label.example/1.png",
            qrCodeUrl = null,
            carrier = "USPS",
            service = "Priority",
            baseRate = 7.36,
        )

    // ---- API-key gate --------------------------------------------------------

    @Test
    fun `every api route rejects a request with no or invalid ShipKit-Api-Key`() {
        val w = wire()
        JavalinTest.test(w.app) { _, client ->
            post(client.origin, "/api/shipment/create", key = null).use {
                assertEquals(401, it.code, "no key -> 401")
            }
            post(client.origin, "/api/shipment/create", key = "sk_test_totally_wrong").use {
                assertEquals(401, it.code, "bad key -> 401")
            }
            // Health is deliberately open (liveness probe) and needs no key.
            get(client.origin, "/api/health", key = null).use {
                assertEquals(200, it.code, "health is unauthenticated")
            }
            // A valid key is admitted (this feature is unconfigured -> 503, not 401).
            post(client.origin, "/api/shipment/create", key = w.key).use {
                assertEquals(503, it.code, "valid key passes the gate")
            }
        }
    }

    @Test
    fun `a revoked key is rejected at the gate`() {
        val w = wire()
        w.keys.listAll().forEach { w.keys.revoke(it.id) }
        JavalinTest.test(w.app) { _, client ->
            post(client.origin, "/api/shipment/create", key = w.key).use {
                assertEquals(401, it.code)
            }
        }
    }

    // ---- Graceful 503 for each unconfigured feature --------------------------

    @Test
    fun `each unconfigured feature returns 503 for an authenticated caller`() {
        val w = wire() // easyPost=null, payments=null, sms disabled
        JavalinTest.test(w.app) { _, client ->
            post(client.origin, "/api/shipment/create", w.key).use {
                assertEquals(503, it.code, "shipping unconfigured")
            }
            post(client.origin, "/api/payment/session", w.key).use {
                assertEquals(503, it.code, "payments unconfigured")
            }
            post(client.origin, "/api/verification/start", w.key).use {
                assertEquals(503, it.code, "sms disabled")
            }
        }
    }

    // ---- Happy path + idempotency --------------------------------------------

    @Test
    fun `happy-path label buy returns the label and the second call is idempotent`() {
        val easyPost = mockk<EasyPostService>()
        val payments = mockk<LiftedPaymentsClient>()
        every { payments.verifyPayment(any()) } returns
            PaymentVerification("approved", eci = "05", cavv = "crypto", liabilityShift = true)
        every { easyPost.buyLabel(any(), any(), any(), any()) } returns boughtLabel()

        val store = InMemoryLabelStore().apply { savePaymentSession(session()) }
        val w = wire(easyPost = easyPost, payments = payments, store = store)

        JavalinTest.test(w.app) { _, client ->
            val first =
                post(client.origin, "/api/payment/purchase-label/s1", w.key).use {
                    it.code to
                        it.body!!.string()
                }
            assertEquals(200, first.first)
            assertTrue(first.second.contains("https://label.example/1.png"), "label URL returned")

            val second =
                post(client.origin, "/api/payment/purchase-label/s1", w.key).use {
                    it.code to
                        it.body!!.string()
                }
            assertEquals(200, second.first)
            assertTrue(
                second.second.contains("https://label.example/1.png"),
                "same label on repeat call",
            )
        }
        // The carrier was charged exactly once across both calls.
        verify(exactly = 1) { easyPost.buyLabel(any(), any(), any(), any()) }
    }

    // ---- Forced 3-D Secure enforcement ---------------------------------------

    @Test
    fun `a declined 3DS result refuses the purchase and never buys a label`() {
        val easyPost = mockk<EasyPostService>()
        val payments = mockk<LiftedPaymentsClient>()
        every { payments.verifyPayment(any()) } returns PaymentVerification("declined")
        val store = InMemoryLabelStore().apply { savePaymentSession(session()) }
        val w = wire(easyPost = easyPost, payments = payments, store = store)

        JavalinTest.test(w.app) { _, client ->
            post(client.origin, "/api/payment/purchase-label/s1", w.key).use {
                assertEquals(402, it.code)
            }
        }
        verify(exactly = 0) { easyPost.buyLabel(any(), any(), any(), any()) }
    }

    @Test
    fun `approved WITHOUT a liability shift is refused end-to-end`() {
        val easyPost = mockk<EasyPostService>()
        val payments = mockk<LiftedPaymentsClient>()
        // Gateway approved, but no shift -> forced-3DS policy refuses the buy.
        every { payments.verifyPayment(any()) } returns
            PaymentVerification("approved", eci = "07", cavv = null, liabilityShift = false)
        val store = InMemoryLabelStore().apply { savePaymentSession(session()) }
        val w = wire(easyPost = easyPost, payments = payments, store = store)

        JavalinTest.test(w.app) { _, client ->
            post(client.origin, "/api/payment/purchase-label/s1", w.key).use {
                assertEquals(402, it.code)
            }
        }
        verify(exactly = 0) { easyPost.buyLabel(any(), any(), any(), any()) }
    }

    // ---- Concurrency: exactly-once purchase -----------------------------------

    @Test
    fun `concurrent purchase calls buy exactly one label and never diverge`() {
        val easyPost = mockk<EasyPostService>()
        val payments = mockk<LiftedPaymentsClient>()
        every { payments.verifyPayment(any()) } returns
            PaymentVerification("approved", eci = "05", cavv = "crypto", liabilityShift = true)
        // Slow the buy so all callers race the single claim window.
        every { easyPost.buyLabel(any(), any(), any(), any()) } answers {
            Thread.sleep(200)
            boughtLabel()
        }
        val store = InMemoryLabelStore().apply { savePaymentSession(session()) }
        val w = wire(easyPost = easyPost, payments = payments, store = store)

        JavalinTest.test(w.app) { _, client ->
            val pool = Executors.newFixedThreadPool(8)
            val tasks =
                (1..8).map {
                    Callable {
                        post(client.origin, "/api/payment/purchase-label/s1", w.key).use {
                            it.code to it.body!!.string()
                        }
                    }
                }
            val results = pool.invokeAll(tasks).map { it.get() }
            pool.shutdown()

            val codes = results.map { it.first }
            // Winners get 200 (with the label); losers racing the in-flight buy get 409.
            assertTrue(codes.all { it == 200 || it == 409 }, "only 200/409, got $codes")
            assertTrue(codes.contains(200), "at least one caller receives the label")
            val labelUrls =
                results
                    .filter { it.first == 200 }
                    .map { Regex(""""label_url":"([^"]+)"""").find(it.second)?.groupValues?.get(1) }
                    .toSet()
            assertEquals(1, labelUrls.size, "all successful callers see the same single label")
        }
        // The critical guarantee: the carrier (and the card) is hit exactly once.
        verify(exactly = 1) { easyPost.buyLabel(any(), any(), any(), any()) }
    }

    // ---- Webhook HMAC verification -------------------------------------------

    private fun sign(
        secret: String,
        body: String,
    ): String {
        // Mirrors EasyPost's ValidateWebhook: NFKD(secret) key, HMAC-SHA256 over
        // the RAW body bytes, value prefixed with the literal "hmac-sha256-hex=".
        val key = Normalizer.normalize(secret, Normalizer.Form.NFKD).toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val hex =
            mac
                .doFinal(
                    body.toByteArray(Charsets.UTF_8),
                ).joinToString("") { "%02x".format(it) }
        return "hmac-sha256-hex=$hex"
    }

    private fun webhook(
        origin: String,
        sig: String?,
        body: String,
    ): Response {
        val b =
            Request
                .Builder()
                .url(
                    "$origin/api/webhook/easypost",
                ).post(body.toRequestBody(jsonMedia))
        if (sig != null) b.header("X-Hmac-Signature", sig)
        return ok.newCall(b.build()).execute()
    }

    @Test
    fun `webhook accepts a valid HMAC signature and rejects tampering, missing, and wrong-prefix`() {
        // Precomposed 'é' (U+00E9) exercises NFKD normalization of the secret.
        val secret = "whsec_café_2026"
        val w = wire(config = config(webhookSecret = secret))
        val body = """{"description":"tracker.updated","result":{"status":"delivered"}}"""

        JavalinTest.test(w.app) { _, client ->
            // Valid signature over the exact raw bytes -> accepted (no API key needed).
            webhook(client.origin, sign(secret, body), body).use {
                assertEquals(200, it.code, "valid HMAC accepted")
            }
            // Same signature but a mutated body -> rejected.
            webhook(
                client.origin,
                sign(secret, body),
                """{"description":"tracker.updated","evil":1}""",
            ).use {
                assertEquals(401, it.code, "tampered body rejected")
            }
            // Missing signature header -> rejected.
            webhook(client.origin, null, body).use {
                assertEquals(401, it.code, "missing signature rejected")
            }
            // Correct hex but without the required "hmac-sha256-hex=" prefix -> rejected.
            webhook(client.origin, sign(secret, body).removePrefix("hmac-sha256-hex="), body).use {
                assertEquals(401, it.code, "missing prefix rejected")
            }
        }
    }

    @Test
    fun `webhook returns 503 when no signing secret is configured`() {
        val w = wire(config = config(webhookSecret = null))
        JavalinTest.test(w.app) { _, client ->
            webhook(client.origin, "hmac-sha256-hex=deadbeef", "{}").use {
                assertEquals(503, it.code)
            }
        }
    }

    // ---- Publishable-key scope (browser widget) ------------------------------

    @Test
    fun `a publishable key runs the customer flow but is refused on privileged routes`() {
        val w = wire() // easyPost=null, payments=null → unconfigured features 503
        val pub =
            KeyGenerator.mint("widget", KeyGenerator.Mode.TEST, KeyGenerator.Scope.PUBLISHABLE)
        w.keys.add(pub.record)

        JavalinTest.test(w.app) { _, client ->
            // Allowed by scope → the ONLY reason these fail is the unconfigured
            // feature (503), proving the scope gate let them through.
            post(client.origin, "/api/shipment/create", pub.plaintext).use {
                assertEquals(503, it.code, "rating is publishable-safe")
            }
            post(client.origin, "/api/payment/session", pub.plaintext).use {
                assertEquals(503, it.code, "opening a payment session is publishable-safe")
            }
            // Refused by scope BEFORE any feature check (403, not 503).
            post(client.origin, "/api/shipment/buy", pub.plaintext).use {
                assertEquals(403, it.code, "a free label buy needs a secret key")
            }
            post(
                client.origin,
                "/api/config/markup",
                pub.plaintext,
                """{"percentage_markup":0,"fixed_fee_cents":0}""",
            ).use { assertEquals(403, it.code, "rewriting pricing needs a secret key") }
            post(client.origin, "/api/admin/cleanup", pub.plaintext).use {
                assertEquals(403, it.code, "purging data needs a secret key")
            }
            // A SECRET key is not scope-limited: the same privileged route only
            // 503s (unconfigured), never 403.
            post(client.origin, "/api/shipment/buy", w.key).use {
                assertEquals(503, it.code, "a secret key reaches the privileged route")
            }
        }
    }

    // ---- Payment charge-window + amount reconciliation ------------------------

    @Test
    fun `a purchase on an expired session is refused and never buys a label`() {
        val easyPost = mockk<EasyPostService>()
        val payments = mockk<LiftedPaymentsClient>()
        every { payments.verifyPayment(any()) } returns
            PaymentVerification("approved", eci = "05", cavv = "crypto", liabilityShift = true)
        // createdAt 31 minutes ago → past the 30-minute charge window.
        val stale = session().copy(createdAt = System.currentTimeMillis() - 31 * 60 * 1000L)
        val store = InMemoryLabelStore().apply { savePaymentSession(stale) }
        val w = wire(easyPost = easyPost, payments = payments, store = store)

        JavalinTest.test(w.app) { _, client ->
            post(client.origin, "/api/payment/purchase-label/s1", w.key).use {
                assertEquals(410, it.code, "an expired session cannot be bought")
            }
        }
        verify(exactly = 0) { easyPost.buyLabel(any(), any(), any(), any()) }
    }

    @Test
    fun `a charged amount that differs from the authorized amount refuses the buy`() {
        val easyPost = mockk<EasyPostService>()
        val payments = mockk<LiftedPaymentsClient>()
        // Gateway reports approved+shift but a DIFFERENT amount than the session's 8.74.
        every { payments.verifyPayment(any()) } returns
            PaymentVerification(
                "approved",
                eci = "05",
                cavv = "crypto",
                liabilityShift = true,
                amount = BigDecimal("999.99"),
            )
        val store = InMemoryLabelStore().apply { savePaymentSession(session()) }
        val w = wire(easyPost = easyPost, payments = payments, store = store)

        JavalinTest.test(w.app) { _, client ->
            post(client.origin, "/api/payment/purchase-label/s1", w.key).use {
                assertEquals(402, it.code, "an amount mismatch must refuse the buy")
            }
        }
        verify(exactly = 0) { easyPost.buyLabel(any(), any(), any(), any()) }
    }
}
