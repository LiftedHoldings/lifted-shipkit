package com.lifted.shipkit.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Phone normalization is used for admin auth and label ownership, so it is pinned. */
class PhoneNumbersTest {
    @Test
    fun `normalize strips formatting and a leading US country code`() {
        assertEquals("5551234567", PhoneNumbers.normalize("+1 (555) 123-4567"))
        assertEquals("5551234567", PhoneNumbers.normalize("15551234567"))
        assertEquals("5551234567", PhoneNumbers.normalize("555.123.4567"))
        // A non-11-digit string keeps all its digits (no country-code stripping).
        assertEquals("445551234567", PhoneNumbers.normalize("445551234567"))
    }

    @Test
    fun `E164 and display formats derive from the normalized number`() {
        assertEquals("+15551234567", PhoneNumbers.toE164("(555) 123-4567"))
        assertEquals("555-123-4567", PhoneNumbers.toDisplay("15551234567"))
        // A number that is not 10 digits falls back to the raw normalized digits.
        assertEquals("12345", PhoneNumbers.toDisplay("12345"))
    }

    @Test
    fun `isValid is true only for exactly ten digits`() {
        assertTrue(PhoneNumbers.isValid("+1 555 123 4567"))
        assertFalse(PhoneNumbers.isValid("555 123"))
        assertFalse(PhoneNumbers.isValid(""))
    }
}
