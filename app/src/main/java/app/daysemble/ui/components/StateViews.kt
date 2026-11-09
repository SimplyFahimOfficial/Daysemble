package app.daysemble.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Space

/**
 * An empty-state action fades in after the message, [index] x the stagger later, with a small rise.
 * Used by the buttons of [StateMessage].
 */
@Composable
fun Modifier.emptyActionEntrance(index: Int): Modifier =
    staggeredEntrance(index, baseDelay = DsTheme.motion.medium)

/** Centered message used for empty / error / offline states. */
@Composable
fun StateMessage(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    tint: Color = DsTheme.colors.textSecondary,
    action: (@Composable () -> Unit)? = null,
) {
    val m = DsTheme.motion
    // The icon breathes (1.00 to 1.04, one cycle per 3 s) while it is on screen. It pauses when the
    // screen is not resumed or its tab is hidden, and it does not move at all with motion off.
    val resumed by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = m.enabled && LocalTabVisible.current && resumed.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    val breath = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(active) {
        if (!active) { breath.snapTo(1f); return@LaunchedEffect }
        val half = tween<Float>(m.breathePeriod / 2, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        while (true) { breath.animateTo(m.breatheScale, half); breath.animateTo(1f, half) }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.x3, vertical = Space.x3)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .graphicsLayer { scaleX = breath.value; scaleY = breath.value }
                .size(56.dp)
                .clip(Radii.pill)
                .background(DsTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.height(Space.l))
        Text(title, style = DsTheme.type.title, color = DsTheme.colors.textPrimary, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(Space.xs))
            Text(body, style = DsTheme.type.bodySmall, color = DsTheme.colors.textSecondary, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(Space.l))
            action()
        }
    }
}

@Composable
fun ErrorState(title: String, body: String?, onRetry: (() -> Unit)?, retryLabel: String, modifier: Modifier = Modifier) =
    StateMessage(
        Icons.Rounded.ErrorOutline, title, modifier, body, tint = DsTheme.colors.error,
        action = onRetry?.let { { DsButton(retryLabel, it, style = DsButtonStyle.Secondary) } },
    )

@Composable
fun OfflineState(title: String, body: String?, modifier: Modifier = Modifier) =
    StateMessage(Icons.Rounded.CloudOff, title, modifier, body)

/** Skeleton row placeholder with a gentle pulse (static when reduced motion is on). */
@Composable
fun SkeletonRows(modifier: Modifier = Modifier, count: Int = 4) {
    val m = DsTheme.motion
    val alpha = if (m.reduced) 0.6f else {
        val t = rememberInfiniteTransition(label = "skeleton")
        val a by t.animateFloat(0.45f, 0.85f, infiniteRepeatable(tween(m.skeletonPulse, easing = LinearEasing), RepeatMode.Reverse), label = "a")
        a
    }
    val loading = androidx.compose.ui.res.stringResource(app.daysemble.R.string.loading)
    Column(
        modifier
            .padding(horizontal = Space.gutter)
            .semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .fillMaxWidth(if (i % 2 == 0) 0.85f else 0.6f)
                    .height(18.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .clip(Radii.chip)
                    .background(DsTheme.colors.surfaceVariant),
            )
        }
    }
}

enum class SyncIndicator { Idle, Syncing, Synced, Offline, Error, NeedsAttention }

/** Compact sync status pill. Announced politely to screen readers when it changes. */
@Composable
fun SyncBadge(state: SyncIndicator, label: String, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    AnimatedContent(
        targetState = state to label,
        transitionSpec = { m.fadeThrough(size = m.contentSize()) },
        label = "sync",
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) { (s, text) ->
        val (icon, tint) = when (s) {
            SyncIndicator.Idle, SyncIndicator.Synced -> Icons.Rounded.CheckCircle to c.success
            SyncIndicator.Syncing -> Icons.Rounded.Sync to c.textSecondary
            SyncIndicator.Offline -> Icons.Rounded.CloudOff to c.textSecondary
            SyncIndicator.Error, SyncIndicator.NeedsAttention -> Icons.Rounded.SyncProblem to c.error
        }
        Row(
            Modifier
                .heightIn(min = 28.dp)
                .clip(Radii.pill)
                .background(c.surfaceVariant)
                .padding(horizontal = Space.m - Space.xxs, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs + Space.xxs),
        ) {
            val rotation = if (s == SyncIndicator.Syncing && !m.reduced) {
                val t = rememberInfiniteTransition(label = "spin")
                val r by t.animateFloat(360f, 0f, infiniteRepeatable(tween(m.spinnerTurn, easing = LinearEasing)), label = "r")
                r
            } else 0f
            Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = rotation })
            Text(text, style = DsTheme.type.label, color = c.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}
