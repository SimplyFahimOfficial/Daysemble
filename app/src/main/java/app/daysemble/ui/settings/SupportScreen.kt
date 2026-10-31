package app.daysemble.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daysemble.R
import app.daysemble.support.SupportConfig
import app.daysemble.support.SupportIntents
import app.daysemble.support.UpiQr
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsCard
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.components.DsRow
import app.daysemble.ui.components.DsTopBar
import app.daysemble.ui.components.HairlineDivider
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.delay

/**
 * Support my work: Ko-fi and UPI first, then the project and contact links. Reached from Settings and
 * About only; the app never asks for support on its own. All details come from [SupportConfig].
 */
@Composable
fun SupportRoute(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    // Short status line for copy/share/save results, announced politely to screen readers.
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var failed by rememberSaveable { mutableStateOf(false) }
    fun say(text: String, error: Boolean = false) { message = text; failed = error }
    LaunchedEffect(message) { if (message != null) { delay(5_000); message = null } }

    fun open(url: String) {
        if (!SupportIntents.openUrl(ctx, url)) {
            SupportIntents.copy(ctx, url, url)
            say(res.getString(R.string.support_no_browser), error = true)
        }
    }
    fun copyUpiId() {
        if (SupportIntents.copy(ctx, res.getString(R.string.support_upi_id_label), SupportConfig.UPI_ID)) {
            say(res.getString(R.string.support_upi_id_copied))
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) {
            if (SupportIntents.saveQr(ctx, uri)) say(res.getString(R.string.support_qr_saved))
            else say(res.getString(R.string.support_qr_failed), error = true)
        }
    }

    Column(Modifier.fillMaxSize().background(DsTheme.colors.background)) {
        DsTopBar(stringResource(R.string.support_title), onBack)
        SettingsBody(loaded = true) {
            Text(
                stringResource(R.string.support_intro), style = DsTheme.type.body, color = DsTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
            )

            SectionHeader(stringResource(R.string.support_section_payment), Modifier.padding(top = Space.l))
            KofiCard(onOpen = { open(SupportConfig.KOFI_URL) })
            Spacer(Modifier.height(Space.m))
            UpiCard(
                onOpenUpi = {
                    if (!SupportIntents.openUpiApp(ctx, res.getString(R.string.support_upi_chooser))) {
                        SupportIntents.copy(ctx, res.getString(R.string.support_upi_id_label), SupportConfig.UPI_ID)
                        say(res.getString(R.string.support_no_upi_app), error = true)
                    }
                },
                onCopy = ::copyUpiId,
                onShare = {
                    val text = res.getString(R.string.support_qr_share_text, SupportConfig.UPI_ID, SupportConfig.UPI_PAYEE_NAME)
                    if (!SupportIntents.shareQr(ctx, res.getString(R.string.support_qr_share_chooser), text)) {
                        say(res.getString(R.string.support_qr_failed), error = true)
                    }
                },
                onSave = { saver.launch("simplyfahim-upi-qr.png") },
            )
            AnimatedVisibility(message != null, enter = fadeIn(DsTheme.motion.enter()), exit = fadeOut(DsTheme.motion.exit())) {
                Text(
                    message.orEmpty(), style = DsTheme.type.label,
                    color = if (failed) DsTheme.colors.error else DsTheme.colors.success,
                    modifier = Modifier
                        .padding(horizontal = Space.gutter, vertical = Space.s)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            HairlineDivider(Modifier.padding(top = Space.xl))
            SectionHeader(stringResource(R.string.support_section_project), Modifier.padding(top = Space.l))
            Text(
                stringResource(R.string.support_project_body), style = DsTheme.type.bodySmall, color = DsTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xs),
            )
            SupportConfig.REPOSITORY_URL?.let { repo ->
                LinkRow(stringResource(R.string.support_repository), repo, Icons.Rounded.Code) { open(repo) }
            }
            LinkRow(stringResource(R.string.support_website), SupportConfig.WEBSITE_URL, Icons.Rounded.Language) { open(SupportConfig.WEBSITE_URL) }
            LinkRow(stringResource(R.string.support_github_profile), SupportConfig.GITHUB_PROFILE_URL, Icons.Rounded.Person) {
                open(SupportConfig.GITHUB_PROFILE_URL)
            }
        }
    }
}

@Composable
private fun KofiCard(onOpen: () -> Unit) {
    val c = DsTheme.colors
    DsCard(Modifier.padding(horizontal = Space.gutter).fillMaxWidth()) {
        Column(Modifier.padding(Space.l)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Box(Modifier.size(40.dp).clip(Radii.pill).background(c.accentContainer), contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_kofi), null, tint = c.accentStrong, modifier = Modifier.size(Sizes.icon))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.support_kofi_title), style = DsTheme.type.title, color = c.textPrimary)
                    Text(displayUrl(SupportConfig.KOFI_URL), style = DsTheme.type.label, color = c.textTertiary)
                }
            }
            Spacer(Modifier.height(Space.s))
            Text(stringResource(R.string.support_kofi_body), style = DsTheme.type.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(Space.m))
            DsButton(
                stringResource(R.string.support_kofi_action), onOpen, style = DsButtonStyle.Accent,
                icon = ImageVector.vectorResource(R.drawable.ic_kofi), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun UpiCard(onOpenUpi: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit, onSave: () -> Unit) {
    val c = DsTheme.colors
    val qr = remember { UpiQr.bitmap().asImageBitmap() }
    val qrDescription = stringResource(R.string.support_upi_qr_description, SupportConfig.UPI_ID, SupportConfig.UPI_PAYEE_NAME)
    DsCard(Modifier.padding(horizontal = Space.gutter).fillMaxWidth()) {
        Column(Modifier.padding(Space.l)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Box(Modifier.size(40.dp).clip(Radii.pill).background(c.violetContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Payments, null, tint = c.violet, modifier = Modifier.size(Sizes.icon))
                }
                Text(stringResource(R.string.support_upi_title), style = DsTheme.type.title, color = c.textPrimary, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(Space.s))
            Text(stringResource(R.string.support_upi_body), style = DsTheme.type.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(Space.l))
            // Always dark-on-white, in dark theme too: scanners need the contrast and the quiet zone.
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = 260.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(Radii.card)
                    .background(Color.White)
                    .border(Sizes.hairline, c.outline, Radii.card)
                    .semantics { contentDescription = qrDescription },
            ) {
                Image(qr, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(Space.l))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.support_upi_id_label), style = DsTheme.type.caps, color = c.textTertiary)
                    SelectionContainer { Text(SupportConfig.UPI_ID, style = DsTheme.type.bodyStrong, color = c.textPrimary) }
                    Spacer(Modifier.height(Space.xs))
                    Text(stringResource(R.string.support_upi_payee_label), style = DsTheme.type.caps, color = c.textTertiary)
                    Text(SupportConfig.UPI_PAYEE_NAME, style = DsTheme.type.bodyStrong, color = c.textPrimary)
                }
                DsIconButton(Icons.Rounded.ContentCopy, stringResource(R.string.support_upi_copy), onCopy, container = c.surfaceVariant)
            }
            Spacer(Modifier.height(Space.l))
            DsButton(
                stringResource(R.string.support_upi_open), onOpenUpi, style = DsButtonStyle.Primary,
                icon = Icons.Rounded.Payments, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.s))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                DsButton(stringResource(R.string.support_qr_share), onShare, style = DsButtonStyle.Ghost, icon = Icons.Rounded.Share)
                DsButton(stringResource(R.string.support_qr_save), onSave, style = DsButtonStyle.Ghost, icon = Icons.Rounded.Download)
            }
            Spacer(Modifier.height(Space.m))
            Text(stringResource(R.string.support_upi_check, SupportConfig.UPI_PAYEE_NAME), style = DsTheme.type.label, color = c.textTertiary)
        }
    }
}

@Composable
private fun LinkRow(title: String, url: String, icon: ImageVector, onClick: () -> Unit) {
    DsRow(title, subtitle = displayUrl(url), icon = icon, onClick = onClick) {
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = DsTheme.colors.textTertiary, modifier = Modifier.size(Sizes.iconSmall))
    }
}

private fun displayUrl(url: String) = url.removePrefix("https://").removeSuffix("/")
