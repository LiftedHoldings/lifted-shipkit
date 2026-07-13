package com.lifted.shipkit.payments

import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.math.BigDecimal

/**
 * The [LiftedPaymentsClient] HTTP surface exercised against a mocked OkHttp
 * client — the two hosts are honoured, the hosted-form URL is read from the
 * response, and payment verification is derived only from the gateway's own
 * transaction record (never from return-URL params).
 */
class LiftedPaymentsClientHttpTest {
    private val config =
        LiftedPaymentsConfig(
            bearerToken = "test-bearer",
            terminalId = 3,
            dbaId = 7,
        )

    private fun http(
        code: Int,
        body: String,
    ): OkHttpClient {
        val http = mockk<OkHttpClient>()
        every { http.newCall(any()) } answers {
            val req = firstArg<Request>()
            val call = mockk<Call>()
            every { call.execute() } returns
                Response
                    .Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("mock")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            call
        }
        return http
    }

    @Test
    fun `createHostedPayment posts to the dashboard host and returns the pay-page URL`() {
        val reqSlot = slot<Request>()
        val http = mockk<OkHttpClient>()
        every { http.newCall(capture(reqSlot)) } answers {
            val call = mockk<Call>()
            every { call.execute() } returns
                Response
                    .Builder()
                    .request(reqSlot.captured)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("ok")
                    .body(
                        """{"url":"https://dashboard.maverickpayments.com/pay/abc","code":"<script></script>"}"""
                            .toResponseBody("application/json".toMediaType()),
                    ).build()
            call
        }

        val client = LiftedPaymentsClient(config, http)
        val result =
            client.createHostedPayment(
                amount = java.math.BigDecimal("8.74"),
                externalId = "shipkit-1",
                returnUrl = "https://shop.example/api/payment/return/s1",
            )

        assertEquals("https://dashboard.maverickpayments.com/pay/abc", result.url)
        // Hosted assets live on the DASHBOARD host, never the gateway host.
        assertTrue(
            reqSlot.captured.url
                .toString()
                .startsWith("https://dashboard.maverickpayments.com"),
        )
        assertEquals("Bearer test-bearer", reqSlot.captured.header("Authorization"))
    }

    @Test
    fun `createHostedPayment raises IOException on a non-2xx gateway response`() {
        val client = LiftedPaymentsClient(config, http(500, "boom"))
        assertThrows(IOException::class.java) {
            client.createHostedPayment(
                java.math.BigDecimal("8.74"),
                "shipkit-1",
                "https://x/return",
            )
        }
    }

    @Test
    fun `verifyPayment reads approval + liability shift from the transaction list envelope`() {
        val body =
            """
            {"items":[{"id":"txn_1","status":{"status":"Approved"},
                       "threeds":{"eci":"05","cavv":"cavv3ds01"}}]}
            """.trimIndent()
        val client = LiftedPaymentsClient(config, http(200, body))

        val v = client.verifyPayment("shipkit-1")
        assertEquals("approved", v.status)
        assertTrue(v.liabilityShift)
        assertEquals("05", v.eci)
        assertEquals("cavv3ds01", v.cavv)
    }

    @Test
    fun `verifyPayment maps an approved-but-unshifted transaction to declined (forced 3DS)`() {
        val body =
            """{"items":[{"id":"txn_2","status":{"status":"Approved"},"threeds":{"eci":"07"}}]}"""
        val client = LiftedPaymentsClient(config, http(200, body))

        val v = client.verifyPayment("shipkit-2")
        assertEquals("declined", v.status)
        assertFalse(v.liabilityShift)
    }

    @Test
    fun `verifyPayment returns pending when no transaction exists yet`() {
        val client = LiftedPaymentsClient(config, http(200, """{"items":[]}"""))
        assertEquals("pending", client.verifyPayment("nope").status)
    }

    @Test
    fun `verifyPayment raises IOException on a gateway error`() {
        val client = LiftedPaymentsClient(config, http(503, "unavailable"))
        assertThrows(IOException::class.java) { client.verifyPayment("shipkit-1") }
    }

    // ---- Real Maverick contract: sale / capture / refund / get / hosted-fields ----

    /** Mock that captures the outgoing request AND returns a canned response. */
    private fun capturing(
        code: Int,
        body: String,
        slot: CapturingSlot<Request>,
    ): OkHttpClient {
        val http = mockk<OkHttpClient>()
        every { http.newCall(capture(slot)) } answers {
            val call = mockk<Call>()
            every { call.execute() } returns
                Response
                    .Builder()
                    .request(slot.captured)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("mock")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            call
        }
        return http
    }

