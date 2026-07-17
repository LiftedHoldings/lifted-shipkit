package com.lifted.shipkit.events

import com.google.gson.GsonBuilder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Configuration for the optional usage-event webhook. When set, every successful
 * label purchase is reported to [webhookUrl] as a `label.purchased` event — the
 * generic "report purchases to your platform" hook for hosted/managed
 * deployments, billing reconciliation, or analytics. Resolved from
 * `SHIPKIT_EVENTS_WEBHOOK_URL` / `SHIPKIT_EVENTS_WEBHOOK_TOKEN`; both unset by
 * default (no events are sent).
 */
data class UsageEventsConfig(
    /** Absolute URL the event envelope is POSTed to. */
    val webhookUrl: String,
    /** Optional shared secret, sent as `Authorization: Bearer <token>`. */
    val bearerToken: String? = null,
)

/**
 * A successful label purchase, ready to be reported. All money fields are
 * integer **cents** so the receiving platform never parses floats. Carries no
 * card data and never the full API key — [tenantKey] is the caller's short,
 * non-secret display prefix (e.g. `pk_live_abc123`).
 */
data class LabelPurchasedEvent(
    val tenantKey: String?,
    val sessionId: String,
    val carrier: String?,
    val service: String?,
    val carrierCostCents: Int?,
    val markupPct: Double,
    val fixedFeeCents: Int,
    val buyerChargeCents: Int,
    val currency: String,
    val easyPostShipmentId: String?,
    val paymentTxnId: String?,
    val paymentRail: String,
) {
    companion object {
        /** Convert a dollar amount to integer cents, rounding half-up. */
        fun dollarsToCents(dollars: Double): Int =
            BigDecimal
                .valueOf(dollars)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact()
    }
}

/**
 * Fire-and-forget webhook emitter for usage events.
 *
 * Guarantees, in order of importance:
 *
 *  1. **Never blocks or fails a purchase.** [emitLabelPurchased] hands the event
 *     to a single background daemon thread and returns immediately; every
 *     delivery failure (network error, non-2xx, bad URL) is swallowed and
 *     logged, never propagated.
 *  2. **Off by default.** A `null` [config] (env unset) makes every emit a
 *     no-op; no thread, no socket.
 *  3. **At-most-twice delivery.** One retry after [retryDelayMs]; there is no
 *     queue persistence — the receiving platform must treat events as
 *     best-effort telemetry, not a ledger.
 *  4. **No secrets in the payload.** The tenant is identified by the non-secret
 *     key display prefix; card data never appears (none of it ever reaches the
 *     event type).
 */
class UsageEventEmitter(
    private val config: UsageEventsConfig?,
    httpClient: OkHttpClient? = null,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(UsageEventEmitter::class.java)

    private val http: OkHttpClient =
        httpClient
            ?: OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

    /**
     * Single daemon worker with a **bounded** queue; created only when the
     * webhook is configured. A dead webhook host under purchase load must not
     * grow the heap without bound — once [MAX_QUEUED_EVENTS] deliveries are
     * pending, the oldest is dropped (best-effort telemetry, not a ledger).
     */
    private val executor: ExecutorService? =
        if (config != null) {
            ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(MAX_QUEUED_EVENTS),
                { r -> Thread(r, "shipkit-usage-events").apply { isDaemon = true } },
                ThreadPoolExecutor.DiscardOldestPolicy(),
            )
        } else {
            null
        }

    /** True when a webhook URL is configured and events will be sent. */
    val enabled: Boolean get() = config != null

    /**
     * Report a successful label purchase. Returns immediately; delivery happens
     * on the background worker. Never throws.
     */
    fun emitLabelPurchased(event: LabelPurchasedEvent) {
        val cfg = config ?: return
        try {
            val body = gson.toJson(envelope(event))
            executor?.execute { deliver(cfg, body) }
        } catch (e: RejectedExecutionException) {
            log.debug("Usage event dropped (emitter shut down): {}", e.message)
        } catch (e: Exception) {
            log.warn("Usage event dropped (could not serialize/enqueue): {}", e.message)
        }
    }

    /** The canonical `label.purchased` envelope, version 1. */
    internal fun envelope(event: LabelPurchasedEvent): Map<String, Any?> =
        linkedMapOf(
            "event" to "label.purchased",
            "version" to 1,
            "tenantKey" to event.tenantKey,
            "occurredAt" to Instant.now().toString(),
            "source" to "shipkit",
            "data" to
                linkedMapOf(
                    "session_id" to event.sessionId,
                    "carrier" to event.carrier,
                    "service" to event.service,
                    "carrier_cost_cents" to event.carrierCostCents,
                    "markup_pct" to event.markupPct,
                    "fixed_fee_cents" to event.fixedFeeCents,
                    "buyer_charge_cents" to event.buyerChargeCents,
                    "currency" to event.currency,
                    "easypost_shipment_id" to event.easyPostShipmentId,
                    "payment_txn_id" to event.paymentTxnId,
                    "payment_rail" to event.paymentRail,
                ),
        )

    /**
     * POST with one retry on transport errors / 5xx. A 4xx is the receiver
     * rejecting the event — retrying an identical payload cannot succeed, so it
     * is dropped immediately. Every failure is swallowed (logged, never thrown).
     */
    private fun deliver(
        cfg: UsageEventsConfig,
        body: String,
    ) {
        try {
            val first = attempt(cfg, body)
            if (first != null && first in 200..299) return
            if (first != null && first in 400..499) {
                log.warn("Usage event rejected by the receiver (HTTP {}); not retrying", first)
                return
            }
            try {
                Thread.sleep(retryDelayMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            val second = attempt(cfg, body)
            if (second == null || second !in 200..299) {
                log.warn("Usage event delivery failed after retry (event dropped)")
            }
        } catch (e: Exception) {
            // Fire-and-forget guarantee: nothing here may ever propagate.
            log.warn("Usage event delivery error: {}", e.message)
        }
    }

    /** One HTTP attempt: the response status code, or `null` on a transport error. */
    private fun attempt(
        cfg: UsageEventsConfig,
        body: String,
    ): Int? {
        val request =
            Request
                .Builder()
                .url(cfg.webhookUrl)
                .post(body.toRequestBody(JSON))
                .apply { cfg.bearerToken?.let { header("Authorization", "Bearer $it") } }
                .build()
        return try {
            http.newCall(request).execute().use { it.code }
        } catch (e: IOException) {
            log.debug("Usage event attempt failed: {}", e.message)
            null
        }
    }

    /** Drain the worker (bounded) — used at shutdown; safe to call when disabled. */
    override fun close() {
        executor?.shutdown()
        try {
            executor?.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        /** Shared no-op instance for deployments/tests without a webhook. */
        val DISABLED = UsageEventEmitter(config = null)

        private const val DEFAULT_RETRY_DELAY_MS = 1_000L

        /** Pending-delivery cap; beyond it the oldest queued event is dropped. */
        private const val MAX_QUEUED_EVENTS = 1_000
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val CALL_TIMEOUT_SECONDS = 10L
        private const val SHUTDOWN_WAIT_SECONDS = 5L

        private val JSON = "application/json".toMediaType()

        /** Nulls serialized so the envelope shape is stable for consumers. */
        private val gson = GsonBuilder().serializeNulls().create()
    }
}
