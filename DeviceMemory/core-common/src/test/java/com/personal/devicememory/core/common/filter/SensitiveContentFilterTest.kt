package com.personal.devicememory.core.common.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveContentFilterTest {

    @Test
    fun testOtpRedaction() {
        val input = "Your OTP is 482910 to verify your account."
        val redacted = SensitiveContentFilter.redact(input)
        assertEquals("Your [OTP_REDACTED] to verify your account.", redacted)
        assertTrue(SensitiveContentFilter.containsSensitive(input))
    }

    @Test
    fun testOneTimePasswordRedaction() {
        val input = "Your one-time password is: 954123 valid for 5 mins"
        val redacted = SensitiveContentFilter.redact(input)
        assertEquals("Your [OTP_REDACTED] valid for 5 mins", redacted)
    }

    @Test
    fun testPasswordRedaction() {
        val input = "Login with password: SuperSecretP@ss123 now"
        val redacted = SensitiveContentFilter.redact(input)
        assertEquals("Login with [PASSWORD_REDACTED] now", redacted)
    }

    @Test
    fun testCreditCardRedaction() {
        val input = "Card charged: 4111 2222 3333 4444 successfully"
        val redacted = SensitiveContentFilter.redact(input)
        assertEquals("Card charged: [CARD_REDACTED] successfully", redacted)
    }

    @Test
    fun testUpiIdRedaction() {
        val input = "Send payment to user.name@oksbi or merchant@paytm"
        val redacted = SensitiveContentFilter.redact(input)
        assertEquals("Send payment to [UPI_REDACTED] or [UPI_REDACTED]", redacted)
    }

    @Test
    fun testAadhaarRedaction() {
        val input = "Aadhaar number: 1234 5678 9012 for eKYC"
        val redacted = SensitiveContentFilter.redact(input)
        assertEquals("Aadhaar number: [AADHAAR_REDACTED] for eKYC", redacted)
    }

    @Test
    fun testBankAccRedaction() {
        val input = "Amount credited to A/C No: 12345678901234 on 24-Nov"
        val redacted = SensitiveContentFilter.redact(input)
        assertEquals("Amount credited to [ACCOUNT_REDACTED] on 24-Nov", redacted)
    }

    @Test
    fun testTamilWithSensitiveContent() {
        val input = "உங்கள் OTP குறியீடு 894512 ஆகும்"
        // OTP is in English letters, rest in Tamil
        val redacted = SensitiveContentFilter.redact(input)
        assertEquals("உங்கள் [OTP_REDACTED] ஆகும்", redacted)
    }

    @Test
    fun testNormalTextNotRedacted() {
        val input = "Meeting with Suresh at 5 PM in Chennai"
        val redacted = SensitiveContentFilter.redact(input)
        assertEquals(input, redacted)
        assertFalse(SensitiveContentFilter.containsSensitive(input))
    }

    @Test
    fun testNullHandling() {
        assertNull(SensitiveContentFilter.redact(null))
        assertFalse(SensitiveContentFilter.containsSensitive(null))
    }
}
