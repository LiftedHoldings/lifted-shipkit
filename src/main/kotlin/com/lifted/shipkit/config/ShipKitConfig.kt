package com.lifted.shipkit.config

import com.lifted.shipkit.model.SurchargeConfig
import com.lifted.shipkit.model.TierMode
import com.lifted.shipkit.payments.CardEntryMode
import com.lifted.shipkit.payments.LiftedPaymentsConfig
import com.lifted.shipkit.payments.PaymentsEnvironment
import com.lifted.shipkit.sms.SmsConfig
import com.lifted.shipkit.store.DbConfig
import com.lifted.shipkit.store.StoreBackend
import com.lifted.shipkit.util.PhoneNumbers

/**
 * All ShipKit configuration, resolved from the environment exactly once at
 * startup. There are **no hardcoded credentials** anywhere in this project:
 * secrets are always read from the environment, and every non-secret value has
 * a safe default. Missing-but-required secrets fail fast with a clear message
 * when the feature that needs them is first used.
 *
 * The environment-variable names are the single canonical set (see
 * `.env.example`): `SHIPKIT_*` for server/store, `EASYPOST_*` for shipping,
 * `LIFTED_PAYMENTS_*` for the 3-D Secure gateway, and `TWILIO_*` for the
 * optional SMS module.
 */
