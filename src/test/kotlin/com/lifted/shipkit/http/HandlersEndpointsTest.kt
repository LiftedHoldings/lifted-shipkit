package com.lifted.shipkit.http

import com.lifted.shipkit.buildApp
import com.lifted.shipkit.config.ShipKitConfig
import com.lifted.shipkit.model.AddressResult
import com.lifted.shipkit.model.BatchResult
import com.lifted.shipkit.model.BoughtLabel
import com.lifted.shipkit.model.CarrierMessage
import com.lifted.shipkit.model.PaymentSession
import com.lifted.shipkit.model.CustomsResult
import com.lifted.shipkit.model.EndShipperResult
import com.lifted.shipkit.model.LabelRecord
import com.lifted.shipkit.model.RateQuote
import com.lifted.shipkit.model.ScanFormResult
import com.lifted.shipkit.model.ShipmentQuote
import com.lifted.shipkit.payments.HostedPaymentResult
import com.lifted.shipkit.payments.LiftedPaymentsClient
import com.lifted.shipkit.payments.PaymentVerification
import com.lifted.shipkit.security.InMemoryApiKeyStore
import com.lifted.shipkit.security.KeyGenerator
import com.lifted.shipkit.shipping.EasyPostService
import com.lifted.shipkit.shipping.PaymentPricing
import com.lifted.shipkit.sms.SmsConfig
import com.lifted.shipkit.sms.SmsVerifier
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

/**
 * Endpoint-by-endpoint coverage of the [Handlers] adapter surface with fake
 * service clients: the shipping endpoints, markup config, payment session/status,
 * label retrieval/shredding, SMS verification, and the admin-gated API-key
 * management. Complements [ApiIntegrationTest] (which owns the security-critical
 * gate/3DS/concurrency scenarios).
 */
class HandlersEndpointsTest {
    private val jsonMedia = "application/json".toMediaType()
    private val ok = OkHttpClient()
    private val adminPhone = "5551230000"

    /** A deterministic in-memory SMS verifier: the fixed code `123456` approves. */
    private class FakeSms(
        override val enabled: Boolean = true,
    ) : SmsVerifier {
        override fun start(phoneNumber: String) = true

        override fun check(
            phoneNumber: String,
            code: String,
        ) = code == "123456"
    }

    private val easyPost = mockk<EasyPostService>()
    private val payments = mockk<LiftedPaymentsClient>()
    private val store = InMemoryLabelStore()
    private val keys = InMemoryApiKeyStore()
    private val key =
        KeyGenerator
            .mint("it", KeyGenerator.Mode.TEST)
            .also {
                keys.add(it.record)
            }.plaintext

    private val config =
        ShipKitConfig(
            port = 0,
            baseUrl = "http://localhost",
            corsOrigins = "*",
            easyPostApiKey = "ep",
            easyPostWebhookSecret = null,
            payments = null,
            adminPhoneWhitelist = listOf(adminPhone),
            sms = SmsConfig(enabled = true),
            storeBackend = StoreBackend.MEMORY,
            db = null,
        )

    private fun app(): Javalin =
        buildApp(config, Handlers(config, store, easyPost, payments, FakeSms(), keys))

    private fun post(
        origin: String,
        path: String,
        body: String = "{}",
        key: String? = this.key,
        sessionId: String? = null,
    ): Response {
        val b = Request.Builder().url(origin + path).post(body.toRequestBody(jsonMedia))
        if (key != null) b.header("ShipKit-Api-Key", key)
        // Admin/history session id travels in the header only — never the URL.
        if (sessionId != null) b.header("X-Session-ID", sessionId)
        return ok.newCall(b.build()).execute()
    }

    private fun get(
        origin: String,
        path: String,
        key: String? = this.key,
        sessionId: String? = null,
    ): Response {
        val b = Request.Builder().url(origin + path).get()
        if (key != null) b.header("ShipKit-Api-Key", key)
        if (sessionId != null) b.header("X-Session-ID", sessionId)
        return ok.newCall(b.build()).execute()
    }

    private fun delete(
        origin: String,
        path: String,
        sessionId: String? = null,
    ): Response {
        val b =
            Request
                .Builder()
                .url(origin + path)
                .delete()
                .header("ShipKit-Api-Key", key)
        if (sessionId != null) b.header("X-Session-ID", sessionId)
        return ok.newCall(b.build()).execute()
    }

    // ---- Shipping endpoints --------------------------------------------------

