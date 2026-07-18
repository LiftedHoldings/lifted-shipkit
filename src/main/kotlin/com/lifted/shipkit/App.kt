package com.lifted.shipkit

import com.lifted.shipkit.config.ShipKitConfig
import com.lifted.shipkit.events.UsageEventEmitter
import com.lifted.shipkit.http.ApiKeyRejected
import com.lifted.shipkit.http.Handlers
import com.lifted.shipkit.payments.LiftedPaymentsClient
import com.lifted.shipkit.security.ApiKeyStore
import com.lifted.shipkit.shipping.EasyPostService
import com.lifted.shipkit.sms.SmsVerifier
import com.lifted.shipkit.store.LabelStore
import io.javalin.Javalin
import io.javalin.http.staticfiles.Location
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.lifted.shipkit.App")

/**
 * ShipKit — the open shipping toolkit, secured by Lifted Payments 3-D Secure.
 *
 * Boots a Javalin server, wires the configured services, and registers the API.
 * Everything is driven by the environment (see [ShipKitConfig]); there are no
 * secrets in this code. Missing optional integrations degrade gracefully.
 */
fun main() {
    val config = ShipKitConfig.fromEnv()

    val store = LabelStore.create(config.storeBackend, config.db)
    val apiKeys = ApiKeyStore.create(config.storeBackend, config.db)
    val easyPost = config.easyPostApiKey?.let { EasyPostService(it) }
    val payments = config.payments?.let { LiftedPaymentsClient(it) }
    val sms = SmsVerifier.create(config.sms)
    val events = UsageEventEmitter(config.usageEvents)
    val handlers = Handlers(config, store, easyPost, payments, sms, apiKeys, events)

    val app = buildApp(config, handlers).start(config.port)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            events.close()
            store.close()
            apiKeys.close()
        },
    )

    log.info(
        "ShipKit started on port {} (shipping={}, payments={}, sms={}, store={}, events={})",
        config.port,
        if (config.shippingEnabled) "on" else "off",
        if (config.paymentsEnabled) "on" else "off",
        if (sms.enabled) "on" else "off",
        config.storeBackend,
        if (events.enabled) "on" else "off",
    )
}

/**
 * Build the fully-wired (but **not yet started**) Javalin application: static
 * files, CORS, the 404 page, the API-key gate, and every route. Extracted from
 * [main] so the in-process integration tests ([io.javalin.testtools.JavalinTest])
 * can exercise the real route graph and API-key filter against fake service
 * clients — with no port binding and no environment.
 */
internal fun buildApp(
    config: ShipKitConfig,
    handlers: Handlers,
): Javalin {
    val app =
        Javalin.create { cfg ->
            cfg.staticFiles.add { staticFiles ->
                staticFiles.hostedPath = "/"
                staticFiles.directory = "/public"
                staticFiles.location = Location.CLASSPATH
            }
            // CORS for the embeddable widget. `*` in dev; explicit origins in prod.
            cfg.plugins.enableCors { cors ->
                cors.add { rule ->
                    if (config.corsOrigins.trim() == "*") {
                        rule.anyHost()
                    } else {
                        config.corsOrigins
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .forEach { rule.allowHost(it) }
                    }
                }
            }
        }

    app.error(404) { ctx ->
        ctx.contentType("text/html")
        // `use` closes the classpath stream deterministically (no FD leak per 404).
        val page =
            ctx.javaClass
                .getResourceAsStream("/public/404.html")
                ?.use { it.bufferedReader().readText() }
        ctx.result(page ?: "404 - Not found")
    }

    registerRoutes(app, handlers, config)
    return app
}

