package app.daysemble.support

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

/** Android hand-offs for the Support page. None of them reports or records a payment. */
object SupportIntents {
    private const val QR_DIR = "support"
    private const val QR_FILE = "daysemble-upi-qr.png"

    /** Opens an http(s) link in the user's browser. False when no app can open it. */
    fun openUrl(context: Context, url: String): Boolean {
        val uri = url.toUri()
        if (uri.scheme != "https" && uri.scheme != "http") return false
        return start(context, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    private fun upiIntent() = Intent(Intent.ACTION_VIEW, SupportConfig.upiPaymentUri.toUri())

    /** Whether any installed app handles `upi://pay` links (visible through the manifest's `<queries>`). */
    fun hasUpiApp(context: Context): Boolean =
        context.packageManager.queryIntentActivities(upiIntent(), 0).isNotEmpty()

    /**
     * Lets the user pick a UPI app, prefilled with the payee and no amount. Returns false when no UPI
     * app is installed. A true result only means an app opened, never that a payment was made.
     */
    fun openUpiApp(context: Context, chooserTitle: String): Boolean {
        if (!hasUpiApp(context)) return false
        return start(context, Intent.createChooser(upiIntent(), chooserTitle))
    }

    /**
     * Copies [text]. Returns true when the app should confirm it itself: Android 13 and later show
     * their own clipboard confirmation, so a second message would be noise there.
     */
    fun copy(context: Context, label: String, text: String): Boolean {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    }

    /** Shares the QR code as a PNG through the system share sheet (read-only, via FileProvider). */
    fun shareQr(context: Context, chooserTitle: String, text: String): Boolean {
        val uri = runCatching { qrFileUri(context) }.getOrElse { return false }
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        return start(context, Intent.createChooser(send, chooserTitle))
    }

    /** Writes the QR PNG to a document the user picked (Storage Access Framework: no storage permission). */
    fun saveQr(context: Context, target: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(target, "wt")?.use { UpiQr.writePng(it) } ?: error("no stream")
    }.isSuccess

    private fun qrFileUri(context: Context): Uri {
        val dir = File(context.cacheDir, QR_DIR).apply { mkdirs() }
        val file = File(dir, QR_FILE)
        file.outputStream().use { UpiQr.writePng(it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.support.files", file)
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
