package com.lifted.shipkit.payments

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal

/**
 * Configuration for the Lifted Payments 3-D Secure gateway. Every value is
 * supplied from the environment; there are no hardcoded credentials.
 *
 * Two hosts are used and must never be interchanged:
 *  - [gatewayBaseUrl] — *processing*: sale/auth/capture/refund and the
 *    server-to-server transaction lookup used to verify a charge.
 *  - [dashboardBaseUrl] — *hosted assets / vault*: the hosted 3-D Secure
 *    payment form the cardholder is redirected to.
 *
 * @param bearerToken     Gateway API bearer token — required, from the environment.
 * @param terminalId      Gateway terminal id — required; encodes DBA+MID+TID and
 *                        MUST accompany every money call.
 * @param dbaId           Gateway DBA (merchant) id — required, from the environment.
 * @param gatewayBaseUrl  Processing host (safe, non-secret default provided).
 * @param dashboardBaseUrl Hosted-form / vault host (safe, non-secret default provided).
 */
data class LiftedPaymentsConfig(
    val bearerToken: String,
    val terminalId: Int,
    val dbaId: Int,
    val gatewayBaseUrl: String = DEFAULT_GATEWAY_BASE_URL,
    val dashboardBaseUrl: String = DEFAULT_DASHBOARD_BASE_URL,
) {
    companion object {
        const val DEFAULT_GATEWAY_BASE_URL = "https://gateway.maverickpayments.com"
        const val DEFAULT_DASHBOARD_BASE_URL = "https://dashboard.maverickpayments.com"
    }
}

/** A hosted 3-D Secure payment form, ready to redirect the cardholder to. */
data class HostedPaymentResult(
    val url: String,
    val code: String,
    val amount: BigDecimal,
)

/**
 * The server-verified outcome of a card payment. Derived only from the
 * gateway's own transaction record — never from redirect/return-URL params.
 *
 * @param status         normalized lifecycle status: `pending`, `authenticated`,
 *                       `approved`, `declined`, or `failed`.
 * @param eci            3-D Secure ECI (scheme-specific; see [deriveLiabilityShift]).
 * @param cavv           3-D Secure cryptogram (CAVV/AAV); no cryptogram = no shift.
 * @param liabilityShift true only when the authenticated result shifts
 *                       fraud-chargeback liability to the issuer.
 * @param amount         the gateway's own captured/authorized amount, when the
 *                       transaction record carries one — used to reconcile
 *                       against the session amount before a label is bought
 *                       (API_MASTERY invariant #7: captured == authorized).
 */
data class PaymentVerification(
    val status: String,
    val eci: String? = null,
    val cavv: String? = null,
    val liabilityShift: Boolean = false,
    val amount: BigDecimal? = null,
)

/**
 * Client for the Lifted Payments hosted-form flow with **3-D Secure required**.
 *
 * The gateway authenticates the cardholder (biometric / OTP / risk-based) before
 * authorization, which shifts fraud chargeback liability to the issuer and lifts
 * approval rates. ShipKit never touches raw card data — it only creates the
 * hosted form and then reads the charge result back from the gateway.
 *
 * Shipping-label purchases are one of the highest fraud- and chargeback-risk
 * categories in commerce; forcing 3-D Secure is the whole point of this layer.
 * Get a 3-D Secure merchant account: https://liftedholdings.com/payments
 */