    @Test
    fun `address verify, shipment create, buy, and smartrates return the mapped payloads`() {
        every { easyPost.verifyAddress(any()) } returns
            AddressResult(
                id = "adr_1",
                street1 = "1 A St",
                city = "Denver",
                state = "CO",
                zip = "80202",
                verified = true,
                residential = true,
            )
        every { easyPost.createShipment(any(), any(), any(), any(), any()) } returns
            ShipmentQuote(
                id = "shp_1",
                rates =
                    listOf(
                        RateQuote(
                            id = "rate_1",
                            carrier = "USPS",
                            service = "Priority",
                            rate = "8.74",
                            baseRate = 7.36,
                            deliveryDays = 2,
                        ),
                    ),
                messages = listOf(CarrierMessage("USPS", "info", "ok")),
            )
        every { easyPost.buyLabel(any(), any(), any(), any()) } returns
            BoughtLabel(
                shipmentId = "shp_1",
                trackingCode = "1Z",
                labelUrl = "https://l/1.png",
                carrier = "USPS",
                service = "Priority",
                baseRate = 7.36,
            )
        every { easyPost.estimatedDeliveryDates("shp_1") } returns
            listOf(mapOf("service" to "Priority"))

        JavalinTest.test(app()) { _, client ->
            post(
                client.origin,
                "/api/address/verify",
                """{"street1":"1 A St","city":"Denver","state":"CO","zip":"80202"}""",
            ).use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().contains(""""verified":true"""))
            }
            post(
                client.origin,
                "/api/shipment/create",
                """{"from":{},"to":{},"parcel":{"weight_oz":16}}""",
            ).use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().contains("rate_1"))
            }
            post(
                client.origin,
                "/api/shipment/buy",
                """{"shipment_id":"shp_1","rate_id":"rate_1"}""",
            ).use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().contains("https://l/1.png"))
            }
            post(client.origin, "/api/shipment/smartrates", """{"shipment_id":"shp_1"}""").use {
                assertEquals(200, it.code)
            }
            // Missing required field -> 400.
            post(client.origin, "/api/shipment/smartrates", "{}").use { assertEquals(400, it.code) }
        }
    }

    @Test
    fun `endshipper, customs, batch, and scanform endpoints work`() {
        every { easyPost.createEndShipper(any()) } returns
            EndShipperResult(id = "es_1", name = "Sender")
        every { easyPost.createCustomsInfo(any()) } returns
            CustomsResult(id = "cstinfo_1", contentsType = "merchandise")
        every { easyPost.createAndBuyBatch(any()) } returns
            BatchResult(id = "batch_1", state = "created", numShipments = 2)
        every { easyPost.createScanForm("batch_1") } returns
            ScanFormResult(id = "sf_1", formUrl = "https://sf/1", status = "created")

        JavalinTest.test(app()) { _, client ->
            get(client.origin, "/api/endshipper/get").use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().contains(""""configured":false"""))
            }
            post(
                client.origin,
                "/api/endshipper/create",
                """{"name":"Sender","street1":"1 A","city":"Denver","state":"CO","zip":"80202"}""",
            ).use {
                assertEquals(200, it.code)
            }
            // Now the id is persisted and surfaced.
            get(client.origin, "/api/endshipper/get").use {
                assertTrue(it.body!!.string().contains("es_1"))
            }
            post(
                client.origin,
                "/api/customs/create",
                """{"contents_type":"merchandise","items":[]}""",
            ).use {
                assertEquals(200, it.code)
            }
            post(client.origin, "/api/batch/create", """{"shipment_ids":["shp_1","shp_2"]}""").use {
                assertEquals(200, it.code)
            }
            post(client.origin, "/api/scanform/create", """{"batch_id":"batch_1"}""").use {
                assertEquals(200, it.code)
            }
        }
    }

    // ---- Markup config -------------------------------------------------------

    @Test
    fun `markup config reads the default and validates updates`() {
        JavalinTest.test(app()) { _, client ->
            get(client.origin, "/api/config/markup").use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().contains(""""percentage_markup":12.0"""))
            }
            post(
                client.origin,
                "/api/config/markup",
                """{"percentage_markup":15.5,"fixed_fee_cents":75}""",
            ).use {
                assertEquals(200, it.code)
            }
            get(client.origin, "/api/config/markup").use {
                assertTrue(it.body!!.string().contains(""""fixed_fee_cents":75"""))
            }
            // Missing fields -> 400.
            post(client.origin, "/api/config/markup", """{"percentage_markup":10}""").use {
                assertEquals(400, it.code)
            }
            // Negative markup is rejected by the domain model -> 400.
            post(
                client.origin,
                "/api/config/markup",
                """{"percentage_markup":-1,"fixed_fee_cents":0}""",
            ).use {
                assertEquals(400, it.code)
            }
        }
    }

    // ---- Payment session + status --------------------------------------------

    @Test
    fun `payment session is server-priced and status is verified server-side`() {
        every { easyPost.priceRate("shp_1", "rate_1", any()) } returns
            PaymentPricing.Quote(amount = "8.74", baseRate = BigDecimal("7.36"), currency = "USD")
        every { payments.createHostedPayment(any(), any(), any()) } returns
            HostedPaymentResult(
                url = "https://pay.example/abc",
                code = "<script></script>",
                amount = BigDecimal("8.74"),
            )
        every { payments.verifyPayment(any()) } returns
            PaymentVerification("approved", eci = "05", cavv = "cavv3ds", liabilityShift = true)

        JavalinTest.test(app()) { _, client ->
            val body =
                post(
                    client.origin,
                    "/api/payment/session",
                    """{"shipment_id":"shp_1","rate_id":"rate_1","amount":"0.01"}""",
                ).use {
                    assertEquals(200, it.code)
                    it.body!!.string()
                }
            // Amount is the server price, NOT the client's "0.01".
            assertTrue(body.contains(""""amount":"8.74""""), body)
            assertTrue(body.contains(""""form_url":"https://pay.example/abc""""))
            val sessionId = Regex(""""session_id":"([^"]+)"""").find(body)!!.groupValues[1]

            get(client.origin, "/api/payment/status/$sessionId").use {
                assertEquals(200, it.code)
                val s = it.body!!.string()
                assertTrue(s.contains(""""status":"approved""""))
                assertTrue(s.contains(""""liability_shift":true"""))
            }
            // Unknown session -> 404.
            get(client.origin, "/api/payment/status/nope").use { assertEquals(404, it.code) }
        }
    }

    @Test
    fun `status poll never downgrades a persisted approved charge`() {
        // A saved-card session's externalId is a gateway txn id, so verifyPayment's
        // externalId lookup legitimately finds nothing and reports "pending". The
        // stored `approved` documents a REAL charge — stamping pending over it
        // would re-open the charge gate on the saved-card retry path (double
        // charge). The poll must keep reporting approved and keep it persisted.
        every { payments.verifyPayment(any()) } returns PaymentVerification("pending")
        store.savePaymentSession(
            PaymentSession(
                sessionId = "saved-idem-1",
                amount = 7.32,
                description = "saved-card purchase",
                externalId = "223668",
                createdAt = System.currentTimeMillis(),
                status = "approved",
            ),
        )

        JavalinTest.test(app()) { _, client ->
            get(client.origin, "/api/payment/status/saved-idem-1").use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().contains(""""status":"approved""""))
            }
        }
        assertEquals("approved", store.getPaymentSession("saved-idem-1")!!.status)
    }

    // ---- Labels --------------------------------------------------------------

    @Test
    fun `label retrieval by id and session, and shredding, behave`() {
        store.saveLabel(
            LabelRecord(
                id = "lbl_1",
                sessionId = "sess_1",
                labelUrl = "https://l/x.png",
                carrier = "USPS",
            ),
        )
        JavalinTest.test(app()) { _, client ->
            get(client.origin, "/api/label/lbl_1").use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().contains("https://l/x.png"))
            }
            get(client.origin, "/api/label/unknown").use { assertEquals(404, it.code) }
            get(client.origin, "/api/label/session/sess_1").use { assertEquals(200, it.code) }
            delete(
                client.origin,
                "/api/label/session/sess_1/shred",
            ).use { assertEquals(200, it.code) }
            delete(
                client.origin,
                "/api/label/session/sess_1/shred",
            ).use { assertEquals(404, it.code) }
            post(client.origin, "/api/admin/cleanup").use { assertEquals(200, it.code) }
        }
    }

    // ---- SMS verification + admin API-key management -------------------------

    @Test
    fun `verification gates history and the admin-only key management surface`() {
        JavalinTest.test(app()) { _, client ->
            // Non-admin verification: start + check -> a verified session.
            val startBody =
                post(client.origin, "/api/verification/start", """{"phone":"5559990000"}""").use {
                    assertEquals(200, it.code)
                    it.body!!.string()
                }
            val userSession = Regex(""""sessionId":"([^"]+)"""").find(startBody)!!.groupValues[1]
            post(
                client.origin,
                "/api/verification/check",
                """{"sessionId":"$userSession","phone":"5559990000","code":"123456"}""",
            ).use { assertEquals(200, it.code) }
            // History is now reachable with that session (id in the header).
            get(
                client.origin,
                "/api/history/labels",
                sessionId = userSession,
            ).use { assertEquals(200, it.code) }
            // A non-admin session cannot mint keys.
            post(
                client.origin,
                "/api/keys",
                """{"label":"x"}""",
                sessionId = userSession,
            ).use {
                assertEquals(403, it.code)
            }

            // An unauthorized admin phone is rejected at start.
            post(
                client.origin,
                "/api/verification/start",
                """{"phone":"5550001111","admin":true}""",
            ).use {
                assertEquals(403, it.code)
            }

            // A whitelisted admin phone: start + check -> admin session.
            val adminStart =
                post(
                    client.origin,
                    "/api/verification/start",
                    """{"phone":"$adminPhone","admin":true}""",
                ).use {
                    it.body!!.string()
                }
            val adminSession = Regex(""""sessionId":"([^"]+)"""").find(adminStart)!!.groupValues[1]
            post(
                client.origin,
                "/api/verification/check",
                """{"sessionId":"$adminSession","phone":"$adminPhone","code":"123456"}""",
            ).use { assertEquals(200, it.code) }

            // Admin can mint, list, and revoke keys.
            val created =
                post(
                    client.origin,
                    "/api/keys",
                    """{"label":"prod","mode":"live"}""",
                    sessionId = adminSession,
                ).use {
                    assertEquals(201, it.code)
                    it.body!!.string()
                }
            assertTrue(created.contains("sk_live_"), "the full key is shown once")
            val newId = Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1]
            get(
                client.origin,
                "/api/admin/labels",
                sessionId = adminSession,
            ).use { assertEquals(200, it.code) }
            get(client.origin, "/api/keys", sessionId = adminSession).use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().contains("prod"))
            }
            delete(
                client.origin,
                "/api/keys/$newId",
                sessionId = adminSession,
            ).use { assertEquals(200, it.code) }
            delete(
                client.origin,
                "/api/keys/does-not-exist",
                sessionId = adminSession,
            ).use {
                assertEquals(404, it.code)
            }
        }
    }

    @Test
    fun `admin surfaces reject a request with no verification session`() {
        JavalinTest.test(app()) { _, client ->
            get(client.origin, "/api/keys").use { assertEquals(401, it.code) }
            get(client.origin, "/api/history/labels").use { assertEquals(401, it.code) }
        }
    }

    @Test
    fun `a session id in the URL query string is ignored — only the header is honored`() {
        JavalinTest.test(app()) { _, client ->
            // Mint a genuinely-verified admin session.
            val adminStart =
                post(
                    client.origin,
                    "/api/verification/start",
                    """{"phone":"$adminPhone","admin":true}""",
                ).use { it.body!!.string() }
            val adminSession = Regex(""""sessionId":"([^"]+)"""").find(adminStart)!!.groupValues[1]
            post(
                client.origin,
                "/api/verification/check",
                """{"sessionId":"$adminSession","phone":"$adminPhone","code":"123456"}""",
            ).use { assertEquals(200, it.code) }

            // The valid session id in the QUERY STRING is NOT accepted (it would leak
            // through access/proxy logs, browser history, and the Referer header).
            get(client.origin, "/api/admin/labels?sessionId=$adminSession").use {
                assertEquals(401, it.code, "session id in the URL is ignored")
            }
            // The SAME session id in the X-Session-ID header IS accepted.
            get(client.origin, "/api/admin/labels", sessionId = adminSession).use {
                assertEquals(200, it.code, "session id in the header is honored")
            }
        }
    }

    @Test
    fun `check refuses to verify an admin session with an OTP for a different phone`() {
        JavalinTest.test(app()) { _, client ->
            // 1) A session is STARTED for the admin phone (attacker never sees its OTP).
            val adminStart =
                post(
                    client.origin,
                    "/api/verification/start",
                    """{"phone":"$adminPhone","admin":true}""",
                ).use { it.body!!.string() }
            val adminSession = Regex(""""sessionId":"([^"]+)"""").find(adminStart)!!.groupValues[1]

            // 2) Attacker tries to verify the ADMIN session with an OTP for THEIR phone.
            //    FakeSms approves code 123456 for any phone, so only the phone binding
            //    stands between the attacker and admin — it must hold.
            post(
                client.origin,
                "/api/verification/check",
                """{"sessionId":"$adminSession","phone":"5557778888","code":"123456"}""",
            ).use { assertEquals(400, it.code, "cross-phone verify is refused") }

            // 3) The admin session is therefore NOT valid → admin surface stays closed.
            get(client.origin, "/api/admin/labels", sessionId = adminSession).use {
                assertEquals(401, it.code, "the session was never verified")
            }
        }
    }
}
