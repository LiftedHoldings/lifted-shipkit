package com.lifted.shipkit.payments

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Which Maverick environment a merchant account is boarded on. Selects the live
 * vs sandbox gateway/dashboard bases when explicit base URLs are not supplied.
 */
enum class PaymentsEnvironment {
    LIVE,
    SANDBOX,
    ;

    companion object {
        /** Parse `LIFTED_PAYMENTS_ENV` (case-insensitive); anything but `sandbox` = live. */
        fun fromString(raw: String?): PaymentsEnvironment =
            if (raw?.trim()?.lowercase() == "sandbox") SANDBOX else LIVE
    }
}

/**
 * How the cardholder enters card data in the browser. Both are valid Maverick
 * integration models and ShipKit supports both:
 *
 *  - [HOSTED_FIELDS] (primary, Launchpad-proven): the server mints a short-lived
 *    Hosted Fields access token ([LiftedPaymentsClient.hostedFieldsToken]); the
 *    browser tokenizes the card in-place against Maverick and returns a card
 *    token; the server charges it with [LiftedPaymentsClient.sale] and forced
 *    3-D Secure. Card data never touches this page or the merchant's server.
 *  - [HOSTED_FORM]: the buyer is redirected to a Maverick-hosted payment page.
 *    PCI-reducing. The creation path defaults to the verified
 *    [LiftedPaymentsConfig.DEFAULT_HOSTED_FORM_PATH] and remains
 *    config-overridable (see [LiftedPaymentsConfig.hostedFormPath]).
 */
enum class CardEntryMode {
    HOSTED_FIELDS,
    HOSTED_FORM,
    ;

    companion object {
        /** Parse `LIFTED_PAYMENTS_CARD_ENTRY` (case-insensitive); default [HOSTED_FIELDS]. */
        fun fromString(raw: String?): CardEntryMode {
            val norm =
                raw
                    ?.trim()
                    ?.lowercase()
                    ?.replace("-", "")
                    ?.replace("_", "") ?: ""
            return if (norm == "hostedform" || norm == "form" || norm == "redirect") {
                HOSTED_FORM
            } else {
                HOSTED_FIELDS
            }
        }
    }
}

/**
 * Configuration for the Lifted Payments 3-D Secure gateway (Maverick's Internal
 * Gateway). Every credential is supplied from the environment; there are no
 * hardcoded secrets, and every non-secret has a safe default.
 *
 * Two hosts are used and must never be interchanged:
 *  - [gatewayBaseUrl] — *processing*: `/payment/sale`, `/payment/{id}/capture`,
 *    `/payment/{id}/refund`, and `GET /payment/{id}`.
 *  - [dashboardBaseUrl] — *hosted assets / vault*: Hosted Fields token minting
 *    and (config-driven) the hosted payment page.
 *
 * @param bearerToken      Gateway API bearer token — required, from the environment.
 * @param terminalId       Gateway terminal id — required; encodes DBA+MID+TID and
 *                         MUST accompany every money call.
 * @param dbaId            Gateway DBA (merchant) id — required, from the environment.
 * @param environment      Live vs sandbox selection (drives the default bases).
 * @param cardEntryMode    Hosted Fields (primary) vs hosted form for browser entry.
 * @param gatewayBaseUrl   Processing host (safe, non-secret default per [environment]).
 * @param dashboardBaseUrl Hosted-assets / vault host (safe, non-secret default).
 * @param hostedFormPath   Hosted-form creation path (verified default; overridable).
 * @param frictionlessAllowed Whether this deployment's account/tier is permitted to
 *                         run **frictionless** money paths — charge with `3ds:false`
 *                         and save/charge cards on file. **Account-gated, server-side
 *                         only** (see `FrictionlessGate`): `false` by default and for
 *                         every self-host / bring-your-own-payments deployment, where
 *                         forced 3-D Secure is the only mode. When `false` the
 *                         frictionless methods ([saleFrictionless], [saveCard],
 *                         [chargeSavedCard]) refuse to run — defence in depth beneath
 *                         the HTTP gate, so no caller can charge 3ds-off off the rails.
 */
