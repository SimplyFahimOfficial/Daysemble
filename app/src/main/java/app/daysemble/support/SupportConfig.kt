package app.daysemble.support

/**
 * "Support my work" configuration: the developer's payment and contact details. This is the only
 * place these values live, so updating a link is a one-line change.
 *
 * Daysemble's GitHub repository is private, so no repository link is shown; the public GitHub
 * profile is linked instead. If the repository becomes public, add its URL here and show it on the
 * Support screen.
 *
 * Nothing here is sent anywhere by the app: links open in apps the user picks, and the UPI QR
 * code is generated on the device.
 */
object SupportConfig {
    /** Ko-fi profile (account "SimplyFahim"). */
    const val KOFI_URL = "https://ko-fi.com/simplyfahim"

    /** The developer's personal website. Not a project page. */
    const val WEBSITE = "https://SimplyFahim.pages.dev"

    /** The developer's public GitHub profile. */
    const val GITHUB_PROFILE = "https://github.com/Fahimofficial/"

    /** Public UPI ID (VPA) and the payee name UPI apps show before the user confirms. */
    const val UPI_ID = "SimplyFahim@sbi"
    const val UPI_PAYEE_NAME = "Md Fahim"
    const val UPI_CURRENCY = "INR"

    /**
     * Standard UPI payment link that only identifies the payee:
     * `upi://pay?pa=SimplyFahim@sbi&pn=Md%20Fahim&cu=INR`.
     *
     * No amount (`am`) is ever set: the user enters it and confirms in their own UPI app. The same
     * string is the "Open UPI app" intent data and the QR payload. `@` stays literal (valid in a URI
     * query, and what bank-issued UPI QR codes use); anything else outside the RFC 3986 unreserved
     * set is percent-encoded.
     */
    fun upiPaymentUri(
        upiId: String = UPI_ID,
        payeeName: String = UPI_PAYEE_NAME,
        currency: String = UPI_CURRENCY,
    ): String = "upi://pay?pa=${encode(upiId)}&pn=${encode(payeeName)}&cu=${encode(currency)}"

    private val KEPT_AS_IS: Set<Char> = (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~', '@')).toSet()
    private const val BYTE_MASK = 0xFF

    private fun encode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and BYTE_MASK
            val c = unsigned.toChar()
            if (c in KEPT_AS_IS) append(c) else append("%%%02X".format(unsigned))
        }
    }
}
