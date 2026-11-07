package app.daysemble.support

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SupportConfigTest {
    @Test fun upiLinkIdentifiesThePayeeOnlyWithNoAmount() {
        assertThat(SupportConfig.upiPaymentUri()).isEqualTo("upi://pay?pa=SimplyFahim@sbi&pn=Md%20Fahim&cu=INR")
        assertThat(SupportConfig.upiPaymentUri()).doesNotContain("am=")
    }

    @Test fun upiLinkPercentEncodesUnsafeCharacters() {
        assertThat(SupportConfig.upiPaymentUri(upiId = "a&b@bank", payeeName = "Jane Doe"))
            .isEqualTo("upi://pay?pa=a%26b@bank&pn=Jane%20Doe&cu=INR")
    }

    @Test fun supportDetailsAreTheDevelopersOwn() {
        assertThat(SupportConfig.UPI_ID).isEqualTo("SimplyFahim@sbi")
        assertThat(SupportConfig.UPI_PAYEE_NAME).isEqualTo("Md Fahim")
        assertThat(SupportConfig.UPI_CURRENCY).isEqualTo("INR")
        assertThat(SupportConfig.KOFI_URL).isEqualTo("https://ko-fi.com/simplyfahim")
        assertThat(SupportConfig.WEBSITE).isEqualTo("https://SimplyFahim.pages.dev")
        assertThat(SupportConfig.GITHUB_PROFILE).isEqualTo("https://github.com/Fahimofficial/")
    }
}
