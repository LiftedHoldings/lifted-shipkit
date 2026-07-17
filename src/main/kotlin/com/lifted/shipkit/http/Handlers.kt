package com.lifted.shipkit.http

import com.easypost.exception.EasyPostException
import com.lifted.shipkit.config.ShipKitConfig
import com.lifted.shipkit.model.LabelRecord
import com.lifted.shipkit.model.MarkupConfig
import com.lifted.shipkit.model.PaymentSession
import com.lifted.shipkit.model.TrackingRecord
import com.lifted.shipkit.payments.LiftedPaymentsClient
import com.lifted.shipkit.security.ApiKeyStore
import com.lifted.shipkit.security.KeyGenerator
import com.lifted.shipkit.security.RateLimiter
import com.lifted.shipkit.shipping.EasyPostService
import com.lifted.shipkit.sms.SmsVerifier
import com.lifted.shipkit.store.LabelStore
import com.lifted.shipkit.util.PhoneNumbers
import io.javalin.http.Context
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * All HTTP request handlers. Each handler is a thin adapter: it parses the
 * request, delegates to a service, and serializes a JSON response using the
 * canonical snake_case response contract. Business logic lives in
 * [EasyPostService], [LiftedPaymentsClient], and [LabelStore].
 *
 * Optional features degrade gracefully: if EasyPost, Lifted Payments, or SMS
 * verification is not configured, the relevant endpoints return HTTP 503 with a
 * clear message rather than failing obscurely.
 */
