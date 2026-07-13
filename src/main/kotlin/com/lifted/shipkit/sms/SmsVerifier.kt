package com.lifted.shipkit.sms

import com.google.gson.Gson
import com.lifted.shipkit.util.PhoneNumbers
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory

/**
 * Optional SMS phone verification, used to gate the purchase-history and admin
 * surfaces. It is **off by default** — the free toolkit runs with no Twilio
 * account and no secrets. Enable it by setting `SHIPKIT_SMS_ENABLED=true` and
 * providing Twilio Verify credentials via environment variables.
 */
data class SmsConfig(
    val enabled: Boolean = false,
    val accountSid: String? = null,
    val authToken: String? = null,
    val verifyServiceSid: String? = null,
) {
    /** True only when enabled *and* fully configured. */
    val usable: Boolean
        get() =
            enabled &&
                !accountSid.isNullOrBlank() &&
                !authToken.isNullOrBlank() &&
                !verifyServiceSid.isNullOrBlank()
}

/** Sends and checks one-time SMS verification codes. */
interface SmsVerifier {
    /** Whether verification is actually available in this deployment. */
    val enabled: Boolean

    /** Start a verification for [phoneNumber]; returns true if the code was sent. */
    fun start(phoneNumber: String): Boolean

    /** Check a [code] for [phoneNumber]; returns true if approved. */
    fun check(
        phoneNumber: String,
        code: String,
    ): Boolean

    companion object {
        /** Build the verifier the config asks for, defaulting to the disabled no-op. */
        fun create(config: SmsConfig): SmsVerifier =
            if (config.usable) TwilioSmsVerifier(config) else DisabledSmsVerifier
    }
}

/** The default: SMS verification is unavailable. */
object DisabledSmsVerifier : SmsVerifier {
    override val enabled = false

    override fun start(phoneNumber: String) = false

    override fun check(
        phoneNumber: String,
        code: String,
    ) = false
}

/**
 * Twilio Verify implementation over the REST API (no Twilio SDK dependency).
 * Credentials come only from [SmsConfig]; nothing is hardcoded.
 */
class TwilioSmsVerifier(
    private val config: SmsConfig,
    private val http: OkHttpClient = OkHttpClient(),
) : SmsVerifier {
    private val log = LoggerFactory.getLogger(TwilioSmsVerifier::class.java)
    private val gson = Gson()
    private val base = "https://verify.twilio.com/v2/Services/${config.verifyServiceSid}"

    override val enabled = true

    override fun start(phoneNumber: String): Boolean =
        post(
            url = "$base/Verifications",
            form =
                FormBody
                    .Builder()
                    .add("To", PhoneNumbers.toE164(phoneNumber))
                    .add("Channel", "sms")
                    .build(),
            expectedStatus = "pending",
            action = "start verification",
        )

    override fun check(
        phoneNumber: String,
        code: String,
    ): Boolean =
        post(
            url = "$base/VerificationCheck",
            form =
                FormBody
                    .Builder()
                    .add("To", PhoneNumbers.toE164(phoneNumber))
                    .add("Code", code)
                    .build(),
            expectedStatus = "approved",
            action = "check verification",
        )

    private fun post(
        url: String,
        form: FormBody,
        expectedStatus: String,
        action: String,
    ): Boolean {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", Credentials.basic(config.accountSid!!, config.authToken!!))
                .post(form)
                .build()
        return try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    log.warn("Twilio Verify failed to {} (HTTP {})", action, response.code)
                    return false
                }
                @Suppress("UNCHECKED_CAST")
                val parsed = gson.fromJson(body, Map::class.java) as? Map<String, Any?>
                (parsed?.get("status") as? String).equals(expectedStatus, ignoreCase = true)
            }
        } catch (e: Exception) {
            log.warn("Twilio Verify error during {}: {}", action, e.message)
            false
        }
    }
}
