package app.daysemble.support

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.net.toUri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Android side of Support My Work: links, UPI intents, clipboard and QR image export. */
object SupportIntents {
    const val QR_FILE_NAME = "SimplyFahim-UPI-QR.png"
    private const val SHARE_DIR = "support_share"
    private const val PNG_QUALITY = 100 // ignored for PNG (lossless), required by the API

    /** Opens an https link in a browser. False when no app can open it. */
    fun openUrl(context: Context, url: String): Boolean {
        val uri = url.toUri()
        if (uri.scheme != "https") return false
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        return launch(context, intent)
    }

    private fun upiIntent() = Intent(Intent.ACTION_VIEW, SupportConfig.upiPaymentUri.toUri())

    /** Installed apps that handle `upi://pay` (visible through the manifest's `<queries>`). */
    fun installedUpiAppCount(context: Context): Int {
        val pm = context.packageManager
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(upiIntent(), PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(upiIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        }
        return list.size
    }

    /**
     * Lets the user pick a UPI app. False when none is installed. A launch only means an app
     * opened; it says nothing about whether a payment was made.
     */
    fun openUpiApp(context: Context, chooserTitle: CharSequence): Boolean {
        val count = installedUpiAppCount(context)
        if (count == 0) return false
        val intent = if (count == 1) upiIntent() else Intent.createChooser(upiIntent(), chooserTitle)
        return launch(context, intent)
    }

    fun copyUpiId(context: Context) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("UPI ID", SupportConfig.UPI_ID))
    }

    fun createQrBitmap(sizePx: Int): Bitmap {
        val qr = UpiQrCode.encode(SupportConfig.upiPaymentUri, sizePx)
        return Bitmap.createBitmap(qr.argb, qr.width, qr.height, Bitmap.Config.ARGB_8888)
    }

    @Throws(IOException::class)
    fun writeQrPng(bitmap: Bitmap, out: OutputStream) {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) throw IOException("PNG encoding failed")
        out.flush()
    }

    /** Caches the PNG and opens the share sheet through the non-exported FileProvider. */
    @Throws(IOException::class)
    fun shareQr(context: Context, bitmap: Bitmap, text: String, chooserTitle: CharSequence): Boolean {
        val file = File(File(context.cacheDir, SHARE_DIR).apply { mkdirs() }, QR_FILE_NAME)
        file.outputStream().use { writeQrPng(bitmap, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(QR_FILE_NAME, uri)
        return launch(context, Intent.createChooser(send, chooserTitle))
    }

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
