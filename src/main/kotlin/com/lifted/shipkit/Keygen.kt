package com.lifted.shipkit

import com.lifted.shipkit.config.ShipKitConfig
import com.lifted.shipkit.security.ApiKeyStore
import com.lifted.shipkit.security.KeyGenerator

/**
 * Command-line minter for ShipKit API keys.
 *
 * Mints a key into the configured store (memory or Postgres — resolved from the
 * exact same environment the server reads via [ShipKitConfig]) and prints the
 * full secret to stdout **once**. Nothing else ever surfaces the plaintext.
 *
 * ### Usage
 * Via the Gradle task (preferred):
 * ```
 * ./gradlew shipkitKeygen -Plabel=prod-checkout          # a sk_live_ key
 * ./gradlew shipkitKeygen -Plabel=local-dev -Ptest       # a sk_test_ key
 * ```
 * Or directly on the classpath:
 * ```
 * java -cp shipkit-all.jar com.lifted.shipkit.KeygenKt --label=prod-checkout [--test]
 * ```
 *
 * For a durable key set `SHIPKIT_STORE=postgres` (+ `SHIPKIT_DATABASE_URL` and DB
 * credentials) so the key persists and the running server can see it; with the
 * default in-memory store the key lives only for this process and is useful only
 * for scripted tests.
 *
 * @param args `--label=<name>` (or `--label <name>`) is required; `--test` mints
 *   a `…_test_` key instead of `…_live_`; `--publishable` mints a browser-safe
 *   `pk_…` widget key instead of a full `sk_…` server key.
 */
fun main(args: Array<String>) {
    val label =
        argValue(args, "--label")
            ?: System.getProperty("label")?.trim()?.takeIf { it.isNotEmpty() }
            ?: run {
                System.err.println(
                    "error: a label is required, e.g. ./gradlew shipkitKeygen -Plabel=prod-checkout",
                )
                return
            }

    val mode =
        if (args.any { it == "--test" } || System.getProperty("test") != null) {
            KeyGenerator.Mode.TEST
        } else {
            KeyGenerator.Mode.LIVE
        }

    val scope =
        if (args.any { it == "--publishable" } || System.getProperty("publishable") != null) {
            KeyGenerator.Scope.PUBLISHABLE
        } else {
            KeyGenerator.Scope.SECRET
        }

    val config = ShipKitConfig.fromEnv()
    val store = ApiKeyStore.create(config.storeBackend, config.db)
    try {
        val minted = KeyGenerator.mint(label = label, mode = mode, scope = scope)
        store.add(minted.record)

        val banner = "=".repeat(72)
        println(banner)
        println("ShipKit API key minted")
        println(banner)
        println("  id       : ${minted.record.id}")
        println("  label    : ${minted.record.label}")
        println("  prefix   : ${minted.record.prefix}")
        println("  store    : ${config.storeBackend}")
        println()
        println("  API key (shown ONCE — copy it now, it cannot be retrieved again):")
        println()
        println("      ${minted.plaintext}")
        println()
        println("  Send it as the  ShipKit-Api-Key  header on every /api/* request.")
        if (config.storeBackend.name == "MEMORY") {
            println()
            println(
                "  NOTE: SHIPKIT_STORE=memory — this key lives only for this process and " +
                    "will NOT be visible to a separately running server. Use " +
                    "SHIPKIT_STORE=postgres for a durable key.",
            )
        }
        println(banner)
    } finally {
        store.close()
    }
}

/** Read `--flag=value` or `--flag value` from [args]; returns `null` if absent/blank. */
private fun argValue(
    args: Array<String>,
    flag: String,
): String? {
    args.firstOrNull { it.startsWith("$flag=") }?.let {
        return it.substringAfter("=").trim().takeIf { v -> v.isNotEmpty() }
    }
    val idx = args.indexOf(flag)
    if (idx >= 0 && idx + 1 < args.size) {
        return args[idx + 1].trim().takeIf { it.isNotEmpty() }
    }
    return null
}
