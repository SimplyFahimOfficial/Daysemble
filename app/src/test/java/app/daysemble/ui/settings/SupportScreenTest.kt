package app.daysemble.ui.settings

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.daysemble.support.SupportConfig
import app.daysemble.support.UpiQr
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SupportScreenTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var activity: Activity

    private fun show() {
        compose.setContent {
            activity = LocalActivity.current!!
            DaysembleTheme { SupportRoute(onBack = {}) }
        }
    }

    @Test
    fun showsIntroPaymentDetailsAndLinksButNotThePrivateRepository() {
        show()
        compose.onNodeWithText(
            "Your support helps me maintain existing applications, improve features and continue developing " +
                "independent open-source projects. Every contribution is voluntary and appreciated.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Support on Ko-fi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("UPI QR code for SimplyFahim@sbi, payee SimplyFahim. Scan it with a UPI app.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SimplyFahim@sbi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SimplyFahim.pages.dev").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("github.com/Fahimofficial").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Source code on GitHub").assertDoesNotExist()
    }

    @Test
    fun linksOpenTheExactUrls() {
        show()
        compose.onNodeWithText("Support on Ko-fi").performScrollTo().performClick()
        shadowOf(activity).nextStartedActivity.let {
            assertThat(it.action).isEqualTo(Intent.ACTION_VIEW)
            assertThat(it.dataString).isEqualTo("https://ko-fi.com/simplyfahim")
        }
        compose.onNodeWithText("Website").performScrollTo().performClick()
        assertThat(shadowOf(activity).nextStartedActivity.dataString).isEqualTo("https://SimplyFahim.pages.dev")
        compose.onNodeWithText("More projects on GitHub").performScrollTo().performClick()
        assertThat(shadowOf(activity).nextStartedActivity.dataString).isEqualTo("https://github.com/Fahimofficial/")
    }

    @Test
    fun withoutAUpiAppTheIdIsCopiedAndNothingClaimsPayment() {
        show()
        compose.onNodeWithText("Pay with a UPI app").performScrollTo().performClick()
        assertThat(shadowOf(activity).nextStartedActivity).isNull()
        compose.onNodeWithText("No UPI app found", substring = true).assertExists()
        val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip
        assertThat(clip!!.getItemAt(0).text.toString()).isEqualTo("SimplyFahim@sbi")
    }

    @Test
    fun withAUpiAppAChooserOpensWithTheUpiUri() {
        val upi = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SupportConfig.upiPaymentUri))
        val info = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply { packageName = "com.example.upi"; name = "Pay" }
        }
        shadowOf(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>().packageManager)
            .addResolveInfoForIntent(upi, info)
        show()
        compose.onNodeWithText("Pay with a UPI app").performScrollTo().performClick()
        val started = shadowOf(activity).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_CHOOSER)
        @Suppress("DEPRECATION")
        val inner = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(inner.dataString).isEqualTo("upi://pay?pa=SimplyFahim@sbi&pn=SimplyFahim&cu=INR")
    }

    @Test
    fun androidBitmapOfTheCodeDecodesToTheUpiUri() {
        val bmp = UpiQr.bitmap()
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, px)))).text
        assertThat(text).isEqualTo(SupportConfig.upiPaymentUri)
    }
}
