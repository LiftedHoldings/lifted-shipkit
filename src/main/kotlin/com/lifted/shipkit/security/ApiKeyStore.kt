package com.lifted.shipkit.security

import com.lifted.shipkit.store.DbConfig
import com.lifted.shipkit.store.StoreBackend
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant

/**
 * A stored API key: the SHA-256 [hash] of the secret plus non-secret metadata.
 * The full key is **never** part of this record — see [KeyGenerator].
 *
 * @param id         opaque record id (safe to expose; used to revoke).
 * @param label      operator-facing name.
 * @param hash       SHA-256 hex of the full key (the only thing kept of the secret).
 * @param prefix     short display prefix (`sk_live_ab12cd`) to identify the key in a list.
 * @param createdAt  when the key was minted.
 * @param lastUsedAt last time the key authenticated a request, or `null` if never used.
 * @param revoked    `true` once the key has been revoked (revoked keys never authenticate).
 * @param scope      capability scope: [KeyGenerator.Scope.SECRET] (full server key)
 *                   or [KeyGenerator.Scope.PUBLISHABLE] (browser widget key limited
 *                   to the customer rating + payment flow). Defaults to SECRET so a
 *                   legacy row with no stored scope keeps full behaviour.
 */
data class ApiKeyRecord(
    val id: String,
    val label: String,
    val hash: String,
    val prefix: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val revoked: Boolean,
    val scope: KeyGenerator.Scope = KeyGenerator.Scope.SECRET,
) {
    /**
     * Non-secret projection safe to serialize to an admin client. Deliberately
     * omits [hash] so the stored secret material never leaves the server.
     */
    fun toPublicMap(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "label" to label,
            "prefix" to prefix,
            "scope" to scope.token,
            "created_at" to createdAt.toString(),
            "last_used_at" to lastUsedAt?.toString(),
            "revoked" to revoked,
        )
}

/**
 * Persistence for API keys, mirroring [com.lifted.shipkit.store.LabelStore]'s two
 * backends so key storage follows the same `SHIPKIT_STORE` switch.
 *
 *  - [InMemoryApiKeyStore] — zero-config default; per-process, lost on restart.
 *  - [PostgresApiKeyStore] — durable, multi-instance storage in the
 *    `shipkit_api_keys` table, used when `SHIPKIT_STORE=postgres`.
 *
 * Only hashes are stored; a leaked store yields no usable keys.
 */
interface ApiKeyStore : AutoCloseable {
    /** Initialize the backend (create tables). */
    fun start() {}

    /** Persist a freshly minted [record]. */
    fun add(record: ApiKeyRecord)

    /** Find a key by the SHA-256 hex [hash] of the presented secret, or `null`. */
    fun findByHash(hash: String): ApiKeyRecord?

    /** Fetch a single key by [id], or `null`. */
    fun get(id: String): ApiKeyRecord?

    /** All keys, newest first (for the admin list surface). */
    fun listAll(): List<ApiKeyRecord>

    /**
     * Revoke the key with [id]. Returns `true` if a live key was revoked, `false`
     * if it was unknown or already revoked. Idempotent.
     */
    fun revoke(id: String): Boolean

    /** Record that the key [id] just authenticated a request, at [at]. */
    fun touchLastUsed(
        id: String,
        at: Instant = Instant.now(),
    )

    override fun close() {}

    companion object {
        /** Build the API-key store matching the configured [backend]. */
        fun create(
            backend: StoreBackend,
            db: DbConfig?,
        ): ApiKeyStore =
            when (backend) {
                StoreBackend.MEMORY -> {
                    InMemoryApiKeyStore()
                }

                StoreBackend.POSTGRES -> {
                    PostgresApiKeyStore(
                        requireNotNull(db) {
                            "SHIPKIT_STORE=postgres requires SHIPKIT_DATABASE_URL and DB credentials"
                        },
                    )
                }
            }.also { it.start() }
    }
}

/**
 * In-process [ApiKeyStore] backed by a thread-safe map. Great for local
 * development and tests; state is lost on restart, so keys minted here do not
 * survive a process bounce. Use [PostgresApiKeyStore] for durable, shared keys.
 */
class InMemoryApiKeyStore : ApiKeyStore {
    private val byId = java.util.concurrent.ConcurrentHashMap<String, ApiKeyRecord>()

    override fun add(record: ApiKeyRecord) {
        byId[record.id] = record
    }

    override fun findByHash(hash: String): ApiKeyRecord? =
        byId.values.firstOrNull { it.hash == hash }

    override fun get(id: String): ApiKeyRecord? = byId[id]

    override fun listAll(): List<ApiKeyRecord> = byId.values.sortedByDescending { it.createdAt }

