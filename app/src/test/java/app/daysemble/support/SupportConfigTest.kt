package app.daysemble.support

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class SupportConfigTest {
    @Test fun `official details are exact`() {
        assertThat(SupportConfig.KOFI_URL).isEqualTo("https://ko-fi.com/simplyfahim")
        assertThat(SupportConfig.UPI_ID).isEqualTo("SimplyFahim@sbi")
        assertThat(SupportConfig.UPI_PAYEE_NAME).isEqualTo("SimplyFahim")
        assertThat(SupportConfig.UPI_CURRENCY).isEqualTo("INR")
        assertThat(SupportConfig.WEBSITE_URL).isEqualTo("https://SimplyFahim.pages.dev")
        assertThat(SupportConfig.GITHUB_PROFILE_URL).isEqualTo("https://github.com/Fahimofficial/")
    }

    @Test fun `private repository is not linked and no other app's repository is used`() {
        assertThat(SupportConfig.PROJECT_REPOSITORY_URL).isNull()
    }

    @Test fun `upi link has payee, name and currency and no amount`() {
        assertThat(SupportConfig.upiPaymentUri).isEqualTo("upi://pay?pa=SimplyFahim@sbi&pn=SimplyFahim&cu=INR")
        val p = UpiPaymentUri.parse(SupportConfig.upiPaymentUri)!!
        assertThat(p).containsExactly("pa", "SimplyFahim@sbi", "pn", "SimplyFahim", "cu", "INR")
    }

    @Test fun `builder encodes reserved characters and rejects bad ids`() {
        assertThat(UpiPaymentUri.build("a.b@okaxis", "A B&C")).isEqualTo("upi://pay?pa=a.b@okaxis&pn=A%20B%26C&cu=INR")
        assertThat(UpiPaymentUri.isValidVpa("nope")).isFalse()
        assertThat(UpiPaymentUri.isValidVpa("x@")).isFalse()
    }

    @Test fun `generated qr decodes to the upi link at several sizes`() {
        listOf(160, 400, 768).forEach { size ->
            val qr = UpiQrCode.encode(SupportConfig.upiPaymentUri, size)
            val text = QRCodeReader().decode(
                BinaryBitmap(HybridBinarizer(RGBLuminanceSource(qr.width, qr.height, qr.argb))),
                mapOf(DecodeHintType.TRY_HARDER to true),
            ).text
            assertThat(text).isEqualTo("upi://pay?pa=SimplyFahim@sbi&pn=SimplyFahim&cu=INR")
            assertThat(qr.argb.toSet()).containsExactly(UpiQrCode.DARK, UpiQrCode.LIGHT)
        }
    }

    /** For independent decoding outside the JVM (zxing-cpp, OpenCV). */
    @Test fun `export qr png`() {
        val qr = UpiQrCode.encode(SupportConfig.upiPaymentUri, 768)
        val img = BufferedImage(qr.width, qr.height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, qr.width, qr.height, qr.argb, 0, qr.width)
        ImageIO.write(img, "png", File("build/support-qr/upi-qr.png").apply { parentFile?.mkdirs() })
    }
}
