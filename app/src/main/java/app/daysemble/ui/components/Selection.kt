package app.daysemble.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space

/** Filter / choice chip. Selected = inverted (ink on cream / cream on ink). */
@Composable
fun DsChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    /** Tab for filters/segments, Checkbox for multi-select, Button for actions. */
    role: Role = Role.Tab,
) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val bg = animateStateColor(if (selected) c.inverse else c.surfaceVariant, m.standard(m.medium), label = "chipBg")
    val fg = animateStateColor(if (selected) c.onInverse else c.textPrimary, m.standard(m.medium), label = "chipFg")
    // The 48dp row takes the touch; the press is shown on the pill the user sees (ripple clipped to
    // it, and a small press scale), not as a rectangle around it.
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = Sizes.touch)
            .then(
                when (role) {
                    Role.Checkbox -> Modifier.toggleable(
                        value = selected, interactionSource = interaction, indication = null, role = role, onValueChange = { onClick() },
                    )
                    Role.Button -> Modifier.clickable(interactionSource = interaction, indication = null, role = role, onClick = onClick)
                    else -> Modifier.selectable(selected = selected, interactionSource = interaction, indication = null, role = role, onClick = onClick)
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .pressScale(interaction)
                .clip(Radii.pill)
                .background(bg)
                .indication(interaction, LocalIndication.current)
                .padding(horizontal = Space.m + Space.xxs, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs + Space.xxs),
        ) {
            if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Text(text, style = DsTheme.type.label, color = fg, maxLines = 1)
        }
    }
}

/**
 * Segmented control with a sliding indicator (calendar view switcher, theme picker).
 * Segment widths are equal so the label layout never changes while the indicator moves.
 */
@Composable
fun <T> DsSegmented(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val index = options.indexOf(selected).coerceAtLeast(0)
    val animIndex by animateFloatAsState(index.toFloat(), m.indicator(), label = "seg")
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(Radii.pill)
            .background(c.surfaceVariant)
            .padding(2.dp),
    ) {
        val segW = maxWidth / options.size
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset((segW * animIndex).roundToPx(), 0) }
                .width(segW)
                .height(48.dp)
                .clip(Radii.pill)
                .background(c.surface)
                .border(BorderStroke(Sizes.hairline, c.outline), Radii.pill),
        )
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEachIndexed { i, opt ->
                val isSel = i == index
                // Same spring as the indicator, so the label lights up as the indicator arrives.
                val labelColor = animateStateColor(if (isSel) c.textPrimary else c.textSecondary, m.indicator(), label = "segLabel")
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(Radii.pill)
                        .selectable(selected = isSel, role = Role.Tab, onClick = { onSelect(opt) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(opt),
                        style = DsTheme.type.label,
                        color = labelColor,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Round task checkbox. Completion fills the circle and draws the tick in 120 ms.
 * [dashed] renders the "optional / habit" style seen in the references (used for subtasks).
 */
@Composable
fun DsCheck(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = DsTheme.colors.textPrimary,
    dashed: Boolean = false,
) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val progress by animateFloatAsState(if (checked) 1f else 0f, m.standard(m.short), label = "check")
    // Completing (or reopening) is a commit: a light tick on the same frame the circle starts to fill.
    val haptics = LocalHapticFeedback.current
    val change by rememberUpdatedState(onCheckedChange)
    Box(
        modifier
            .size(Sizes.touch)
            .clip(androidx.compose.foundation.shape.CircleShape) // round press feedback, like the circle it is
            .semantics { this.contentDescription = contentDescription }
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = {
                haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                change(it)
            })
            .padding(13.dp),
        contentAlignment = Alignment.Center,
    ) {
        val ringColor = if (checked) tint else c.outlineStrong
        Canvas(Modifier.size(Sizes.checkbox)) {
            val stroke = 1.75.dp.toPx()
            val r = size.minDimension / 2 - stroke / 2
            drawCircle(
                color = ringColor, radius = r,
                style = Stroke(
                    stroke,
                    pathEffect = if (dashed && !checked) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())) else null,
                ),
            )
            if (progress > 0f) {
                drawCircle(color = tint.copy(alpha = progress), radius = r * (0.6f + 0.4f * progress))
                // Tick, drawn progressively.
                val p1 = Offset(size.width * 0.30f, size.height * 0.52f)
                val p2 = Offset(size.width * 0.44f, size.height * 0.66f)
                val p3 = Offset(size.width * 0.71f, size.height * 0.38f)
                val tickColor = c.background
                val first = (progress * 2f).coerceAtMost(1f)
                drawLine(tickColor, p1, p1 + (p2 - p1) * first, stroke, StrokeCap.Round)
                if (progress > 0.5f) {
                    val second = (progress - 0.5f) * 2f
                    drawLine(tickColor, p2, p2 + (p3 - p2) * second, stroke, StrokeCap.Round)
                }
            }
        }
    }
}
