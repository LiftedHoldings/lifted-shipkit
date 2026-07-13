package com.lifted.shipkit.config

import com.lifted.shipkit.model.ForcedThreeDsViolation
import com.lifted.shipkit.model.TierMode
import com.lifted.shipkit.payments.CardEntryMode
import com.lifted.shipkit.payments.PaymentsEnvironment
import com.lifted.shipkit.store.StoreBackend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Config resolution is security-relevant (it decides whether secrets are present
 * and features enabled), so it is tested against a stubbed environment rather
 * than the real process environment. It also pins the canonical env-var names.
 */
class ShipKitConfigTest {
    private fun env(vars: Map<String, String>) = ShipKitConfig.Companion.Env { vars[it] }

    @Test
    fun `empty environment yields safe, feature-off defaults`() {
        val config = ShipKitConfig.fromEnv(env(emptyMap()))

        assertEquals(8080, config.port)
        assertEquals("http://localhost:8080", config.baseUrl)
        assertEquals("*", config.corsOrigins)
        assertNull(config.easyPostApiKey)
        assertFalse(config.shippingEnabled)
        assertFalse(config.paymentsEnabled)
        assertFalse(config.sms.enabled)
        assertEquals(StoreBackend.MEMORY, config.storeBackend)
        assertNull(config.db)
        assertTrue(config.adminPhoneWhitelist.isEmpty())
        // Tier defaults to the free self-host tier with the surcharge OFF.
        assertEquals(TierMode.SELF_HOST, config.tier)
        assertFalse(config.surcharge.enabled)
        // Frictionless is OFF by default — forced 3-D Secure.
        assertFalse(config.frictionlessAllowed)
    }

    @Test
    fun `frictionless is allowed on a Lifted merchant or managed account that opts in`() {
        val merchant =
            ShipKitConfig.fromEnv(
                env(
                    mapOf(
                        "SHIPKIT_TIER" to "merchant",
                        "SHIPKIT_FRICTIONLESS_ENABLED" to "true",
                        "LIFTED_PAYMENTS_BEARER" to "token",
                        "LIFTED_PAYMENTS_TERMINAL_ID" to "1",
                        "LIFTED_PAYMENTS_DBA_ID" to "2",
                    ),
                ),
            )
        assertTrue(merchant.frictionlessAllowed)
        // The capability is threaded into the payments client, not just the config.
        assertTrue(merchant.payments?.frictionlessAllowed == true)

        val managed =
            ShipKitConfig.fromEnv(
                env(mapOf("SHIPKIT_TIER" to "managed", "SHIPKIT_FRICTIONLESS_ENABLED" to "true")),
            )
        assertTrue(managed.frictionlessAllowed)
    }

    @Test
    fun `a self-host deployment that tries to disable 3DS is REFUSED at startup`() {
        // Requesting frictionless on self-host / BYO must fail fast, not silently
        // downgrade the deployment's forced-3DS security.
        assertThrows(ForcedThreeDsViolation::class.java) {
            ShipKitConfig.fromEnv(
                env(
                    mapOf(
                        "SHIPKIT_TIER" to "selfhost",
                        "SHIPKIT_FRICTIONLESS_ENABLED" to "true",
                    ),
                ),
            )
        }
        // The tier defaults to self-host, so enabling frictionless with NO tier set
        // is likewise refused.
        assertThrows(ForcedThreeDsViolation::class.java) {
            ShipKitConfig.fromEnv(env(mapOf("SHIPKIT_FRICTIONLESS_ENABLED" to "true")))
        }
    }

    @Test
    fun `a merchant tier that does NOT opt in stays forced-3DS`() {
        val config = ShipKitConfig.fromEnv(env(mapOf("SHIPKIT_TIER" to "merchant")))
        assertFalse(
            config.frictionlessAllowed,
            "eligible tier without the toggle is still forced-3DS",
        )
    }

    @Test
    fun `tier and buyer surcharge are read from the environment`() {
        val config =
            ShipKitConfig.fromEnv(
                env(
                    mapOf(
                        "SHIPKIT_TIER" to "merchant",
                        "SHIPKIT_SURCHARGE_ENABLED" to "true",
                    ),
                ),
            )
        assertEquals(TierMode.MERCHANT, config.tier)
        assertTrue(config.surcharge.enabled)
        assertEquals(0, config.surcharge.percentage.compareTo(java.math.BigDecimal("3.75")))
        assertEquals(15, config.surcharge.fixedCents)
    }

    @Test
    fun `sandbox env selects the sandbox gateway and dashboard bases`() {
        val config =
            ShipKitConfig.fromEnv(
                env(
                    mapOf(
                        "LIFTED_PAYMENTS_BEARER" to "token",
                        "LIFTED_PAYMENTS_TERMINAL_ID" to "1",
                        "LIFTED_PAYMENTS_DBA_ID" to "2",
                        "LIFTED_PAYMENTS_ENV" to "sandbox",
                        "LIFTED_PAYMENTS_CARD_ENTRY" to "hosted_form",
                    ),
                ),
            )
        assertEquals(PaymentsEnvironment.SANDBOX, config.payments?.environment)
        assertEquals(
            "https://sandbox-gateway.maverickpayments.com",
            config.payments?.gatewayBaseUrl,
        )
        assertEquals(
            "https://sandbox-dashboard.maverickpayments.com",
            config.payments?.dashboardBaseUrl,
        )
        assertEquals(CardEntryMode.HOSTED_FORM, config.payments?.cardEntryMode)
    }

