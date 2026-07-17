package com.lifted.shipkit.model

/**
 * A purchased shipping label, retained for a fixed window so customers can
 * re-download it without exposing card or account data.
 */
data class LabelRecord(
    val id: String,
    val sessionId: String,
    val labelUrl: String,
    val qrCodeUrl: String? = null,
    val trackingCode: String? = null,
    val carrier: String? = null,
    val service: String? = null,
    val amount: Double? = null,
    val baseRate: Double? = null,
    val percentageMarkup: Double? = null,
    val fixedFeeCents: Int? = null,
    val shipmentId: String? = null,
    val senderPhone: String? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null,
) {
    /** Merchant margin on this label, when the raw carrier cost is known. */
    val profit: Double?
        get() = if (amount != null && baseRate != null) amount - baseRate else null
}

/**
 * A payment attempt secured by Lifted Payments 3-D Secure. Persisted so the
 * client status poll (which verifies the charge server-side against the gateway)
 * and the idempotent label purchase can find it.
 *
 * [amount] and [paidBaseRate] are **server-computed** at session creation from
 * the selected EasyPost rate plus the merchant markup — the client-supplied
 * amount is never trusted. [paidBaseRate] is the raw carrier cost the customer
 * paid for; the label purchase refuses to buy a fresh rate that reprices above
 * it, so a peak/dimensional reweight can never charge the merchant more than the
 * customer paid.
 */
data class PaymentSession(
    val sessionId: String,
    val amount: Double,
    val description: String,
    val externalId: String,
    val createdAt: Long,
    var status: String = "pending",
    var completedAt: Long? = null,
    var shipmentId: String? = null,
    var rateId: String? = null,
    var paidBaseRate: Double? = null,
    var currency: String = "USD",
    var labelUrl: String? = null,
    var qrCodeUrl: String? = null,
    var trackingCode: String? = null,
    var endShipperId: String? = null,
    // Server-verified 3-D Secure result (read back from the gateway, never from
    // attacker-controllable return-URL params). Persisted for chargeback audit.
    var threeDsEci: String? = null,
    var threeDsCavv: String? = null,
    var liabilityShift: Boolean = false,
) {
    /**
     * True only when the gateway reported the charge Approved **and** 3-D Secure
     * produced a liability shift. Forced-3DS policy: no shift, no purchase.
     */
    val approved: Boolean get() = status == "approved" && liabilityShift
}

/**
 * The latest carrier tracking state for a shipment, persisted from a
 * signature-verified EasyPost `tracker.*` webhook. Keyed by [trackingCode] (the
 * carrier tracking number, unique per label) so a merchant can query a shipment's
 * current lifecycle without holding the original payment session.
 *
 * All provider-supplied fields are optional: EasyPost omits `status_detail`,
 * `carrier`, or `est_delivery_date` on some events, and this record simply
 * mirrors whatever the last event carried. [eventAt] is the provider's own event
 * timestamp (opaque ISO string); [updatedAt] is when ShipKit persisted it.
 */
data class TrackingRecord(
    val trackingCode: String,
    val status: String? = null,
    val statusDetail: String? = null,
    val carrier: String? = null,
    val estDeliveryDate: String? = null,
    val shipmentId: String? = null,
    val eventAt: String? = null,
    val updatedAt: String? = null,
)

/** A short-lived SMS phone-verification session (optional history/admin auth). */
data class VerificationSession(
    val sessionId: String,
    val phoneNumber: String,
    val verified: Boolean,
    val createdAt: String? = null,
    val expiresAt: String? = null,
)
