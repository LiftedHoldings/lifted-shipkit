package com.lifted.shipkit.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The three adoption tiers parse leniently and default to the free self-host tier. */
class TierModeTest {
    @Test
    fun `parses the canonical slugs case- and separator-insensitively`() {
        assertEquals(TierMode.SELF_HOST, TierMode.fromString("selfhost"))
        assertEquals(TierMode.SELF_HOST, TierMode.fromString("self-host"))
        assertEquals(TierMode.SELF_HOST, TierMode.fromString("SELF_HOST"))
        assertEquals(TierMode.MERCHANT, TierMode.fromString("merchant"))
        assertEquals(TierMode.MERCHANT, TierMode.fromString("Tier2"))
        assertEquals(TierMode.MANAGED, TierMode.fromString("managed"))
        assertEquals(TierMode.MANAGED, TierMode.fromString("3"))
    }

    @Test
    fun `blank or unknown defaults to self-host`() {
        assertEquals(TierMode.SELF_HOST, TierMode.fromString(null))
        assertEquals(TierMode.SELF_HOST, TierMode.fromString(""))
        assertEquals(TierMode.SELF_HOST, TierMode.fromString("enterprise"))
    }

    @Test
    fun `each tier carries a human label and stable slug`() {
        assertEquals("selfhost", TierMode.SELF_HOST.slug)
        assertEquals("merchant", TierMode.MERCHANT.slug)
        assertEquals("managed", TierMode.MANAGED.slug)
        assertEquals("Lifted 3-D Secure merchant account", TierMode.MERCHANT.label)
    }
}
