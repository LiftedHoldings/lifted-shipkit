package com.lifted.shipkit.model

/**
 * The three ShipKit adoption tiers, selected once at startup from the
 * environment (`SHIPKIT_TIER`). The tier is *informational* to the running
 * server — it does not gate any endpoint — but it lets a deployment declare
 * which commercial model it runs under, so the widget/demo and operators can
 * surface the honest pricing story (see `/api/config/tier`).
 *
 * 1. [SELF_HOST] — DIY, free MIT. Bring your OWN payments + your OWN EasyPost.
 *    Full control, heaviest dev work.
 * 2. [MERCHANT] — apply for a Lifted 3-D Secure merchant account
 *    (https://liftedholdings.com/payments). Cost is the merchant account only —
 *    **3.75% + $0.15 / transaction + $25 / month** — and that per-transaction
 *    fee can be surcharged to the buyer via [SurchargeConfig]. You host or Lifted
 *    hosts; both are free. Bring your own EasyPost.
 * 3. [MANAGED] — one JS snippet on Lifted's rails: our 3DS account + our
 *    EasyPost, zero infrastructure, free hosting. Lifted earns by marking up the
 *    shipping rates ([MarkupConfig]) rather than charging the merchant.
 *
 * There is also a services upsell (custom integration / bespoke builds) reached
 * at support@liftedholdings.com — that is a sales channel, not a runtime tier.
 */
enum class TierMode(
    val slug: String,
    val label: String,
) {
    SELF_HOST("selfhost", "Self-host (DIY · free MIT)"),
    MERCHANT("merchant", "Lifted 3-D Secure merchant account"),
    MANAGED("managed", "Fully managed (plug & play)"),
    ;

    companion object {
        /**
         * Parse the `SHIPKIT_TIER` value (case-insensitive; hyphens/underscores
         * ignored so `self-host`, `self_host`, and `selfhost` all resolve).
         * Defaults to [SELF_HOST] — the free, no-account starting point — for any
         * blank or unrecognized value.
         */
        fun fromString(raw: String?): TierMode {
            val norm =
                raw
                    ?.trim()
                    ?.lowercase()
                    ?.replace("-", "")
                    ?.replace("_", "") ?: ""
            return when (norm) {
                "merchant", "merchantaccount", "tier2", "2" -> MERCHANT
                "managed", "managedkey", "tier3", "3" -> MANAGED
                else -> SELF_HOST
            }
        }
    }
}
