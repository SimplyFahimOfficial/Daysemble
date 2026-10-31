package app.daysemble.support

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Encodes QR codes on the device with ZXing. No network, no QR web service.
 *
 * Returns plain ARGB pixels so it can be tested on the JVM; [SupportIntents.createQrBitmap] turns
 * them into an Android Bitmap.
 */
object UpiQrCode {
    const val DARK: Int = 0xFF000000.toInt()
    const val LIGHT: Int = 0xFFFFFFFF.toInt()

    /** The QR specification asks for a 4-module quiet zone. */
    private const val QUIET_ZONE_MODULES = 4

    /** Printable ASCII. */
    private val PRINTABLE = ' '..'~'

    class Pixels(val width: Int, val height: Int, val argb: IntArray)

    fun encode(content: String, sizePx: Int): Pixels {
        require(sizePx > 0) { "sizePx must be positive" }
        require(content.all { it in PRINTABLE }) { "QR content must be printable ASCII" }
        // No CHARACTER_SET hint: the link is ASCII, and an ECI header confuses some UPI scanners.
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
        )
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val px = IntArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) px[y * m.width + x] = if (m[x, y]) DARK else LIGHT
        return Pixels(m.width, m.height, px)
    }
}