class Handlers(
    private val config: ShipKitConfig,
    private val store: LabelStore,
    private val easyPost: EasyPostService?,
    private val payments: LiftedPaymentsClient?,
    private val sms: SmsVerifier,
    private val apiKeys: ApiKeyStore,
) {
    private val log = LoggerFactory.getLogger(Handlers::class.java)

    /**
     * Denial-of-wallet throttle for the browser-published (publishable-key) paid
     * paths. Held on the (singleton) [Handlers] so its windows persist across
     * requests. Disabled when [ShipKitConfig.rateLimitPerMinute] is not positive.
     */
    private val rateLimiter = RateLimiter(config.rateLimitPerMinute)

    // ---- API-key authentication ---------------------------------------------

    /**
     * Gate for the `/api` surface. Called from Javalin's `before` filter: every
     * `/api` route requires a valid, non-revoked API key in the
     * `ShipKit-Api-Key` header — **except** two machine-to-machine paths that
     * authenticate by other means and which an API-key holder does not drive:
     *
     *  - `GET /api/health` — an unauthenticated liveness/readiness probe.
     *  - `POST /api/webhook/easypost` — authenticated by EasyPost's HMAC-SHA256
     *    signature (a strictly stronger, origin-bound scheme); EasyPost cannot
     *    send a ShipKit key, so requiring one here would make the webhook
     *    permanently un-callable.
     *
     * The presented key is hashed and looked up in constant time (see
     * [KeyGenerator.verify]); the full key is never logged. On success the key's
     * `last_used_at` is stamped.
     *
     * A **publishable** key (`pk_…`) is additionally confined to the customer
     * rating + 3-D Secure payment flow: it is safe to embed in a browser because
     * it cannot reach the privileged actions (direct label buy, batch, customs,
     * markup writes, maintenance, admin, key management). A **secret** key (`sk_…`)
     * reaches everything.
     *
     * Throws [ApiKeyRejected] (mapped to `401`/`403` by [buildApp]) when the
     * request is not authorized; returns normally when it may proceed.
     */
    fun authenticateApiKey(ctx: Context) {
        // CORS preflight carries no custom headers and authenticates nothing —
        // it must never be rejected, or cross-origin (managed/CDN) use breaks.
        if (ctx.method() == io.javalin.http.HandlerType.OPTIONS) return

        val path = ctx.path()
        if (path == "/api/health" || path == "/api/webhook/easypost") return

        val presented = ctx.header(API_KEY_HEADER)?.trim()
        if (presented.isNullOrEmpty()) {
            throw ApiKeyRejected(401, "Missing API key. Send it in the '$API_KEY_HEADER' header.")
        }
        val record = apiKeys.findByHash(KeyGenerator.sha256Hex(presented))
        // Re-verify in constant time even after the hash lookup, so a valid record
        // is confirmed against the presented key without any early-exit compare.
        if (record == null || record.revoked || !KeyGenerator.verify(presented, record.hash)) {
            throw ApiKeyRejected(401, "Invalid or revoked API key.")
        }
        apiKeys.touchLastUsed(record.id)

        // Scope gate: a browser-publishable key may only drive the customer flow.
        if (record.scope == KeyGenerator.Scope.PUBLISHABLE &&
            !isPublishableSafe(ctx.method(), path)
        ) {
            throw ApiKeyRejected(
                403,
                "This endpoint requires a secret API key (sk_…). A publishable key " +
                    "(pk_…) may only rate shipments and run the 3-D Secure payment flow.",
            )
        }

        // Denial-of-wallet throttle: a publishable key is embedded in page source by
        // design, so a lifted key could otherwise loop the EasyPost-billed / gateway
        // paid paths for free. Limit per (key id + client IP). Secret keys are
        // server-side/trusted and are never limited here.
        if (record.scope == KeyGenerator.Scope.PUBLISHABLE &&
            rateLimiter.enabled &&
            isRateLimitedPath(ctx.method(), path) &&
            !rateLimiter.tryAcquire("${record.id}|${ctx.ip()}")
        ) {
            throw ApiKeyRejected(429, "Rate limit exceeded. Please slow down and retry shortly.")
        }
    }

    /**
     * The subset of the publishable-key allowlist that costs money or upstream quota
     * — EasyPost address verification / shipment rating, and the Lifted Payments
     * session / status / label-purchase calls. These are throttled per publishable
     * key + IP; the read-only tier/return endpoints are not.
     */
    private fun isRateLimitedPath(
        method: io.javalin.http.HandlerType,
        path: String,
    ): Boolean =
        when (method) {
            io.javalin.http.HandlerType.POST -> {
                path == "/api/address/verify" ||
                    path == "/api/shipment/create" ||
                    path == "/api/shipment/smartrates" ||
                    path == "/api/payment/session" ||
                    path.startsWith("/api/payment/purchase-label/")
            }

            io.javalin.http.HandlerType.GET -> {
                path.startsWith("/api/payment/status/")
            }

            else -> {
                false
            }
        }

    /**
     * The allowlist a publishable (`pk_…`) key may reach — exactly the widget's
     * customer journey: verify an address, rate a shipment, open a payment
     * session, poll its status (+ the 3-DS return landing), and buy the label the
     * customer just paid for. Everything else under `/api` is secret-only.
     */
    private fun isPublishableSafe(
        method: io.javalin.http.HandlerType,
        path: String,
    ): Boolean =
        when (method) {
            io.javalin.http.HandlerType.POST -> {
                path == "/api/address/verify" ||
                    path == "/api/shipment/create" ||
                    path == "/api/shipment/smartrates" ||
                    path == "/api/payment/session" ||
                    path.startsWith("/api/payment/purchase-label/")
            }

            io.javalin.http.HandlerType.GET -> {
                path.startsWith("/api/payment/status/") ||
                    path.startsWith("/api/payment/return/") ||
                    // Non-secret pricing/tier config — safe for the browser widget.
                    path == "/api/config/tier"
            }

            else -> {
                false
            }
        }

    // ---- Shipping ------------------------------------------------------------

    /** Verify a destination address; emits `{verified, address, residential, errors}`. */
    fun verifyAddress(ctx: Context) =
        withShipping(ctx) { service ->
            val r = service.verifyAddress(ctx.bodyMap())
            ctx.json(
                mapOf(
                    "verified" to r.verified,
                    "address" to
                        mapOf(
                            "name" to r.name,
                            "company" to r.company,
                            "street1" to r.street1,
                            "street2" to r.street2,
                            "city" to r.city,
                            "state" to r.state,
                            "zip" to r.zip,
                            "country" to r.country,
                            "phone" to r.phone,
                        ),
                    "residential" to r.residential,
                    "errors" to r.verificationErrors,
                ),
            )
        }

    /** Rate a shipment; emits `{id, rates:[…], messages:[…]}` with money as strings. */
    fun createShipment(ctx: Context) =
        withShipping(ctx) { service ->
            val body = ctx.bodyMap()

            @Suppress("UNCHECKED_CAST")
            val quote =
                service.createShipment(
                    fromAddress =
                        body["from"] as? Map<String, Any?>
                            ?: body["from_address"] as? Map<String, Any?> ?: emptyMap(),
                    toAddress =
                        body["to"] as? Map<String, Any?>
                            ?: body["to_address"] as? Map<String, Any?> ?: emptyMap(),
                    parcel = body["parcel"] as? Map<String, Any?> ?: emptyMap(),
                    options = body["options"],
                    markup = store.getMarkupConfig(),
                )
            ctx.json(
                mapOf(
                    "id" to quote.id,
                    "rates" to
                        quote.rates.map {
                            mapOf(
                                "id" to it.id,
                                "carrier" to it.carrier,
                                "service" to it.service,
                                "rate" to it.rate,
                                "currency" to it.currency,
                                "delivery_days" to it.deliveryDays,
                            )
                        },
                    "messages" to
                        quote.messages.map {
                            mapOf(
                                "carrier" to it.carrier,
                                "type" to it.type,
                                "message" to it.message,
                            )
                        },
                ),
            )
        }

    fun smartRates(ctx: Context) =
        withShipping(ctx) { service ->
            val shipmentId =
                ctx.bodyMap()["shipment_id"] as? String
                    ?: return@withShipping fail(ctx, 400, "shipment_id is required")
            ctx.json(
                mapOf(
                    "shipment_id" to shipmentId,
                    "rates" to service.estimatedDeliveryDates(shipmentId),
                ),
            )
        }

    /** Low-level direct label buy (self-host backends that gate payment themselves). */
    fun buyLabel(ctx: Context) =
        withShipping(ctx) { service ->
            val body = ctx.bodyMap()
            val shipmentId =
                body["shipment_id"] as? String
                    ?: return@withShipping fail(ctx, 400, "shipment_id is required")
            val rateId = body["rate_id"] as? String
            val endShipperId = (body["end_shipper_id"] as? String) ?: store.getEndShipperId()
            val bought = service.buyLabel(shipmentId, rateId, endShipperId)
            ctx.json(
                mapOf(
                    "id" to bought.shipmentId,
                    "tracking_code" to (bought.trackingCode ?: ""),
                    "label_url" to (bought.labelUrl ?: ""),
                    "qr_code_url" to bought.qrCodeUrl,
                    "carrier" to bought.carrier,
                    "service" to bought.service,
                    "status" to (bought.status ?: ""),
                ),
            )
        }

    fun batchCreate(ctx: Context) =
        withShipping(ctx) { service ->
            @Suppress("UNCHECKED_CAST")
            val ids =
                ctx.bodyMap()["shipment_ids"] as? List<String>
                    ?: return@withShipping fail(ctx, 400, "shipment_ids is required")
            ctx.json(service.createAndBuyBatch(ids))
        }

    fun scanFormCreate(ctx: Context) =
        withShipping(ctx) { service ->
            val batchId =
                ctx.bodyMap()["batch_id"] as? String
                    ?: return@withShipping fail(ctx, 400, "batch_id is required")
            ctx.json(service.createScanForm(batchId))
        }

    fun customsCreate(ctx: Context) =
        withShipping(ctx) { service ->
            ctx.json(service.createCustomsInfo(ctx.bodyMap()))
        }

    fun getEndShipper(ctx: Context) {
        val id = store.getEndShipperId()
        ctx.json(mapOf("success" to true, "end_shipper_id" to id, "configured" to (id != null)))
    }

    fun createEndShipper(ctx: Context) =
        withShipping(ctx) { service ->
            val result = service.createEndShipper(ctx.bodyMap())
            store.setEndShipperId(result.id)
            ctx.json(
                mapOf(
                    "success" to true,
                    "end_shipper_id" to result.id,
                    "name" to result.name,
                    "company" to result.company,
                ),
            )
        }

    /**
     * EasyPost tracking webhook receiver. Verifies `X-Hmac-Signature` before
     * trusting anything: `hmac-sha256-hex=` + hex HMAC-SHA256 over the RAW request
     * bytes, keyed by the NFKD-normalized webhook secret, compared in constant
     * time. An unverifiable or forged event is rejected — never processed.
     *
     * Once verified, a `tracker.*` event's latest state (status, status_detail,
     * carrier, est_delivery_date, event timestamp) is persisted keyed by tracking
     * code so `/api/tracking/{trackingCode}` can surface the current lifecycle. A
     * malformed body or a non-tracking / unmatched event is a graceful no-op that
     * still returns `200` (the provider treats any 2xx as delivered and will not
     * retry); a persistence failure is NOT swallowed — it propagates to a `5xx` so
     * EasyPost retries rather than silently dropping the event.
     */
    fun webhook(ctx: Context) {
        val secret =
            config.easyPostWebhookSecret
                ?: return fail(ctx, 503, "Webhook verification is not configured")
        val provided =
            ctx.header("X-Hmac-Signature")
                ?: return fail(ctx, 401, "Missing X-Hmac-Signature")
        if (!hmacMatches(secret, ctx.bodyAsBytes(), provided)) {
            log.warn("Rejected EasyPost webhook with invalid signature")
            fail(ctx, 401, "Invalid webhook signature")
            return
        }
        // Parse defensively: a malformed body must not crash the endpoint (still
        // 200, as the provider expects). A store failure inside persistTracking,
        // however, deliberately propagates so the event is retried, not lost.
        runCatching { ctx.bodyMap() }.onSuccess { event ->
            log.info(
                "Verified tracking webhook: {}",
                event["description"] ?: event["object"] ?: "event",
            )
            persistTracking(event)
        }
        ctx.status(200).result("Event received")
    }

    /**
     * Persist the tracking state carried by a verified EasyPost tracker event. The
     * Tracker object rides in the top-level `result`; a `tracking_code` is required
     * to key the row. An event without one (a non-tracking event, or a payload that
     * simply does not carry a tracker) is ignored — a safe no-op, not an error.
     */
    private fun persistTracking(event: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val result = event["result"] as? Map<String, Any?> ?: return
        val trackingCode =
            (result["tracking_code"] as? String)?.takeIf { it.isNotBlank() } ?: return
        val record =
            TrackingRecord(
                trackingCode = trackingCode,
                status = result["status"] as? String,
                statusDetail = result["status_detail"] as? String,
                carrier = result["carrier"] as? String,
                estDeliveryDate = result["est_delivery_date"] as? String,
                shipmentId = result["shipment_id"] as? String,
                // Provider event timestamp: the tracker's own updated_at, else the
                // envelope's created_at. Opaque ISO string, stored as-is.
                eventAt = (result["updated_at"] as? String) ?: (event["created_at"] as? String),
            )
        store.saveTrackingUpdate(record)
        log.info(
            "Persisted tracking update {} status={} carrier={}",
            trackingCode,
            record.status,
            record.carrier,
        )
    }

    /**
     * Report the latest stored carrier tracking state for a tracking code,
     * persisted from verified EasyPost tracker webhooks. `404` when no event has
     * been received yet. Secret-key only (not in the publishable allowlist) — a
     * merchant read, not part of the browser widget flow.
     */
    fun getTracking(ctx: Context) {
        val record = store.getTracking(ctx.pathParam("trackingCode"))
        if (record != null) {
            ctx.json(mapOf("success" to true, "tracking" to record.toMap()))
        } else {
            ctx.status(404).json(error("No tracking status recorded for this code yet"))
        }
    }

    private fun hmacMatches(
        secret: String,
        rawBody: ByteArray,
        provided: String,
    ): Boolean {
        val key = Normalizer.normalize(secret, Normalizer.Form.NFKD).toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val hex = mac.doFinal(rawBody).joinToString("") { "%02x".format(it) }
        val expected = "hmac-sha256-hex=$hex"
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            provided.toByteArray(Charsets.UTF_8),
        )
    }

    // ---- Payments (Lifted Payments 3-D Secure) -------------------------------

    /**
     * Open a 3-D Secure payment session for a selected rate. The charge amount is
     * computed **server-side** from the rate id + merchant markup — any
     * client-supplied amount is ignored, so a customer can never set their own
     * price. Emits `{session_id, form_url, amount, currency, expires_at}`.
     */
    fun paymentSession(ctx: Context) =
        withPayments(ctx) { client ->
            val service =
                easyPost
                    ?: return@withPayments fail(
                        ctx,
                        503,
                        "Shipping is not configured (set EASYPOST_API_KEY)",
                    )
            val body = ctx.bodyMap()
            val shipmentId =
                body["shipment_id"] as? String
                    ?: return@withPayments fail(ctx, 400, "shipment_id is required")
            val rateId =
                body["rate_id"] as? String
                    ?: return@withPayments fail(ctx, 400, "rate_id is required")

            // Authoritative amount: rate + markup, never the client's number.
            val quote =
                try {
                    service.priceRate(shipmentId, rateId, store.getMarkupConfig())
                } catch (e: IllegalArgumentException) {
                    return@withPayments fail(ctx, 400, e.message ?: "Invalid rate_id")
                }
            if (!quote.currency.equals("USD", ignoreCase = true)) {
                return@withPayments fail(
                    ctx,
                    400,
                    "Only USD rates can be charged (got ${quote.currency})",
                )
            }

            val sessionId = UUID.randomUUID().toString()
            val createdAt = System.currentTimeMillis()
            val externalId = "shipkit-$createdAt-$sessionId"
            // `subtotal` is the shipping price (carrier rate + merchant markup).
            // The amount the card is actually charged is that plus the tier-2 buyer
            // surcharge when enabled (3.75% + $0.15) — off by default, so the total
            // equals the subtotal. Exact BigDecimal money; the total is what the
            // 3-D Secure authorization/reconciliation runs against.
            val subtotal = BigDecimal(quote.amount)
            val surchargeFee =
                if (config.surcharge.enabled) {
                    config.surcharge.feeOn(
                        subtotal,
                    )
                } else {
                    null
                }
            val amount = config.surcharge.applyTo(subtotal)
            // Advertised expiry derives from the SAME window enforced at purchase
            // time (isPaymentWindowOpen), so the two can never drift apart.
            val expiresAt =
                Instant
                    .ofEpochMilli(createdAt)
                    .plus(PAYMENT_WINDOW_MINUTES, ChronoUnit.MINUTES)
                    .toString()

            val result =
                try {
                    client.createHostedPayment(
                        amount = amount,
                        externalId = externalId,
                        returnUrl = "${config.baseUrl}/api/payment/return/$sessionId",
                    )
                } catch (e: IOException) {
                    log.warn("Hosted-form creation failed: {}", e.message)
                    return@withPayments fail(
                        ctx,
                        502,
                        "Could not open a payment session; please retry",
                    )
                }

            store.savePaymentSession(
                PaymentSession(
                    sessionId = sessionId,
                    amount = amount.toDouble(),
                    description = body["description"] as? String ?: "Shipping Label Purchase",
                    externalId = externalId,
                    createdAt = createdAt,
                    shipmentId = shipmentId,
                    rateId = rateId,
                    paidBaseRate = quote.baseRate.toDouble(),
                    currency = quote.currency.uppercase(),
                    endShipperId = body["end_shipper_id"] as? String,
                ),
            )
            ctx.json(
                mapOf(
                    "session_id" to sessionId,
                    "form_url" to result.url,
                    // `amount` is the total charged (subtotal + surcharge). The
                    // widget reads this; it stays a decimal string.
                    "amount" to amount.toPlainString(),
                    "subtotal" to subtotal.toPlainString(),
                    "surcharge" to
                        mapOf(
                            "enabled" to config.surcharge.enabled,
                            "amount" to (surchargeFee?.toPlainString() ?: "0.00"),
                            "percentage" to config.surcharge.percentage.toPlainString(),
                            "fixed_cents" to config.surcharge.fixedCents,
                        ),
                    "currency" to quote.currency.uppercase(),
                    "expires_at" to expiresAt,
                ),
            )
        }

    /**
     * 3-D Secure return landing. Deliberately trusts NOTHING in the query string —
     * it only sends the cardholder back to the SPA, which then polls
     * `/api/payment/status` where the charge is verified server-side.
     */
    fun paymentReturn(ctx: Context) {
        ctx.redirect(config.baseUrl.ifBlank { "/" })
    }

    /**
     * Report a payment's status by verifying it **server-side** against Lifted
     * Payments (never from return-URL params). A payment is only `approved` when
     * the gateway approved the charge AND 3-D Secure produced a liability shift.
     * Emits `{status, three_ds:{eci, liability_shift}}`.
     *
     * The 3-D Secure **CAVV** cryptogram is deliberately NOT returned to the client:
     * it is cardholder-authentication data with no reason to leave the server. It is
     * still persisted on the session (`threeDsCavv`) for reconciliation/audit; the
     * widget only consumes `liability_shift`.
     */
    fun paymentStatus(ctx: Context) =
        withPayments(ctx) { client ->
            val session =
                store.getPaymentSession(ctx.pathParam("sessionId"))
                    ?: return@withPayments fail(ctx, 404, "Payment session not found")

            val verification =
                try {
                    client.verifyPayment(session.externalId)
                } catch (e: IOException) {
                    log.warn("Payment verification failed for {}: {}", session.sessionId, e.message)
                    return@withPayments fail(ctx, 502, "Could not verify payment; please retry")
                }

            session.status = verification.status
            session.threeDsEci = verification.eci
            session.threeDsCavv = verification.cavv
            session.liabilityShift = verification.liabilityShift
            if (verification.status in TERMINAL_STATUSES) {
                session.completedAt = System.currentTimeMillis()
            }
            store.savePaymentSession(session)

            ctx.json(
                mapOf(
                    "status" to verification.status,
                    // CAVV is intentionally omitted — it is auth-only cryptographic
                    // data and never leaves the server (kept on the session for audit).
                    "three_ds" to
                        mapOf(
                            "eci" to verification.eci,
                            "liability_shift" to verification.liabilityShift,
                        ),
                ),
            )
        }

    /**
     * Buy the label for a paid session. **Idempotent**: once the label exists,
     * every call returns the same label. The purchase is gated on a fresh
     * server-side verification (approved + liability shift), guarded by an atomic
     * claim so a status-poll racing the return callback can never double-buy, and
     * refuses to buy a rate that reprices above what the customer paid.
     * Emits `{label_url, qr_code_url, tracking_code, carrier, service}`.
     */
    fun purchaseLabelForSessionEndpoint(ctx: Context) =
        withPayments(ctx) { client ->
            val sessionId = ctx.pathParam("sessionId")
            val session =
                store.getPaymentSession(sessionId)
                    ?: return@withPayments fail(ctx, 404, "Payment session not found")

            // Idempotent fast path: already purchased → return the same label
            // (allowed even after the charge window closes; it is already paid).
            if (session.labelUrl != null) {
                ctx.json(labelResponse(session))
                return@withPayments
            }

            // Enforce the advertised payment window. A stale session/hosted form
            // must not remain BUYABLE long after the client was told it expired.
            if (!isPaymentWindowOpen(session)) {
                return@withPayments fail(
                    ctx,
                    410,
                    "Payment session has expired; please start a new checkout",
                )
            }

            val service =
                easyPost
                    ?: return@withPayments fail(
                        ctx,
                        503,
                        "Shipping is not configured (set EASYPOST_API_KEY)",
                    )

            // Re-verify server-side every time; never trust a prior/return state.
            val verification =
                try {
                    client.verifyPayment(session.externalId)
                } catch (e: IOException) {
                    return@withPayments fail(ctx, 502, "Could not verify payment; please retry")
                }
            session.status = verification.status
            session.threeDsEci = verification.eci
            session.threeDsCavv = verification.cavv
            session.liabilityShift = verification.liabilityShift
            store.savePaymentSession(session)

            if (verification.status != "approved" || !verification.liabilityShift) {
                return@withPayments fail(
                    ctx,
                    402,
                    "Payment not approved with a 3-D Secure liability shift (status=${verification.status})",
                )
            }

            // Amount reconciliation: the gateway's own captured amount must equal
            // the amount we authorized for this session before we spend money on a
            // label. Skipped only when the gateway record carries no amount.
            verification.amount?.let { charged ->
                val authorized =
                    BigDecimal
                        .valueOf(
                            session.amount,
                        ).setScale(2, RoundingMode.HALF_UP)
                if (charged.setScale(2, RoundingMode.HALF_UP).compareTo(authorized) != 0) {
                    log.warn(
                        "Payment amount mismatch for {} (charged {}, authorized {}); refusing to buy",
                        sessionId,
                        charged,
                        authorized,
                    )
                    return@withPayments fail(
                        ctx,
                        402,
                        "Charged amount does not match the authorized amount; refusing to buy",
                    )
                }
            }

            // Atomic idempotency claim: exactly one caller may proceed to buy.
            if (!store.claimLabelPurchase(sessionId)) {
                val fresh = store.getPaymentSession(sessionId)
                if (fresh?.labelUrl != null) {
                    ctx.json(labelResponse(fresh))
                    return@withPayments
                }
                return@withPayments fail(
                    ctx,
                    409,
                    "Label purchase already in progress; retry shortly",
                )
            }

            val bought =
                try {
                    service.buyLabel(
                        shipmentId =
                            session.shipmentId
                                ?: throw IllegalStateException("Session has no shipment_id"),
                        originalRateId = session.rateId,
                        endShipperId = session.endShipperId ?: store.getEndShipperId(),
                        maxBaseRate = session.paidBaseRate?.let { BigDecimal.valueOf(it) },
                    )
                } catch (e: Exception) {
                    // Release the claim so a legitimate retry can proceed.
                    store.releaseLabelPurchaseClaim(sessionId)
                    log.warn("Label purchase failed for {}: {}", sessionId, e.message)
                    // Return a fixed, generic message — the upstream detail (which can
                    // reveal the merchant's cost basis, e.g. "Rate increased since
                    // payment (7.50 > 7.36)", or EasyPost account internals) is logged
                    // above but never surfaced to the client.
                    return@withPayments fail(
                        ctx,
                        502,
                        "Label purchase failed; please retry",
                    )
                }

            session.labelUrl = bought.labelUrl
            session.qrCodeUrl = bought.qrCodeUrl
            session.trackingCode = bought.trackingCode
            store.savePaymentSession(session)

            bought.labelUrl?.let { url ->
                val markup = store.getMarkupConfig()
                store.saveLabel(
                    LabelRecord(
                        id = UUID.randomUUID().toString(),
                        sessionId = session.sessionId,
                        labelUrl = url,
                        qrCodeUrl = bought.qrCodeUrl,
                        trackingCode = bought.trackingCode,
                        carrier = bought.carrier,
                        service = bought.service,
                        amount = session.amount,
                        baseRate = bought.baseRate,
                        percentageMarkup = markup.percentageMarkup,
                        fixedFeeCents = markup.fixedFeeCents,
                        shipmentId = session.shipmentId,
                        senderPhone = bought.senderPhone,
                    ),
                )
            }
            ctx.json(
                mapOf(
                    "label_url" to session.labelUrl,
                    "qr_code_url" to session.qrCodeUrl,
                    "tracking_code" to session.trackingCode,
                    "carrier" to bought.carrier,
                    "service" to bought.service,
                ),
            )
        }

    private fun labelResponse(session: PaymentSession): Map<String, Any?> {
        val label = store.getLabelBySession(session.sessionId)
        return mapOf(
            "label_url" to session.labelUrl,
            "qr_code_url" to session.qrCodeUrl,
            "tracking_code" to session.trackingCode,
            "carrier" to label?.carrier,
            "service" to label?.service,
        )
    }

    // ---- Frictionless / card-on-file (account-gated; SPEC_R3 §5) -------------

    /**
     * Save a card on file (Maverick customer vault) for one-tap repeat checkout.
     * **Server-side and account-gated**: reachable only with a **secret** key
     * (never a publishable/browser key — see [isPublishableSafe]) and only when the
     * deployment's tier armed [ShipKitConfig.frictionlessAllowed]. A self-host / BYO
     * deployment is refused with `403` here (and again in the client, defence in
     * depth). Card data never reaches ShipKit: the body carries a Maverick Hosted
     * Fields **card token**, not a PAN. Emits `{success, vault_id, card_token}`.
     */
    fun saveCardOnFile(ctx: Context) =
        withPayments(ctx) { client ->
            if (!requireFrictionless(ctx)) return@withPayments
            val body = ctx.bodyMap()
            val cardToken =
                (body["card_token"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@withPayments fail(ctx, 400, "card_token is required")

            @Suppress("UNCHECKED_CAST")
            val billing = body["billing"] as? Map<String, Any?>
            val saved =
                try {
                    client.saveCard(cardToken, billing)
                } catch (e: IOException) {
                    log.warn("Save-card failed: {}", e.message)
                    return@withPayments fail(ctx, 502, "Could not save the card; please retry")
                }
            ctx.json(
                mapOf(
                    "success" to true,
                    "vault_id" to saved.vaultId,
                    "card_token" to saved.cardToken,
                ),
            )
        }

    /**
     * One-tap purchase on a **saved card**: price the rate server-side, charge the
     * saved card **frictionlessly** (`3ds:false`), and buy the label — the
     * account-gated repeat-checkout path. Like [saveCardOnFile] it is secret-key +
     * [ShipKitConfig.frictionlessAllowed] gated.
     *
     * The charge amount is computed on the server from `shipment_id` + `rate_id` +
     * markup (a client-sent amount is ignored). **Idempotent** on a required
     * `idempotency_key`: the charge and the label are taken at most once, and the
     * card is charged before the label is bought — a buy that fails after a
     * successful charge does NOT re-charge on retry (the recorded `approved` session
     * short-circuits the charge, only the label buy is re-attempted).
     * Emits `{label_url, qr_code_url, tracking_code, carrier, service, transaction_id, amount}`.
     */
    fun chargeSavedCard(ctx: Context) =
        withPayments(ctx) { client ->
            if (!requireFrictionless(ctx)) return@withPayments
            val service =
                easyPost
                    ?: return@withPayments fail(
                        ctx,
                        503,
                        "Shipping is not configured (set EASYPOST_API_KEY)",
                    )
            val body = ctx.bodyMap()
            val vaultId =
                (body["vault_id"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@withPayments fail(ctx, 400, "vault_id is required")
            val shipmentId =
                (body["shipment_id"] as? String)?.takeIf { it.isNotBlank() }
                    ?: return@withPayments fail(ctx, 400, "shipment_id is required")
            val rateId =
                (body["rate_id"] as? String)?.takeIf { it.isNotBlank() }
                    ?: return@withPayments fail(ctx, 400, "rate_id is required")
            // A caller-supplied idempotency key makes the charge + buy at-most-once.
            val idempotencyKey =
                (body["idempotency_key"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@withPayments fail(ctx, 400, "idempotency_key is required")

            @Suppress("UNCHECKED_CAST")
            val billing = body["billing"] as? Map<String, Any?>
            val sessionId = "saved-$idempotencyKey"

            // Idempotent fast path: already bought → return the same label.
            store.getPaymentSession(sessionId)?.let { existing ->
                if (existing.labelUrl != null) {
                    ctx.json(labelResponse(existing))
                    return@withPayments
                }
            }

            // Authoritative amount: rate + markup + tier surcharge; never the client's.
            val quote =
                try {
                    service.priceRate(shipmentId, rateId, store.getMarkupConfig())
                } catch (e: IllegalArgumentException) {
                    return@withPayments fail(ctx, 400, e.message ?: "Invalid rate_id")
                }
            if (!quote.currency.equals("USD", ignoreCase = true)) {
                return@withPayments fail(
                    ctx,
                    400,
                    "Only USD rates can be charged (got ${quote.currency})",
                )
            }
            val amount = config.surcharge.applyTo(BigDecimal(quote.amount))

            // Serialize per idempotency key: exactly one caller charges + buys.
            if (!store.claimLabelPurchase(sessionId)) {
                val fresh = store.getPaymentSession(sessionId)
                if (fresh?.labelUrl != null) {
                    ctx.json(labelResponse(fresh))
                    return@withPayments
                }
                return@withPayments fail(
                    ctx,
                    409,
                    "A saved-card purchase is already in progress; retry shortly",
                )
            }

            // Charge only if a prior attempt has not already done so. A recorded
            // `approved` session means the card was charged but the label buy failed
            // — retry the buy WITHOUT charging again.
            val priorCharge = store.getPaymentSession(sessionId)
            if (priorCharge?.status != "approved") {
                val result =
                    try {
                        client.chargeSavedCard(amount, vaultId, billing)
                    } catch (e: IOException) {
                        store.releaseLabelPurchaseClaim(sessionId)
                        log.warn("Saved-card charge failed for {}: {}", sessionId, e.message)
                        return@withPayments fail(
                            ctx,
                            502,
                            "Could not charge the saved card; please retry",
                        )
                    }
                if (!result.approved) {
                    store.releaseLabelPurchaseClaim(sessionId)
                    return@withPayments fail(
                        ctx,
                        402,
                        "Saved-card charge not approved (status=${result.status})",
                    )
                }
                store.savePaymentSession(
                    PaymentSession(
                        sessionId = sessionId,
                        amount = amount.toDouble(),
                        description =
                            body["description"] as? String ?: "Shipping Label Purchase (saved card)",
                        externalId = result.transactionId.ifBlank { sessionId },
                        createdAt = System.currentTimeMillis(),
                        status = "approved",
                        shipmentId = shipmentId,
                        rateId = rateId,
                        paidBaseRate = quote.baseRate.toDouble(),
                        currency = "USD",
                        endShipperId = body["end_shipper_id"] as? String,
                    ),
                )
            }

            val session = store.getPaymentSession(sessionId)
            val bought =
                try {
                    service.buyLabel(
                        shipmentId = shipmentId,
                        originalRateId = rateId,
                        endShipperId = session?.endShipperId ?: store.getEndShipperId(),
                        maxBaseRate = quote.baseRate,
                    )
                } catch (e: Exception) {
                    // The card is already charged; release the claim so a retry can
                    // complete the buy (the recorded session skips the re-charge).
                    store.releaseLabelPurchaseClaim(sessionId)
                    log.warn("Saved-card label buy failed for {}: {}", sessionId, e.message)
                    // Return a fixed, generic message — the upstream detail (which can
                    // reveal the merchant's cost basis, e.g. "Rate increased since
                    // payment (7.50 > 7.36)", or EasyPost account internals) is logged
                    // above but never surfaced to the client.
                    return@withPayments fail(
                        ctx,
                        502,
                        "Label purchase failed; please retry",
                    )
                }

            if (session != null) {
                session.labelUrl = bought.labelUrl
                session.qrCodeUrl = bought.qrCodeUrl
                session.trackingCode = bought.trackingCode
                store.savePaymentSession(session)
                bought.labelUrl?.let { url ->
                    val markup = store.getMarkupConfig()
                    store.saveLabel(
                        LabelRecord(
                            id = UUID.randomUUID().toString(),
                            sessionId = session.sessionId,
                            labelUrl = url,
                            qrCodeUrl = bought.qrCodeUrl,
                            trackingCode = bought.trackingCode,
                            carrier = bought.carrier,
                            service = bought.service,
                            amount = session.amount,
                            baseRate = bought.baseRate,
                            percentageMarkup = markup.percentageMarkup,
                            fixedFeeCents = markup.fixedFeeCents,
                            shipmentId = shipmentId,
                            senderPhone = bought.senderPhone,
                        ),
                    )
                }
            }
            ctx.json(
                mapOf(
                    "label_url" to bought.labelUrl,
                    "qr_code_url" to bought.qrCodeUrl,
                    "tracking_code" to bought.trackingCode,
                    "carrier" to bought.carrier,
                    "service" to bought.service,
                    "transaction_id" to (session?.externalId ?: ""),
                    "amount" to amount.toPlainString(),
                ),
            )
        }

    /**
     * Gate the frictionless money paths. Writes a `403` and returns `false` unless
     * [ShipKitConfig.frictionlessAllowed] — the account/tier capability — is armed.
     * Forced 3-D Secure is the only mode for self-host / BYO, so those deployments
     * are refused here.
     */
    private fun requireFrictionless(ctx: Context): Boolean {
        if (!config.frictionlessAllowed) {
            fail(
                ctx,
                403,
                "Frictionless mode (3-D Secure off / card-on-file) is not enabled for this " +
                    "account. Forced 3-D Secure is the only mode for self-host / " +
                    "bring-your-own-payments. Frictionless is available on a Lifted " +
                    "merchant/managed account or enterprise custom-dev (support@liftedholdings.com).",
            )
            return false
        }
        return true
    }

    // ---- Tier + pricing model ------------------------------------------------

    /**
     * Report the deployment's adoption tier and its pricing knobs — the honest
     * three-tier story surfaced to the widget/demo:
     *
     *  - `tier` — `selfhost` | `merchant` | `managed` (informational only).
     *  - `surcharge` — the tier-2 buyer surcharge (3.75% + $0.15) and whether it
     *    is passed to the buyer.
     *  - `shipping_markup` — the tier-3 markup Lifted earns on managed shipping.
     *  - `payments` — the gateway environment + browser card-entry model.
     *
     * Non-secret marketing/config data, so it is reachable by publishable keys.
     * Apply for a merchant/managed account: https://liftedholdings.com/payments
     */
    fun getTierConfig(ctx: Context) {
        val markup = store.getMarkupConfig()
        ctx.json(
            mapOf(
                "tier" to config.tier.slug,
                "label" to config.tier.label,
                "surcharge" to
                    mapOf(
                        "enabled" to config.surcharge.enabled,
                        "percentage" to config.surcharge.percentage.toPlainString(),
                        "fixed_cents" to config.surcharge.fixedCents,
                        "monthly_fee_usd" to MERCHANT_MONTHLY_FEE_USD,
                    ),
                "shipping_markup" to
                    mapOf(
                        "percentage_markup" to markup.percentageMarkup,
                        "fixed_fee_cents" to markup.fixedFeeCents,
                    ),
                "payments" to
                    mapOf(
                        "configured" to config.paymentsEnabled,
                        "environment" to (
                            config.payments
                                ?.environment
                                ?.name
                                ?.lowercase()
                        ),
                        "card_entry" to (
                            config.payments
                                ?.cardEntryMode
                                ?.name
                                ?.lowercase()
                        ),
                    ),
                // The account-gated frictionless capability (SPEC_R3 §5). `allowed`
                // (and `save_cards`, gated by the same capability) is FALSE by default
                // and always false for self-host / BYO — forced 3-D Secure is their
                // only mode. This is server-derived; it is never a client toggle.
                "frictionless" to
                    mapOf(
                        "allowed" to config.frictionlessAllowed,
                        "save_cards" to config.frictionlessAllowed,
                    ),
            ),
        )
    }

    // ---- Markup configuration ------------------------------------------------

    fun getMarkupConfig(ctx: Context) {
        val config = store.getMarkupConfig()
        ctx.json(
            mapOf(
                "percentage_markup" to config.percentageMarkup,
                "fixed_fee_cents" to config.fixedFeeCents,
                "fixed_fee_dollars" to config.fixedFeeCents / 100.0,
            ),
        )
    }

    fun updateMarkupConfig(ctx: Context) {
        val body = ctx.bodyMap()
        val percentage = (body["percentage_markup"] as? Number)?.toDouble()
        val fixedFeeCents = (body["fixed_fee_cents"] as? Number)?.toInt()
        if (percentage == null || fixedFeeCents == null) {
            ctx.status(400).json(error("percentage_markup and fixed_fee_cents are required"))
            return
        }
        try {
            store.updateMarkupConfig(MarkupConfig(percentage, fixedFeeCents))
            ctx.json(
                mapOf(
                    "success" to true,
                    "percentage_markup" to percentage,
                    "fixed_fee_cents" to fixedFeeCents,
                ),
            )
        } catch (e: IllegalArgumentException) {
            ctx.status(400).json(error(e.message ?: "Invalid markup"))
        }
    }

    // ---- Labels --------------------------------------------------------------

    fun getLabel(ctx: Context) = respondLabel(ctx, store.getLabel(ctx.pathParam("labelId")))

    fun getLabelBySession(ctx: Context) =
        respondLabel(ctx, store.getLabelBySession(ctx.pathParam("sessionId")))

    fun shredLabel(ctx: Context) {
        if (store.deleteLabelBySession(ctx.pathParam("sessionId"))) {
            ctx.json(
                mapOf(
                    "success" to true,
                    "message" to
                        "Label removed from storage. This does not void the label with the carrier.",
                ),
            )
        } else {
            ctx.status(404).json(error("Label not found or already expired"))
        }
    }

    fun cleanupExpired(ctx: Context) {
        ctx.json(
            mapOf(
                "success" to true,
                "deletedLabels" to store.cleanupExpiredLabels(),
                "deletedSessions" to store.cleanupExpiredPaymentSessions(),
                "deletedVerifications" to store.cleanupExpiredVerificationSessions(),
            ),
        )
    }

    // ---- Admin + purchase history (gated by SMS verification) ----------------

    fun startVerification(ctx: Context) =
        withSms(ctx) {
            val body = ctx.bodyMap()
            val phone =
                (body["phone"] as? String)?.takeIf { it.isNotBlank() }
                    ?: return@withSms fail(ctx, 400, "phone is required")
            val isAdmin = body["admin"] as? Boolean ?: false

            if (isAdmin && !isAdminPhone(phone)) {
                log.warn("Rejected admin verification for phone ending in {}", maskPhone(phone))
                return@withSms fail(
                    ctx,
                    403,
                    "This phone number is not authorized for admin access",
                )
            }
            if (sms.start(phone)) {
                ctx.json(
                    mapOf(
                        "success" to true,
                        "sessionId" to store.createVerificationSession(phone),
                        "message" to "Verification code sent",
                    ),
                )
            } else {
                ctx.status(502).json(error("Failed to send verification code"))
            }
        }

    fun checkVerification(ctx: Context) =
        withSms(ctx) {
            val body = ctx.bodyMap()
            val sessionId = body["sessionId"] as? String
            val phone = body["phone"] as? String
            val code = body["code"] as? String
            if (sessionId.isNullOrBlank() || phone.isNullOrBlank() || code.isNullOrBlank()) {
                return@withSms fail(ctx, 400, "sessionId, phone, and code are required")
            }
            // The OTP is checked for `phone`, and the session is promoted to
            // verified ONLY if it was started for that same phone (store enforces
            // the phone binding). This closes the cross-phone bypass where an
            // attacker verifies an admin-phone session with an OTP for their own
            // number.
            if (sms.check(phone, code) &&
                store.markVerificationSessionVerified(sessionId, phone)
            ) {
                ctx.json(mapOf("success" to true, "verified" to true, "sessionId" to sessionId))
            } else {
                ctx.status(400).json(
                    mapOf(
                        "success" to false,
                        "verified" to false,
                        "error" to "Invalid verification code",
                    ),
                )
            }
        }

    fun getLabelHistory(ctx: Context) {
        val session = verifiedSession(ctx) ?: return
        ctx.json(
            mapOf(
                "success" to true,
                "labels" to store.getLabelsByPhone(session.phoneNumber).map { it.toMap() },
                "phoneNumber" to session.phoneNumber,
            ),
        )
    }

    fun getAllLabels(ctx: Context) {
        val session = verifiedSession(ctx) ?: return
        if (!isAdminPhone(session.phoneNumber)) {
            ctx.status(403).json(error("This phone number is not authorized for admin access"))
            return
        }
        val labels = store.getAllLabels()
        ctx.json(
            mapOf(
                "success" to true,
                "count" to labels.size,
                "labels" to labels.map { it.toMap(includeProfit = true) },
            ),
        )
    }

    // ---- API-key management (admin-gated) ------------------------------------

    /**
     * Mint a new API key. **Admin-gated** (a verified admin session — see
     * [requireAdmin]) on top of the API-key requirement the `before` filter
     * already enforces. The full key is returned **once** in `api_key`; only its
     * hash + metadata are stored. Body: `{label, mode?, scope?}` where `mode` is
     * `"live"` (default) or `"test"`, and `scope` is `"secret"` (default; full
     * server key) or `"publishable"` (browser widget key limited to the customer
     * rating + payment flow). Emits `201`.
     */
    fun createApiKey(ctx: Context) {
        if (!requireAdmin(ctx)) return
        val body = ctx.bodyMap()
        val label =
            (body["label"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return fail(ctx, 400, "label is required")
        val mode = KeyGenerator.Mode.fromString(body["mode"] as? String)
        val scope = KeyGenerator.Scope.fromString(body["scope"] as? String)

        val minted = KeyGenerator.mint(label, mode, scope)
        apiKeys.add(minted.record)
        // Log the id/prefix only — never the full key.
        log.info("Minted API key {} (prefix {})", minted.record.id, minted.record.prefix)

        ctx.status(201).json(
            minted.record.toPublicMap() +
                mapOf(
                    "mode" to mode.name.lowercase(),
                    // Shown ONCE; not retrievable afterwards.
                    "api_key" to minted.plaintext,
                    "message" to
                        "Store this key now — it is shown only once and cannot be retrieved again.",
                ),
        )
    }

    /** List all API keys (metadata only; never the key or its hash). Admin-gated. */
    fun listApiKeys(ctx: Context) {
        if (!requireAdmin(ctx)) return
        val keys = apiKeys.listAll()
        ctx.json(mapOf("count" to keys.size, "keys" to keys.map { it.toPublicMap() }))
    }

    /** Revoke an API key by id. Idempotent; `404` if unknown. Admin-gated. */
    fun revokeApiKey(ctx: Context) {
        if (!requireAdmin(ctx)) return
        val id = ctx.pathParam("id")
        if (apiKeys.get(id) == null) {
            return fail(ctx, 404, "API key not found")
        }
        val revoked = apiKeys.revoke(id)
        ctx.json(
            mapOf(
                "success" to true,
                "id" to id,
                "revoked" to true,
                "already_revoked" to !revoked,
            ),
        )
    }

    /**
     * Admin gate for the key-management surface. Reuses ShipKit's existing admin
     * mechanism: a valid SMS-verified session (`X-Session-ID` header) whose
     * phone is in [ShipKitConfig.adminPhoneWhitelist]. When SMS verification is
     * disabled there is no runtime admin, so keys are managed with the
     * `shipkitKeygen` CLI instead. Writes the `401`/`403` response itself and
     * returns `false` when access is denied.
     */
    private fun requireAdmin(ctx: Context): Boolean {
        val session = verifiedSession(ctx) ?: return false
        if (!isAdminPhone(session.phoneNumber)) {
            fail(ctx, 403, "This session is not authorized for admin access")
            return false
        }
        return true
    }

    // ---- Helpers -------------------------------------------------------------

    private fun verifiedSession(ctx: Context): com.lifted.shipkit.model.VerificationSession? {
        // The admin/history session id is read from the `X-Session-ID` HEADER only —
        // never the query string. A session identifier in the URL leaks through
        // access logs, proxy logs, browser history, and the Referer header (OWASP
        // session-in-URL). The admin/widget caller can always send a header.
        val sessionId = ctx.header("X-Session-ID")
        if (sessionId.isNullOrBlank()) {
            ctx.status(401).json(error("Verification session required"))
            return null
        }
        val session = store.getValidVerificationSession(sessionId)
        if (session == null) ctx.status(401).json(error("Invalid or expired session"))
        return session
    }

    private fun isAdminPhone(phone: String) =
        config.adminPhoneWhitelist.contains(PhoneNumbers.normalize(phone))

    /**
     * True while a payment session is still inside its advertised charge window
     * (see [PAYMENT_WINDOW_MINUTES]); the same window is returned to the client as
     * `expires_at`. Enforced before a label is bought so a stale session cannot be
     * spent long after the customer was told it expired.
     */
    private fun isPaymentWindowOpen(session: PaymentSession): Boolean =
        System.currentTimeMillis() - session.createdAt <=
            PAYMENT_WINDOW_MINUTES * 60_000L

    private fun maskPhone(phone: String): String {
        val n = PhoneNumbers.normalize(phone)
        return if (n.length >= 4) "***${n.takeLast(4)}" else "****"
    }

    private fun respondLabel(
        ctx: Context,
        label: LabelRecord?,
    ) {
        if (label != null) {
            ctx.json(mapOf("success" to true, "label" to label.toMap()))
        } else {
            ctx.status(404).json(error("Label not found or expired"))
        }
    }

    private inline fun withShipping(
        ctx: Context,
        block: (EasyPostService) -> Unit,
    ) {
        val service =
            easyPost
                ?: return run {
                    ctx.status(503).json(error("Shipping is not configured (set EASYPOST_API_KEY)"))
                    Unit
                }
        runCarrier(ctx) { block(service) }
    }

    private inline fun withPayments(
        ctx: Context,
        block: (LiftedPaymentsClient) -> Unit,
    ) {
        val client =
            payments
                ?: return run {
                    ctx.status(503).json(
                        error(
                            "Lifted Payments 3-D Secure is not configured. " +
                                "Get a merchant account at https://liftedholdings.com/payments",
                        ),
                    )
                    Unit
                }
        block(client)
    }

    private inline fun withSms(
        ctx: Context,
        block: () -> Unit,
    ) {
        if (!sms.enabled) {
            ctx.status(503).json(
                error(
                    "SMS verification is disabled (set SHIPKIT_SMS_ENABLED=true and Twilio credentials)",
                ),
            )
            return
        }
        block()
    }

    private inline fun runCarrier(
        ctx: Context,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: EasyPostException) {
            ctx.status(400).json(error(e.message ?: "Carrier request failed"))
        } catch (e: IllegalArgumentException) {
            ctx.status(400).json(error(e.message ?: "Invalid request"))
        } catch (e: IllegalStateException) {
            ctx.status(400).json(error(e.message ?: "Invalid request"))
        } catch (e: Exception) {
            log.warn("Unexpected error handling carrier request: {}", e.message)
            ctx.status(500).json(error("Unexpected error"))
        }
    }

    private fun error(message: String) = mapOf("success" to false, "error" to message)

    /** Write a JSON error response with [code]; returns Unit so it can follow `?:`. */
    private fun fail(
        ctx: Context,
        code: Int,
        message: String,
    ) {
        ctx.status(code).json(error(message))
    }

    private fun Context.bodyMap(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return bodyAsClass(Map::class.java) as Map<String, Any?>
    }

    private fun LabelRecord.toMap(includeProfit: Boolean = false): Map<String, Any?> {
        val base =
            mapOf(
                "id" to id,
                "sessionId" to sessionId,
                "labelUrl" to labelUrl,
                "qrCodeUrl" to qrCodeUrl,
                "trackingCode" to trackingCode,
                "carrier" to carrier,
                "service" to service,
                "amount" to amount,
                "createdAt" to createdAt,
                "expiresAt" to expiresAt,
            )
        if (!includeProfit) return base
        return base +
            mapOf(
                "baseRate" to baseRate,
                "percentageMarkup" to percentageMarkup,
                "fixedFeeCents" to fixedFeeCents,
                "senderPhone" to senderPhone,
                "shipmentId" to shipmentId,
                "profit" to profit,
            )
    }

    /** snake_case tracking view for the `/api/tracking` read endpoint. */
    private fun TrackingRecord.toMap(): Map<String, Any?> =
        mapOf(
            "tracking_code" to trackingCode,
            "status" to status,
            "status_detail" to statusDetail,
            "carrier" to carrier,
            "est_delivery_date" to estDeliveryDate,
            "shipment_id" to shipmentId,
            "event_at" to eventAt,
            "updated_at" to updatedAt,
        )

    private companion object {
        /** Statuses at which a payment session is considered finished. */
        private val TERMINAL_STATUSES = setOf("approved", "declined", "failed")

        /** Canonical header carrying the caller's `sk_live_…`/`sk_test_…` API key. */
        private const val API_KEY_HEADER = "ShipKit-Api-Key"

        /**
         * The tier-2 Lifted 3-D Secure merchant-account monthly fee (USD),
         * surfaced by `/api/config/tier` alongside the per-transaction surcharge.
         * Pricing lives at https://liftedholdings.com/payments.
         */
        private const val MERCHANT_MONTHLY_FEE_USD = 25

        /**
         * How long a payment session stays chargeable/buyable. Advertised to the
         * client as `expires_at` AND enforced before a label is bought, so the two
         * can never disagree. (The session ROW is retained far longer for label
         * re-download; this is only the charge window.)
         */
        private const val PAYMENT_WINDOW_MINUTES = 30L
    }
}

/**
 * Thrown by [Handlers.authenticateApiKey] when a request is not authorized:
 * [statusCode] is `401` (missing/invalid/revoked key) or `403` (a publishable key
 * on a secret-only endpoint). Mapped to a canonical `{success:false, error}` body
 * by [com.lifted.shipkit.buildApp].
 */
class ApiKeyRejected(
    val statusCode: Int,
    message: String,
) : RuntimeException(message)
