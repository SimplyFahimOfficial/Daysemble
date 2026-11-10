package app.daysemble.support

/**
 * Help & Support configuration: the project's help links and the developer's payment details.
 * This is the only place these values live, so updating a link is a one-line change.
 *
 * Daysemble's GitHub repository is private, so there is no repository or issue-tracker link and
 * no "Report a bug" row. If the repository becomes public, add its issues URL here and show a row
 * for it in the Help & Support page.
 *
 * Nothing here is sent anywhere by the app: links open in apps the user picks, and the UPI QR
 * code is generated on the device.
 */
object SupportConfig {
    /**
     * The Daysemble Telegram group. Verified: the project page links it as "Daysemble Telegram
     * group" and t.me resolves it to a group titled DAYSEMBLE. Don't guess other usernames.
     */
    const val TELEGRAM_URL = "https://t.me/daysemble"

    /** The Daysemble page on the developer's site. */
    const val PROJECT_WEBSITE = "https://simplyfahim.pages.dev/daysemble"

    /** Website, email and social links for the developer, kept on the site instead of in the app. */
    const val CONTACT_URL = "https://simplyfahim.pages.dev/contactme"

    /** Payment methods beyond UPI, kept on the site instead of in the app. */
    const val PAY_OPTIONS_URL = "https://simplyfahim.pages.dev/pay-options"

    /** Public UPI ID (VPA) and the payee name UPI apps show before the user confirms. */
    const val UPI_ID = "SimplyFahim@sbi"
    const val UPI_PAYEE_NAME = "Md Fahim"
    const val UPI_CURRENCY = "INR"

    /**
     * Standard UPI payment link that only identifies the payee:
     * `upi://pay?pa=SimplyFahim@sbi&pn=Md%20Fahim&cu=INR`.
     *
     * No amount (`am`) is ever set: the user enters it and confirms in their own UPI app. The same
     * string is the "Pay with UPI" intent data and the QR payload. `@` stays literal (valid in a URI
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
