package app.daysemble.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import app.daysemble.ui.theme.Sizes

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
            ThemePicker(selected = theme, onSelect = onTheme, modifier = Modifier.padding(horizontal = Space.gutter))

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

/**
 * Light, Dark and System as small previews drawn from the real theme tokens (canvas, panel,
 * text, the orange date dot), so each option shows what it will look like. The choice is saved
 * in settings and applied at once; System follows the phone's dark-theme setting.
 */
@Composable
fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    val modes = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        if (threeFit(maxWidth)) {
            // Equal-height cards: a subtitle that wraps doesn't leave the others shorter.
            Row(
                Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                modes.forEach { mode -> ThemeOption(mode, mode == selected, { onSelect(mode) }, Modifier.weight(1f).fillMaxHeight(), stacked = false) }
            }
        } else {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                modes.forEach { mode -> ThemeOption(mode, mode == selected, { onSelect(mode) }, Modifier.fillMaxWidth(), stacked = true) }
            }
        }
    }
}

/**
 * Whether three option cards fit side by side without breaking words: with large text (or a
 * narrow phone) they stack as full-width rows instead ("Syste/m", "DEFA/ULT" otherwise).
 */
@Composable
private fun threeFit(width: androidx.compose.ui.unit.Dp): Boolean {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    return (width - Space.m * 2) / 3 >= 92.dp * fontScale
}

@Composable
private fun ThemeOption(mode: ThemeMode, selected: Boolean, onClick: () -> Unit, modifier: Modifier, stacked: Boolean) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val ring = app.daysemble.ui.components.animateStateColor(if (selected) c.accent else c.outline, m.standard(m.medium), label = "themeRing")
    val ringWidth by animateDpAsState(if (selected) 2.dp else 1.dp, m.standard(m.medium), label = "themeRingW")
    val name = stringResource(
        when (mode) {
            ThemeMode.LIGHT -> R.string.theme_light
            ThemeMode.DARK -> R.string.theme_dark
            ThemeMode.SYSTEM -> R.string.theme_system
        },
    )
    val sub = stringResource(
        when (mode) {
            ThemeMode.LIGHT -> R.string.theme_light_sub
            ThemeMode.DARK -> R.string.theme_dark_sub
            ThemeMode.SYSTEM -> R.string.theme_system_sub
        },
    )
    val selectedText = stringResource(R.string.state_selected)
    val card = modifier
        .clip(Radii.card)
        .border(ringWidth, ring, Radii.card)
        .background(c.surface)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .semantics(mergeDescendants = true) {
            contentDescription = "$name, $sub"
            stateDescription = if (selected) selectedText else ""
        }
        .padding(Space.s)
    if (stacked) {
        Row(card, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            ThemePreview(mode, Modifier.size(width = 88.dp, height = 56.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = DsTheme.type.label, color = c.textPrimary)
                Text(sub, style = DsTheme.type.caps, color = c.textSecondary)
            }
        }
        return
    }
    Column(card, horizontalAlignment = Alignment.CenterHorizontally) {
        ThemePreview(mode, Modifier.fillMaxWidth().height(72.dp))
        Spacer(Modifier.height(Space.s))
        Text(name, style = DsTheme.type.label, color = c.textPrimary, maxLines = 1)
        // Wraps rather than ellipsizing ("Follows your p…" on a three-across card).
        Text(sub, style = DsTheme.type.caps, color = c.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(Space.xxs))
    }
}

