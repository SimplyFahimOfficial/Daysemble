package app.daysemble.ui.settings

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.daysemble.BuildConfig
import app.daysemble.R
import app.daysemble.support.SupportConfig
import app.daysemble.support.SupportIntents
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsCard
import app.daysemble.ui.components.DsRow
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ko-fi's brand red, used only to tint the Ko-fi mark. */
private val KofiRed = Color(0xFFFF5E5B)

/** Sharp when enlarged or saved, and quick to encode. */
private const val QR_SIZE_PX = 768

object SupportTags {
    const val QR = "support_qr"
}

/**
 * Settings > Support My Work. Voluntary Ko-fi and UPI support, kept apart from the project and
 * contact links. Values come from [SupportConfig]; the QR code is generated on the device.
 */
@Composable
fun SupportRoute(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = DsTheme.colors
    val motion = DsTheme.motion
    var showLargeQr by rememberSaveable { mutableStateOf(false) }

    // Inline status line (live region), like the Data page's export/import messages.
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var messageId by remember { mutableIntStateOf(0) }
    fun say(text: String, isError: Boolean = false) {
        message = text; failed = isError; messageId++
    }
    LaunchedEffect(messageId) {
        if (message != null) { delay(4_000); message = null }
    }

    val qr by produceState<Bitmap?>(null) {
        value = withContext(Dispatchers.Default) { SupportIntents.createQrBitmap(QR_SIZE_PX) }
    }

    val linkFailed = stringResource(R.string.support_link_failed)
    val open: (String) -> Unit = { url -> if (!SupportIntents.openUrl(ctx, url)) say(linkFailed, true) }

    val saved = stringResource(R.string.support_qr_saved)
    val saveFailed = stringResource(R.string.support_qr_save_failed)
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bmp = qr
        if (uri == null || bmp == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { SupportIntents.writeQrPng(bmp, it) } ?: error("no stream")
                }.isSuccess
            }
            if (ok) say(saved) else say(saveFailed, true)
        }
    }

    SettingsScaffold(stringResource(R.string.support_title), onBack) {
        Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.s)) {
            Icon(Icons.Rounded.Favorite, null, tint = c.accentStrong, modifier = Modifier.padding(top = Space.xxs).size(Sizes.icon))
            Spacer(Modifier.width(Space.m))
            Text(stringResource(R.string.support_intro), style = DsTheme.type.body, color = c.textSecondary)
        }

        // Payment options
        SectionHeader(stringResource(R.string.support_section_payment), Modifier.padding(top = Space.l).semantics { heading() })

        DsCard(Modifier.padding(horizontal = Space.gutter).fillMaxWidth(), onClick = { open(SupportConfig.KOFI_URL) }) {
            Row(Modifier.padding(Space.l), verticalAlignment = Alignment.CenterVertically) {
                Badge(KofiRed) { Icon(painterResource(R.drawable.ic_kofi), null, tint = KofiRed, modifier = Modifier.size(Sizes.icon)) }
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.support_kofi_title), style = DsTheme.type.title, color = c.textPrimary)
                    Text(SupportConfig.displayUrl(SupportConfig.KOFI_URL), style = DsTheme.type.bodySmall, color = c.textSecondary)
                }
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = c.textTertiary, modifier = Modifier.size(Sizes.iconSmall))
            }
        }

        Spacer(Modifier.height(Space.m))

        DsCard(Modifier.padding(horizontal = Space.gutter).fillMaxWidth()) {
            Column(Modifier.padding(Space.l)) {
                Text(
                    stringResource(R.string.support_upi_title), style = DsTheme.type.title, color = c.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(Space.xs))
                Text(stringResource(R.string.support_upi_body), style = DsTheme.type.bodySmall, color = c.textSecondary)
                Spacer(Modifier.height(Space.l))

                QrTile(qr, reduced = motion.reduced, onEnlarge = { showLargeQr = true }, modifier = Modifier.align(Alignment.CenterHorizontally))

                Spacer(Modifier.height(Space.l))
                val copied = stringResource(R.string.support_upi_id_copied)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(stringResource(R.string.support_upi_id_label).uppercase(), style = DsTheme.type.caps, color = c.textTertiary)
                        Text(SupportConfig.UPI_ID, style = DsTheme.type.titleLarge, color = c.textPrimary)
                        Text(stringResource(R.string.support_upi_payee, SupportConfig.UPI_PAYEE_NAME), style = DsTheme.type.label, color = c.textSecondary)
                    }
                    IconButton(onClick = { SupportIntents.copyUpiId(ctx); say(copied) }) {
                        Icon(Icons.Rounded.ContentCopy, stringResource(R.string.support_copy_upi_id), tint = c.textPrimary)
                    }
                }

                Spacer(Modifier.height(Space.l))
                val chooser = stringResource(R.string.support_choose_upi_app)
                val noUpi = stringResource(R.string.support_no_upi_app)
                DsButton(
                    stringResource(R.string.support_open_upi_app),
                    { if (!SupportIntents.openUpiApp(ctx, chooser)) say(noUpi, true) },
                    Modifier.fillMaxWidth(), style = DsButtonStyle.Primary,
                )
                Spacer(Modifier.height(Space.s))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    val shareText = stringResource(R.string.support_share_text, SupportConfig.UPI_ID, SupportConfig.UPI_PAYEE_NAME)
                    val shareTitle = stringResource(R.string.support_share_qr)
                    val shareFailed = stringResource(R.string.support_qr_share_failed)
                    DsButton(
                        shareTitle,
                        {
                            val bmp = qr
                            if (bmp != null) scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching { SupportIntents.shareQr(ctx, bmp, shareText, shareTitle) }.getOrDefault(false)
                                }
                                if (!ok) say(shareFailed, true)
                            }
                        },
                        Modifier.weight(1f), style = DsButtonStyle.Secondary, icon = Icons.Rounded.Share, enabled = qr != null,
                    )
                    DsButton(
                        stringResource(R.string.support_save_qr), { saver.launch(SupportIntents.QR_FILE_NAME) },
                        Modifier.weight(1f), style = DsButtonStyle.Secondary, icon = Icons.Rounded.Download, enabled = qr != null,
                    )
                }
                val fadeMs = if (motion.reduced) 0 else 160
                AnimatedVisibility(message != null, enter = fadeIn(tween(fadeMs)), exit = fadeOut(tween(fadeMs))) {
                    Text(
                        message.orEmpty(), style = DsTheme.type.label, color = if (failed) c.error else c.success,
                        modifier = Modifier.padding(top = Space.s).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Spacer(Modifier.height(Space.m))
                Text(stringResource(R.string.support_upi_note, SupportConfig.UPI_PAYEE_NAME), style = DsTheme.type.label, color = c.textTertiary)
            }
        }

        // About Daysemble: project and contact links, separate from payments.
        SectionHeader(stringResource(R.string.support_section_about), Modifier.padding(top = Space.xl).semantics { heading() })
        Text(
            stringResource(R.string.support_project_info, SupportConfig.DEVELOPER_NAME, BuildConfig.VERSION_NAME),
            style = DsTheme.type.bodySmall, color = c.textSecondary,
            modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xs),
        )
        val external: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = c.textTertiary, modifier = Modifier.size(Sizes.iconSmall))
        }
        // Daysemble's own repository is private, so its link is only shown once SupportConfig has a public one.
        SupportConfig.PROJECT_REPOSITORY_URL?.let { repo ->
            DsRow(stringResource(R.string.support_repository), subtitle = SupportConfig.displayUrl(repo), onClick = { open(repo) }, trailing = external)
        }
        DsRow(
            stringResource(R.string.support_website), subtitle = SupportConfig.displayUrl(SupportConfig.WEBSITE_URL),
            icon = Icons.Rounded.Language, onClick = { open(SupportConfig.WEBSITE_URL) }, trailing = external,
        )
        DsRow(
            stringResource(R.string.support_github_profile), subtitle = SupportConfig.displayUrl(SupportConfig.GITHUB_PROFILE_URL),
            icon = Icons.Rounded.Person, onClick = { open(SupportConfig.GITHUB_PROFILE_URL) }, trailing = external,
        )
    }

    val bmp = qr
    if (showLargeQr && bmp != null) LargeQrDialog(bmp) { showLargeQr = false }
}

