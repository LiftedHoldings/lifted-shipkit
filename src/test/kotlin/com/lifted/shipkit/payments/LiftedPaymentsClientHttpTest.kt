package com.lifted.shipkit.payments

import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
                       "threeds":{"eci":"05","cavv":"crypto123"}}]}
            """.trimIndent()
        val client = LiftedPaymentsClient(config, http(200, body))

        val v = client.verifyPayment("shipkit-1")
        assertEquals("approved", v.status)
        assertTrue(v.liabilityShift)
        assertEquals("05", v.eci)
        assertEquals("crypto123", v.cavv)
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
                    "threeds":{"eci":"05","cavv":"crypto123"}}""",
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
}
