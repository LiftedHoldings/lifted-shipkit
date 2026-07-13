package com.lifted.shipkit.store

import com.lifted.shipkit.model.LabelRecord
import com.lifted.shipkit.model.MarkupConfig
import com.lifted.shipkit.model.PaymentSession
import com.lifted.shipkit.model.VerificationSession

/** Which persistence backend to use. */
enum class StoreBackend { MEMORY, POSTGRES }

/**
 * Persistent state for ShipKit: markup config, the reusable EndShipper id,
 * purchased labels, in-flight payment sessions, and optional SMS verification
 * sessions.
 *
 * Two implementations ship in the box:
 *  - [InMemoryLabelStore] — the zero-config default; great for a single
 *    instance and for local development.
 *  - [PostgresLabelStore] — durable, multi-instance storage on PostgreSQL,
 *    enabled by setting `SHIPKIT_STORE=postgres`, `SHIPKIT_DATABASE_URL`, and
 *    the `SHIPKIT_DB_USER` / `SHIPKIT_DB_PASSWORD` environment variables.
 */
interface LabelStore : AutoCloseable {
    /** Initialize the backend (create tables, seed defaults). */
    fun start() {}

    // Markup configuration
    fun getMarkupConfig(): MarkupConfig

    fun updateMarkupConfig(config: MarkupConfig)

    // Reusable EndShipper id (USPS label compliance)
    fun getEndShipperId(): String?

    fun setEndShipperId(id: String)

    // Labels (retained for [RETENTION_DAYS])
    fun saveLabel(label: LabelRecord)

    fun getLabel(id: String): LabelRecord?

    fun getLabelBySession(sessionId: String): LabelRecord?

    fun getAllLabels(): List<LabelRecord>

    fun getLabelsByPhone(phone: String): List<LabelRecord>

    fun deleteLabelBySession(sessionId: String): Boolean

    fun cleanupExpiredLabels(): Int

    // Payment sessions
    fun savePaymentSession(session: PaymentSession)

    fun getPaymentSession(sessionId: String): PaymentSession?

    /**
     * Atomically claim the exclusive right to purchase a label for [sessionId].
     * Returns `true` for exactly one caller; every other concurrent caller gets
     * `false`. This is the idempotency guard that stops a status-poll racing the
     * return callback from buying (and billing) two labels for one payment.
     */
    fun claimLabelPurchase(sessionId: String): Boolean

    /** Release a claim taken by [claimLabelPurchase] when the purchase failed. */
    fun releaseLabelPurchaseClaim(sessionId: String)

    fun cleanupExpiredPaymentSessions(): Int

    // SMS verification sessions (optional)
    fun createVerificationSession(phone: String): String

    /**
     * Mark the verification session [sessionId] verified **only if** its stored
     * phone equals [phone] (normalized). The phone binding is security-critical:
     * the OTP is checked for [phone], so a session may be promoted to verified
     * only when it is the session that was started for that same phone — otherwise
     * a caller could pass an OTP for their own number against a session started
     * for the admin's number and inherit admin access. Returns `false` (not
     * verified) on any mismatch, unknown id, or expiry.
     */
    fun markVerificationSessionVerified(
        sessionId: String,
        phone: String,
    ): Boolean

    fun getValidVerificationSession(sessionId: String): VerificationSession?

    fun cleanupExpiredVerificationSessions(): Int

    override fun close() {}

    companion object {
        /** Days a purchased label / payment session is retained. */
        const val RETENTION_DAYS = 45L

        /** Build the store the config asks for. */
        fun create(
            backend: StoreBackend,
            db: DbConfig?,
        ): LabelStore =
            when (backend) {
                StoreBackend.MEMORY -> {
                    InMemoryLabelStore()
                }

                StoreBackend.POSTGRES -> {
                    PostgresLabelStore(
                        requireNotNull(db) {
                            "SHIPKIT_STORE=postgres requires SHIPKIT_DATABASE_URL and DB credentials"
                        },
                    )
                }
            }.also { it.start() }
    }
}
