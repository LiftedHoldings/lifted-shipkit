package com.lifted.shipkit.security

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A coarse, in-memory fixed-window rate limiter that guards the
 * browser-published (publishable-key) paid paths against "denial-of-wallet"
 * abuse.
 *
 * A publishable key (`pk_…`) is embedded in page source **by design** (the widget
 * reads it from the DOM), so anyone can lift it and loop the EasyPost-billed
 * address-verify / shipment-create calls or the payment-session path to run up
 * the merchant's upstream bill and saturate the server — at zero cost to the
 * attacker. Secret keys are server-side and trusted, so they are not limited here.
 *
 * The bucket key is per **(API-key id + client IP)**: [permitsPerMinute] requests
 * are allowed per rolling 60-second window; the next request in the same window is
 * refused (the caller maps it to HTTP 429). A non-positive [permitsPerMinute]
 * disables limiting entirely. State is bounded — stale windows are pruned
 * opportunistically so key/IP churn cannot grow the map without limit.
 */
class RateLimiter(
    private val permitsPerMinute: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Window(
        @Volatile var startMillis: Long,
        val count: AtomicInteger,
    )

    private val windows = ConcurrentHashMap<String, Window>()

    /** Whether limiting is active. `false` short-circuits every check to allow. */
    val enabled: Boolean get() = permitsPerMinute > 0

    /**
     * Record one hit for [key]. Returns `true` when the caller is within budget for
     * the current window, `false` when it has exceeded [permitsPerMinute] and should
     * be refused with `429`. Thread-safe: the window swap is an atomic per-key
     * [ConcurrentHashMap.compute], and the counter is atomic.
     */
    fun tryAcquire(key: String): Boolean {
        if (permitsPerMinute <= 0) return true
        val now = clock()
        // Opportunistic prune so the map can't grow without bound under key/IP churn.
        if (windows.size > MAX_TRACKED) prune(now)
        val window =
            windows.compute(key) { _, existing ->
                if (existing == null || now - existing.startMillis >= WINDOW_MILLIS) {
                    Window(now, AtomicInteger(0))
                } else {
                    existing
                }
            }!!
        return window.count.incrementAndGet() <= permitsPerMinute
    }

    private fun prune(now: Long) {
        windows.entries.removeIf { now - it.value.startMillis >= WINDOW_MILLIS }
    }

    private companion object {
        private const val WINDOW_MILLIS = 60_000L

        /** Cap on tracked (key, IP) windows before an opportunistic prune runs. */
        private const val MAX_TRACKED = 10_000
    }
}
