package app.daysemble.support

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Encodes the UPI code exactly as the app draws it (same pixel buffer), writes it to a PNG, reads the
 * PNG back and decodes it.
 */
class UpiQrTest {

    private fun decode(pixels: IntArray, side: Int): String {
        val source = RGBLuminanceSource(side, side, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.PURE_BARCODE to false)).text
    }

    @Test
    fun pngOfTheCodeDecodesToTheUpiUri() {
        val matrix = UpiQr.matrix(SupportConfig.upiPaymentUri())
        val scale = 8
        val side = matrix.width * scale
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, side, side, UpiQr.pixels(matrix, scale), 0, side)

        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        // Kept for manual inspection: app/build/reports/support/upi-qr.png (tests run in the module directory).
        File("build/reports/support").apply { mkdirs() }.resolve("upi-qr.png").writeBytes(png)

        val read = ImageIO.read(ByteArrayInputStream(png))
        val readPixels = read.getRGB(0, 0, read.width, read.height, null, 0, read.width)
        assertThat(decode(readPixels, read.width)).isEqualTo("upi://pay?pa=SimplyFahim@sbi&pn=SimplyFahim&cu=INR")
    }

    @Test
    fun codeHasAWhiteQuietZoneOfFourModules() {
        val matrix = UpiQr.matrix(SupportConfig.upiPaymentUri())
        val q = UpiQr.QUIET_ZONE_MODULES
        for (i in 0 until matrix.width) {
            for (j in 0 until q) {
                assertThat(matrix[i, j]).isFalse()
                assertThat(matrix[j, i]).isFalse()
                assertThat(matrix[i, matrix.height - 1 - j]).isFalse()
                assertThat(matrix[matrix.width - 1 - j, i]).isFalse()
            }
        }
        // Top-left finder pattern starts right after the quiet zone.
        assertThat(matrix[q, q]).isTrue()
    }

    @Test
    fun pixelsUseOnlyBlackAndWhite() {
        val px = UpiQr.pixels(UpiQr.matrix(SupportConfig.upiPaymentUri()), 2).toSet()
        assertThat(px).containsExactly(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
    }
}