@Composable
private fun Badge(tint: Color, content: @Composable () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { content() }
}

/** Dark modules on white in both themes, so every scanner can read it. */
@Composable
private fun QrTile(bitmap: Bitmap?, reduced: Boolean, onEnlarge: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.support_upi_qr_description, SupportConfig.UPI_PAYEE_NAME, SupportConfig.UPI_ID)
    Box(
        modifier
            .widthIn(max = 240.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(Radii.card)
            .background(Color.White)
            .clickable(role = Role.Image, onClickLabel = stringResource(R.string.support_upi_enlarge), onClick = onEnlarge)
            .testTag(SupportTags.QR),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            bitmap,
            transitionSpec = { val ms = if (reduced) 0 else 200; fadeIn(tween(ms)) togetherWith fadeOut(tween(ms)) },
            label = "qr",
        ) { b ->
            if (b == null) {
                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(28.dp))
            } else {
                Image(
                    remember(b) { b.asImageBitmap() }, description,
                    filterQuality = FilterQuality.None, modifier = Modifier.padding(Space.s),
                )
            }
        }
    }
}

@Composable
private fun LargeQrDialog(bitmap: Bitmap, onDismiss: () -> Unit) {
    val c = DsTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(Radii.dialog).background(c.surfaceRaised).padding(Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.support_scan_title), style = DsTheme.type.titleLarge, color = c.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Space.l))
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                Image(
                    remember(bitmap) { bitmap.asImageBitmap() },
                    stringResource(R.string.support_upi_qr_description, SupportConfig.UPI_PAYEE_NAME, SupportConfig.UPI_ID),
                    filterQuality = FilterQuality.None, modifier = Modifier.padding(Space.s),
                )
            }
            Spacer(Modifier.height(Space.m))
            Text(SupportConfig.UPI_ID, style = DsTheme.type.title, color = c.textPrimary)
            Text(SupportConfig.UPI_PAYEE_NAME, style = DsTheme.type.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(Space.l))
            DsButton(stringResource(R.string.support_close), onDismiss, Modifier.fillMaxWidth(), style = DsButtonStyle.Secondary)
        }
    }
}
