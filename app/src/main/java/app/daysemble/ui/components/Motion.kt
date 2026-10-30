package app.daysemble.ui.components

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
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
 * Content that is replaced as a whole when [key] changes (a list for another day). Rows of the new
 * content appear immediately, in one piece, with no per-row fade (which, together with the old rows
 * fading out, showed two days on top of each other and made the list flash). Only a short shift of
 * [app.daysemble.ui.theme.DsMotion.swapShift] in [direction] (+1 forward, -1 back) says which way the day moved; it is drawn,
 * not laid out, so nothing re-measures. Reduced motion: no shift.
 *
 * The caller keys anything that belongs to one version of the content (e.g. its LazyListState)
 * inside [content], which is re-created per [key].
 */
@Composable
fun <K> ContentSwap(key: K, direction: Int, content: @Composable () -> Unit) {
    val m = DsTheme.motion
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shiftPx = with(LocalDensity.current) { m.swapShift.toPx() }
    androidx.compose.runtime.key(key) {
        // Rows appear at once for the first frames of this version, then item motion is normal again.
        var fresh by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; fresh = false }
        val shift = remember { Animatable(if (m.reduced || direction == 0) 0f else 1f) }
        LaunchedEffect(Unit) { if (shift.value != 0f) shift.animateTo(0f, m.enter(m.medium)) }
        val dir = (if (rtl) -direction else direction).toFloat()
        androidx.compose.foundation.layout.Box(Modifier.graphicsLayer { translationX = dir * shiftPx * shift.value }) {
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
