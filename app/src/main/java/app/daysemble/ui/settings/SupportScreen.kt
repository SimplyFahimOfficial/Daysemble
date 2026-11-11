package app.daysemble.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import app.daysemble.ui.components.DsDialogAction
import app.daysemble.ui.components.DsDialogButtons
import app.daysemble.ui.theme.Radii
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daysemble.R
import app.daysemble.support.SupportConfig
import app.daysemble.support.SupportIntents
import app.daysemble.support.UpiQrCode
import app.daysemble.ui.LocalSnackbar
import app.daysemble.ui.components.AccentCard
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsCard
import app.daysemble.ui.components.DsRow
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val COPIED_FEEDBACK_MS = 2_000L
private val QrSize = 120.dp
private val EnlargedQrMaxSize = 320.dp

/** What the Support page can do. Plain lambdas keep [SupportContent] stateless and testable. */
@Immutable
internal class SupportActions(
    val onOpenUrl: (String) -> Unit,
    val onCopyUpiId: () -> Unit,
    val onOpenUpiApp: () -> Unit,
    val onShareQr: () -> Unit,
    val onSaveQr: () -> Unit,
)

/** Shows a short snackbar message from a string resource. Shared by the Help and Support pages. */
@Composable
internal fun rememberSay(): (Int) -> Unit {
    val res = LocalResources.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    return { id -> scope.launch { snackbar.showSnackbar(res.getString(id), duration = SnackbarDuration.Short) } }
}

/**
 * Help & Support › Support My Work: the optional, native UPI donation page. Nothing is collected
 * or stored, and no payment is ever reported as made.
 */
@Composable
fun SupportRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val say = rememberSay()
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SupportIntents.QR_MIME_TYPE)) { uri ->
        if (uri != null) say(if (SupportIntents.saveQr(context, uri)) R.string.support_qr_saved else R.string.support_qr_save_failed)
    }
    val actions = remember(context) {
        SupportActions(
            onOpenUrl = { url -> if (!SupportIntents.openUrl(context, url)) say(R.string.support_no_app_for_link) },
            onCopyUpiId = { SupportIntents.copyUpiId(context) },
            onOpenUpiApp = {
                if (SupportIntents.openUpiApp(context) == SupportIntents.UpiResult.NO_UPI_APP) say(R.string.support_no_upi_app)
            },
            onShareQr = { if (!SupportIntents.shareQr(context)) say(R.string.support_share_qr_failed) },
            onSaveQr = { saveLauncher.launch(SupportIntents.QR_FILE_NAME) },
        )
    }
    SettingsScaffold(stringResource(R.string.support_my_work), onBack) { SupportContent(actions) }
}

@Composable
internal fun SupportContent(actions: SupportActions) {
    val c = DsTheme.colors
    Text(
        stringResource(R.string.support_intro),
        style = DsTheme.type.bodySmall,
        color = c.textPrimary,
        modifier = Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.s, bottom = Space.xs),
    )
    DsCard(Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.xs).fillMaxWidth()) {
        UpiSection(actions)
    }
    AccentCard(Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.s)) {
        DsRow(
            stringResource(R.string.support_more_ways),
            subtitle = stringResource(R.string.support_more_ways_sub),
            icon = Icons.Rounded.Payments,
            onClick = { actions.onOpenUrl(SupportConfig.PAY_OPTIONS_URL) },
            trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = c.textTertiary, modifier = Modifier.size(18.dp)) },
        )
    }
    Text(
        stringResource(R.string.support_optional_note),
        style = DsTheme.type.bodySmall,
        color = c.textSecondary,
        modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xs),
    )
}