data class LiftedPaymentsConfig(
    val bearerToken: String,
    val terminalId: Int,
    val dbaId: Int,
    val environment: PaymentsEnvironment = PaymentsEnvironment.LIVE,
    val cardEntryMode: CardEntryMode = CardEntryMode.HOSTED_FIELDS,
    val gatewayBaseUrl: String = defaultGatewayBase(environment),
    val dashboardBaseUrl: String = defaultDashboardBase(environment),
    val frictionlessAllowed: Boolean = false,
    /**
     * Hosted-form (hosted payment page) creation path on the dashboard host.
     * [DEFAULT_HOSTED_FORM_PATH] is the verified default per the gateway
     * contract; it stays a config value so a deployment can override it if its
     * account is provisioned differently.
     *
     * The Hosted Fields flow ([CardEntryMode.HOSTED_FIELDS]) is the primary
     * path and needs no hosted-form endpoint.
     */
    val hostedFormPath: String = DEFAULT_HOSTED_FORM_PATH,
) {
    companion object {
        const val DEFAULT_GATEWAY_BASE_URL = "https://gateway.maverickpayments.com"
        const val DEFAULT_DASHBOARD_BASE_URL = "https://dashboard.maverickpayments.com"
        const val SANDBOX_GATEWAY_BASE_URL = "https://sandbox-gateway.maverickpayments.com"
        const val SANDBOX_DASHBOARD_BASE_URL = "https://sandbox-dashboard.maverickpayments.com"

        // Verified default hosted-form creation path per the gateway contract;
        // overridable via LiftedPaymentsConfig.hostedFormPath.
        const val DEFAULT_HOSTED_FORM_PATH = "/api/gateway/hosted-form"

        /** Live vs sandbox gateway (processing) base for [environment]. */
        fun defaultGatewayBase(environment: PaymentsEnvironment): String =
            if (environment ==
                PaymentsEnvironment.SANDBOX
            ) {
                SANDBOX_GATEWAY_BASE_URL
            } else {
                DEFAULT_GATEWAY_BASE_URL
            }

        /** Live vs sandbox dashboard (hosted-assets / vault) base for [environment]. */
        fun defaultDashboardBase(environment: PaymentsEnvironment): String =
            if (environment ==
                PaymentsEnvironment.SANDBOX
            ) {
                SANDBOX_DASHBOARD_BASE_URL
            } else {
                DEFAULT_DASHBOARD_BASE_URL
            }
    }
}

/** A hosted 3-D Secure payment form, ready to redirect the cardholder to. */
data class HostedPaymentResult(
    val url: String,
    val code: String,
    val amount: BigDecimal,
)

/**
 * A minted Hosted Fields access token — the short-lived credential the browser
 * uses to tokenize a card in-place against Maverick before the server charges
 * the resulting card token with [LiftedPaymentsClient.sale].
 *
 * @param accessToken the token the browser passes to the Hosted Fields SDK.
 * @param expiration  minutes the token is valid (echoed from the request).
 */
data class HostedFieldsToken(
    val accessToken: String,
    val expiration: Int,
)

/**
 * A card saved on file in the Maverick customer vault (SPEC_R3 §5). [vaultId] is
 * the single reference a caller stores and later passes to
 * [LiftedPaymentsClient.chargeSavedCard]; it encodes `"{customerId}:{cardId}"`
 * because charging a saved card needs the card id to resolve its token.
 *
 * @param vaultId    opaque `"{customerId}:{cardId}"` saved-card reference.
 * @param customerId the vault customer id.
 * @param cardId     the vault card id under that customer.
 * @param cardToken  the vault card token, when the card-add echoed one (may be blank;
 *                   [LiftedPaymentsClient.chargeSavedCard] re-resolves it either way).
 */
data class SavedCard(
    val vaultId: String,
    val customerId: String,
    val cardId: String,
    val cardToken: String = "",
)

/**
 * The result of a money call (`/payment/sale`, `/payment/{id}/capture`,
 * `/payment/{id}/refund`) parsed into ShipKit's canonical shape.
 *
 * @param transactionId  the gateway transaction id (used for capture/refund/get).
 * @param status         normalized lifecycle status: `approved`, `declined`,
 *                       `failed`, `authenticated`, or `pending`.
 * @param approved       whether this call succeeded under ShipKit policy (for a
 *                       sale: approved AND liability-shifted; for capture/refund:
 *                       the gateway reported an approved/captured result).
 * @param eci            3-D Secure ECI (scheme-specific; see [deriveLiabilityShift]).
 * @param cavv           3-D Secure cryptogram (CAVV/AAV); no cryptogram = no shift.
 * @param liabilityShift true only when the authenticated result shifts liability.
 * @param amount         the gateway's own amount on the record, when present.
 * @param authCode       issuer authorization code, when present.
 * @param message        human-readable gateway message, when present.
 */
