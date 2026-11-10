package app.daysemble.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.support.SupportConfig
import app.daysemble.ui.theme.DaysembleTheme
import app.daysemble.ui.theme.DsTheme
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
 * Renders the Support My Work page in both themes: the QR code on the rendered screen must decode
 * to the UPI link, More Ways to Pay must open the configured URL, and each button must call its action.
 * Renders are kept in build/support-qr/ for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1700dp-xxhdpi")
class SupportScreenTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val calls = mutableListOf<String>()
    private val actions = SupportActions(
        onOpenUrl = { opened += it },
        onCopyUpiId = { calls += "copy" },
        onOpenUpiApp = { calls += "upi" },
        onShareQr = { calls += "share" },
        onSaveQr = { calls += "save" },
    )

    private fun show(theme: ThemeMode) {
        compose.setContent {
            DaysembleTheme(themeMode = theme) {
                Column(Modifier.fillMaxWidth().background(DsTheme.colors.background)) { SupportContent(actions) }
            }
        }
    }

    @Test fun lightThemeQrCodeOnScreenDecodesToTheUpiLink() = renderAndDecode(ThemeMode.LIGHT, "light")

    @Test fun darkThemeQrCodeOnScreenDecodesToTheUpiLink() = renderAndDecode(ThemeMode.DARK, "dark")

    private fun renderAndDecode(theme: ThemeMode, name: String) {
        show(theme)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/support-qr").apply { mkdirs() }
        File(dir, "support-screen-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val decoded = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
            mapOf(DecodeHintType.TRY_HARDER to true),
        ).text
        assertThat(decoded).isEqualTo("upi://pay?pa=SimplyFahim@sbi&pn=Md%20Fahim&cu=INR")
    }

    @Test fun linksAndActionsAreWired() {
        show(ThemeMode.LIGHT)
        compose.onNodeWithText("Copy UPI ID").performClick()
        compose.onNodeWithText("Pay with UPI").performClick()
        compose.onNodeWithText("Share QR").performClick()
        compose.onNodeWithText("Save QR").performClick()
        compose.onNodeWithText("More Ways to Pay").performClick()
        compose.onNodeWithText("Copied").assertExists()

        assertThat(opened).containsExactly(SupportConfig.PAY_OPTIONS_URL)
        assertThat(calls).containsExactly("copy", "upi", "share", "save").inOrder()
    }

    @Test fun showsThePayeeAndTheOptionalNoteButNoKofi() {
        show(ThemeMode.LIGHT)
        compose.onNodeWithText(SupportConfig.UPI_ID).assertExists()
        compose.onNodeWithText("Payee: ${SupportConfig.UPI_PAYEE_NAME}").assertExists()
        compose.onNodeWithText("Contributions are optional", substring = true).assertExists()
        compose.onNodeWithText("Ko-fi", substring = true, ignoreCase = true).assertDoesNotExist()
    }
}
