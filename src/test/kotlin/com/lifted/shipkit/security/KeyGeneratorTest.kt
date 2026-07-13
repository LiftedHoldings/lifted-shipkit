package com.lifted.shipkit.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The key generator is security-critical: format, hashing, constant-time verify,
 * and the shown-once/hash-at-rest contract are all pinned here.
 */
class KeyGeneratorTest {
    @Test
    fun `live keys carry the sk_live prefix and test keys sk_test`() {
        val live = KeyGenerator.mint("prod", KeyGenerator.Mode.LIVE)
        val test = KeyGenerator.mint("dev", KeyGenerator.Mode.TEST)

        assertTrue(live.plaintext.startsWith("sk_live_"), "live key must start with sk_live_")
        assertTrue(test.plaintext.startsWith("sk_test_"), "test key must start with sk_test_")
        assertTrue(live.record.prefix.startsWith("sk_live_"))
        assertEquals(KeyGenerator.Mode.LIVE, KeyGenerator.Mode.of(live.plaintext))
        assertEquals(KeyGenerator.Mode.TEST, KeyGenerator.Mode.of(test.plaintext))
    }

    @Test
    fun `minted record stores only a hash, never the plaintext`() {
        val minted = KeyGenerator.mint("prod")

        // The record must not carry the secret in any field.
        assertNotEquals(minted.plaintext, minted.record.hash)
        assertFalse(minted.record.hash.contains(minted.plaintext))
        assertFalse(minted.record.prefix == minted.plaintext)
        // Hash is the SHA-256 hex of the plaintext (64 hex chars).
        assertEquals(64, minted.record.hash.length)
        assertEquals(KeyGenerator.sha256Hex(minted.plaintext), minted.record.hash)
    }

    @Test
    fun `every minted key is unique`() {
        val keys = (1..500).map { KeyGenerator.mint("k").plaintext }.toSet()
        assertEquals(500, keys.size, "minted keys must not collide")
    }

    @Test
    fun `verify accepts the real key and rejects a wrong one`() {
        val minted = KeyGenerator.mint("prod")

        assertTrue(KeyGenerator.verify(minted.plaintext, minted.record.hash))
        assertFalse(KeyGenerator.verify(minted.plaintext + "x", minted.record.hash))
        assertFalse(KeyGenerator.verify("sk_live_totally_wrong", minted.record.hash))
    }

    @Test
    fun `mode resolution defaults to live for unknown input`() {
        assertEquals(KeyGenerator.Mode.LIVE, KeyGenerator.Mode.fromString(null))
        assertEquals(KeyGenerator.Mode.LIVE, KeyGenerator.Mode.fromString("prod"))
        assertEquals(KeyGenerator.Mode.TEST, KeyGenerator.Mode.fromString(" TEST "))
    }
}
