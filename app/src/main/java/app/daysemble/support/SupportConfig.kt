package app.daysemble.support

/**
 * Every link and payment detail the Support page shows. Change them here; nothing else hardcodes them.
 * The app never handles money: it only opens Ko-fi or a UPI app chosen by the user, and stores nothing.
 */
object SupportConfig {
    const val KOFI_URL = "https://ko-fi.com/simplyfahim"
    const val UPI_ID = "SimplyFahim@sbi"
    const val UPI_PAYEE_NAME = "SimplyFahim"
    const val UPI_CURRENCY = "INR"
    const val WEBSITE_URL = "https://SimplyFahim.pages.dev"
    const val GITHUB_PROFILE_URL = "https://github.com/Fahimofficial/"

    /**
     * Daysemble's own repository. It is private, so the app doesn't link to it (the link would show
     * GitHub's 404 page to everyone else). Set it to "https://github.com/Fahimofficial/Daysemble"
     * once the repository is public; the Support page shows the row whenever this isn't null.
     */
    val REPOSITORY_URL: String? = null

    /** The payment link encoded in the QR code and sent to UPI apps. No amount: the payer chooses it. */
    val upiPaymentUri: String = upiPaymentUri(UPI_ID, UPI_PAYEE_NAME, UPI_CURRENCY)
}

/**
 * A UPI deep link (`upi://pay`, NPCI linking specification) with payee address [upiId], payee name
 * [payeeName] and [currency], and deliberately no `am` (amount) parameter. Values are percent-encoded
 * except for URI-unreserved characters and `@`, so a VPA reads as `name@bank` like bank-issued codes.
 */
fun upiPaymentUri(upiId: String, payeeName: String, currency: String): String =
    "upi://pay?pa=${encodeUpiValue(upiId)}&pn=${encodeUpiValue(payeeName)}&cu=${encodeUpiValue(currency)}"

private const val BYTE_MASK = 0xFF
private const val ASCII_END = 0x80

private fun encodeUpiValue(value: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and BYTE_MASK
        val ch = code.toChar()
        if ((code < ASCII_END && ch.isLetterOrDigit()) || ch in "-._~@") append(ch)
        else append('%').append("%02X".format(code))
    }
}
