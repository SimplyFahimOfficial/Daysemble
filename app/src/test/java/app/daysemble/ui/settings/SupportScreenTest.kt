package app.daysemble.ui.settings

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.support.SupportConfig
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders Support My Work (Robolectric native graphics, not a device) in light and dark, checks the
 * content and links, and decodes the QR code from the rendered pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class SupportScreenTest {
    @get:Rule val compose = createComposeRule()

    private var theme by mutableStateOf(ThemeMode.LIGHT)

    private fun render(mode: ThemeMode, first: Boolean = true) {
        theme = mode
        if (first) compose.setContent { DaysembleTheme(themeMode = theme, reducedMotionOverride = true) { SupportRoute(onBack = {}) } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("UPI QR code", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun decode(bmp: Bitmap): String {
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        return QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, px))),
            mapOf(DecodeHintType.TRY_HARDER to true),
        ).text
    }

    private fun save(bmp: Bitmap, name: String) {
        val f = File("build/support-qr/$name.png").apply { parentFile?.mkdirs() }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `shows payment options, project links and no private repository link`() {
        render(ThemeMode.LIGHT)
        compose.onNodeWithText("Support My Work").assertExists()
        compose.onNodeWithText("Your support helps me maintain", substring = true).assertExists()
        compose.onNodeWithText("Support on Ko-fi").assertExists()
        compose.onNodeWithText("ko-fi.com/simplyfahim").assertExists()
        compose.onNodeWithText("Open UPI app").assertExists()
        compose.onNodeWithText("Payee: SimplyFahim").assertExists()
        compose.onNodeWithText("SimplyFahim.pages.dev").performScrollTo().assertExists()
        compose.onNodeWithText("github.com/Fahimofficial").performScrollTo().assertExists()
        assertThat(compose.onAllNodesWithText("github.com/Fahimofficial/Daysemble", substring = true, ignoreCase = true).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Source code on GitHub").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("QuantumMPV", substring = true).fetchSemanticsNodes()).isEmpty()
    }

    @Test fun `rendered qr decodes to the upi link in light and dark`() {
        for ((i, mode) in listOf(ThemeMode.LIGHT, ThemeMode.DARK).withIndex()) {
            render(mode, first = i == 0)
            val qr = compose.onNodeWithTag(SupportTags.QR).captureToImage().asAndroidBitmap()
            save(qr, "rendered-qr-${mode.name.lowercase()}")
            assertThat(decode(qr)).isEqualTo("upi://pay?pa=SimplyFahim@sbi&pn=SimplyFahim&cu=INR")
            save(compose.onRoot().captureToImage().asAndroidBitmap(), "support-${mode.name.lowercase()}")
        }
    }
}
