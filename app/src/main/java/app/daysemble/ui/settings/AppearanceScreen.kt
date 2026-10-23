package app.daysemble.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.R
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.ui.brand.BrandLogo
import app.daysemble.ui.brand.LogoVariant
import app.daysemble.ui.components.DsSegmented
import app.daysemble.ui.components.DsTopBar
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.daysembleViewModel
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Space

@Composable
fun AppearanceRoute(onBack: (() -> Unit)?) {
    val vm = daysembleViewModel { c, _ -> AppearanceViewModel(c.settings, c.launcherIcon::apply) }
    val s by vm.state.collectAsStateWithLifecycle()
    // Until the page's own flow emits, use the settings the app is already running with (no empty frame).
    val settings = s ?: app.daysemble.ui.LocalAppSettings.current ?: return
    AppearanceScreen(
        theme = settings.themeMode,
        logo = settings.logo,
        reducedMotion = settings.reducedMotionOverride,
        onBack = onBack,
        onTheme = vm::setTheme,
        onLogo = vm::selectLogo,
        onReducedMotion = vm::setReducedMotion,
    )
}

private enum class MotionChoice(val value: Boolean?) { SYSTEM(null), REDUCED(true), FULL(false) }

@Composable
fun AppearanceScreen(
    theme: ThemeMode,
    logo: LogoVariant,
    reducedMotion: Boolean?,
    onBack: (() -> Unit)?,
    onTheme: (ThemeMode) -> Unit,
    onLogo: (LogoVariant) -> Unit,
    onReducedMotion: (Boolean?) -> Unit,
) {
    val c = DsTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background),
    ) {
        DsTopBar(stringResource(R.string.settings_appearance), onBack)
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = Space.x3),
        ) {
            SectionHeader(stringResource(R.string.settings_theme))
            DsSegmented(
                options = ThemeMode.entries,
                selected = theme,
                label = {
                    stringResource(
                        when (it) {
                            ThemeMode.SYSTEM -> R.string.theme_system
                            ThemeMode.LIGHT -> R.string.theme_light
                            ThemeMode.DARK -> R.string.theme_dark
                        },
                    )
                },
                onSelect = onTheme,
                modifier = Modifier.padding(horizontal = Space.gutter),
            )

            Spacer(Modifier.height(Space.xxl))
            SectionHeader(stringResource(R.string.settings_logo))
            LogoPicker(selected = logo, onSelect = onLogo, modifier = Modifier.padding(horizontal = Space.gutter))
            Text(
                stringResource(R.string.logo_launcher_note),
                style = DsTheme.type.bodySmall,
                color = c.textSecondary,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.m),
            )
            val anyMissing = LogoVariant.entries.any { !booleanResource(it.suppliedFlag) }
            if (anyMissing) {
                Row(
                    Modifier
                        .padding(horizontal = Space.gutter)
                        .clip(Radii.control)
                        .background(c.surfaceVariant)
                        .padding(Space.m),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Icon(Icons.Rounded.Info, null, tint = c.textSecondary)
                    Text(stringResource(R.string.logo_placeholder_note), style = DsTheme.type.bodySmall, color = c.textSecondary)
                }
            }

            Spacer(Modifier.height(Space.xxl))
            SectionHeader(stringResource(R.string.settings_motion))
            DsSegmented(
                options = MotionChoice.entries,
                selected = MotionChoice.entries.first { it.value == reducedMotion },
                label = {
                    stringResource(
                        when (it) {
                            MotionChoice.SYSTEM -> R.string.motion_system
                            MotionChoice.REDUCED -> R.string.motion_reduced
                            MotionChoice.FULL -> R.string.motion_full
                        },
                    )
                },
                onSelect = { onReducedMotion(it.value) },
                modifier = Modifier.padding(horizontal = Space.gutter),
            )
            Text(
                stringResource(R.string.motion_note),
                style = DsTheme.type.bodySmall,
                color = c.textSecondary,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.m),
            )
        }
    }
}

@Composable
fun LogoPicker(selected: LogoVariant, onSelect: (LogoVariant) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        LogoVariant.entries.forEach { v ->
            LogoOption(v, v == selected, { onSelect(v) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LogoOption(variant: LogoVariant, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val ring by animateColorAsState(if (selected) c.accent else c.outline, m.standard(m.medium), label = "ring")
    val ringWidth by animateDpAsState(if (selected) 2.dp else 1.dp, m.standard(m.medium), label = "ringW")
    val name = stringResource(variant.label)
    val selectedText = stringResource(R.string.state_selected)
    val defaultText = stringResource(R.string.logo_default_badge)
    Column(
        modifier
            .clip(Radii.card)
            .border(ringWidth, ring, Radii.card)
            .background(c.surface)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = if (variant == LogoVariant.DEFAULT) "$name, $defaultText" else name
                stateDescription = if (selected) selectedText else ""
            }
            .padding(Space.m),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(vertical = Space.xs), contentAlignment = Alignment.Center) {
            BrandLogo(variant = variant, size = 72.dp, decorative = true)
        }
        Spacer(Modifier.height(Space.s))
        Text(name, style = DsTheme.type.label, color = c.textPrimary, textAlign = TextAlign.Center, maxLines = 1)
        Box(Modifier.heightIn(min = 20.dp), contentAlignment = Alignment.Center) {
            if (variant == LogoVariant.DEFAULT) {
                Text(defaultText.uppercase(), style = DsTheme.type.caps, color = c.textSecondary)
            }
        }
    }
}
