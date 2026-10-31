package app.daysemble.support

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.OutputStream

/**
 * Builds the UPI QR code on the device (ZXing encoder, no network). Modules are always black on
 * white with a four-module quiet zone, whatever the app theme, because scanners need that contrast.
 */
object UpiQr {
    const val QUIET_ZONE_MODULES = 4
    private val PRINTABLE_ASCII = ' '..'~'
    private const val PNG_QUALITY = 100 // ignored for PNG (lossless), required by the API
    private const val DARK = 0xFF000000.toInt()
    private const val LIGHT = 0xFFFFFFFF.toInt()

    /**
     * One bit per module, quiet zone included. No character-set hint: the UPI link is ASCII, and a
     * UTF-8 hint would add an ECI header that some UPI scanners don't understand.
     */
    fun matrix(content: String): BitMatrix {
        require(content.all { it in PRINTABLE_ASCII }) { "UPI QR content must be printable ASCII" }
        return QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
            ),
        )
    }

    /** ARGB pixels for [matrix] with each module drawn as [scale]×[scale] pixels; the side is `matrix.width * scale`. */
    fun pixels(matrix: BitMatrix, scale: Int): IntArray {
        require(scale > 0)
        val side = matrix.width * scale
        val out = IntArray(side * side)
        for (y in 0 until side) {
            val my = y / scale
            for (x in 0 until side) out[y * side + x] = if (matrix[x / scale, my]) DARK else LIGHT
        }
        return out
    }

    /** A crisp bitmap of [content]. Display it with nearest-neighbour filtering so module edges stay sharp. */
    fun bitmap(content: String = SupportConfig.upiPaymentUri, scale: Int = 16): Bitmap {
        val m = matrix(content)
        val side = m.width * scale
        return Bitmap.createBitmap(pixels(m, scale), side, side, Bitmap.Config.ARGB_8888)
    }

    fun writePng(out: OutputStream, content: String = SupportConfig.upiPaymentUri) {
        val bmp = bitmap(content)
        try {
            check(bmp.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) { "PNG encoding failed" }
        } finally {
            bmp.recycle()
        }
    }
}
