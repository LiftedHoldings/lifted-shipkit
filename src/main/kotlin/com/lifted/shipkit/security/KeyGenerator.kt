package com.lifted.shipkit.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Mints and verifies ShipKit API keys.
 *
 * A ShipKit API key is a single opaque secret shaped like `sk_live_<random>` (or
 * `sk_test_<random>` for a non-production key). The random portion is 32 bytes
 * of cryptographically secure entropy (256 bits), URL-safe Base64 encoded — far
 * beyond brute-force reach.
 *
 * ### Security model
 * - **Shown once.** The full plaintext key is returned exactly once, at mint
 *   time ([MintedKey.plaintext]); it is never persisted and cannot be recovered.
 * - **Hash at rest.** Only the SHA-256 hash of the key ([ApiKeyRecord.hash]) plus
 *   non-secret metadata (label, a short display [ApiKeyRecord.prefix], timestamps,
 *   revoked flag) are stored. A leaked database yields no usable keys.
 * - **Constant-time verify.** [verify] compares digests with
 *   [MessageDigest.isEqual], which does not short-circuit on the first differing
 *   byte, so an attacker cannot time their way to a valid key.
 * - **Never logged.** No method here logs, prints, or returns the full key except
 *   the deliberate one-time [MintedKey.plaintext]; callers must never log it
 *   either. Use [ApiKeyRecord.prefix] to identify a key in logs.
 *
 * This object is stateless and therefore thread-safe. Persistence is the concern
 * of [ApiKeyStore].
 */
object KeyGenerator {
    /** Environment a key is scoped to; the middle token of the key prefix. */
    enum class Mode(
        val token: String,
    ) {
        /** Production keys: `…_live_…`. */
        LIVE("live"),

        /** Non-production keys: `…_test_…`. */
        TEST("test"),
        ;

        companion object {
            /**
             * Resolve a [Mode] from a loose string (`"live"`/`"test"`, case- and
             * whitespace-insensitive). Anything unrecognized defaults to [LIVE].
             */
            fun fromString(raw: String?): Mode =
                when (raw?.trim()?.lowercase()) {
                    "test" -> TEST
                    else -> LIVE
                }

            /** The mode a full key belongs to, inferred from its `_live_`/`_test_` token. */
            fun of(key: String): Mode? =
                when {
                    key.contains("_${LIVE.token}_") -> LIVE
                    key.contains("_${TEST.token}_") -> TEST
                    else -> null
                }
        }
    }

    /**
     * Capability scope of a key — the credential split that keeps a key safe to
     * publish in a browser (`pk_…`) distinct from a full server key (`sk_…`).
     *
     *  - [SECRET] (`sk_…`) — full access to every `/api` route: rating, the
     *    3-D Secure payment flow, **and** privileged actions (direct label buy,
     *    batch, customs/EndShipper, markup writes, maintenance). Server-only; keep
     *    it off any page.
     *  - [PUBLISHABLE] (`pk_…`) — the browser-embeddable widget key. It may run the
     *    customer flow only (address verify, rate, open a payment session, poll its
     *    status, and buy the label the customer just paid for). It **cannot** buy a
     *    free label, rewrite pricing, or purge data, so exposing it in page source
     *    drains nothing.
     */
    enum class Scope(
        val token: String,
    ) {
        /** Full server key: `sk_…`. */
        SECRET("sk"),

        /** Browser-publishable widget key: `pk_…`. */
        PUBLISHABLE("pk"),
        ;

        companion object {
            /**
             * Resolve a [Scope] from a loose string. `"publishable"`/`"public"`/
             * `"pk"` → [PUBLISHABLE]; anything else (including null) → [SECRET], so
             * a key is only ever browser-publishable when explicitly requested.
             */
            fun fromString(raw: String?): Scope =
                when (raw?.trim()?.lowercase()) {
                    "publishable", "public", "pk" -> PUBLISHABLE
                    else -> SECRET
                }

            /** The scope a full key belongs to, inferred from its `sk_`/`pk_` prefix. */
            fun of(key: String): Scope? =
                when {
                    key.startsWith("${PUBLISHABLE.token}_") -> PUBLISHABLE
                    key.startsWith("${SECRET.token}_") -> SECRET
                    else -> null
                }
        }
    }

    /**
     * The result of minting a key. [plaintext] is the only time the full secret
     * exists outside the caller — surface it to the operator once, then discard.
     * [record] is what gets persisted (hash + metadata, no secret).
     */
    data class MintedKey(
        /** Full `sk_live_…`/`sk_test_…` secret. Shown ONCE; never stored or logged. */
        val plaintext: String,
        /** The persistable record (SHA-256 hash + non-secret metadata). */
        val record: ApiKeyRecord,
    )

    /** Bytes of entropy in the random portion of a key (256 bits). */
    private const val ENTROPY_BYTES = 32

    /** Characters of the random portion echoed into the display [ApiKeyRecord.prefix]. */
    private const val DISPLAY_CHARS = 6

    private val secureRandom = SecureRandom()
    private val base64Url = Base64.getUrlEncoder().withoutPadding()

    /**
     * Mint a brand-new API key for [label] in the given [mode] and [scope]. The
     * key is shaped `{scope}_{mode}_{random}` (e.g. `sk_live_…`, `pk_test_…`).
     *
     * @param label operator-facing name for the key (e.g. `"prod-checkout"`); not secret.
     * @param mode  [Mode.LIVE] (default) or [Mode.TEST].
     * @param scope [Scope.SECRET] (default; full server key) or [Scope.PUBLISHABLE]
     *   (browser-embeddable widget key). Defaults to SECRET so a key is never
     *   accidentally minted with browser-publishable reach.
     * @return a [MintedKey] whose [MintedKey.plaintext] must be shown to the
     *   operator exactly once and then dropped.
     */
    fun mint(
        label: String,
        mode: Mode = Mode.LIVE,
        scope: Scope = Scope.SECRET,
    ): MintedKey {
        val raw = ByteArray(ENTROPY_BYTES).also { secureRandom.nextBytes(it) }
        val random = base64Url.encodeToString(raw)
        val prefix = "${scope.token}_${mode.token}_"
        val plaintext = prefix + random
        val record =
            ApiKeyRecord(
                id = UUID.randomUUID().toString(),
                label = label,
                hash = sha256Hex(plaintext),
                prefix = prefix + random.take(DISPLAY_CHARS),
                createdAt = Instant.now(),
                lastUsedAt = null,
                revoked = false,
                scope = scope,
            )
        return MintedKey(plaintext = plaintext, record = record)
    }

    /**
     * SHA-256 of [value], lower-case hex. Used to hash a key for storage and to
     * hash a presented key for a constant-time lookup/compare.
     */
    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Constant-time check that [candidate] (a full presented key) hashes to
     * [storedHash]. Uses [MessageDigest.isEqual] so comparison time does not leak
     * how many leading bytes matched.
     *
     * @param candidate  the key presented by the caller.
     * @param storedHash the SHA-256 hex hash held at rest for a known key.
     * @return `true` iff they correspond to the same secret.
     */
    fun verify(
        candidate: String,
        storedHash: String,
    ): Boolean =
        MessageDigest.isEqual(
            sha256Hex(candidate).toByteArray(Charsets.US_ASCII),
            storedHash.toByteArray(Charsets.US_ASCII),
        )
}