    override fun revoke(id: String): Boolean {
        val current = byId[id] ?: return false
        if (current.revoked) return false
        byId[id] = current.copy(revoked = true)
        return true
    }

    override fun touchLastUsed(
        id: String,
        at: Instant,
    ) {
        byId.computeIfPresent(id) { _, current -> current.copy(lastUsedAt = at) }
    }
}

/**
 * Durable [ApiKeyStore] on **PostgreSQL**, matching Lifted's managed Postgres.
 * Connections come from a dedicated **HikariCP** pool; every statement is
 * parameterized (no string-concatenated SQL). Timestamps are `TIMESTAMPTZ`.
 */
class PostgresApiKeyStore(
    private val config: DbConfig,
) : ApiKeyStore {
    private val log = LoggerFactory.getLogger(PostgresApiKeyStore::class.java)

    private val dataSource: HikariDataSource by lazy {
        log.info("Initializing PostgreSQL API-key connection pool on port {}", config.port)
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.username
                password = config.password
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = 4
                poolName = "shipkit-pg-apikeys"
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
                    CREATE TABLE IF NOT EXISTS shipkit_api_keys (
                        id TEXT PRIMARY KEY,
                        label TEXT NOT NULL,
                        hash TEXT NOT NULL UNIQUE,
                        prefix TEXT NOT NULL,
                        scope TEXT NOT NULL DEFAULT 'sk',
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        last_used_at TIMESTAMPTZ,
                        revoked BOOLEAN NOT NULL DEFAULT FALSE
                    )
                    """.trimIndent(),
                )
                // Additive migration for a table created before scoped keys: the
                // column defaults to 'sk', so every pre-existing key keeps full
                // (secret) behaviour and no key silently becomes publishable.
                stmt.execute(
                    "ALTER TABLE shipkit_api_keys ADD COLUMN IF NOT EXISTS scope TEXT NOT NULL DEFAULT 'sk'",
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_api_keys_hash ON shipkit_api_keys (hash)",
                )
            }
        }
    }

    override fun add(record: ApiKeyRecord) {
        withConnection { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO shipkit_api_keys (id, label, hash, prefix, scope, created_at, last_used_at, revoked)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, record.id)
                    stmt.setString(2, record.label)
                    stmt.setString(3, record.hash)
                    stmt.setString(4, record.prefix)
                    stmt.setString(5, record.scope.token)
                    stmt.setTimestamp(6, Timestamp.from(record.createdAt))
                    if (record.lastUsedAt != null) {
                        stmt.setTimestamp(7, Timestamp.from(record.lastUsedAt))
                    } else {
                        stmt.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE)
                    }
                    stmt.setBoolean(8, record.revoked)
                    stmt.executeUpdate()
                }
        }
    }

    override fun findByHash(hash: String): ApiKeyRecord? =
        queryOne("SELECT * FROM shipkit_api_keys WHERE hash = ?", hash)

    override fun get(id: String): ApiKeyRecord? =
        queryOne("SELECT * FROM shipkit_api_keys WHERE id = ?", id)

    override fun listAll(): List<ApiKeyRecord> =
        withConnection { conn ->
            conn
                .prepareStatement("SELECT * FROM shipkit_api_keys ORDER BY created_at DESC")
                .use { stmt ->
                    stmt.executeQuery().use { rs ->
                        val out = mutableListOf<ApiKeyRecord>()
                        while (rs.next()) out.add(rs.toRecord())
                        out
                    }
                }
        }

    override fun revoke(id: String): Boolean =
        withConnection { conn ->
            conn
                .prepareStatement(
                    "UPDATE shipkit_api_keys SET revoked = TRUE WHERE id = ? AND revoked = FALSE",
                ).use { stmt ->
                    stmt.setString(1, id)
                    stmt.executeUpdate() > 0
                }
        }

    override fun touchLastUsed(
        id: String,
        at: Instant,
    ) {
        withConnection { conn ->
            conn
                .prepareStatement("UPDATE shipkit_api_keys SET last_used_at = ? WHERE id = ?")
                .use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(at))
                    stmt.setString(2, id)
                    stmt.executeUpdate()
                }
        }
    }

    override fun close() {
        if (dataSource.isRunning) dataSource.close()
    }

    private fun queryOne(
        sql: String,
        param: String,
    ): ApiKeyRecord? =
        withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, param)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
            }
        }

    private fun ResultSet.toRecord() =
        ApiKeyRecord(
            id = getString("id"),
            label = getString("label"),
            hash = getString("hash"),
            prefix = getString("prefix"),
            createdAt = getTimestamp("created_at").toInstant(),
            lastUsedAt = getTimestamp("last_used_at")?.toInstant(),
            revoked = getBoolean("revoked"),
            scope = KeyGenerator.Scope.fromString(getString("scope")),
        )
}
