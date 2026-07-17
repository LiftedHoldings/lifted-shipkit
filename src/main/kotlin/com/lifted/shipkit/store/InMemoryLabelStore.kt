package com.lifted.shipkit.store

import com.lifted.shipkit.model.LabelRecord
import com.lifted.shipkit.model.MarkupConfig
import com.lifted.shipkit.model.PaymentSession
import com.lifted.shipkit.model.TrackingRecord
import com.lifted.shipkit.model.VerificationSession
import com.lifted.shipkit.util.PhoneNumbers
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Zero-config, in-process [LabelStore]. Ideal for a single instance and for
 * local development. State is lost on restart, so use [PostgresLabelStore] when
 * you need durability or run more than one node.
 */
class InMemoryLabelStore : LabelStore {
    private data class Expiring<T>(
        val value: T,
        val expiresAt: Instant,
    )

    @Volatile private var markup: MarkupConfig = MarkupConfig.DEFAULT

    @Volatile private var endShipperId: String? = null

    private val labels = ConcurrentHashMap<String, Expiring<LabelRecord>>()
    private val paymentSessions = ConcurrentHashMap<String, Expiring<PaymentSession>>()
    private val verifications = ConcurrentHashMap<String, Expiring<VerificationSession>>()
    private val tracking = ConcurrentHashMap<String, TrackingRecord>()

    // Idempotency guard: a session id present here has an in-flight (or completed)
    // label purchase, so no second caller may buy again.
    private val purchaseClaims = ConcurrentHashMap.newKeySet<String>()

    private fun retention() = Instant.now().plus(LabelStore.RETENTION_DAYS, ChronoUnit.DAYS)

    override fun getMarkupConfig(): MarkupConfig = markup

    override fun updateMarkupConfig(config: MarkupConfig) {
        markup = config
    }

    override fun getEndShipperId(): String? = endShipperId

    override fun setEndShipperId(id: String) {
        endShipperId = id
    }

    override fun saveLabel(label: LabelRecord) {
        val normalized =
            label.copy(
                senderPhone = label.senderPhone?.let { PhoneNumbers.normalize(it) },
            )
        labels[label.id] = Expiring(normalized, retention())
    }

    override fun getLabel(id: String): LabelRecord? =
        labels[id]
            ?.takeIf {
                it.expiresAt.isAfter(Instant.now())
            }?.value

    override fun getLabelBySession(sessionId: String): LabelRecord? =
        labels.values
            .filter { it.expiresAt.isAfter(Instant.now()) && it.value.sessionId == sessionId }
            .maxByOrNull { it.value.id }
            ?.value

    override fun getAllLabels(): List<LabelRecord> = labels.values.map { it.value }

    override fun getLabelsByPhone(phone: String): List<LabelRecord> {
        val target = PhoneNumbers.normalize(phone)
        return labels.values
            .filter { it.expiresAt.isAfter(Instant.now()) && it.value.senderPhone == target }
            .map { it.value }
    }

    override fun deleteLabelBySession(sessionId: String): Boolean {
        val match =
            labels.entries.firstOrNull {
                it.value.expiresAt.isAfter(Instant.now()) && it.value.value.sessionId == sessionId
            } ?: return false
        return labels.remove(match.key) != null
    }

    override fun cleanupExpiredLabels(): Int = removeExpired(labels)

    override fun saveTrackingUpdate(record: TrackingRecord) {
        // Upsert keyed on the tracking code; `compute` applies atomically per key.
        // EasyPost delivers webhooks out of order and retries old events, so an event
        // strictly older than the stored one (by provider event time — ISO-8601 UTC,
        // lexically comparable) is ignored to avoid regressing e.g. delivered → in_transit.
        // A missing event time can't be ordered, so it falls back to last-write-wins.
        // A known shipment id is preserved when a later event omits it (mirrors the
        // Postgres COALESCE upsert), and we stamp our own persist time.
        tracking.compute(record.trackingCode) { _, existing ->
            if (existing != null && isOlderEvent(record.eventAt, existing.eventAt)) {
                existing
            } else {
                record.copy(
                    shipmentId = record.shipmentId ?: existing?.shipmentId,
                    updatedAt = Instant.now().toString(),
                )
            }
        }
    }

    // True only when [incoming] is strictly older than [stored] by provider event
    // time. Both must be present to order; a missing timestamp is treated as
    // not-older so it still wins (last-write-wins).
    private fun isOlderEvent(
        incoming: String?,
        stored: String?,
    ): Boolean = incoming != null && stored != null && incoming < stored

    override fun getTracking(trackingCode: String): TrackingRecord? = tracking[trackingCode]

    override fun savePaymentSession(session: PaymentSession) {
        // Never regress a bought label back to null. A loser thread racing the
        // 3-D Secure return callback can persist a stale (pre-buy) copy of the
        // session AFTER the winner has already stored the label URL; without this
        // guard that write would blank out label_url/qr/tracking and 409 the paid
        // customer forever. `compute` applies the merge ATOMICALLY per key (a plain
        // read-then-put would leave the very clobber window open), mirroring the
        // Postgres COALESCE upsert.
        paymentSessions.compute(session.sessionId) { _, existing ->
            existing?.value?.let { prev ->
                if (session.labelUrl == null) session.labelUrl = prev.labelUrl
                if (session.qrCodeUrl == null) session.qrCodeUrl = prev.qrCodeUrl
                if (session.trackingCode == null) session.trackingCode = prev.trackingCode
            }
            Expiring(session, retention())
        }!!
    }

    override fun getPaymentSession(sessionId: String): PaymentSession? =
        paymentSessions[sessionId]?.takeIf { it.expiresAt.isAfter(Instant.now()) }?.value

    // `add` returns true only for the first caller — an atomic compare-and-set.
    override fun claimLabelPurchase(sessionId: String): Boolean = purchaseClaims.add(sessionId)

    override fun releaseLabelPurchaseClaim(sessionId: String) {
        purchaseClaims.remove(sessionId)
    }

    override fun cleanupExpiredPaymentSessions(): Int = removeExpired(paymentSessions)

    override fun createVerificationSession(phone: String): String {
        val sessionId = UUID.randomUUID().toString()
        val session =
            VerificationSession(
                sessionId = sessionId,
                phoneNumber = PhoneNumbers.normalize(phone),
                verified = false,
            )
        verifications[sessionId] = Expiring(session, Instant.now().plus(30, ChronoUnit.MINUTES))
        return sessionId
    }

    override fun markVerificationSessionVerified(
        sessionId: String,
        phone: String,
    ): Boolean {
        val current =
            verifications[sessionId]?.takeIf { it.expiresAt.isAfter(Instant.now()) } ?: return false
        // Phone binding: only the session started for this exact phone may be
        // verified with an OTP checked for this phone.
        if (current.value.phoneNumber != PhoneNumbers.normalize(phone)) return false
        verifications[sessionId] = current.copy(value = current.value.copy(verified = true))
        return true
    }

    override fun getValidVerificationSession(sessionId: String): VerificationSession? =
        verifications[sessionId]
            ?.takeIf { it.expiresAt.isAfter(Instant.now()) && it.value.verified }
            ?.value

    override fun cleanupExpiredVerificationSessions(): Int = removeExpired(verifications)

    private fun <T> removeExpired(map: ConcurrentHashMap<String, Expiring<T>>): Int {
        val now = Instant.now()
        val expired = map.entries.filter { !it.value.expiresAt.isAfter(now) }.map { it.key }
        expired.forEach { map.remove(it) }
        return expired.size
    }
}
