package app.daysemble.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The single motion vocabulary for Daysemble. Durations: short 120, medium 220, long 320 ms.
 * When [reduced] is true every spec collapses to a short fade and nothing moves.
 *
 * Which spec to use for which interaction:
 * - Feedback (press, selection, toggles): [indicator] or `standard(short)`. Starts on the same frame.
 * - Content changes inside a screen (summary for another day, step text): [fadeThrough].
 * - Primary tabs (Today, Calendar, Tasks): [cover], [tab] ms, a short shift in tab order.
 * - Pushing and popping screens (details, Settings, editors): `navigation/NavMotion`, [long] ms.
 * - Expand and collapse: `enter()`/`exit()` with expand/shrink; fade only when reduced.
 * - Task completion: `standard(medium)` for colour and the drawn strike-through.
 * - Dialogs and sheets: Material's own motion, so they behave like the rest of Android.
 */
@Immutable
data class DsMotion(val reduced: Boolean = false) {
    val short: Int get() = 120
    val medium: Int get() = if (reduced) 120 else 220
    val long: Int get() = if (reduced) 120 else 320

    /** Primary tab switches: quicker than a push, because nothing new is being opened. */
    val tab: Int get() = if (reduced) 120 else 240

    /**
     * Swap of two opaque full screens. The incoming one fades in on top while the outgoing one stays
     * fully drawn underneath until it is covered, so there is never a frame where the background
     * shows through. [direction] (+1 forward, -1 back, 0 none) adds a small shift; none when reduced.
     */
    fun cover(direction: Int, rtl: Boolean): ContentTransform {
        val d = tab
        val e = if (rtl) -direction else direction
        val enter = if (reduced || e == 0) fadeIn(enter<Float>(d)) else
            fadeIn(enter(d)) + slideInHorizontally(enter(d)) { w -> e * w / 28 }
        val exit = if (reduced || e == 0) hold(d) else
            slideOutHorizontally(enter(d)) { w -> -e * w / 56 } + hold(d)
        return ContentTransform(enter, exit, sizeTransform = null)
    }

    /**
     * Swap of content that shares one background (no surface of its own): the old content leaves
     * quickly, then the new content arrives, so the two never overlap as a double image.
     */
    fun fadeThrough(direction: Int, rtl: Boolean): ContentTransform {
        val e = if (rtl) -direction else direction
        val outD = 90
        val inD = medium
        val enter = if (reduced || e == 0) fadeIn(tween(inD, delayMillis = if (reduced) 0 else outD / 2, easing = EmphasizedDecelerate)) else
            fadeIn(tween(inD, delayMillis = outD / 2, easing = EmphasizedDecelerate)) +
                slideInHorizontally(tween(inD, delayMillis = outD / 2, easing = EmphasizedDecelerate)) { w -> e * w / 14 }
        val exit = if (reduced || e == 0) fadeOut(tween(outD, easing = EmphasizedAccelerate)) else
            fadeOut(tween(outD, easing = EmphasizedAccelerate)) + slideOutHorizontally(tween(outD, easing = EmphasizedAccelerate)) { w -> -e * w / 28 }
        return ContentTransform(enter, exit, sizeTransform = null)
    }

    /** Keeps a screen fully visible for [durationMillis], then removes it (used under a covering screen). */
    fun hold(durationMillis: Int): ExitTransition = fadeOut(tween(durationMillis = 1, delayMillis = durationMillis))

    fun <T> enter(duration: Int = medium): FiniteAnimationSpec<T> = tween(duration, easing = EmphasizedDecelerate)
    fun <T> exit(duration: Int = short): FiniteAnimationSpec<T> = tween(duration, easing = EmphasizedAccelerate)
    fun <T> standard(duration: Int = medium): FiniteAnimationSpec<T> = tween(duration, easing = Standard)

    /** Selection indicators: critically-damped-ish, no visible bounce. */
    fun <T> indicator(): FiniteAnimationSpec<T> =
        if (reduced) tween(short) else spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    companion object {
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
        val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}

val LocalDsMotion = staticCompositionLocalOf { DsMotion() }