data class GatewayResult(
    val transactionId: String,
    val status: String,
    val approved: Boolean,
    val eci: String? = null,
    val cavv: String? = null,
    val liabilityShift: Boolean = false,
    val amount: BigDecimal? = null,
    val authCode: String? = null,
    val message: String? = null,
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
 *                       (captured == authorized).
 */
data class PaymentVerification(
    val status: String,
    val eci: String? = null,
    val cavv: String? = null,
    val liabilityShift: Boolean = false,
    val amount: BigDecimal? = null,
)

/**
 * Client for the Lifted Payments gateway (Maverick Internal Gateway) with
 * **3-D Secure required**. It speaks the real Maverick contract:
 *
 *  - Charge:  `POST /payment/sale`  `{terminal:{id}, amount, card:{token}, "3ds":true, billing}`
 *  - Capture: `POST /payment/{id}/capture` `{terminal:{id}[, amount][, partial:{sequence,total}]}`
 *  - Refund:  `POST /payment/{id}/refund`  `{terminal:{id}[, amount]}` (Maverick has NO /void)
 *  - Get:     `GET  /payment/{id}`
 *  - Lookup:  `GET  /payments?filter[externalId]=...` (list envelope `{items,_meta}`)
 *  - Bearer auth on every call; every money call carries `terminal.id`.
 *
 * Gateway-contract notes for integrators:
 *  - A decline surfaces as **HTTP 422 with no transaction id** — record your own
 *    attempt row keyed by `externalId` *before* charging, or a declined attempt
 *    leaves no trace to reconcile against.
 *  - A repeat same-card + same-amount attempt may 422 as **duplicate-suspected**;
 *    resolve the prior attempt via the `externalId` lookup ([verifyPayment])
 *    before re-charging.
 *
 * The gateway authenticates the cardholder (biometric / OTP / risk-based) before
 * authorization, which shifts fraud chargeback liability to the issuer and lifts
 * approval rates. ShipKit never touches raw card data — the browser tokenizes the
 * card (Hosted Fields) or is sent to a Maverick-hosted form, and the server only
 * charges a token and reads the result back from the gateway.
 *
 * Shipping-label purchases are one of the highest fraud- and chargeback-risk
 * categories in commerce; forcing 3-D Secure is the whole point of this layer —
 * there is no code path that authorizes a card without it.
 *
 * Get a 3-D Secure merchant account: https://liftedholdings.com/payments
 */
class LiftedPaymentsClient(
    private val config: LiftedPaymentsConfig,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val log = LoggerFactory.getLogger(LiftedPaymentsClient::class.java)
    private val gson = Gson()
    private val jsonMedia = "application/json".toMediaType()

    // ---- Hosted Fields (primary, PCI-reducing browser card entry) ------------

    /**
     * Mint a short-lived **Hosted Fields** access token so the browser can
     * tokenize a card in-place against Maverick. 3-D Secure is requested
     * unconditionally (`"3ds": true`) — forced-3DS holds from the very first
     * step. The returned [HostedFieldsToken.accessToken] is handed to the Hosted
     * Fields SDK; the resulting card token is charged server-side by [sale].
     *
     * @param domain      the https origin the fields are embedded on (required by
     *                    Maverick; must be https).
     * @param expirationMinutes token lifetime, clamped to 1..30 minutes.
     */
    @Throws(IOException::class)
    fun hostedFieldsToken(
        domain: String,
        expirationMinutes: Int = 15,
    ): HostedFieldsToken {
        val cleanDomain = domain.trim().trimEnd('/')
        if (!cleanDomain.startsWith("https://")) {
            throw IOException("Hosted Fields domain must be https")
        }
        val expiration = expirationMinutes.coerceIn(1, 30)
        val payload =
            mapOf(
                "expiration" to expiration,
                "terminal" to config.terminalId,
                "domain" to cleanDomain,
                "saveCard" to "disabled",
                // Forced 3-D Secure from the first browser step — never off.
                "3ds" to true,
            )
        // Hosted Fields token minting is a DASHBOARD-host endpoint.
        val json = postJson("${config.dashboardBaseUrl}/api/hosted-fields/token", payload)
        val token =
            (json["accessToken"] as? String)
                ?: (json["token"] as? String)
                ?: throw IOException("Lifted Payments did not return a Hosted Fields token")
        return HostedFieldsToken(accessToken = token, expiration = expiration)
    }

    // ---- Money calls: sale / capture / refund / get --------------------------

    /**
     * Charge a tokenized card with **forced 3-D Secure** —
     * `POST /payment/sale` with body
     * `{terminal:{id}, amount, card:{token}, "3ds":true, billing}`.
     *
     * The card is always nested under `card` (a top-level token is a 422 at the
     * gateway); the amount is a 2-decimal string (ROUND_HALF_UP). A sale is only
     * [GatewayResult.approved] when the gateway approved it AND 3-D Secure
     * produced a liability shift — no shift, no approval.
     *
     * @param amount    the server-computed charge (never a client-supplied number).
     * @param cardToken the Maverick card token (from Hosted Fields tokenization).
     * @param billing   optional billing details passed through to the gateway.
     */
    @Throws(IOException::class)
    fun sale(
        amount: BigDecimal,
        cardToken: String,
        billing: Map<String, Any?>? = null,
    ): GatewayResult {
        if (cardToken.isBlank()) throw IOException("a card token is required to charge")
        val body =
            buildMap<String, Any?> {
                put("terminal", mapOf("id" to config.terminalId))
                put("amount", amount2dp(amount))
                put("card", mapOf("token" to cardToken))
                // Forced 3-D Secure — a BOOLEAN true, per the gateway contract.
                put("3ds", true)
                put("source", "Internet")
                billing?.takeIf { it.isNotEmpty() }?.let { put("billing", it) }
            }
        val json = postJson("${config.gatewayBaseUrl}/payment/sale", body)
        // A sale must be liability-shifted to count as approved (forced 3DS).
        return toGatewayResult(json, requireShift = true)
    }

    /**
     * Capture a previously authorized transaction — `POST /payment/{id}/capture`.
     * Per the gateway contract:
     *
     *  - Full capture: `{terminal:{id}}` — no amount field at all ([amount] null).
     *  - Partial capture: a **top-level** `amount` below the original auth —
     *    `{terminal:{id}, amount}`.
     *  - Multi-partial (split-shipment) capture: the top-level `amount` **plus**
     *    `partial:{sequence,total}`, where [sequence] is which capture this is
     *    (1-based) out of [total] planned captures.
     *
     * [sequence] and [total] must be given together, with `sequence in 1..total`,
     * and require an [amount]. The transaction is already 3-D Secure authenticated
     * from the sale, so approval here is not shift-gated.
     */
    @Throws(IOException::class)
    fun capture(
        transactionId: String,
        amount: BigDecimal? = null,
        sequence: Int? = null,
        total: Int? = null,
    ): GatewayResult {
        val id = requireTxnId(transactionId)
        if ((sequence == null) != (total == null)) {
            throw IOException("capture sequence and total must be supplied together")
        }
        if (sequence != null && total != null) {
            if (amount == null) {
                throw IOException("a multi-partial capture requires an amount")
            }
            if (total < 1 || sequence !in 1..total) {
                throw IOException("capture sequence must be within 1..total")
            }
        }
        val body =
            buildMap<String, Any?> {
                put("terminal", mapOf("id" to config.terminalId))
                // Partial capture = TOP-LEVEL amount; a full capture sends none.
                amount?.let { put("amount", amount2dp(it)) }
                // Multi-partial (split-shipment) captures also declare their slot.
                if (sequence != null && total != null) {
                    put("partial", mapOf("sequence" to sequence, "total" to total))
                }
            }
        val json = postJson("${config.gatewayBaseUrl}/payment/$id/capture", body)
        return toGatewayResult(json, requireShift = false)
    }

    /**
     * Refund (or reverse) a transaction — `POST /payment/{id}/refund`
     * `{terminal:{id}}`, with an optional [amount] for a partial refund.
     *
     * Maverick's gateway exposes **no `/void`** rail — a refund is the reversal
     * primitive for both settled and unsettled transactions.
     */
    @Throws(IOException::class)
    fun refund(
        transactionId: String,
        amount: BigDecimal? = null,
    ): GatewayResult {
        val id = requireTxnId(transactionId)
        val body =
            buildMap<String, Any?> {
                put("terminal", mapOf("id" to config.terminalId))
                amount?.let { put("amount", amount2dp(it)) }
            }
        val json = postJson("${config.gatewayBaseUrl}/payment/$id/refund", body)
        return toGatewayResult(json, requireShift = false)
    }

    /**
     * Fetch a single transaction by id — `GET /payment/{id}` — and interpret it
     * under forced-3DS policy. This is the authoritative server-side verification
     * for a Hosted Fields sale (the sale response and this lookup agree). Some
     * hosts wrap the record under `item`/`data`/`payment`; those are unwrapped.
     */
    @Throws(IOException::class)
    fun getPayment(transactionId: String): PaymentVerification {
        val id = requireTxnId(transactionId)
        val json = getJson("${config.gatewayBaseUrl}/payment/$id")
        val record =
            listOf("item", "data", "payment")
                .firstNotNullOfOrNull { json[it] as? Map<*, *> }
                ?: json
        return interpretTransaction(record)
    }

    // ---- Frictionless + card-on-file (account-gated; SPEC_R3 §5) -------------

    /**
     * Charge a tokenized card **frictionlessly** — `POST /payment/sale` with
     * `"3ds":false` — for a faster repeat checkout. This is the ONE code path that
     * charges a card without 3-D Secure, and it is **account-gated**: it runs only
     * when [LiftedPaymentsConfig.frictionlessAllowed] is true (a Lifted
     * merchant/managed account that opted in). A self-host / bring-your-own-payments
     * deployment never reaches an allowed config, so this method refuses with
     * [IOException] — forced 3-D Secure ([sale]) stays its only charge path.
     *
     * Unlike [sale], approval is NOT liability-shift-gated (there is no 3-D Secure
     * authentication to shift liability); `approved` reflects the gateway's own
     * decision.
     *
     * @param amount    the server-computed charge (never a client-supplied number).
     * @param cardToken a Maverick card token (Hosted Fields tokenization or a vault
     *                  card token from [chargeSavedCard]).
     * @param billing   optional billing details passed through to the gateway.
     */
    @Throws(IOException::class)
    fun saleFrictionless(
        amount: BigDecimal,
        cardToken: String,
        billing: Map<String, Any?>? = null,
    ): GatewayResult {
        requireFrictionless()
        if (cardToken.isBlank()) throw IOException("a card token is required to charge")
        val body =
            buildMap<String, Any?> {
                put("terminal", mapOf("id" to config.terminalId))
                put("amount", amount2dp(amount))
                put("card", mapOf("token" to cardToken))
                // Frictionless — 3-D Secure is explicitly OFF (a BOOLEAN false).
                put("3ds", false)
                put("source", "Internet")
                billing?.takeIf { it.isNotEmpty() }?.let { put("billing", it) }
            }
        val json = postJson("${config.gatewayBaseUrl}/payment/sale", body)
        // No 3-D Secure here, so approval is not shift-gated.
        return toGatewayResult(json, requireShift = false)
    }

    /**
     * Save a card on file in the Maverick **customer vault**, modelled on Launchpad
     * `maverick_gateway.vault_add`: create a vault customer, attach a billing
     * record, then attach the card — all on the dashboard host. Returns a
     * [SavedCard] whose [SavedCard.vaultId] encodes `"{customerId}:{cardId}"`, the
     * single reference [chargeSavedCard] later resolves to a card token to charge.
     *
     * **Account-gated** — refuses with [IOException] unless
     * [LiftedPaymentsConfig.frictionlessAllowed] is true. Card data never reaches
     * ShipKit: [cardToken] is a Maverick Hosted Fields card token, not a PAN.
     *
     * Fail-closed: if the card cannot be attached (a 2xx with no card id yields an
     * unchargeable vault entry), the just-created customer is best-effort deleted and
     * an [IOException] is raised, so no orphan/phantom saved card is left behind.
     *
     * @param cardToken a Maverick Hosted Fields card token to vault.
     * @param billing   cardholder billing (`first_name`, `last_name`, `email`,
     *                  `phone`, `company`, `address1`, `city`, `state`, `zip`,
     *                  `country`); `address1` + `zip` are needed for the billing
     *                  record the card-add references.
     */
    @Throws(IOException::class)
    fun saveCard(
        cardToken: String,
        billing: Map<String, Any?>? = null,
    ): SavedCard {
        requireFrictionless()
        if (cardToken.isBlank()) {
            throw IOException(
                "a Hosted Fields card token is required to save a card",
            )
        }
        val b = billing ?: emptyMap()

        val customerBody =
            buildMap<String, Any?> {
                mapVaultString(b, "first_name", "firstName")
                mapVaultString(b, "last_name", "lastName")
                mapVaultString(b, "email", "email")
                mapVaultString(b, "phone", "phone")
                mapVaultString(b, "company", "company")
                mapVaultString(b, "address1", "address1")
                mapVaultString(b, "city", "city")
                mapVaultString(b, "state", "state")
                mapVaultString(b, "zip", "zipCode")
                put("dba", mapOf("id" to config.dbaId))
            }
        val customer = postJson("${config.dashboardBaseUrl}/api/customer-vault", customerBody)
        val customerId =
            (customer["id"] ?: customer["customerId"])?.toString()?.trim()?.takeIf {
                it.isNotEmpty()
            }
                ?: throw IOException("vault customer was not created")

        // Billing record is REQUIRED before a card (the card-add references its id);
        // returns "" when the merchant supplied too little address to create one.
        val billingId = createVaultBilling(customerId, b)

        val cardBody =
            buildMap<String, Any?> {
                put("terminal", mapOf("id" to config.terminalId))
                billingId?.let { put("billing", mapOf("id" to it)) }
                put("token", cardToken)
            }
        val added =
            postJson("${config.dashboardBaseUrl}/api/customer-vault/$customerId/card", cardBody)
        val cardId =
            (added["id"] ?: added["cardId"])?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (cardId == null) {
            // Fail closed: an id-less card-add is unchargeable. Best-effort delete the
            // orphan customer so nothing phantom is left in the merchant's vault.
            deleteQuiet("${config.dashboardBaseUrl}/api/customer-vault/$customerId")
            throw IOException("the card could not be saved; please re-enter the card details")
        }
        val cardToken2 = (added["token"] as? String)?.trim().orEmpty()
        return SavedCard(
            vaultId = encodeVault(customerId, cardId),
            customerId = customerId,
            cardId = cardId,
            cardToken = cardToken2,
        )
    }

    /**
     * Charge a **saved card** frictionlessly. Resolves [vaultId]
     * (`"{customerId}:{cardId}"`) to its vault card token
     * (`GET /api/customer-vault/{customerId}/card/{cardId}`), then charges it via
     * [saleFrictionless] (`3ds:false`) — the one-tap repeat-purchase path, modelled
     * on Launchpad `maverick_gateway.sale(vault_id=...)`.
     *
     * **Account-gated** — refuses with [IOException] unless
     * [LiftedPaymentsConfig.frictionlessAllowed] is true.
     *
     * @param amount  the server-computed charge (never a client-supplied number).
     * @param vaultId the saved-card reference from [saveCard].
     * @param billing optional billing details passed through to the gateway.
     */
    @Throws(IOException::class)
    fun chargeSavedCard(
        amount: BigDecimal,
        vaultId: String,
        billing: Map<String, Any?>? = null,
    ): GatewayResult {
        requireFrictionless()
        val token = vaultCardToken(vaultId)
        return saleFrictionless(amount, token, billing)
    }

    /**
     * Resolve a saved-card [vaultId] (`"{customerId}:{cardId}"`) to the vault CARD
     * token used to charge it (`GET /api/customer-vault/{customerId}/card/{cardId}`).
     * `internal` so the resolution is unit-testable without a live gateway.
     */
    @Throws(IOException::class)
    internal fun vaultCardToken(vaultId: String): String {
        requireFrictionless()
        val (customerId, cardId) = decodeVault(vaultId)
        if (customerId.isEmpty() || cardId.isEmpty()) {
            throw IOException("invalid saved-card reference")
        }
        val rec = getJson("${config.dashboardBaseUrl}/api/customer-vault/$customerId/card/$cardId")
        return (rec["token"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IOException("saved card token not found")
    }

    /** Refuse any frictionless money path unless this account/tier is gated on. */
    private fun requireFrictionless() {
        if (!config.frictionlessAllowed) {
            throw IOException(
                "Frictionless mode (3-D Secure off / card-on-file) is not enabled for this " +
                    "account. Forced 3-D Secure is the only mode off Lifted's rails.",
            )
        }
    }

    /**
     * POST a billing-information record for a vault customer; returns its id, or
     * `null` when there is too little address to create one (the card-add then
     * surfaces Maverick's own error). Mirrors Launchpad `_create_billing_information`.
     */
    @Throws(IOException::class)
    private fun createVaultBilling(
        customerId: String,
        billing: Map<String, Any?>,
    ): String? {
        val body =
            buildMap<String, Any?> {
                mapVaultString(billing, "first_name", "firstName")
                mapVaultString(billing, "last_name", "lastName")
                mapVaultString(billing, "address1", "address")
                mapVaultString(billing, "city", "city")
                mapVaultString(billing, "state", "state")
                mapVaultString(billing, "zip", "zip")
                put(
                    "country",
                    (billing["country"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "US",
                )
            }
        // Not enough to create a billing record — the card-add will surface the error.
        if (body["address"] == null || body["zip"] == null) return null
        val rec =
            postJson(
                "${config.dashboardBaseUrl}/api/customer-vault/$customerId/billing-information",
                body,
            )
        return (rec["id"])?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Copy `billing[srcKey]` (a non-blank string) into this map under [dstKey], capped at 120 chars. */
    private fun MutableMap<String, Any?>.mapVaultString(
        billing: Map<String, Any?>,
        srcKey: String,
        dstKey: String,
    ) {
        (billing[srcKey] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            put(
                dstKey,
                it.take(120),
            )
        }
    }

    /** Best-effort DELETE that never throws — used only for fail-closed vault cleanup. */
    private fun deleteQuiet(url: String) {
        runCatching {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${config.bearerToken}")
                    .delete()
                    .build()
            http.newCall(request).execute().use { /* discard */ }
        }
    }

    // ---- Hosted form (verified default path; config-overridable) -------------

    /**
     * Create a **hosted 3-D Secure payment form** for [amount] and return its URL
     * for the cardholder to complete on a Maverick-hosted page
     * ([CardEntryMode.HOSTED_FORM]).
     *
     * 3-D Secure is requested `Required` unconditionally — there is no flag to
     * disable it. The creation path is [LiftedPaymentsConfig.hostedFormPath],
     * whose default ([LiftedPaymentsConfig.DEFAULT_HOSTED_FORM_PATH]) is verified
     * against the gateway contract and stays overridable for differently
     * provisioned accounts. The primary integration is Hosted Fields
     * ([hostedFieldsToken] + [sale]); this path exists for deployments that
     * prefer a redirect model.
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
                // Hosted assets live on the dashboard host; the path defaults to
                // the verified hosted-form endpoint and is config-overridable.
                .url("${config.dashboardBaseUrl}${config.hostedFormPath}")
                .header("Authorization", "Bearer ${config.bearerToken}")
                .post(gson.toJson(payload).toRequestBody(jsonMedia))
                .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                log.warn("Lifted Payments hosted-form request failed (HTTP {})", response.code)
                // Surface the gateway's own {name,message,code,status} reason.
                val reason =
                    (parseJsonObject(body)?.get("message") as? String)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                throw IOException(
                    "Failed to create hosted payment form (HTTP ${response.code})" +
                        (reason?.let { ": $it" } ?: ""),
                )
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
     * processing host by its [externalId] —
     * `GET /payments?filter[externalId]=...` (Bearer merchant token; the brackets
     * and value are URL-encoded). Used by the hosted-form flow, where the
     * transaction id is not known up front. The only trusted source of payment
     * truth: return-URL query params are attacker-controllable and are never used
     * to decide whether a label may be bought. (Hosted Fields sales verify by
     * transaction id via [getPayment].)
     *
     * The reply is the standard list envelope `{items, _meta:{totalCount,...}}`.
     * `_meta.totalCount` MUST be exactly 1 before the item is trusted: per the
     * gateway contract a missing/malformed filter param silently returns the FULL
     * unfiltered transaction list, so any other count — zero, many, or absent —
     * maps to `pending` (not-found semantics), never to "take the first item".
     *
     * Returns a [PaymentVerification]; a transaction not yet present (the
     * cardholder has not finished the hosted flow) maps to `pending`.
     */
    @Throws(IOException::class)
    fun verifyPayment(externalId: String): PaymentVerification {
        // URL-encode the filter key (its brackets) and the reference value. The id
        // is server-generated today (so this is defense-in-depth), but a lookup
        // key must never be able to smuggle extra query params or a filter
        // operator — and an unencoded/malformed filter degrades to the FULL
        // unfiltered list on the gateway side.
        val charset = Charsets.UTF_8.name()
        val encodedKey = java.net.URLEncoder.encode("filter[externalId]", charset)
        val encodedId = java.net.URLEncoder.encode(externalId, charset)
        val request =
            Request
                .Builder()
                // Processing/transaction data lives on the gateway host. externalId
                // is a filterable field on the transaction list endpoint.
                .url("${config.gatewayBaseUrl}/payments?$encodedKey=$encodedId")
                .header("Authorization", "Bearer ${config.bearerToken}")
                .get()
                .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                log.warn("Lifted Payments transaction lookup failed (HTTP {})", response.code)
                throw IOException("Failed to verify payment (HTTP ${response.code})")
            }
            val txn =
                singleFilteredTransaction(body) ?: return PaymentVerification(status = "pending")
            // Exact-match guard (defense-in-depth beneath the totalCount check):
            // the returned record must POSITIVELY carry our externalId — any
            // record matched by a working filter necessarily does. A mismatch or
            // a missing field means this is not our txn — treat it as
            // not-yet-present.
            val returnedId = (txn["externalId"] as? String)?.trim()
            if (returnedId != externalId) {
                log.warn("Transaction externalId mismatch on lookup; ignoring the returned record")
                return PaymentVerification(status = "pending")
            }
            return interpretTransaction(txn)
        }
    }

    /**
     * Extract THE transaction from a filtered list envelope, or `null` (pending
     * semantics) unless the envelope proves the filter applied:
     * `_meta.totalCount == 1` and exactly one item. Anything else — including a
     * multi-item reply, which is the signature of the gateway ignoring a
     * missing/malformed filter and returning the full unfiltered list — is never
     * trusted.
     */
    private fun singleFilteredTransaction(body: String): Map<*, *>? {
        val json = parseJsonObject(body) ?: return null
        val meta = json["_meta"] as? Map<*, *> ?: return null
        val totalCount = (meta["totalCount"] as? Number)?.toInt() ?: return null
        val items = (json["items"] as? List<*>).orEmpty()
        if (totalCount != 1 || items.size != 1) {
            if (totalCount > 1 || items.size > 1) {
                log.warn(
                    "Transaction lookup returned {} records (items={}) — filter did not " +
                        "apply; refusing to trust the list",
                    totalCount,
                    items.size,
                )
            }
            return null
        }
        return items[0] as? Map<*, *>
    }

    /**
     * Map a gateway transaction record to a [PaymentVerification] under
     * forced-3DS policy (approved requires a liability shift). Extracted as a pure
     * function so the status/liability logic is unit-testable without a live
     * gateway.
     */
    internal fun interpretTransaction(txn: Map<*, *>): PaymentVerification {
        val r = toGatewayResult(txn, requireShift = true)
        return PaymentVerification(
            status = r.status,
            eci = r.eci,
            cavv = r.cavv,
            liabilityShift = r.liabilityShift,
            amount = r.amount,
        )
    }

    /**
     * The single source of truth that maps a raw gateway record (sale / capture /
     * refund / transaction lookup) into a [GatewayResult].
     *
     * @param requireShift when true (a sale), an approved gateway status only
     *   normalizes to `approved` if 3-D Secure produced a liability shift —
     *   otherwise it is refused as `declined`. Capture/refund of an
     *   already-authenticated transaction pass `false`.
     */
    private fun toGatewayResult(
        txn: Map<*, *>,
        requireShift: Boolean,
    ): GatewayResult {
        val gatewayStatus = statusValue(txn["status"]) ?: (txn["response"] as? String)?.trim()
        val statusLower = gatewayStatus?.lowercase()

        val threeds = txn["threeds"] as? Map<*, *>
        val eci = (threeds?.get("eci") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val cavv =
            ((threeds?.get("cavv") ?: threeds?.get("aav")) as? String)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val shift = deriveLiabilityShift(eci, cavv)

        val statusApproved = statusLower in APPROVED_STATUSES
        val statusDeclined = statusLower in DECLINED_STATUSES

        val normalized =
            when {
                // Approved with (or not requiring) a liability shift is the only
                // "buy the label" state; approved WITHOUT a shift under forced 3DS
                // is refused as declined.
                statusApproved && (!requireShift || shift) -> "approved"

                statusApproved && requireShift && !shift -> "declined"

                statusDeclined -> "declined"

                statusLower == "error" || statusLower == "failed" -> "failed"

                // 3-D Secure completed but the sale is not yet captured/approved.
                statusLower == null -> if (threeds != null) "authenticated" else "pending"

                else -> "pending"
            }

        return GatewayResult(
            transactionId =
                (txn["id"] as? String)
                    ?: (txn["transactionId"] as? String)
                    ?: (txn["id"]?.toString() ?: txn["transactionId"]?.toString() ?: ""),
            status = normalized,
            approved = normalized == "approved",
            eci = eci,
            cavv = cavv,
            liabilityShift = shift,
            amount = parseTxnAmount(txn),
            authCode =
                (txn["authCode"] ?: txn["auth_code"])?.toString()?.trim()?.takeIf {
                    it
                        .isNotEmpty()
                },
            message =
                (txn["message"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: gatewayStatus,
        )
    }

    /**
     * Read a gateway `status`, which may be a bare string or a nested object
     * (`{status|message|reason}`), into a plain string. Mirrors the gateway's own
     * status envelope.
     */
    private fun statusValue(status: Any?): String? =
        when (status) {
            is Map<*, *> -> {
                (status["status"] ?: status["message"] ?: status["reason"])
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }

            is String -> {
                status.trim().takeIf { it.isNotEmpty() }
            }

            else -> {
                null
            }
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

    // ---- HTTP + JSON helpers -------------------------------------------------

    /** POST a JSON body to the gateway/dashboard with Bearer auth; parse the object reply. */
    @Throws(IOException::class)
    private fun postJson(
        url: String,
        body: Map<String, Any?>,
    ): Map<String, Any?> {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer ${config.bearerToken}")
                .header("Accept", "application/json")
                .post(gson.toJson(body).toRequestBody(jsonMedia))
                .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                log.warn(
                    "Lifted Payments request to {} failed (HTTP {})",
                    url.substringAfterLast('/'),
                    response.code,
                )
                // Error replies carry a {name,message,code,status} envelope; surface
                // the gateway's own `message` (e.g. a 422 decline/duplicate reason)
                // so callers see WHY, not just the status code.
                val reason =
                    (parseJsonObject(raw)?.get("message") as? String)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                throw IOException(
                    "Lifted Payments gateway error (HTTP ${response.code})" +
                        (reason?.let { ": $it" } ?: ""),
                )
            }
            return parseJsonObject(raw)
                ?: throw IOException("Invalid JSON response from Lifted Payments gateway")
        }
    }

    /** GET a JSON object from the gateway/dashboard with Bearer auth. */
    @Throws(IOException::class)
    private fun getJson(url: String): Map<String, Any?> {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer ${config.bearerToken}")
                .header("Accept", "application/json")
                .get()
                .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                log.warn(
                    "Lifted Payments lookup {} failed (HTTP {})",
                    url.substringAfterLast('/'),
                    response.code,
                )
                throw IOException("Lifted Payments gateway error (HTTP ${response.code})")
            }
            return parseJsonObject(raw)
                ?: throw IOException("Invalid JSON response from Lifted Payments gateway")
        }
    }

    private fun parseJsonObject(body: String): Map<String, Any?>? =
        try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(body, Map::class.java) as? Map<String, Any?>
        } catch (e: Exception) {
            null
        }

    /**
     * Serialize an amount to the gateway's 2-decimal string, ROUND_HALF_UP, after
     * enforcing the same bounds the verified gateway does: strictly **positive** and
     * no greater than [MAX_AMOUNT]. A zero/negative charge (e.g. a `0.00` test/free
     * carrier rate or a malformed markup) or an absurd amount is rejected **locally**
     * with an [IOException] before any money call leaves the process, rather than
     * drawing an opaque gateway `422` after the request is sent.
     */
    @Throws(IOException::class)
    private fun amount2dp(amount: BigDecimal): String {
        val scaled = amount.setScale(2, RoundingMode.HALF_UP)
        if (scaled.signum() <= 0) {
            throw IOException("charge amount must be greater than zero")
        }
        if (scaled > MAX_AMOUNT) {
            throw IOException("charge amount exceeds the maximum of $MAX_AMOUNT")
        }
        return scaled.toPlainString()
    }

    private fun requireTxnId(transactionId: String): String =
        transactionId.trim().takeIf { it.isNotEmpty() }
            ?: throw IOException("transaction id required")

    companion object {
        /**
         * Upper bound on a single charge, mirroring the verified gateway's
         * `_MAX_AMOUNT`. A larger amount is refused locally (see [amount2dp]) rather
         * than sent and bounced as an opaque `422`.
         */
        private val MAX_AMOUNT = BigDecimal("1000000.00")

        /**
         * Gateway statuses that count as a successful authorization/capture.
         * Maverick uses several synonyms across sale/capture/refund replies.
         */
        private val APPROVED_STATUSES =
            setOf("approved", "approval", "success", "succeeded", "captured")

        /** Gateway statuses that are a definitive decline. */
        private val DECLINED_STATUSES =
            setOf(
                "declined",
                "decline",
                "denied",
                "nsf",
                "insufficient",
                "card_declined",
                "do_not_honor",
            )

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

        /** Encode a vault `customerId` + `cardId` into a single `"{cust}:{card}"` reference. */
        internal fun encodeVault(
            customerId: String,
            cardId: String,
        ): String = "${customerId.trim()}:${cardId.trim()}"

        /**
         * Decode a `"{customerId}:{cardId}"` reference back to its parts. Splits on the
         * FIRST `:` only (ids never contain one, but this is unambiguous either way);
         * a malformed reference yields a blank part, which callers reject.
         */
        internal fun decodeVault(vaultId: String): Pair<String, String> {
            val raw = vaultId.trim()
            val idx = raw.indexOf(':')
            if (idx <= 0 || idx == raw.length - 1) return "" to ""
            return raw.substring(0, idx).trim() to raw.substring(idx + 1).trim()
        }
    }
}
