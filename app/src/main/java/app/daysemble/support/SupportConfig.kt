package app.daysemble.support

/**
 * Single source of truth for Settings > Support My Work.
 *
 * Change links or payment details here only: the screen, the on-device UPI QR code, the share text
 * and the intents all read from this object. Nothing here is sent anywhere by the app; links open
 * only when the user taps them, in an app the user picks.
 */
object SupportConfig {
    const val DEVELOPER_NAME = "SimplyFahim"

    const val KOFI_URL = "https://ko-fi.com/simplyfahim"

    const val UPI_ID = "SimplyFahim@sbi"
    const val UPI_PAYEE_NAME = "SimplyFahim"
    const val UPI_CURRENCY = "INR"

    /** The developer's personal website. Not a project page. */
    const val WEBSITE_URL = "https://SimplyFahim.pages.dev"

    /** The developer's public GitHub profile. */
    const val GITHUB_PROFILE_URL = "https://github.com/Fahimofficial/"

    /**
     * Daysemble's own repository link. The repository (Fahimofficial/Daysemble) is private, so it is
     * deliberately not shown in the app. Set this to the public URL if the repository is made public.
     */
    val PROJECT_REPOSITORY_URL: String? = null

    /** `upi://pay?pa=SimplyFahim@sbi&pn=SimplyFahim&cu=INR`: no amount, so the payer chooses it. */
    val upiPaymentUri: String = UpiPaymentUri.build(UPI_ID, UPI_PAYEE_NAME, UPI_CURRENCY)

    /** Drops the scheme and trailing slash for display, e.g. `ko-fi.com/simplyfahim`. */
    fun displayUrl(url: String): String = url.substringAfter("://").trimEnd('/')
}
