package com.lifted.shipkit.sms

import io.mockk.every
import io.mockk.mockk
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SMS verification is an optional module. These tests pin the on/off wiring and
 * the Twilio Verify success/failure mapping (against a mocked OkHttp client).
 */
class SmsVerifierTest {
    @Test
    fun `create returns the disabled no-op unless fully configured`() {
        assertSame(DisabledSmsVerifier, SmsVerifier.create(SmsConfig(enabled = false)))
        // Enabled but missing credentials is still not usable -> disabled.
        assertSame(DisabledSmsVerifier, SmsVerifier.create(SmsConfig(enabled = true)))
        assertFalse(DisabledSmsVerifier.enabled)
        assertFalse(DisabledSmsVerifier.start("5551234567"))
        assertFalse(DisabledSmsVerifier.check("5551234567", "000000"))

        val usable =
            SmsConfig(
                enabled = true,
                accountSid = "AC1",
                authToken = "tok",
                verifyServiceSid = "VA1",
            )
        assertTrue(usable.usable)
        assertTrue(SmsVerifier.create(usable) is TwilioSmsVerifier)
    }

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
                    .message("m")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            call
        }
        return http
    }

    private val config =
        SmsConfig(enabled = true, accountSid = "AC1", authToken = "tok", verifyServiceSid = "VA1")

    @Test
    fun `Twilio start succeeds only on a pending status`() {
        assertTrue(
            TwilioSmsVerifier(config, http(201, """{"status":"pending"}""")).start("5551234567"),
        )
        assertFalse(
            TwilioSmsVerifier(config, http(201, """{"status":"failed"}""")).start("5551234567"),
        )
        assertFalse(TwilioSmsVerifier(config, http(500, "err")).start("5551234567"))
    }

    @Test
    fun `Twilio check succeeds only on an approved status`() {
        assertTrue(
            TwilioSmsVerifier(
                config,
                http(200, """{"status":"approved"}"""),
            ).check("5551234567", "123456"),
        )
        assertFalse(
            TwilioSmsVerifier(
                config,
                http(200, """{"status":"pending"}"""),
            ).check("5551234567", "000000"),
        )
    }

    @Test
    fun `a network error maps to false, never an exception`() {
        val http = mockk<OkHttpClient>()
        every { http.newCall(any()) } answers {
            val call = mockk<Call>()
            every { call.execute() } throws java.io.IOException("boom")
            call
        }
        assertFalse(TwilioSmsVerifier(config, http).start("5551234567"))
    }
}
