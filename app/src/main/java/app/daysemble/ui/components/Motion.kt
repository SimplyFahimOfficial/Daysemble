package app.daysemble.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import app.daysemble.ui.theme.DsTheme

/**
 * False while a list's content has just been replaced as a whole (another day selected): its rows
 * then appear at once instead of each fading in. See [ContentSwap].
 */
private val LocalItemsAppear = compositionLocalOf { true }

/**
 * List item motion for changes *inside* a list (a task completed, deleted, added, reordered):
 * rows fade in, fade out and slide to their new place. With reduced motion they only cross-fade.
 *
 * [fadeOut] = false for lists whose removed rows are usually replaced by other rows at once
 * (the selected day's items in the month view): a fading old row would sit over the new one.
 */
@Composable
fun LazyItemScope.itemMotion(fadeOut: Boolean = true): Modifier {
    val m = DsTheme.motion
    val appear = LocalItemsAppear.current
    return if (m.reduced) {
        Modifier.animateItem(fadeInSpec = if (appear) m.standard(m.short) else null, placementSpec = null, fadeOutSpec = if (fadeOut) m.standard(m.short) else null)
    } else {
        Modifier.animateItem(
            fadeInSpec = if (appear) m.enter() else null,
            placementSpec = m.standard(m.medium),
            fadeOutSpec = if (fadeOut) m.exit() else null,
        )
    }
}

/**
 * Content that is replaced as a whole when [key] changes (a list for another day). The new content
 * appears at once, in one piece (no per-row fade, which together with the old rows fading out
 * showed two days on top of each other), fading in while it rises by
 * [app.daysemble.ui.theme.DsMotion.contentShift] (or, with [fade] false, not at all). Both are
 * drawn, not laid out, so nothing re-measures. Motion off: no fade, no rise.
 *
 * The caller keys anything that belongs to one version of the content (e.g. its LazyListState)
 * inside [content], which is re-created per [key].
 */
@Composable
fun <K> ContentSwap(key: K, fade: Boolean = true, shift: Dp = DsTheme.motion.contentShift, content: @Composable () -> Unit) {
    val m = DsTheme.motion
    val shiftPx = with(LocalDensity.current) { shift.toPx() }
    androidx.compose.runtime.key(key) {
        // Rows appear at once for the first frames of this version, then item motion is normal again.
        var fresh by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; fresh = false }
        val enter = remember { Animatable(if (m.enabled && fade) 0f else 1f) }
        LaunchedEffect(Unit) { if (enter.value < 1f) enter.animateTo(1f, m.spring()) }
        androidx.compose.foundation.layout.Box(
            Modifier.graphicsLayer {
                val p = enter.value
                alpha = p.coerceIn(0f, 1f)
                translationY = (1f - p) * shiftPx
            },
        ) {
            CompositionLocalProvider(LocalItemsAppear provides !fresh) { content() }
        }
    }
}

/** Runs [content] with row appearance animations off (see [ContentSwap]); for a list's first frames. */
@Composable
fun ItemsAppearInstantly(instant: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalItemsAppear provides !instant, content = content)
}

/**
 * A component's colour that animates when its own state changes (selected, completed, focused…)
 * and follows a theme change at once. The theme change itself is one cross-fade of the whole app
 * (see DaysembleTheme). With plain animateColorAsState every themed colour also ran its own
 * 220–400 ms animation to the new theme, so cards, date cells and chips lagged behind the rest of
 * the screen and recomposed on every frame of it.
 */
@Composable
fun animateStateColor(
    targetValue: androidx.compose.ui.graphics.Color,
    animationSpec: androidx.compose.animation.core.AnimationSpec<androidx.compose.ui.graphics.Color>,
    @Suppress("UNUSED_PARAMETER") label: String = "stateColor",
): androidx.compose.ui.graphics.Color {
    val dark = DsTheme.colors.isDark
    val anim = remember { androidx.compose.animation.Animatable(targetValue) }
    // The theme the animated value belongs to.
    val valueTheme = remember { androidx.compose.runtime.mutableStateOf(dark) }
    LaunchedEffect(targetValue, dark) {
        if (valueTheme.value != dark) {
            anim.snapTo(targetValue)
            valueTheme.value = dark
        } else if (anim.targetValue != targetValue || anim.value != targetValue) {
            anim.animateTo(targetValue, animationSpec)
        }
    }
    return if (valueTheme.value != dark) targetValue else anim.value
}

/**
 * The id of the task or event that was just added from Quick Add, set by the app root for a few
 * seconds. Rows with that id tint briefly ([newItemHighlight]) so the eye finds the new item.
 */
val LocalNewItem = compositionLocalOf<String?> { null }

/** A brief accent tint behind the row when [id] is the item that was just added (see [LocalNewItem]). */
@Composable
fun Modifier.newItemHighlight(id: String): Modifier {
    val newId = LocalNewItem.current
    val m = DsTheme.motion
    val tint = remember(id) { Animatable(0f) }
    // Every change of the newest id ends this row's tint first: a second item added while this one
    // is still fading cancels the fade, which must not leave the row tinted.
    LaunchedEffect(newId) {
        if (newId != null && newId == id && m.enabled) {
            tint.snapTo(1f)
            tint.animateTo(0f, tween(m.long * 3))
        } else if (tint.value != 0f) {
            tint.snapTo(0f)
        }
    }
    val color = DsTheme.colors.accent
    return drawBehind { if (tint.value > 0f) drawRect(color.copy(alpha = HIGHLIGHT_ALPHA * tint.value)) }
}

private const val HIGHLIGHT_ALPHA = 0.16f

/** True while the tab (or page) this content belongs to is the one on screen; tabs composed in the background see false. */
val LocalTabVisible = compositionLocalOf { true }