data class ShipKitConfig(
    val port: Int,
    val baseUrl: String,
    val corsOrigins: String,
    val easyPostApiKey: String?,
    val easyPostWebhookSecret: String?,
    val payments: LiftedPaymentsConfig?,
    val adminPhoneWhitelist: List<String>,
    val sms: SmsConfig,
    val storeBackend: StoreBackend,
    val db: DbConfig?,
    /**
     * The declared adoption tier ([TierMode]). Informational — it does not gate
     * any endpoint — but it lets the deployment surface an honest pricing story
     * (self-host / merchant / managed) via `/api/config/tier`. Defaults to
     * self-host, the free, no-account starting point.
     */
    val tier: TierMode = TierMode.SELF_HOST,
    /**
     * The tier-2 buyer-surcharge framework ([SurchargeConfig]). Off by default;
     * when enabled the 3.75% + $0.15 merchant-account fee is added to the amount
     * the card is charged, so the merchant nets the shipping price.
     */
    val surcharge: SurchargeConfig = SurchargeConfig.DISABLED,
) {
    val shippingEnabled: Boolean get() = easyPostApiKey != null
    val paymentsEnabled: Boolean get() = payments != null

    companion object {
        /** How the [ShipKitConfig] reads the environment (overridable for tests). */
        fun interface Env {
            operator fun get(name: String): String?
        }

        private val SYSTEM_ENV = Env { System.getenv(it) }

        fun fromEnv(env: Env = SYSTEM_ENV): ShipKitConfig {
            val port = env["SHIPKIT_PORT"]?.toIntOrNull() ?: 8080
            val baseUrl =
                env["SHIPKIT_BASE_URL"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "http://localhost:8080"
            val corsOrigins =
                env["SHIPKIT_CORS_ORIGINS"]?.trim()?.takeIf { it.isNotEmpty() } ?: "*"

            val easyPostApiKey = env["EASYPOST_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }
            val easyPostWebhookSecret =
                env["EASYPOST_WEBHOOK_SECRET"]?.trim()?.takeIf { it.isNotEmpty() }

            // Lifted Payments 3-D Secure is enabled only when fully configured.
            val bearer = env["LIFTED_PAYMENTS_BEARER"]?.trim()?.takeIf { it.isNotEmpty() }
            val terminalId = env["LIFTED_PAYMENTS_TERMINAL_ID"]?.toIntOrNull()
            val dbaId = env["LIFTED_PAYMENTS_DBA_ID"]?.toIntOrNull()
            // live | sandbox — selects the default gateway/dashboard bases. An
            // explicit LIFTED_PAYMENTS_API_BASE / _DASHBOARD_BASE still wins.
            val paymentsEnv = PaymentsEnvironment.fromString(env["LIFTED_PAYMENTS_ENV"])
            val payments =
                if (bearer != null && terminalId != null && dbaId != null) {
                    LiftedPaymentsConfig(
                        bearerToken = bearer,
                        terminalId = terminalId,
                        dbaId = dbaId,
                        environment = paymentsEnv,
                        cardEntryMode = CardEntryMode.fromString(env["LIFTED_PAYMENTS_CARD_ENTRY"]),
                        gatewayBaseUrl =
                            env["LIFTED_PAYMENTS_API_BASE"]?.trim()?.takeIf { it.isNotEmpty() }
                                ?: LiftedPaymentsConfig.defaultGatewayBase(paymentsEnv),
                        dashboardBaseUrl =
                            env["LIFTED_PAYMENTS_DASHBOARD_BASE"]?.trim()?.takeIf {
                                it.isNotEmpty()
                            }
                                ?: LiftedPaymentsConfig.defaultDashboardBase(paymentsEnv),
                        hostedFormPath =
                            env["LIFTED_PAYMENTS_HOSTED_FORM_PATH"]?.trim()?.takeIf {
                                it
                                    .isNotEmpty()
                            }
                                ?: LiftedPaymentsConfig.DEFAULT_HOSTED_FORM_PATH,
                    )
                } else {
                    null
                }

            val adminPhones =
                env["SHIPKIT_ADMIN_PHONES"]
                    ?.split(",")
                    ?.map { PhoneNumbers.normalize(it) }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()

            val sms =
                SmsConfig(
                    enabled = env["SHIPKIT_SMS_ENABLED"].toBoolean(),
                    accountSid = env["TWILIO_ACCOUNT_SID"]?.trim(),
                    authToken = env["TWILIO_AUTH_TOKEN"]?.trim(),
                    verifyServiceSid = env["TWILIO_VERIFY_SERVICE_SID"]?.trim(),
                )

            val backend =
                when (env["SHIPKIT_STORE"]?.trim()?.lowercase()) {
                    "postgres" -> StoreBackend.POSTGRES
                    else -> StoreBackend.MEMORY
                }
            val db =
                if (backend == StoreBackend.POSTGRES) {
                    DbConfig(
                        jdbcUrl =
                            env["SHIPKIT_DATABASE_URL"]
                                ?: error("SHIPKIT_STORE=postgres requires SHIPKIT_DATABASE_URL"),
                        username =
                            env["SHIPKIT_DB_USER"]
                                ?: error("SHIPKIT_STORE=postgres requires SHIPKIT_DB_USER"),
                        password =
                            env["SHIPKIT_DB_PASSWORD"]
                                ?: error("SHIPKIT_STORE=postgres requires SHIPKIT_DB_PASSWORD"),
                    )
                } else {
                    null
                }

            // Adoption tier + buyer-surcharge toggle (tier 2). The surcharge rate is
            // the fixed Lifted 3DS merchant-account cost (3.75% + $0.15); only the
            // on/off toggle is environment-driven.
            val tier = TierMode.fromString(env["SHIPKIT_TIER"])
            val surcharge =
                if (env["SHIPKIT_SURCHARGE_ENABLED"].toBoolean()) {
                    SurchargeConfig.STANDARD
                } else {
                    SurchargeConfig.DISABLED
                }

            return ShipKitConfig(
                port = port,
                baseUrl = baseUrl,
                corsOrigins = corsOrigins,
                easyPostApiKey = easyPostApiKey,
                easyPostWebhookSecret = easyPostWebhookSecret,
                payments = payments,
                adminPhoneWhitelist = adminPhones,
                sms = sms,
                storeBackend = backend,
                db = db,
                tier = tier,
                surcharge = surcharge,
            )
        }

        private fun String?.toBoolean(): Boolean =
            this?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
    }
}