/** A miniature Today: canvas with a weekday bar and the orange dot, and the agenda panel below. */
@Composable
private fun ThemePreview(mode: ThemeMode, modifier: Modifier) {
    val border = DsTheme.colors.outline
    androidx.compose.foundation.Canvas(modifier.clip(Radii.control).border(Sizes.hairline, border, Radii.control)) {
        fun scene(p: app.daysemble.ui.theme.DsColors, left: Float, right: Float) {
            clipRect(left = left, right = right) {
                drawRect(p.background)
                val u = size.height / 12f
                drawRoundRect(p.textPrimary, topLeft = Offset(u * 1.6f, u * 1.6f), size = Size(u * 5f, u * 1.6f), cornerRadius = CornerRadius(u))
                drawCircle(p.accentFill, radius = u * 0.6f, center = Offset(u * 7.6f, u * 2.5f))
                drawRoundRect(p.textSecondary.copy(alpha = 0.5f), topLeft = Offset(u * 1.6f, u * 4.2f), size = Size(size.width * 0.55f, u * 0.9f), cornerRadius = CornerRadius(u))
                val top = u * 6.4f
                drawRoundRect(p.surface, topLeft = Offset(u * 0.8f, top), size = Size(size.width - u * 1.6f, size.height), cornerRadius = CornerRadius(u * 2f))
                drawRoundRect(p.outline, topLeft = Offset(u * 0.8f, top), size = Size(size.width - u * 1.6f, size.height), cornerRadius = CornerRadius(u * 2f), style = Stroke(1.dp.toPx()))
                drawCircle(p.inverse, radius = u * 0.95f, center = Offset(u * 3f, top + u * 2.2f))
                drawRoundRect(p.textSecondary.copy(alpha = 0.35f), topLeft = Offset(u * 5f, top + u * 1.7f), size = Size(size.width * 0.4f, u * 0.9f), cornerRadius = CornerRadius(u))
            }
        }
        when (mode) {
            ThemeMode.LIGHT -> scene(app.daysemble.ui.theme.LightDsColors, 0f, size.width)
            ThemeMode.DARK -> scene(app.daysemble.ui.theme.DarkDsColors, 0f, size.width)
            ThemeMode.SYSTEM -> {
                scene(app.daysemble.ui.theme.LightDsColors, 0f, size.width / 2)
                scene(app.daysemble.ui.theme.DarkDsColors, size.width / 2, size.width)
            }
        }
    }
}

@Composable
fun LogoPicker(selected: LogoVariant, onSelect: (LogoVariant) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        if (threeFit(maxWidth)) {
            Row(
                Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                LogoVariant.entries.forEach { v -> LogoOption(v, v == selected, { onSelect(v) }, Modifier.weight(1f).fillMaxHeight(), stacked = false) }
            }
        } else {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                LogoVariant.entries.forEach { v -> LogoOption(v, v == selected, { onSelect(v) }, Modifier.fillMaxWidth(), stacked = true) }
            }
        }
    }
}

@Composable
private fun LogoOption(variant: LogoVariant, selected: Boolean, onClick: () -> Unit, modifier: Modifier, stacked: Boolean) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val ring = app.daysemble.ui.components.animateStateColor(if (selected) c.accent else c.outline, m.standard(m.medium), label = "ring")
    val ringWidth by animateDpAsState(if (selected) 2.dp else 1.dp, m.standard(m.medium), label = "ringW")
    val name = stringResource(variant.label)
    val selectedText = stringResource(R.string.state_selected)
    val defaultText = stringResource(R.string.logo_default_badge)
    val card = modifier
        .clip(Radii.card)
        .border(ringWidth, ring, Radii.card)
        .background(c.surface)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .semantics(mergeDescendants = true) {
            contentDescription = if (variant == LogoVariant.DEFAULT) "$name, $defaultText" else name
            stateDescription = if (selected) selectedText else ""
        }
        .padding(Space.m)
    if (stacked) {
        Row(card, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            BrandLogo(variant = variant, size = 56.dp, decorative = true)
            Column(Modifier.weight(1f)) {
                Text(name, style = DsTheme.type.label, color = c.textPrimary)
                if (variant == LogoVariant.DEFAULT) Text(defaultText.uppercase(), style = DsTheme.type.caps, color = c.textSecondary)
            }
        }
        return
    }
    Column(card, horizontalAlignment = Alignment.CenterHorizontally) {
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