@Composable
private fun UpiSection(actions: SupportActions) {
    val c = DsTheme.colors
    Column(Modifier.padding(Space.m)) {
        Text(
            stringResource(R.string.support_upi),
            style = DsTheme.type.title,
            color = c.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.support_upi_scan_hint), style = DsTheme.type.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(Space.s))
        // QR beside the payee details, so the whole page fits on one phone screen.
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            UpiQr()
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.support_upi_id_label).uppercase(), style = DsTheme.type.caps, color = c.textSecondary)
                SelectionContainer {
                    Text(SupportConfig.UPI_ID, style = DsTheme.type.bodyStrong, color = c.textPrimary)
                }
                Text(
                    stringResource(R.string.support_upi_payee, SupportConfig.UPI_PAYEE_NAME),
                    style = DsTheme.type.bodySmall,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(Space.s))
                CopyUpiIdButton(actions.onCopyUpiId, Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(Space.s))
        DsButton(
            stringResource(R.string.support_pay_with_upi), actions.onOpenUpiApp, Modifier.fillMaxWidth(),
            style = DsButtonStyle.Accent, icon = Icons.Rounded.Favorite,
        )
        Spacer(Modifier.height(Space.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            DsButton(
                stringResource(R.string.support_share_qr), actions.onShareQr, Modifier.weight(1f),
                style = DsButtonStyle.Secondary, icon = Icons.Rounded.Share,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.m, vertical = Space.m),
            )
            DsButton(
                stringResource(R.string.support_save_qr), actions.onSaveQr, Modifier.weight(1f),
                style = DsButtonStyle.Secondary, icon = Icons.Rounded.Download,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.m, vertical = Space.m),
            )
        }
        Spacer(Modifier.height(Space.s))
        Text(
            stringResource(R.string.support_upi_verify_hint, SupportConfig.UPI_PAYEE_NAME),
            style = DsTheme.type.bodySmall,
            color = c.textSecondary,
        )
    }
}

/** The QR on its small tile. Tapping it opens [EnlargedQr]. */
@Composable
private fun UpiQr(modifier: Modifier = Modifier) {
    val bitmap = remember { UpiQrCode.toBitmap(UpiQrCode.encode(SupportConfig.upiPaymentUri())).asImageBitmap() }
    val description = stringResource(R.string.support_upi_qr_description, SupportConfig.UPI_ID, SupportConfig.UPI_PAYEE_NAME)
    var enlarged by rememberSaveable { mutableStateOf(false) }
    QrTile(
        bitmap,
        modifier
            .clickable(onClickLabel = stringResource(R.string.support_enlarge_qr), role = Role.Button) { enlarged = true }
            .semantics(mergeDescendants = true) { contentDescription = description },
        Modifier.size(QrSize),
    )
    if (enlarged) EnlargedQr(bitmap, description, onClose = { enlarged = false })
}

/** The QR as large as the dialog allows, easy to scan from another phone, with Close below it. */
@Composable
private fun EnlargedQr(bitmap: ImageBitmap, description: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        shape = Radii.dialog,
        containerColor = DsTheme.colors.surfaceRaised,
        text = {
            // Scrolls in landscape, where the square QR plus Close is taller than the screen.
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                QrTile(
                    bitmap,
                    Modifier.semantics(mergeDescendants = true) { contentDescription = description },
                    Modifier.widthIn(max = EnlargedQrMaxSize).fillMaxWidth().aspectRatio(1f),
                    padding = Space.m,
                )
            }
        },
        confirmButton = {
            DsDialogButtons(listOf(DsDialogAction(stringResource(R.string.action_close), onClose, DsDialogAction.Kind.Confirm)), stacked = true)
        },
    )
}

/** Black on a white tile in both themes; one pixel per module, scaled without filtering. */
@Composable
private fun QrTile(bitmap: ImageBitmap, modifier: Modifier, imageModifier: Modifier, padding: Dp = 6.dp) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(padding)) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = imageModifier,
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.None,
        )
    }
}

@Composable
private fun CopyUpiIdButton(onCopy: () -> Unit, modifier: Modifier = Modifier) {
    var copied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_FEEDBACK_MS)
            copied = false
        }
    }
    AnimatedContent(
        targetState = copied,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        label = "copy_upi_id",
    ) { isCopied ->
        DsButton(
            stringResource(if (isCopied) R.string.support_upi_id_copied else R.string.support_copy_upi_id),
            onClick = {
                onCopy()
                copied = true
            },
            modifier = Modifier.fillMaxWidth(),
            style = DsButtonStyle.Secondary,
            icon = if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.m, vertical = Space.m),
        )
    }
}
