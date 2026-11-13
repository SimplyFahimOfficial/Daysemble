package app.daysemble.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space

/** Flat card: surface + hairline border. Elevation is tonal, never a drop shadow. */
@Composable
fun DsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = DsTheme.colors
    Column(
        modifier
            .clip(Radii.card)
            .background(c.surface)
            .border(Sizes.hairline, c.outline, Radii.card)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        content = content,
    )
}

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = Space.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = DsTheme.type.caps,
            color = DsTheme.colors.textSecondary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        trailing?.invoke(this)
    }
}

/** Dashed hairline separator used between timeline rows (editorial look from the references). */
@Composable
fun DashedDivider(modifier: Modifier = Modifier, color: Color = DsTheme.colors.outline) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(Sizes.hairline),
    ) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier, inset: Boolean = false) {
    Box(
        modifier
            .padding(start = if (inset) Space.gutter else 0.dp)
            .fillMaxWidth()
            .height(Sizes.hairline)
            .background(DsTheme.colors.outline),
    )
}

/** Size of the tile behind a row's icon, and how strongly it is tinted. */
private val ICON_TILE = 36.dp
private const val TILE_ALPHA = 0.12f

/**
 * A group of settings rows in one rounded card. Put [CardDivider] between the rows.
 */
@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.l)
            .clip(Radii.tile)
            .background(DsTheme.colors.surfaceVariant),
        content = content,
    )
}

/**
 * A [SettingsCard] tinted with the accent and outlined by it, for the one row a page wants to stand
 * out (Help & Support, Support My Work, More Ways to Pay). Callers add the horizontal margin.
 */
@Composable
fun AccentCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val accent = DsTheme.colors.accent
    Column(
        modifier
            .fillMaxWidth()
            .clip(Radii.tile)
            .background(accent.copy(alpha = 0.10f))
            .border(Sizes.hairline, accent.copy(alpha = 0.35f), Radii.tile),
        content = content,
    )
}

/** The hairline between two rows of a [SettingsCard], inset past the icon tile. */
@Composable
fun CardDivider() = HairlineDivider(Modifier.padding(start = Space.gutter), inset = false)

/** Standard list row: optional leading icon, title, optional subtitle, optional trailing slot. */
@Composable
fun DsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    role: Role = Role.Button,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = DsTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.rowMin)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = role, onClick = onClick) else Modifier)
            .padding(horizontal = Space.gutter, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        if (icon != null) {
            // A soft tinted tile behind the icon, in the accent unless a tint was asked for.
            val tint = if (!enabled) c.textTertiary else if (iconTint.isSpecified) iconTint else c.accent
            Box(
                Modifier.size(ICON_TILE).clip(Radii.control).background(tint.copy(alpha = TILE_ALPHA)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = tint, modifier = Modifier.size(Sizes.icon)) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = DsTheme.type.bodyStrong, color = if (enabled) c.textPrimary else c.textTertiary)
            if (subtitle != null) {
                Spacer(Modifier.height(Space.xxs))
                Text(
                    subtitle, style = DsTheme.type.bodySmall, color = c.textSecondary,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke(this)
    }
}

/** Single-choice row; place inside a Column with Modifier.selectableGroup(). */
@Composable
fun DsRadioRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    DsRow(
        title = title, subtitle = subtitle,
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        if (selected) Icon(androidx.compose.material.icons.Icons.Rounded.Check, null, tint = DsTheme.colors.accent)
    }
}

@Composable
fun DsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    DsRow(
        title = title, subtitle = subtitle, icon = icon, enabled = enabled,
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Spacer(Modifier.width(Space.xs))
        Switch(
            checked = checked,
            onCheckedChange = null, // whole row is the toggle target
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DsTheme.colors.onInverse,
                checkedTrackColor = DsTheme.colors.inverse,
                uncheckedThumbColor = DsTheme.colors.outlineStrong,
                uncheckedTrackColor = DsTheme.colors.surfaceVariant,
                uncheckedBorderColor = DsTheme.colors.outlineStrong,
            ),
        )
    }
}
