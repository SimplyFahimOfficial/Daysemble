package app.daysemble.support

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.daysemble.R
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Android side of the Support screen: opening links and UPI apps, copying the UPI ID, and saving
 * or sharing the QR code. Every action is started by the user, never throws, and returns what
 * happened so the screen can tell the user. Nothing is stored or sent anywhere.
 */
object SupportIntents {
    const val QR_FILE_NAME = "SimplyFahim-UPI-QR.png"
    const val QR_MIME_TYPE = "image/png"

    /** Must match the FileProvider in the manifest and res/xml/support_share_paths.xml. */
    private const val AUTHORITY_SUFFIX = ".supportshare"
    private const val SHARE_DIR = "support_share"
    private const val PNG_QUALITY = 100

    internal fun createShareQrFile(cacheDir: File): File {
        val directory = File(cacheDir, SHARE_DIR).apply { mkdirs() }
        return File.createTempFile("SimplyFahim-UPI-QR-", ".png", directory)
    }

    enum class UpiResult { OPENED, NO_UPI_APP }

    /** Opens an https link. Returns false if no app can (the link is then copied). */
    fun openUrl(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            copy(context, "link", url)
            false
        }
    }

    fun copyUpiId(context: Context) = copy(context, "UPI ID", SupportConfig.UPI_ID)

    private fun copy(context: Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /**
     * Opens the user's UPI app with the payee filled in: directly if there is one, with a chooser
     * if there are several. With none, copies the UPI ID. Launching a UPI app says nothing about
     * whether a payment is made.
     */
    fun openUpiApp(context: Context): UpiResult {
        val pay = Intent(Intent.ACTION_VIEW, SupportConfig.upiPaymentUri().toUri())

        // Visible through the `upi` <queries> entry in the manifest.
        @Suppress("DEPRECATION")
        val apps = context.packageManager.queryIntentActivities(pay, 0)
        return try {
            when {
                apps.isEmpty() -> throw ActivityNotFoundException("No UPI app installed")
                apps.size == 1 -> context.startActivity(pay)
                else -> context.startActivity(Intent.createChooser(pay, context.getString(R.string.support_choose_upi_app)))
            }
            UpiResult.OPENED
        } catch (_: ActivityNotFoundException) {
            copyUpiId(context)
            UpiResult.NO_UPI_APP
        }
    }

    private fun writeQrPng(out: OutputStream) {
        val bitmap = UpiQrCode.toBitmap(UpiQrCode.encode(SupportConfig.upiPaymentUri()), UpiQrCode.EXPORT_PIXELS_PER_MODULE)
        try {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) { "PNG encoding failed" }
        } finally {
            bitmap.recycle()
        }
    }

    /** Writes the QR PNG to [uri] (from ACTION_CREATE_DOCUMENT, so no storage permission). */
    fun saveQr(context: Context, uri: Uri): Boolean = try {
        val stream = context.contentResolver.openOutputStream(uri) ?: throw IOException("No output stream")
        stream.use(::writeQrPng)
        true
    } catch (_: IOException) {
        false
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** Shares the QR PNG through the system share sheet with temporary read access only. */
    fun shareQr(context: Context): Boolean = try {
        val file = createShareQrFile(context.cacheDir)
        file.outputStream().use(::writeQrPng)
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        val text = context.getString(R.string.support_share_qr_text, SupportConfig.UPI_ID, SupportConfig.UPI_PAYEE_NAME)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = QR_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri(QR_FILE_NAME, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.support_share_qr)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        true
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IllegalStateException) {
        false
    } catch (_: ActivityNotFoundException) {
        false
    }
}