class LiftedPaymentsClient(
    private val config: LiftedPaymentsConfig,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val log = LoggerFactory.getLogger(LiftedPaymentsClient::class.java)
    private val gson = Gson()
    private val jsonMedia = "application/json".toMediaType()

    /**
     * Create a hosted 3-D Secure payment form for [amount].
     *
     * 3-D Secure is requested as `Required` unconditionally — there is no config
     * flag to disable it. The [amount] is the server-computed charge (carrier
     * rate + markup); the client never supplies it.
     *
     * @param amount     server-computed charge amount in dollars.
     * @param externalId caller-supplied idempotency/reference id for the charge
     *                   (also used to look the transaction up on verification).
     * @param returnUrl  URL the gateway redirects to after authentication.
     */
    @Throws(IOException::class)
    fun createHostedPayment(
        amount: BigDecimal,
        externalId: String,
        returnUrl: String,
    ): HostedPaymentResult {
        val payload =
            mapOf(
                "dba" to mapOf("id" to config.dbaId),
                "terminal" to mapOf("id" to config.terminalId),
                // Forced 3-D Secure — no code path authorizes a card without it.
                "threeds" to "Required",
                "amount" to amount.toPlainString(),
                "externalId" to externalId,
                "origin" to "WEB",
                "returnUrl" to returnUrl,
                "returnUrlNavigation" to "top",
                "useLogo" to "Yes",
                "visibleNote" to "No",
                "requestBillingInfo" to "Yes",
                "requestContactInfo" to "No",
                "requestShippingInfo" to "No",
                "sendReceipt" to "No",
                "title" to "Shipping",
            )

        val request =
            Request
                .Builder()
                // Hosted assets live on the dashboard host.
                .url("${config.dashboardBaseUrl}/api/gateway/hosted-form")
                .header("Authorization", "Bearer ${config.bearerToken}")
                .post(gson.toJson(payload).toRequestBody(jsonMedia))
                .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                log.warn("Lifted Payments hosted-form request failed (HTTP {})", response.code)
                throw IOException("Failed to create hosted payment form (HTTP ${response.code})")
            }

            val json =
                parseJsonObject(body)
                    ?: throw IOException("Invalid JSON response from Lifted Payments gateway")
            val url = json["url"] as? String
            val code = json["code"] as? String ?: ""
            if (url.isNullOrEmpty()) {
                throw IOException("Lifted Payments gateway did not return a hosted-form URL")
            }
            return HostedPaymentResult(url = url, code = code, amount = amount)
        }
    }

    /**
     * Verify a charge **server-side** by looking the transaction up on the
     * processing host by its [externalId]. This is the only trusted source of
     * payment truth: return-URL query params are attacker-controllable and are
     * never used to decide whether a label may be bought.
     *
     * Returns a [PaymentVerification]; a transaction not yet present (the
     * cardholder has not finished the hosted flow) maps to `pending`.
     */
    @Throws(IOException::class)
    fun verifyPayment(externalId: String): PaymentVerification {
        // URL-encode the reference before it goes on the query string. The id is
        // server-generated today (so this is defense-in-depth), but a lookup key
        // must never be able to smuggle extra query params or a filter operator.
        val encodedId = java.net.URLEncoder.encode(externalId, Charsets.UTF_8.name())
        val request =
            Request
                .Builder()
                // Processing/transaction data lives on the gateway host. externalId
                // is a filterable field on the transaction list endpoint.
                .url("${config.gatewayBaseUrl}/api/transaction?externalId=$encodedId")
                .header("Authorization", "Bearer ${config.bearerToken}")
                .get()
                .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                log.warn("Lifted Payments transaction lookup failed (HTTP {})", response.code)
                throw IOException("Failed to verify payment (HTTP ${response.code})")
            }
            val txn = firstTransaction(body) ?: return PaymentVerification(status = "pending")
            // Exact-match guard: the transaction-list filter may be a prefix/`-like`
            // match (API_MASTERY §2.0), so confirm the returned record actually
            // carries OUR externalId before trusting it. A mismatch means the
            // gateway returned someone else's txn — treat it as not-yet-present.
            val returnedId = (txn["externalId"] as? String)?.trim()
            if (!returnedId.isNullOrEmpty() && returnedId != externalId) {
                log.warn("Transaction externalId mismatch on lookup; ignoring the returned record")
                return PaymentVerification(status = "pending")
            }
            return interpretTransaction(txn)
        }
    }

    /** Extract the first transaction from either a list envelope or a bare object. */
    private fun firstTransaction(body: String): Map<*, *>? {
        val json = parseJsonObject(body) ?: return null
        val items = json["items"] as? List<*>
        return when {
            items != null -> items.firstOrNull() as? Map<*, *>
            json.containsKey("status") || json.containsKey("id") -> json
            else -> null
        }
    }

    /**
     * Map a gateway transaction record to a [PaymentVerification]. Extracted as a
     * pure function so the status/liability logic is unit-testable without a live
     * gateway.
     */
    internal fun interpretTransaction(txn: Map<*, *>): PaymentVerification {
        @Suppress("UNCHECKED_CAST")
        val statusObj = txn["status"] as? Map<String, Any?>
        val gatewayStatus = (statusObj?.get("status") as? String)?.trim()

        @Suppress("UNCHECKED_CAST")
        val threeds = txn["threeds"] as? Map<String, Any?>
        val eci = (threeds?.get("eci") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val cavv =
            ((threeds?.get("cavv") ?: threeds?.get("aav")) as? String)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val shift = deriveLiabilityShift(eci, cavv)

        val status =
            when (gatewayStatus?.lowercase()) {
                // Approved with a liability shift is the only "buy the label" state
                // under forced 3-D Secure; approved WITHOUT a shift is refused.
                "approved" -> if (shift) "approved" else "declined"

                "declined" -> "declined"

                "error" -> "failed"

                // 3-D Secure completed but the sale is not yet captured.
                null -> if (threeds != null) "authenticated" else "pending"

                else -> "pending"
            }
        return PaymentVerification(
            status = status,
            eci = eci,
            cavv = cavv,
            liabilityShift = shift,
            amount = parseTxnAmount(txn),
        )
    }

    /**
     * Best-effort read of the transaction's own amount. Gson decodes a JSON number
     * to a [Double], so we round-trip through its string form (never the lossy
     * `BigDecimal(double)` ctor) to recover an exact decimal. Returns `null` when
     * the record carries no readable amount, in which case reconciliation is
     * skipped by the caller.
     */
    private fun parseTxnAmount(txn: Map<*, *>): BigDecimal? {
        val raw = txn["amount"] ?: txn["capturedAmount"] ?: txn["authorizedAmount"]
        return when (raw) {
            is Number -> runCatching { BigDecimal(raw.toString()) }.getOrNull()
            is String -> raw.trim().takeIf { it.isNotEmpty() }?.let { it.toBigDecimalOrNull() }
            else -> null
        }
    }

    private fun parseJsonObject(body: String): Map<String, Any?>? =
        try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(body, Map::class.java) as? Map<String, Any?>
        } catch (e: Exception) {
            null
        }

    companion object {
        /**
         * ECIs that carry a fraud-chargeback liability shift, mapping BOTH schemes
         * (the ECI value is *inverted* between Visa and Mastercard, so a single
         * flat set is used deliberately):
         *  - Fully authenticated: Visa `05`, Mastercard `02`.
         *  - Attempted (issuer/ACS unavailable, still shifts): Visa `06`, Mastercard `01`.
         *
         * Not-authenticated (Visa `07` / MC `00`) and every other value carry no
         * shift. A valid cryptogram (CAVV/AAV) is also required — with no
         * cryptogram there is no liability shift regardless of the ECI.
         */
        private val LIABILITY_SHIFT_ECIS = setOf("01", "02", "05", "06")

        /**
         * True when this 3-D Secure result shifts fraud-chargeback liability to the
         * issuer: an authenticated/attempted ECI **and** a present cryptogram.
         */
        fun deriveLiabilityShift(
            eci: String?,
            cavv: String?,
        ): Boolean = !cavv.isNullOrBlank() && eci?.trim() in LIABILITY_SHIFT_ECIS
    }
}
