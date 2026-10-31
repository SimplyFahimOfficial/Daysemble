package app.daysemble.support

import java.net.URLDecoder

/**
 * Builds and parses UPI payment links (`upi://pay?...`, NPCI UPI linking specification).
 *
 * Only `pa` (payee address), `pn` (payee name) and `cu` (currency) are set. There is never an `am`
 * (amount): the payer enters it and confirms in their own UPI app.
 */
object UpiPaymentUri {
    private const val PREFIX = "upi://pay?"
    private const val CURRENCY_CODE_LENGTH = 3
    private const val BYTE_MASK = 0xFF
    private const val ASCII_LIMIT = 0x80
    private val VPA = Regex("^[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9]{1,63}$")

    fun isValidVpa(vpa: String): Boolean = VPA.matches(vpa)

    fun build(payeeAddress: String, payeeName: String, currency: String = "INR"): String {
        require(isValidVpa(payeeAddress)) { "Invalid UPI ID: $payeeAddress" }
        require(payeeName.isNotBlank()) { "Payee name must not be blank" }
        require(currency.length == CURRENCY_CODE_LENGTH) { "Currency must be an ISO 4217 code" }
        return "${PREFIX}pa=${encode(payeeAddress)}&pn=${encode(payeeName)}&cu=${encode(currency)}"
    }

    /** The decoded query parameters of a `upi://pay` link, or null if [uri] is not one. */
    fun parse(uri: String): Map<String, String>? {
        if (!uri.startsWith(PREFIX)) return null
        return uri.removePrefix(PREFIX).split('&').filter { it.isNotEmpty() }.associate { part ->
            part.substringBefore('=') to URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8.name())
        }
    }

    /**
     * Percent-encodes everything outside the RFC 3986 unreserved set, except `@`, which is legal in
     * a query and kept literal because some UPI apps do not match `%40` in a scanned VPA.
     */
    private fun encode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val b = byte.toInt() and BYTE_MASK
            val ch = b.toChar()
            if (b < ASCII_LIMIT && (ch.isLetterOrDigit() || ch in "-._~@")) append(ch) else append("%%%02X".format(b))
        }
    }
}