    private fun bodyOf(request: Request): String {
        val buffer = okio.Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    @Test
    fun `sale posts the real payment-sale contract and reports approval plus shift`() {
        val slot = slot<Request>()
        val http =
            capturing(
                200,
                """{"id":"txn_9","status":{"status":"Approved"},"authCode":"OK123",
                    "threeds":{"eci":"05","cavv":"cavv3ds01"}}""",
                slot,
            )
        val client = LiftedPaymentsClient(config, http)

        val result = client.sale(BigDecimal("9.22"), cardToken = "tok_abc")

        assertEquals("txn_9", result.transactionId)
        assertEquals("approved", result.status)
        assertTrue(result.approved)
        assertTrue(result.liabilityShift)
        assertEquals("OK123", result.authCode)

        val url = slot.captured.url.toString()
        assertTrue(
            url == "https://gateway.maverickpayments.com/payment/sale",
            "posts to /payment/sale, got $url",
        )
        assertEquals("Bearer test-bearer", slot.captured.header("Authorization"))
        val sent = bodyOf(slot.captured)
        // Card nested under `card` as a token, forced 3ds BOOLEAN, terminal.id, 2dp amount.
        assertTrue(sent.contains(""""card":{"token":"tok_abc"}"""), "card token nested: $sent")
        assertTrue(sent.contains(""""3ds":true"""), "forced 3ds boolean: $sent")
        assertTrue(sent.contains(""""terminal":{"id":3}"""), "terminal id: $sent")
        assertTrue(sent.contains(""""amount":"9.22""""), "2dp amount: $sent")
    }

    @Test
    fun `sale approved WITHOUT a shift is refused (forced 3DS)`() {
        val slot = slot<Request>()
        val http =
            capturing(
                200,
                """{"id":"txn_x","status":{"status":"Approved"},"threeds":{"eci":"07"}}""",
                slot,
            )
        val result =
            LiftedPaymentsClient(
                config,
                http,
            ).sale(BigDecimal("9.22"), cardToken = "tok_abc")
        assertEquals("declined", result.status)
        assertFalse(result.approved)
    }

    @Test
    fun `sale raises IOException on a gateway error`() {
        val client = LiftedPaymentsClient(config, http(500, "boom"))
        assertThrows(
            IOException::class.java,
        ) { client.sale(BigDecimal("1.00"), cardToken = "tok_abc") }
    }

    @Test
    fun `the amount guard rejects a non-positive or absurd charge before any HTTP call`() {
        val http = mockk<OkHttpClient>()
        val client = LiftedPaymentsClient(config, http)
        // A 0.00 (test/free carrier rate) or negative amount (malformed markup) is
        // refused LOCALLY, not POSTed to draw an opaque gateway 422.
        assertThrows(IOException::class.java) { client.sale(BigDecimal("0.00"), cardToken = "tok") }
        assertThrows(
            IOException::class.java,
        ) { client.sale(BigDecimal("-1.00"), cardToken = "tok") }
        // Above the gateway's maximum is likewise refused.
        assertThrows(IOException::class.java) {
            client.sale(BigDecimal("1000000.01"), cardToken = "tok")
        }
        // Partial refunds/captures are guarded through the same path.
        assertThrows(IOException::class.java) { client.refund("txn_1", BigDecimal("0.00")) }
        // Not a single money call left the process.
        verify(exactly = 0) { http.newCall(any()) }
    }

    @Test
    fun `capture posts terminal plus a partial amount and approves without a shift`() {
        val slot = slot<Request>()
        val http = capturing(200, """{"id":"txn_9","status":{"status":"captured"}}""", slot)
        val result = LiftedPaymentsClient(config, http).capture("txn_9", BigDecimal("5.00"))

        assertTrue(result.approved, "captured status approves")
        assertEquals(
            "https://gateway.maverickpayments.com/payment/txn_9/capture",
            slot.captured.url.toString(),
        )
        val sent = bodyOf(slot.captured)
        assertTrue(sent.contains(""""terminal":{"id":3}"""), sent)
        assertTrue(
            sent.contains(""""partial":{"amount":"5.00"}"""),
            "partial capture amount: $sent",
        )
    }

    @Test
    fun `refund posts terminal plus amount to the refund path`() {
        val slot = slot<Request>()
        val http = capturing(200, """{"id":"txn_9","status":{"status":"success"}}""", slot)
        val result = LiftedPaymentsClient(config, http).refund("txn_9", BigDecimal("2.50"))

        assertTrue(result.approved, "success status approves")
        assertEquals(
            "https://gateway.maverickpayments.com/payment/txn_9/refund",
            slot.captured.url.toString(),
        )
        assertTrue(bodyOf(slot.captured).contains(""""amount":"2.50""""))
    }

    @Test
    fun `getPayment reads a single transaction by id under forced 3DS`() {
        val body = """{"id":"txn_9","status":{"status":"Approved"},"threeds":{"eci":"05","cavv":"c"}}"""
        val v = LiftedPaymentsClient(config, http(200, body)).getPayment("txn_9")
        assertEquals("approved", v.status)
        assertTrue(v.liabilityShift)
    }

    @Test
    fun `getPayment unwraps a record nested under item`() {
        val body =
            """{"item":{"id":"txn_9","status":{"status":"Approved"},"threeds":{"eci":"07"}}}"""
        val v = LiftedPaymentsClient(config, http(200, body)).getPayment("txn_9")
        // Approved but no shift -> declined under forced 3DS.
        assertEquals("declined", v.status)
    }

    @Test
    fun `hostedFieldsToken mints a token on the dashboard host with forced 3ds`() {
        val slot = slot<Request>()
        val http = capturing(200, """{"accessToken":"hf_abc","expiration":15}""", slot)
        val token = LiftedPaymentsClient(config, http).hostedFieldsToken("https://shop.example")

        assertEquals("hf_abc", token.accessToken)
        assertEquals(
            "https://dashboard.maverickpayments.com/api/hosted-fields/token",
            slot.captured.url.toString(),
        )
        val sent = bodyOf(slot.captured)
        assertTrue(sent.contains(""""3ds":true"""), "forced 3ds: $sent")
        assertTrue(sent.contains(""""terminal":3"""), "terminal: $sent")
        assertTrue(sent.contains(""""domain":"https://shop.example""""), sent)
    }

    @Test
    fun `hostedFieldsToken rejects a non-https domain`() {
        val client = LiftedPaymentsClient(config, http(200, "{}"))
        assertThrows(IOException::class.java) { client.hostedFieldsToken("http://shop.example") }
    }

    // ---- Frictionless + card-on-file (account-gated; SPEC_R3 §5) -------------

    /** Same connection details as [config] but with the frictionless capability armed. */
    private val frictionlessConfig = config.copy(frictionlessAllowed = true)

    @Test
    fun `saleFrictionless posts 3ds false and approval is not shift-gated`() {
        val slot = slot<Request>()
        // Approved with NO 3-D Secure block (no threeds/eci) — must still approve.
        val http = capturing(200, """{"id":"txn_f","status":{"status":"Approved"}}""", slot)
        val result =
            LiftedPaymentsClient(frictionlessConfig, http)
                .saleFrictionless(BigDecimal("4.20"), cardToken = "tok_f")

        assertEquals("approved", result.status)
        assertTrue(result.approved, "no shift required when 3DS is off")
        assertEquals(
            "https://gateway.maverickpayments.com/payment/sale",
            slot.captured.url.toString(),
        )
        val sent = bodyOf(slot.captured)
        assertTrue(sent.contains(""""3ds":false"""), "3ds is explicitly OFF: $sent")
        assertTrue(sent.contains(""""card":{"token":"tok_f"}"""), sent)
        assertTrue(sent.contains(""""amount":"4.20""""), sent)
    }

    @Test
    fun `frictionless methods are REFUSED when the account is not gated on`() {
        // config (frictionlessAllowed=false) is the self-host / BYO default.
        val client = LiftedPaymentsClient(config, http(200, "{}"))
        assertThrows(IOException::class.java) {
            client.saleFrictionless(BigDecimal("1.00"), cardToken = "tok")
        }
        assertThrows(IOException::class.java) { client.saveCard("hf_tok") }
        assertThrows(IOException::class.java) {
            client.chargeSavedCard(BigDecimal("1.00"), vaultId = "10:20")
        }
    }

    @Test
    fun `saveCard creates a vault customer, billing, and card on the dashboard host`() {
        // Distinct id per POST: customer(1) → billing(2, has address+zip) → card(3).
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var call = 0
        val http = mockk<OkHttpClient>()
        every { http.newCall(any()) } answers {
            val req = firstArg<Request>()
            urls += req.url.toString()
            bodies += bodyOf(req)
            val body =
                when (++call) {
                    1 -> """{"id":"cust_1"}"""
                    2 -> """{"id":"bill_1"}"""
                    else -> """{"id":"card_1","token":"vtok_1"}"""
                }
            val c = mockk<Call>()
            every { c.execute() } returns
                Response
                    .Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("ok")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            c
        }

        val saved =
            LiftedPaymentsClient(frictionlessConfig, http).saveCard(
                cardToken = "hf_card_tok",
                billing =
                    mapOf(
                        "first_name" to "Ada",
                        "last_name" to "Lovelace",
                        "address1" to "1 Analytical Way",
                        "city" to "London",
                        "state" to "CA",
                        "zip" to "90210",
                    ),
            )

        assertEquals("cust_1:card_1", saved.vaultId)
        assertEquals("vtok_1", saved.cardToken)
        // Vault ops are DASHBOARD-host; the card-add carries the Hosted Fields token.
        assertTrue(
            urls.all {
                it.startsWith("https://dashboard.maverickpayments.com/api/customer-vault")
            },
            "$urls",
        )
        assertEquals("https://dashboard.maverickpayments.com/api/customer-vault", urls[0])
        assertTrue(urls[1].endsWith("/customer-vault/cust_1/billing-information"), urls[1])
        assertTrue(urls[2].endsWith("/customer-vault/cust_1/card"), urls[2])
        assertTrue(
            bodies[0].contains(""""dba":{"id":7}"""),
            "customer scoped to the DBA: ${bodies[0]}",
        )
        assertTrue(
            bodies[2].contains(""""token":"hf_card_tok""""),
            "card token attached: ${bodies[2]}",
        )
    }

    @Test
    fun `saveCard fails closed and deletes the orphan customer when no card id comes back`() {
        val methods = mutableListOf<String>()
        var call = 0
        val http = mockk<OkHttpClient>()
        every { http.newCall(any()) } answers {
            val req = firstArg<Request>()
            methods += req.method
            // customer(1) ok, billing(2) ok, card(3) returns NO id → unchargeable.
            val body =
                when (++call) {
                    1 -> """{"id":"cust_9"}"""
                    2 -> """{"id":"bill_9"}"""
                    else -> """{"status":"ok"}""" // no id / no token
                }
            val c = mockk<Call>()
            every { c.execute() } returns
                Response
                    .Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("ok")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            c
        }

        assertThrows(IOException::class.java) {
            LiftedPaymentsClient(frictionlessConfig, http).saveCard(
                cardToken = "hf_card_tok",
                billing = mapOf("address1" to "1 A St", "zip" to "90210"),
            )
        }
        // A best-effort DELETE cleaned up the orphan customer (no phantom saved card).
        assertTrue(methods.contains("DELETE"), "orphan customer deleted: $methods")
    }

    @Test
    fun `chargeSavedCard resolves the vault token then charges it frictionlessly`() {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var call = 0
        val http = mockk<OkHttpClient>()
        every { http.newCall(any()) } answers {
            val req = firstArg<Request>()
            urls += req.url.toString()
            bodies += bodyOf(req)
            // 1: GET vault card → token; 2: POST /payment/sale → approved.
            val body =
                if (++call == 1) {
                    """{"id":"card_1","token":"vtok_resolved"}"""
                } else {
                    """{"id":"txn_s","status":{"status":"Approved"}}"""
                }
            val c = mockk<Call>()
            every { c.execute() } returns
                Response
                    .Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("ok")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            c
        }

        val result =
            LiftedPaymentsClient(frictionlessConfig, http)
                .chargeSavedCard(BigDecimal("6.66"), vaultId = "cust_1:card_1")

        assertTrue(result.approved)
        assertEquals(
            "https://dashboard.maverickpayments.com/api/customer-vault/cust_1/card/card_1",
            urls[0],
            "resolves the vault card token on the dashboard host first",
        )
        assertEquals("https://gateway.maverickpayments.com/payment/sale", urls[1])
        // The resolved vault token is what is charged, 3ds OFF.
        assertTrue(bodies[1].contains(""""card":{"token":"vtok_resolved"}"""), bodies[1])
        assertTrue(bodies[1].contains(""""3ds":false"""), bodies[1])
    }

    @Test
    fun `chargeSavedCard rejects a malformed vault reference`() {
        val client = LiftedPaymentsClient(frictionlessConfig, http(200, "{}"))
        assertThrows(IOException::class.java) {
            client.chargeSavedCard(BigDecimal("1.00"), vaultId = "no-colon")
        }
    }
}
