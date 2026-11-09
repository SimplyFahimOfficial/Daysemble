package app.daysemble.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space

/**
 * Primary: ink pill. Accent: coral. Tint: the ink at 8 % (empty-state actions). Secondary: tonal. Raised: one step above a tonal tile (a
 * Secondary button would vanish into one). Ghost: outlined. Text: no container (lowest emphasis).
 */
enum class DsButtonStyle { Primary, Accent, Secondary, Raised, Ghost, Text, Destructive, Tint }

/**
 * Subtle press feedback: a small scale-down on touch-down, back on release. [large] is for
 * full-width cards (see [app.daysemble.ui.theme.DsMotion.pressScale]). Read in the draw phase, so
 * pressing doesn't recompose the content.
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, large: Boolean = false): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val motion = DsTheme.motion
    val scale by animateFloatAsState(
        if (pressed) motion.pressScale(large) else 1f,
        motion.feedback(),
        label = "press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun DsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: DsButtonStyle = DsButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = Space.xl, vertical = Space.m),
) {
    val c = DsTheme.colors
    val (bg, fg, border) = when (style) {
        DsButtonStyle.Primary -> Triple(c.inverse, c.onInverse, Color.Transparent)
        DsButtonStyle.Accent -> Triple(c.accentStrong, c.onAccent, Color.Transparent)
        DsButtonStyle.Secondary -> Triple(c.surfaceVariant, c.textPrimary, Color.Transparent)
        DsButtonStyle.Raised -> Triple(c.surface, c.textPrimary, c.outline)
        DsButtonStyle.Ghost -> Triple(Color.Transparent, c.textPrimary, c.outline)
        DsButtonStyle.Text -> Triple(Color.Transparent, c.textSecondary, Color.Transparent)
        DsButtonStyle.Destructive -> Triple(c.errorContainer, c.error, Color.Transparent)
        DsButtonStyle.Tint -> Triple(c.inverse.copy(alpha = 0.08f), c.textPrimary, Color.Transparent)
    }
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = Sizes.touch)
            .pressScale(interaction)
            .clip(Radii.pill)
            .background(bg)
            .then(if (border != Color.Transparent) Modifier.border(Sizes.hairline, border, Radii.pill) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = fg),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(Sizes.iconSmall))
        Text(text, style = DsTheme.type.label.copy(color = fg), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** 48dp icon button with a 40dp visual container. [contentDescription] is required. */
@Composable
fun DsIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = DsTheme.colors.textPrimary,
    container: Color = Color.Transparent,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(Sizes.touch)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 22.dp),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(Radii.pill)
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(Sizes.icon))
        }
    }
}

/** The brand "add" button. A coral pill, like the dot in the wordmark. */
@Composable
fun DsFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(Sizes.fab)
            .pressScale(interaction)
            .clip(Radii.pill)
            .background(c.accentFill)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = c.onAccent),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = c.onAccent, modifier = Modifier.size(26.dp))
    }
}
