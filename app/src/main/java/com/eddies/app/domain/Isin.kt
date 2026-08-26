package com.eddies.app.domain

/**
 * ISINs, which is how European investors actually identify an instrument.
 *
 * A broker statement lists ISINs, not tickers, and Tradegate is keyed by them
 * exclusively. Validating the check digit locally means a mistyped ISIN is
 * caught before any request goes out, and the error says "that is not a valid
 * ISIN" rather than "not found".
 */
object Isin {

    private val PATTERN = Regex("^[A-Z]{2}[A-Z0-9]{9}[0-9]$")

    fun normalise(raw: String): String = raw.trim().uppercase().replace(" ", "").replace("-", "")

    fun looksLikeIsin(raw: String): Boolean = PATTERN.matches(normalise(raw))

    /**
     * Full validation including the check digit.
     *
     * The algorithm is Luhn, but over a string where every letter first expands
     * to two digits (A becomes 10, Z becomes 35). Expanding first and then
     * running Luhn over the result is the part that is easy to get subtly wrong.
     */
    fun isValid(raw: String): Boolean {
        val isin = normalise(raw)
        if (!PATTERN.matches(isin)) return false

        val digits = StringBuilder()
        for (c in isin) {
            when {
                c.isDigit() -> digits.append(c)
                c in 'A'..'Z' -> digits.append((c - 'A') + 10)
                else -> return false
            }
        }

        // Luhn, doubling every second digit from the right, excluding the check
        // digit itself which is already the last character of the expansion.
        var sum = 0
        var double = true
        for (i in digits.length - 2 downTo 0) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            double = !double
            sum += d
        }
        val check = (10 - (sum % 10)) % 10
        return check == (digits[digits.length - 1] - '0')
    }

    /** The two-letter country prefix, which is where the security is registered, not where it trades. */
    fun country(raw: String): String? =
        normalise(raw).takeIf { looksLikeIsin(it) }?.substring(0, 2)
}

/**
 * Numbers as German venues format them.
 *
 * Tradegate's JSON mixes types: the same field is a JSON number for one
 * instrument and a comma-decimal string for another, seemingly whenever the
 * value would otherwise end in a trailing zero. SAP comes back as
 * `"last":180.38` while ASML comes back as `"last":"1501,60"`.
 *
 * Parsing that with the ordinary path throws, which the caller turns into a null
 * and then into no price at all. Silent, and only for some instruments, which is
 * the worst way for it to fail.
 */
object GermanNumber {

    /**
     * Reads a number written either way.
     *
     * The rule: if there is a comma, it is the decimal separator and any dots
     * are thousands separators. Otherwise a dot is the decimal separator. That
     * covers "1.501,60", "1501,60", "1501.60" and "1501".
     */
    fun parse(raw: String?): java.math.BigDecimal? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalised = if (text.contains(',')) {
            text.replace(".", "").replace(',', '.')
        } else {
            text
        }
        return runCatching { java.math.BigDecimal(normalised) }.getOrNull()
    }
}