    @Test
    fun `an explicit base URL overrides the env-derived sandbox base`() {
        val config =
            ShipKitConfig.fromEnv(
                env(
                    mapOf(
                        "LIFTED_PAYMENTS_BEARER" to "token",
                        "LIFTED_PAYMENTS_TERMINAL_ID" to "1",
                        "LIFTED_PAYMENTS_DBA_ID" to "2",
                        "LIFTED_PAYMENTS_ENV" to "sandbox",
                        "LIFTED_PAYMENTS_API_BASE" to "https://gw.internal.test",
                    ),
                ),
            )
        assertEquals("https://gw.internal.test", config.payments?.gatewayBaseUrl)
        // Dashboard still derives from the sandbox env when not overridden.
        assertEquals(
            "https://sandbox-dashboard.maverickpayments.com",
            config.payments?.dashboardBaseUrl,
        )
    }

    @Test
    fun `payments enabled only when bearer, terminal, and dba are all present`() {
        val partial =
            ShipKitConfig.fromEnv(
                env(
                    mapOf(
                        "LIFTED_PAYMENTS_BEARER" to "token",
                        "LIFTED_PAYMENTS_TERMINAL_ID" to "1",
                    ),
                ),
            )
        assertFalse(partial.paymentsEnabled, "missing DBA id should leave payments disabled")

        val full =
            ShipKitConfig.fromEnv(
                env(
                    mapOf(
                        "LIFTED_PAYMENTS_BEARER" to "token",
                        "LIFTED_PAYMENTS_TERMINAL_ID" to "42",
                        "LIFTED_PAYMENTS_DBA_ID" to "7",
                    ),
                ),
            )
        assertTrue(full.paymentsEnabled)
        assertEquals(42, full.payments?.terminalId)
        assertEquals(7, full.payments?.dbaId)
        assertEquals("https://gateway.maverickpayments.com", full.payments?.gatewayBaseUrl)
        assertEquals("https://dashboard.maverickpayments.com", full.payments?.dashboardBaseUrl)
    }

    @Test
    fun `admin phone whitelist is normalized to ten digits`() {
        val config =
            ShipKitConfig.fromEnv(
                env(
                    mapOf("SHIPKIT_ADMIN_PHONES" to "+1 (555) 123-4567, 555-987-6543"),
                ),
            )
        assertEquals(listOf("5551234567", "5559876543"), config.adminPhoneWhitelist)
    }

    @Test
    fun `sms enabled parses common truthy values`() {
        assertTrue(ShipKitConfig.fromEnv(env(mapOf("SHIPKIT_SMS_ENABLED" to "true"))).sms.enabled)
        assertTrue(ShipKitConfig.fromEnv(env(mapOf("SHIPKIT_SMS_ENABLED" to "YES"))).sms.enabled)
        assertTrue(ShipKitConfig.fromEnv(env(mapOf("SHIPKIT_SMS_ENABLED" to "1"))).sms.enabled)
        assertFalse(ShipKitConfig.fromEnv(env(mapOf("SHIPKIT_SMS_ENABLED" to "false"))).sms.enabled)
        assertFalse(ShipKitConfig.fromEnv(env(mapOf("SHIPKIT_SMS_ENABLED" to "off"))).sms.enabled)
    }

    @Test
    fun `postgres backend reads the database DSN from the environment`() {
        val config =
            ShipKitConfig.fromEnv(
                env(
                    mapOf(
                        "SHIPKIT_STORE" to "postgres",
                        "SHIPKIT_DATABASE_URL" to
                            "jdbc:postgresql://db.internal:5432/shipkit?sslmode=require",
                        "SHIPKIT_DB_USER" to "shipkit",
                        "SHIPKIT_DB_PASSWORD" to "secret",
                    ),
                ),
            )
        assertEquals(StoreBackend.POSTGRES, config.storeBackend)
        assertEquals(5432, config.db?.port)
        assertEquals("shipkit", config.db?.username)
        assertTrue(config.db?.jdbcUrl?.startsWith("jdbc:postgresql://") == true)
        assertTrue(config.db?.jdbcUrl?.contains("sslmode=require") == true)
    }

    @Test
    fun `only the canonical env names are read — legacy aliases are ignored`() {
        // Non-canonical aliases must have NO effect: the store stays MEMORY and the
        // EasyPost key stays unset, proving the code reads only the documented names.
        val config =
            ShipKitConfig.fromEnv(
                env(
                    mapOf(
                        "DATABASE_URL" to "jdbc:postgresql://legacy:3306/x",
                        "STORE" to "postgres",
                        "EASYPOST_KEY" to "should-be-ignored",
                        "SK_PORT" to "9999",
                    ),
                ),
            )
        assertEquals(StoreBackend.MEMORY, config.storeBackend, "legacy STORE alias ignored")
        assertNull(config.easyPostApiKey, "legacy EASYPOST_KEY alias ignored")
        assertEquals(8080, config.port, "legacy SK_PORT alias ignored; canonical default used")
        assertNull(config.db)
    }

    @Test
    fun `postgres port defaults to 5432 when the DSN omits an explicit port`() {
        val config =
            ShipKitConfig.fromEnv(
                env(
                    mapOf(
                        "SHIPKIT_STORE" to "postgres",
                        "SHIPKIT_DATABASE_URL" to
                            "jdbc:postgresql://db.internal/shipkit?sslmode=require",
                        "SHIPKIT_DB_USER" to "shipkit",
                        "SHIPKIT_DB_PASSWORD" to "secret",
                    ),
                ),
            )
        assertEquals(5432, config.db?.port)
    }
}
