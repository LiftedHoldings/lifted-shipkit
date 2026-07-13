package com.lifted.shipkit.security

import com.lifted.shipkit.store.PgTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [PostgresApiKeyStore] against a real **postgres:16** (Testcontainers, or an
 * external DB via `-Dshipkit.test.pg.url`). Skipped gracefully when neither is
 * present (CONTRACTS §8). Only the SHA-256 hash + metadata are ever stored —
 * never the plaintext key.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresApiKeyStoreTest {
    private lateinit var handle: PgTestSupport.Handle
    private lateinit var store: PostgresApiKeyStore

    @BeforeAll
    fun setUp() {
        handle = PgTestSupport.acquire()
        store = PostgresApiKeyStore(handle.db)
        store.start()
    }

    @AfterAll
    fun tearDown() {
        if (this::store.isInitialized) store.close()
        if (this::handle.isInitialized) handle.close()
    }

    @Test
    fun `add then find by hash round-trips only the hash and metadata`() {
        val minted = KeyGenerator.mint("prod-checkout")
        store.add(minted.record)

        val found = store.findByHash(minted.record.hash)
        assertNotNull(found)
        assertEquals(minted.record.id, found!!.id)
        assertEquals("prod-checkout", found.label)
        // The stored hash is the SHA-256 of the plaintext — never the plaintext itself.
        assertEquals(KeyGenerator.sha256Hex(minted.plaintext), found.hash)
        assertFalse(found.hash.contains(minted.plaintext))
        assertNull(store.findByHash("no-such-hash"))
    }

    @Test
    fun `touchLastUsed stamps last_used_at`() {
        val minted = KeyGenerator.mint("stamp")
        store.add(minted.record)
        assertNull(store.get(minted.record.id)!!.lastUsedAt)
        store.touchLastUsed(minted.record.id)
        assertNotNull(store.get(minted.record.id)!!.lastUsedAt)
    }

    @Test
    fun `revoke is idempotent and a revoked key still resolves as revoked`() {
        val minted = KeyGenerator.mint("revoke-me")
        store.add(minted.record)

        assertTrue(store.revoke(minted.record.id))
        assertFalse(store.revoke(minted.record.id), "second revoke is a no-op")
        assertFalse(store.revoke("unknown-id"))

        val found = store.findByHash(minted.record.hash)
        assertNotNull(found)
        assertTrue(found!!.revoked, "the auth filter must still see it, flagged revoked")
    }

    @Test
    fun `listAll returns newest first`() {
        val a = KeyGenerator.mint("list-a").record
        Thread.sleep(5)
        val b = KeyGenerator.mint("list-b").record
        store.add(a)
        store.add(b)
        val labels = store.listAll().map { it.label }
        assertTrue(labels.indexOf("list-b") < labels.indexOf("list-a"), "newest first")
    }
}
