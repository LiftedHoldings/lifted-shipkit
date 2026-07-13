package com.lifted.shipkit.util

/**
 * Phone number normalization and formatting.
 *
 * - Storage format: 10 digits, no country code (e.g. "5551234567")
 * - E.164 format:   +1 prefixed (e.g. "+15551234567")
 * - Display format: xxx-xxx-xxxx (e.g. "555-123-4567")
 */
object PhoneNumbers {
    /** Digits only, with a leading US country code stripped. */
    fun normalize(phoneNumber: String): String {
        val digits = phoneNumber.filter { it.isDigit() }
        return if (digits.length == 11 && digits.startsWith("1")) digits.substring(1) else digits
    }

    /** E.164 form for SMS delivery: `+1XXXXXXXXXX`. */
    fun toE164(phoneNumber: String): String = "+1${normalize(phoneNumber)}"

    /** Human-friendly `xxx-xxx-xxxx`, or the normalized digits if not 10 long. */
    fun toDisplay(phoneNumber: String): String {
        val n = normalize(phoneNumber)
        return if (n.length ==
            10
        ) {
            "${n.substring(0, 3)}-${n.substring(3, 6)}-${n.substring(6, 10)}"
        } else {
            n
        }
    }

    /** True when the number normalizes to exactly 10 digits. */
    fun isValid(phoneNumber: String): Boolean = normalize(phoneNumber).length == 10
}
