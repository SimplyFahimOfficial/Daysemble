package app.daysemble.ui.settings

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.daysemble.R
import app.daysemble.support.SupportConfig
import app.daysemble.support.SupportIntents
import app.daysemble.ui.components.AccentCard
import app.daysemble.ui.components.DsRow
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.components.SettingsCard
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space

/**
 * Settings › Help & Support. Getting help never goes through the payment options: donations are
 * one optional row ([onOpenSupport]) to their own page.
 */
@Composable
fun HelpRoute(onBack: () -> Unit, onOpenSupport: () -> Unit) {
    val context = LocalContext.current
    val say = rememberSay()
    val openUrl = remember(context) { { url: String -> if (!SupportIntents.openUrl(context, url)) say(R.string.support_no_app_for_link) } }
    SettingsScaffold(stringResource(R.string.settings_help), onBack) { HelpContent(openUrl, onOpenSupport) }
}

@Composable
internal fun HelpContent(onOpenUrl: (String) -> Unit, onOpenSupport: () -> Unit) {
    val tertiary = DsTheme.colors.textTertiary
    val external: @Composable RowScope.() -> Unit = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = tertiary, modifier = Modifier.size(18.dp)) }
    val chevron: @Composable RowScope.() -> Unit = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = tertiary) }

    // No "Report a bug" row: the repository is private (see SupportConfig).
    SectionHeader(stringResource(R.string.help_section_get_help))
    SettingsCard {
        DsRow(
            stringResource(R.string.help_telegram), subtitle = stringResource(R.string.help_telegram_sub),
            icon = Icons.Rounded.Forum, onClick = { onOpenUrl(SupportConfig.TELEGRAM_URL) }, trailing = external,
        )
    }
    SectionHeader(stringResource(R.string.help_section_project), Modifier.padding(top = Space.l))
    SettingsCard {
        DsRow(
            stringResource(R.string.help_website), subtitle = stringResource(R.string.help_website_sub),
            icon = Icons.Rounded.Language, onClick = { onOpenUrl(SupportConfig.PROJECT_WEBSITE) }, trailing = external,
        )
    }
    SectionHeader(stringResource(R.string.help_section_support), Modifier.padding(top = Space.l))
    AccentCard(Modifier.padding(horizontal = Space.l)) {
        DsRow(
            stringResource(R.string.support_my_work), subtitle = stringResource(R.string.support_my_work_sub),
            icon = Icons.Rounded.Favorite, onClick = onOpenSupport, trailing = chevron,
        )
    }
    SectionHeader(stringResource(R.string.help_section_developer), Modifier.padding(top = Space.l))
    SettingsCard {
        DsRow(
            stringResource(R.string.help_contact), subtitle = stringResource(R.string.help_contact_sub),
            icon = Icons.Rounded.Person, onClick = { onOpenUrl(SupportConfig.CONTACT_URL) }, trailing = external,
        )
    }
}
