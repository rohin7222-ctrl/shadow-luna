package com.personal.devicememory.core.common.filter

/**
 * Regex-based filter that redacts sensitive content before database insertion.
 * Covers OTPs, passwords, card numbers, bank accounts, UPI, IFSC, Aadhaar.
 */
object SensitiveContentFilter {

    private val REDACTION_RULES: List<Pair<Regex, String>> = listOf(
        // OTP patterns: "OTP is 123456", "code: 482910", "verification code 1234"
        Regex(
            """(?i)\b(?:otp|verification\s*code|one[- ]?time\s*(?:password|code|pin))\s*(?:is|:|=|-)?\s*(\d{4,8})\b"""
        ) to "[OTP_REDACTED]",

        // Password patterns: "password: xyz", "pwd = abc123", "passcode: 1234"
        Regex(
            """(?i)\b(?:password|passwd|pwd|passcode|pin)\s*(?:is|:|=|-)\s*\S+"""
        ) to "[PASSWORD_REDACTED]",

        // Credit/debit card numbers (13-19 digits, with optional spaces/dashes)
        Regex(
            """\b(?:\d[ -]?){13,19}\b"""
        ) to "[CARD_REDACTED]",

        // UPI IDs: name@upi, name@ybl, name@paytm, etc.
        Regex(
            """(?i)\b[a-z0-9._-]+@(?:upi|ybl|paytm|okicici|oksbi|okhdfcbank|apl|axl|ibl|sbi|barodampay)\b"""
        ) to "[UPI_REDACTED]",

        // IFSC codes: 4 letters + 0 + 6 alphanumeric
        Regex(
            """\b[A-Z]{4}0[A-Z0-9]{6}\b"""
        ) to "[IFSC_REDACTED]",

        // Aadhaar numbers: 12 digits (4-4-4 with optional spaces)
        Regex(
            """\b\d{4}\s?\d{4}\s?\d{4}\b"""
        ) to "[AADHAAR_REDACTED]",

        // Bank account numbers: "a/c" or "account" followed by 9-18 digits
        Regex(
            """(?i)\b(?:a/?c|account)\s*(?:no\.?|number|num|#)?\s*:?\s*\d{9,18}\b"""
        ) to "[ACCOUNT_REDACTED]",

        // CVV patterns
        Regex(
            """(?i)\b(?:cvv|cvc|cvv2)\s*(?:is|:|=|-)\s*\d{3,4}\b"""
        ) to "[CVV_REDACTED]",
    )

    /**
     * Returns the input text with all sensitive patterns redacted.
     * Returns null if [text] is null.
     */
    fun redact(text: String?): String? {
        if (text == null) return null
        var result: String = text
        for ((pattern, replacement) in REDACTION_RULES) {
            result = pattern.replace(result, replacement)
        }
        return result
    }

    /**
     * Returns true if [text] contains any sensitive pattern.
     */
    fun containsSensitive(text: String?): Boolean {
        val nonNullText = text ?: return false
        return REDACTION_RULES.any { (pattern, _) -> pattern.containsMatchIn(nonNullText) }
    }
}
