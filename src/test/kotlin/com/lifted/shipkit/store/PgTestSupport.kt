package com.lifted.shipkit.store

import org.junit.jupiter.api.Assumptions
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/**
 * Shared bring-up for the Postgres-backed store tests. Resolves a real
 * PostgreSQL to test against, in priority order:
 *
 *  1. An **external** database from `-Dshipkit.test.pg.url` (+ `.user`/`.password`)
 *     or the `SHIPKIT_TEST_PG_URL` env — handy where the Testcontainers Docker
 *     auto-detection is unavailable but a `postgres:16` is reachable.
 *  2. A **Testcontainers** `postgres:16` when a Docker daemon is available.
 *  3. Otherwise the calling test is **skipped gracefully** (JUnit assumption),
 *     never failed — matching CONTRACTS §8.
 *
 * The chosen database is wiped of ShipKit tables before use so each run starts
 * from a known-empty schema regardless of which source supplied it.
 */
object PgTestSupport {
    /** A live database plus an optional container handle to stop in teardown. */
    class Handle(
        val db: DbConfig,
        private val container: PostgreSQLContainer<*>?,
    ) {
        fun close() {
            container?.stop()
        }
    }

    private fun prop(
        sys: String,
        env: String,
    ): String? =
        System.getProperty(sys)?.takeIf { it.isNotBlank() }
            ?: System.getenv(env)?.takeIf { it.isNotBlank() }

    /**
     * Acquire a database, or abort (skip) the test when none is available.
     * Always returns a freshly-wiped ShipKit schema.
     */
    fun acquire(): Handle {
        val externalUrl = prop("shipkit.test.pg.url", "SHIPKIT_TEST_PG_URL")
        val handle =
            if (externalUrl != null) {
                Handle(
                    DbConfig(
                        jdbcUrl = externalUrl,
                        username =
                            prop("shipkit.test.pg.user", "SHIPKIT_TEST_PG_USER") ?: "postgres",
                        password =
                            prop("shipkit.test.pg.password", "SHIPKIT_TEST_PG_PASSWORD")
                                ?: "postgres",
                    ),
                    container = null,
                )
            } else {
                Assumptions.assumeTrue(
                    dockerAvailable(),
                    "No external Postgres (-Dshipkit.test.pg.url) and no Docker daemon; skipping Postgres store tests",
                )
                val container = PostgreSQLContainer("postgres:16").withDatabaseName("shipkit")
                container.start()
                Handle(
                    DbConfig(container.jdbcUrl, container.username, container.password),
                    container,
                )
            }
        wipe(handle.db)
        return handle
    }

    private fun dockerAvailable(): Boolean =
        try {
            DockerClientFactory.instance().isDockerAvailable
        } catch (t: Throwable) {
            false
        }

    /** Drop every ShipKit-owned table so a store's `start()` DDL runs clean. */
    private fun wipe(db: DbConfig) {
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { conn ->
            conn.createStatement().use { stmt ->
                for (
                table in
                listOf(
                    "shipkit_labels",
                    "shipkit_payment_sessions",
                    "shipkit_verification_sessions",
                    "shipkit_tracking",
                    "shipkit_settings",
                    "shipkit_api_keys",
                )
                ) {
                    stmt.execute("DROP TABLE IF EXISTS $table CASCADE")
                }
            }
        }
    }
}