private fun registerRoutes(
    app: Javalin,
    h: Handlers,
    config: ShipKitConfig,
) {
    // API-key gate: every /api/* route (except health + the HMAC-verified
    // EasyPost webhook) requires a valid `ShipKit-Api-Key` of sufficient scope.
    // authenticateApiKey throws ApiKeyRejected on failure, which halts the
    // pipeline (the endpoint never runs) and is rendered as a canonical 401/403.
    app.before("/api/*") { ctx -> h.authenticateApiKey(ctx) }
    app.exception(ApiKeyRejected::class.java) { e, ctx ->
        ctx
            .status(e.statusCode)
            .json(mapOf("success" to false, "error" to (e.message ?: "Unauthorized")))
    }

    // OpenAPI 3.1 spec + a self-contained, dependency-free API reference page.
    // Both live outside /api so they are reachable without a key.
    app.get("/openapi.yaml") { ctx ->
        val spec = readClasspath("/openapi.yaml")
        if (spec != null) {
            ctx.contentType("application/yaml").result(spec)
        } else {
            ctx.status(404).result("openapi.yaml not found")
        }
    }
    app.get("/docs") { ctx ->
        val page = readClasspath("/docs.html")
        if (page != null) {
            ctx.contentType("text/html").result(page)
        } else {
            ctx.status(404).result("docs not found")
        }
    }

    // Health / readiness
    app.get("/api/health") { ctx ->
        ctx.json(
            mapOf(
                "status" to "ok",
                "shipping" to config.shippingEnabled,
                "payments" to config.paymentsEnabled,
            ),
        )
    }

    // Shipping
    app.post("/api/address/verify") { h.verifyAddress(it) }
    app.post("/api/shipment/create") { h.createShipment(it) }
    app.post("/api/shipment/smartrates") { h.smartRates(it) }
    app.post("/api/shipment/buy") { h.buyLabel(it) }
    app.post("/api/batch/create") { h.batchCreate(it) }
    app.post("/api/scanform/create") { h.scanFormCreate(it) }
    app.post("/api/customs/create") { h.customsCreate(it) }
    app.post("/api/webhook/easypost") { h.webhook(it) }

    // EndShipper (USPS compliance)
    app.get("/api/endshipper/get") { h.getEndShipper(it) }
    app.post("/api/endshipper/create") { h.createEndShipper(it) }

    // Payments — Lifted Payments 3-D Secure
    app.post("/api/payment/session") { h.paymentSession(it) }
    app.get("/api/payment/return/{sessionId}") { h.paymentReturn(it) }
    app.get("/api/payment/status/{sessionId}") { h.paymentStatus(it) }
    app.post("/api/payment/purchase-label/{sessionId}") { h.purchaseLabelForSessionEndpoint(it) }

    // Frictionless / card-on-file (SPEC_R3 §5) — account-gated (secret key +
    // frictionlessAllowed); refused for self-host / BYO where forced 3DS is the only mode.
    app.post("/api/payment/save-card") { h.saveCardOnFile(it) }
    app.post("/api/payment/charge-saved-card") { h.chargeSavedCard(it) }

    // Tier + pricing model (honest three-tier story; publishable-key readable)
    app.get("/api/config/tier") { h.getTierConfig(it) }

    // Markup configuration
    app.get("/api/config/markup") { h.getMarkupConfig(it) }
    app.post("/api/config/markup") { h.updateMarkupConfig(it) }

    // Managed key provisioning — control-plane bearer token ONLY
    // (SHIPKIT_MANAGED_CONFIG_TOKEN; disabled/fail-closed when unset)
    app.post("/api/config/keys") { h.managedCreateApiKey(it) }
    app.get("/api/config/keys") { h.managedListApiKeys(it) }
    app.delete("/api/config/keys/{id}") { h.managedRevokeApiKey(it) }

    // Labels
    app.get("/api/label/{labelId}") { h.getLabel(it) }
    app.get("/api/label/session/{sessionId}") { h.getLabelBySession(it) }
    app.delete("/api/label/session/{sessionId}/shred") { h.shredLabel(it) }

    // Tracking status (persisted from verified EasyPost tracker webhooks)
    app.get("/api/tracking/{trackingCode}") { h.getTracking(it) }

    // Maintenance
    app.post("/api/admin/cleanup") { h.cleanupExpired(it) }

    // Admin + purchase history (gated by optional SMS verification)
    app.post("/api/verification/start") { h.startVerification(it) }
    app.post("/api/verification/check") { h.checkVerification(it) }
    app.get("/api/history/labels") { h.getLabelHistory(it) }
    app.get("/api/admin/labels") { h.getAllLabels(it) }

    // API-key management (admin-gated; also behind the /api/* key requirement)
    app.post("/api/keys") { h.createApiKey(it) }
    app.get("/api/keys") { h.listApiKeys(it) }
    app.delete("/api/keys/{id}") { h.revokeApiKey(it) }
}

/** Read a UTF-8 text resource from the classpath, or `null` if it is absent. */
private fun readClasspath(path: String): String? =
    object {}.javaClass.getResourceAsStream(path)?.use { it.bufferedReader().readText() }
