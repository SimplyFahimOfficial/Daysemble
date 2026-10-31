package app.daysemble.support

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SupportConfigTest {

    @Test
    fun detailsMatchTheOfficialOnes() {
        assertThat(SupportConfig.KOFI_URL).isEqualTo("https://ko-fi.com/simplyfahim")
        assertThat(SupportConfig.UPI_ID).isEqualTo("SimplyFahim@sbi")
        assertThat(SupportConfig.UPI_PAYEE_NAME).isEqualTo("SimplyFahim")
        assertThat(SupportConfig.UPI_CURRENCY).isEqualTo("INR")
        assertThat(SupportConfig.WEBSITE_URL).isEqualTo("https://SimplyFahim.pages.dev")
        assertThat(SupportConfig.GITHUB_PROFILE_URL).isEqualTo("https://github.com/Fahimofficial/")
    }

    @Test
    fun privateRepositoryIsNotLinked() {
        // github.com/Fahimofficial/Daysemble is private (404 for everyone else) until this is set.
        assertThat(SupportConfig.REPOSITORY_URL).isNull()
    }

    @Test
    fun upiUriHasPayeeNameCurrencyAndNoAmount() {
        val uri = SupportConfig.upiPaymentUri
        assertThat(uri).isEqualTo("upi://pay?pa=SimplyFahim@sbi&pn=SimplyFahim&cu=INR")
        val params = uri.substringAfter('?').split('&').map { it.substringBefore('=') }
        assertThat(params).containsExactly("pa", "pn", "cu")
    }

    @Test
    fun upiValuesArePercentEncoded() {
        assertThat(upiPaymentUri("a.b-c_d~e@okbank", "Jane Doe & Co", "INR"))
            .isEqualTo("upi://pay?pa=a.b-c_d~e@okbank&pn=Jane%20Doe%20%26%20Co&cu=INR")
        assertThat(upiPaymentUri("x@y", "Zoë", "INR")).isEqualTo("upi://pay?pa=x@y&pn=Zo%C3%AB&cu=INR")
    }
}
