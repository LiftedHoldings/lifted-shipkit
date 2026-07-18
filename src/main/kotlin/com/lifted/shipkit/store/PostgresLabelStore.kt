package com.lifted.shipkit.store

import com.google.gson.Gson
import com.lifted.shipkit.model.LabelRecord
import com.lifted.shipkit.model.MarkupConfig
import com.lifted.shipkit.model.PaymentSession
import com.lifted.shipkit.model.TrackingRecord
import com.lifted.shipkit.model.VerificationSession
import com.lifted.shipkit.util.PhoneNumbers
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID

/**
 * PostgreSQL connection settings, sourced entirely from the environment. There
 * are no hardcoded hosts, users, or passwords.
 *
 * The DSN is a single JDBC URL (`SHIPKIT_DATABASE_URL`) so the operator controls
 * host, database, and — critically — TLS. Lifted's managed Postgres requires
 * `sslmode=require`; cleartext connections are never used.
 *
 * @param jdbcUrl  full JDBC URL, e.g. `jdbc:postgresql://host:5432/shipkit?sslmode=require`.
 * @param username database user (from `SHIPKIT_DB_USER`).
 * @param password database password (from `SHIPKIT_DB_PASSWORD`).
 */
data class DbConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
) {
    /** Port parsed from the JDBC URL; PostgreSQL's default is 5432. */
    val port: Int
        get() =
            PORT_IN_URL
                .find(jdbcUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull() ?: 5432

    private companion object {
        private val PORT_IN_URL = Regex(":(\\d+)/")
    }
}

/**
 * Durable, multi-instance [LabelStore] backed by **PostgreSQL** — matching
 * Lifted's managed Postgres. Connections come from a **HikariCP** pool (never a
 * single shared mutable [Connection], which is not thread-safe under a
 * concurrent server), and every statement is parameterized (no string-concatenated
 * SQL). Upserts use `INSERT … ON CONFLICT … DO UPDATE`; timestamps are
 * `TIMESTAMPTZ`; the 3-D Secure result is stored as `JSONB`.
 */
class PostgresLabelStore(
    private val config: DbConfig,
) : LabelStore {
    private val log = LoggerFactory.getLogger(PostgresLabelStore::class.java)
    private val gson = Gson()

    private val dataSource: HikariDataSource by lazy {
        log.info("Initializing PostgreSQL connection pool on port {}", config.port)
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.username
                password = config.password
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = 8
                poolName = "shipkit-pg"
            },
        )
    }

    private inline fun <T> withConnection(block: (Connection) -> T): T =
        dataSource.connection.use(block)

    override fun start() {
        withConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS shipkit_settings (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS shipkit_labels (
                        id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        label_url TEXT NOT NULL,
                        qr_code_url TEXT,
                        tracking_code TEXT,
                        carrier TEXT,
                        service TEXT,
                        amount NUMERIC(12, 2),
                        base_rate NUMERIC(12, 2),
                        percentage_markup DOUBLE PRECISION,
                        fixed_fee_cents INTEGER,
                        shipment_id TEXT,
                        sender_phone TEXT,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        expires_at TIMESTAMPTZ NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_labels_session ON shipkit_labels (session_id)",
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_labels_phone ON shipkit_labels (sender_phone)",
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS shipkit_payment_sessions (
                        session_id TEXT PRIMARY KEY,
                        amount NUMERIC(12, 2) NOT NULL,
                        description TEXT,
                        external_id TEXT,
                        status TEXT NOT NULL,
                        shipment_id TEXT,
                        rate_id TEXT,
                        paid_base_rate NUMERIC(12, 2),
                        currency TEXT NOT NULL DEFAULT 'USD',
                        label_url TEXT,
                        qr_code_url TEXT,
                        tracking_code TEXT,
                        end_shipper_id TEXT,
                        three_ds JSONB,
                        purchase_claimed BOOLEAN NOT NULL DEFAULT FALSE,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        completed_at TIMESTAMPTZ,
                        expires_at TIMESTAMPTZ NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS shipkit_tracking (
                        tracking_code TEXT PRIMARY KEY,
                        status TEXT,
                        status_detail TEXT,
                        carrier TEXT,
                        est_delivery_date TEXT,
                        shipment_id TEXT,
                        event_at TEXT,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_tracking_shipment " +
                        "ON shipkit_tracking (shipment_id)",
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS shipkit_verification_sessions (
                        session_id TEXT PRIMARY KEY,
                        phone_number TEXT NOT NULL,
                        verified BOOLEAN NOT NULL DEFAULT FALSE,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        expires_at TIMESTAMPTZ NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
        // Seed the default markup so admins see the effective value; getMarkupConfig
        // still falls back to the documented DEFAULT if the row is ever absent.
        if (getSetting(MARKUP_KEY) == null) updateMarkupConfig(MarkupConfig.DEFAULT)
    }

    // ---- Markup (stored as JSON in settings) ---------------------------------

    override fun getMarkupConfig(): MarkupConfig {
        val json = getSetting(MARKUP_KEY) ?: return MarkupConfig.DEFAULT
        return try {
            gson.fromJson(json, MarkupConfig::class.java)
        } catch (e: Exception) {
            MarkupConfig.DEFAULT
        }
    }

    override fun updateMarkupConfig(config: MarkupConfig) =
        setSetting(MARKUP_KEY, gson.toJson(config))

    // ---- Settings / EndShipper ----------------------------------------------

    override fun getEndShipperId(): String? = getSetting("easypost_end_shipper_id")

    override fun setEndShipperId(id: String) = setSetting("easypost_end_shipper_id", id)

    private fun getSetting(key: String): String? =
        withConnection { conn ->
            conn.prepareStatement("SELECT value FROM shipkit_settings WHERE key = ?").use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun setSetting(
        key: String,
        value: String,
    ) {
        withConnection { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO shipkit_settings (key, value) VALUES (?, ?)
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, key)
                    stmt.setString(2, value)
                    stmt.executeUpdate()
                }
        }
    }

    // ---- Labels --------------------------------------------------------------

    override fun saveLabel(label: LabelRecord) {
        withConnection { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO shipkit_labels
                        (id, session_id, label_url, qr_code_url, tracking_code, carrier, service,
                         amount, base_rate, percentage_markup, fixed_fee_cents, shipment_id,
                         sender_phone, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now() + interval '$RETENTION_DAYS days')
                    ON CONFLICT (id) DO UPDATE SET
                        label_url = EXCLUDED.label_url,
                        qr_code_url = EXCLUDED.qr_code_url,
                        tracking_code = EXCLUDED.tracking_code
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, label.id)
                    stmt.setString(2, label.sessionId)
                    stmt.setString(3, label.labelUrl)
                    stmt.setNullableString(4, label.qrCodeUrl)
                    stmt.setNullableString(5, label.trackingCode)
                    stmt.setNullableString(6, label.carrier)
                    stmt.setNullableString(7, label.service)
                    stmt.setNullableDouble(8, label.amount)
                    stmt.setNullableDouble(9, label.baseRate)
                    stmt.setNullableDouble(10, label.percentageMarkup)
                    stmt.setNullableInt(11, label.fixedFeeCents)
                    stmt.setNullableString(12, label.shipmentId)
                    stmt.setNullableString(
                        13,
                        label.senderPhone?.let { PhoneNumbers.normalize(it) },
                    )
                    stmt.executeUpdate()
                }
        }
    }

    override fun getLabel(id: String): LabelRecord? =
        queryLabel("SELECT * FROM shipkit_labels WHERE id = ? AND expires_at > now()", id)

    override fun getLabelBySession(sessionId: String): LabelRecord? =
        queryLabel(
            "SELECT * FROM shipkit_labels WHERE session_id = ? AND expires_at > now() " +
                "ORDER BY created_at DESC LIMIT 1",
            sessionId,
        )

    override fun getAllLabels(): List<LabelRecord> =
        queryLabels("SELECT * FROM shipkit_labels ORDER BY created_at DESC")

    override fun getLabelsByPhone(phone: String): List<LabelRecord> =
        queryLabels(
            "SELECT * FROM shipkit_labels WHERE sender_phone = ? AND expires_at > now() " +
                "ORDER BY created_at DESC",
            PhoneNumbers.normalize(phone),
        )

    override fun deleteLabelBySession(sessionId: String): Boolean =
        withConnection { conn ->
            conn
                .prepareStatement(
                    "DELETE FROM shipkit_labels WHERE session_id = ? AND expires_at > now()",
                ).use { stmt ->
                    stmt.setString(1, sessionId)
                    stmt.executeUpdate() > 0
                }
        }

    // Retention: created_at < now() - interval '45 days' (expires_at encodes the same).
    override fun cleanupExpiredLabels(): Int =
        withConnection { conn ->
            conn
                .prepareStatement("DELETE FROM shipkit_labels WHERE expires_at <= now()")
                .use { it.executeUpdate() }
        }

    private fun queryLabel(
        sql: String,
        param: String,
    ): LabelRecord? = queryLabels(sql, param).firstOrNull()

    private fun queryLabels(
        sql: String,
        param: String? = null,
    ): List<LabelRecord> =
        withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                if (param != null) stmt.setString(1, param)
                stmt.executeQuery().use { rs ->
                    val out = mutableListOf<LabelRecord>()
                    while (rs.next()) out.add(rs.toLabelRecord())
                    out
                }
            }
        }

    private fun ResultSet.toLabelRecord() =
        LabelRecord(
            id = getString("id"),
            sessionId = getString("session_id"),
            labelUrl = getString("label_url"),
            qrCodeUrl = getString("qr_code_url"),
            trackingCode = getString("tracking_code"),
            carrier = getString("carrier"),
            service = getString("service"),
            amount = getObject("amount")?.let { (it as java.math.BigDecimal).toDouble() },
            baseRate = getObject("base_rate")?.let { (it as java.math.BigDecimal).toDouble() },
            percentageMarkup = getObject("percentage_markup") as? Double,
            fixedFeeCents = (getObject("fixed_fee_cents") as? Number)?.toInt(),
            shipmentId = getString("shipment_id"),
            senderPhone = getString("sender_phone"),
            createdAt = getTimestamp("created_at")?.toInstant()?.toString(),
            expiresAt = getTimestamp("expires_at")?.toInstant()?.toString(),
        )

    // ---- Tracking status -----------------------------------------------------

    override fun saveTrackingUpdate(record: TrackingRecord) {
        withConnection { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO shipkit_tracking
                        (tracking_code, status, status_detail, carrier, est_delivery_date,
                         shipment_id, event_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (tracking_code) DO UPDATE SET
                        status = EXCLUDED.status,
                        status_detail = EXCLUDED.status_detail,
                        carrier = EXCLUDED.carrier,
                        est_delivery_date = EXCLUDED.est_delivery_date,
                        -- Keep a known shipment id if a later event omits it.
                        shipment_id =
                            COALESCE(EXCLUDED.shipment_id, shipkit_tracking.shipment_id),
                        event_at = EXCLUDED.event_at,
                        updated_at = now()
                    -- EasyPost retries/reorders events; ignore one strictly older than
                    -- the stored one (by provider event time). A missing timestamp on
                    -- either side can't be ordered, so it falls back to last-write-wins.
                    WHERE EXCLUDED.event_at IS NULL
                       OR shipkit_tracking.event_at IS NULL
                       OR EXCLUDED.event_at >= shipkit_tracking.event_at
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, record.trackingCode)
                    stmt.setNullableString(2, record.status)
                    stmt.setNullableString(3, record.statusDetail)
                    stmt.setNullableString(4, record.carrier)
                    stmt.setNullableString(5, record.estDeliveryDate)
                    stmt.setNullableString(6, record.shipmentId)
                    stmt.setNullableString(7, record.eventAt)
                    stmt.executeUpdate()
                }
        }
    }

    override fun getTracking(trackingCode: String): TrackingRecord? =
        withConnection { conn ->
            conn
                .prepareStatement("SELECT * FROM shipkit_tracking WHERE tracking_code = ?")
                .use { stmt ->
                    stmt.setString(1, trackingCode)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return@withConnection null
                        TrackingRecord(
                            trackingCode = rs.getString("tracking_code"),
                            status = rs.getString("status"),
                            statusDetail = rs.getString("status_detail"),
                            carrier = rs.getString("carrier"),
                            estDeliveryDate = rs.getString("est_delivery_date"),
                            shipmentId = rs.getString("shipment_id"),
                            eventAt = rs.getString("event_at"),
                            updatedAt = rs.getTimestamp("updated_at")?.toInstant()?.toString(),
                        )
                    }
                }
        }

    // ---- Payment sessions ----------------------------------------------------

    override fun savePaymentSession(session: PaymentSession) {
        val threeDsJson =
            if (session.threeDsEci != null || session.threeDsCavv != null ||
                session.liabilityShift
            ) {
                gson.toJson(
                    mapOf(
                        "eci" to session.threeDsEci,
                        "cavv" to session.threeDsCavv,
                        "liability_shift" to session.liabilityShift,
                    ),
                )
            } else {
                null
            }
        withConnection { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO shipkit_payment_sessions
                        (session_id, amount, description, external_id, status, shipment_id, rate_id,
                         paid_base_rate, currency, label_url, qr_code_url, tracking_code,
                         end_shipper_id, three_ds, completed_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, to_timestamp(? / 1000.0),
                            now() + interval '$RETENTION_DAYS days')
                    ON CONFLICT (session_id) DO UPDATE SET
                        -- Full-replace semantics (matching the in-memory store): a
                        -- session saved over the minimal claim placeholder must
                        -- carry ALL its money fields — amount, the gateway
                        -- transaction reference (external_id), and the
                        -- shipment/rate context — or billing events and refund
                        -- reconciliation silently corrupt.
                        amount = EXCLUDED.amount,
                        description = EXCLUDED.description,
                        external_id = EXCLUDED.external_id,
                        status = EXCLUDED.status,
                        shipment_id = EXCLUDED.shipment_id,
                        rate_id = EXCLUDED.rate_id,
                        paid_base_rate = EXCLUDED.paid_base_rate,
                        currency = EXCLUDED.currency,
                        end_shipper_id = EXCLUDED.end_shipper_id,
                        -- COALESCE so a stale (pre-buy) write from a loser thread
                        -- racing the 3-D Secure return callback can never regress a
                        -- already-bought label back to NULL and 409 the paid buyer.
                        label_url = COALESCE(EXCLUDED.label_url, shipkit_payment_sessions.label_url),
                        qr_code_url = COALESCE(EXCLUDED.qr_code_url, shipkit_payment_sessions.qr_code_url),
                        tracking_code =
                            COALESCE(EXCLUDED.tracking_code, shipkit_payment_sessions.tracking_code),
                        three_ds = EXCLUDED.three_ds,
                        completed_at = EXCLUDED.completed_at
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, session.sessionId)
                    stmt.setBigDecimal(2, java.math.BigDecimal.valueOf(session.amount))
                    stmt.setString(3, session.description)
                    stmt.setString(4, session.externalId)
                    stmt.setString(5, session.status)
                    stmt.setNullableString(6, session.shipmentId)
                    stmt.setNullableString(7, session.rateId)
                    stmt.setNullableDouble(8, session.paidBaseRate)
                    stmt.setString(9, session.currency)
                    stmt.setNullableString(10, session.labelUrl)
                    stmt.setNullableString(11, session.qrCodeUrl)
                    stmt.setNullableString(12, session.trackingCode)
                    stmt.setNullableString(13, session.endShipperId)
                    stmt.setNullableString(14, threeDsJson)
                    if (session.completedAt != null) {
                        stmt.setLong(15, session.completedAt!!)
                    } else {
                        stmt.setNull(15, Types.BIGINT)
                    }
                    stmt.executeUpdate()
                }
        }
    }

    override fun getPaymentSession(sessionId: String): PaymentSession? =
        withConnection { conn ->
            conn
                .prepareStatement(
                    "SELECT * FROM shipkit_payment_sessions WHERE session_id = ? AND expires_at > now()",
                ).use { stmt ->
                    stmt.setString(1, sessionId)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return@withConnection null
                        val threeDs = parseThreeDs(rs.getString("three_ds"))
                        PaymentSession(
                            sessionId = rs.getString("session_id"),
                            amount = rs.getBigDecimal("amount")?.toDouble() ?: 0.0,
                            description = rs.getString("description") ?: "",
                            externalId = rs.getString("external_id") ?: "",
                            createdAt =
                                rs.getTimestamp("created_at")?.time ?: System.currentTimeMillis(),
                            status = rs.getString("status"),
                            completedAt = rs.getTimestamp("completed_at")?.time,
                            shipmentId = rs.getString("shipment_id"),
                            rateId = rs.getString("rate_id"),
                            paidBaseRate = rs.getBigDecimal("paid_base_rate")?.toDouble(),
                            currency = rs.getString("currency") ?: "USD",
                            labelUrl = rs.getString("label_url"),
                            qrCodeUrl = rs.getString("qr_code_url"),
                            trackingCode = rs.getString("tracking_code"),
                            endShipperId = rs.getString("end_shipper_id"),
                            threeDsEci = threeDs["eci"] as? String,
                            threeDsCavv = threeDs["cavv"] as? String,
                            liabilityShift = threeDs["liability_shift"] as? Boolean ?: false,
                        )
                    }
                }
        }

    private fun parseThreeDs(json: String?): Map<String, Any?> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(json, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override fun claimLabelPurchase(sessionId: String): Boolean =
        withConnection { conn ->
            // Claim-or-create: the saved-card flow claims BEFORE any session row
            // exists (the session is only persisted after the charge), so a plain
            // UPDATE would match zero rows and wrongly report "already in
            // progress" forever. Insert a minimal placeholder row claimed=TRUE, or
            // atomically flip an existing unclaimed row; a claimed or
            // already-bought session updates zero rows and the claim is refused.
            // The placeholder expires with normal retention, so a crashed claim
            // self-heals via cleanupExpiredPaymentSessions.
            conn
                .prepareStatement(
                    """
                    INSERT INTO shipkit_payment_sessions
                        (session_id, amount, status, currency, purchase_claimed, expires_at)
                    VALUES (?, 0, 'claiming', 'USD', TRUE, now() + interval '$RETENTION_DAYS days')
                    ON CONFLICT (session_id) DO UPDATE SET purchase_claimed = TRUE
                    WHERE shipkit_payment_sessions.purchase_claimed = FALSE
                      AND shipkit_payment_sessions.label_url IS NULL
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, sessionId)
                    stmt.executeUpdate() == 1
                }
        }

    override fun releaseLabelPurchaseClaim(sessionId: String) {
        withConnection { conn ->
            conn
                .prepareStatement(
                    "UPDATE shipkit_payment_sessions SET purchase_claimed = FALSE WHERE session_id = ?",
                ).use { stmt ->
                    stmt.setString(1, sessionId)
                    stmt.executeUpdate()
                }
        }
    }

    override fun cleanupExpiredPaymentSessions(): Int =
        withConnection { conn ->
            conn
                .prepareStatement("DELETE FROM shipkit_payment_sessions WHERE expires_at <= now()")
                .use { it.executeUpdate() }
        }

    // ---- Verification sessions ----------------------------------------------

    override fun createVerificationSession(phone: String): String {
        val sessionId = UUID.randomUUID().toString()
        withConnection { conn ->
            conn
                .prepareStatement(
                    "INSERT INTO shipkit_verification_sessions (session_id, phone_number, verified, expires_at) " +
                        "VALUES (?, ?, FALSE, now() + interval '30 minutes')",
                ).use { stmt ->
                    stmt.setString(1, sessionId)
                    stmt.setString(2, PhoneNumbers.normalize(phone))
                    stmt.executeUpdate()
                }
        }
        return sessionId
    }

    override fun markVerificationSessionVerified(
        sessionId: String,
        phone: String,
    ): Boolean =
        withConnection { conn ->
            conn
                .prepareStatement(
                    // Phone-bound: the AND phone_number = ? clause is the fix that
                    // stops an OTP checked for one phone from verifying a session
                    // that was started for a different (e.g. admin) phone.
                    "UPDATE shipkit_verification_sessions SET verified = TRUE " +
                        "WHERE session_id = ? AND phone_number = ? AND expires_at > now()",
                ).use { stmt ->
                    stmt.setString(1, sessionId)
                    stmt.setString(2, PhoneNumbers.normalize(phone))
                    stmt.executeUpdate() > 0
                }
        }

    override fun getValidVerificationSession(sessionId: String): VerificationSession? =
        withConnection { conn ->
            conn
                .prepareStatement(
                    "SELECT * FROM shipkit_verification_sessions " +
                        "WHERE session_id = ? AND verified = TRUE AND expires_at > now()",
                ).use { stmt ->
                    stmt.setString(1, sessionId)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return@withConnection null
                        VerificationSession(
                            sessionId = rs.getString("session_id"),
                            phoneNumber = rs.getString("phone_number"),
                            verified = rs.getBoolean("verified"),
                            createdAt = rs.getTimestamp("created_at")?.toInstant()?.toString(),
                            expiresAt = rs.getTimestamp("expires_at")?.toInstant()?.toString(),
                        )
                    }
                }
        }

    override fun cleanupExpiredVerificationSessions(): Int =
        withConnection { conn ->
            conn
                .prepareStatement(
                    "DELETE FROM shipkit_verification_sessions WHERE expires_at <= now()",
                ).use { it.executeUpdate() }
        }

    override fun close() {
        if (dataSource.isRunning) dataSource.close()
    }

    private companion object {
        private const val MARKUP_KEY = "markup_config"
        private const val RETENTION_DAYS = LabelStore.RETENTION_DAYS
    }
}

private fun java.sql.PreparedStatement.setNullableString(
    index: Int,
    value: String?,
) = if (value != null) setString(index, value) else setNull(index, Types.VARCHAR)

private fun java.sql.PreparedStatement.setNullableDouble(
    index: Int,
    value: Double?,
) = if (value !=
    null
) {
    setBigDecimal(index, java.math.BigDecimal.valueOf(value))
} else {
    setNull(index, Types.NUMERIC)
}

private fun java.sql.PreparedStatement.setNullableInt(
    index: Int,
    value: Int?,
) = if (value != null) setInt(index, value) else setNull(index, Types.INTEGER)
