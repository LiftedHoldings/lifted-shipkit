package com.lifted.shipkit.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behavioural contract of the in-memory API-key store: lookup-by-hash,
 * last-used stamping, idempotent revoke, and that a revoked key still resolves
 * (so the filter can reject it) but is flagged revoked.
 */
class InMemoryApiKeyStoreTest {
    private fun store() = InMemoryApiKeyStore()

    @Test
    fun `add then find by hash round-trips the record`() {
        val s = store()
        val minted = KeyGenerator.mint("prod")
        s.add(minted.record)

        val found = s.findByHash(minted.record.hash)
        assertNotNull(found)
        assertEquals(minted.record.id, found!!.id)
        assertNull(s.findByHash("deadbeef"), "unknown hash resolves to null")
    }

    @Test
    fun `touchLastUsed stamps the record`() {
        val s = store()
        val minted = KeyGenerator.mint("prod")
        s.add(minted.record)
        assertNull(s.get(minted.record.id)!!.lastUsedAt)

        s.touchLastUsed(minted.record.id)
        assertNotNull(s.get(minted.record.id)!!.lastUsedAt)
    }

    @Test
    fun `revoke is idempotent and flips the flag`() {
        val s = store()
        val minted = KeyGenerator.mint("prod")
        s.add(minted.record)

        assertTrue(s.revoke(minted.record.id), "first revoke returns true")
        assertFalse(s.revoke(minted.record.id), "second revoke returns false (already revoked)")
        assertFalse(s.revoke("no-such-id"), "unknown id returns false")

        // A revoked key still resolves by hash so the auth filter can reject it.
        val found = s.findByHash(minted.record.hash)
        assertNotNull(found)
        assertTrue(found!!.revoked)
    }

    @Test
    fun `listAll returns newest first`() {
        val s = store()
        val a = KeyGenerator.mint("a").record
        Thread.sleep(2)
        val b = KeyGenerator.mint("b").record
        s.add(a)
        s.add(b)

        val ids = s.listAll().map { it.id }
        assertEquals(listOf(b.id, a.id), ids)
    }
}
