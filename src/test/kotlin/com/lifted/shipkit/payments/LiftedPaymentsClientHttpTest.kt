package com.lifted.shipkit.payments

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
}
