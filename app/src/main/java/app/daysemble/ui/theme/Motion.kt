package app.daysemble.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * Daysemble's motion language. Every animation in the app picks one of these roles instead of its
 * own duration and curve.
 *
 * | Role                      | Spec                                   | Where                                    |
 * |---------------------------|----------------------------------------|------------------------------------------|
 * | Feedback                  | [feedback] 100 ms, [indicator] spring  | press, selection pills, toggles, chips   |
 * | Primary tabs              | [tabExit] then [tabEnter], ~210 ms     | Today / Calendar / Tasks (TabHost)       |
 * | Push / pop (shared axis)  | [axisIn]/[axisOut], [long] ms, 30 dp   | details, Settings pages (NavMotion)      |
 * | Editors (vertical axis)   | [riseIn]/[sinkOut]                     | task and event editors (NavMotion)       |
 * | Content change in place   | [fadeThrough]                          | day summary, calendar view, quick add    |
 * | Expand / collapse         | [expandIn]/[collapseOut]               | completed tasks, subtasks, field errors  |
 * | Task completion           | `standard(medium)` (check, strike)     | TaskRow, DsCheck                         |
 * | Calendar date             | [indicator] (colour only, no movement) | month cells, week header, date strip     |
 * | Dialogs and sheets        | Material's own motion                  | AlertDialog, ModalBottomSheet            |
 * | Onboarding entrance       | [entrance], played once                | OnboardingScreen                         |
 *
 * Rules that keep transitions clean:
 * - Two full-screen contents never fade over each other at the same time ("double exposure"):
 *   the outgoing side fades out quickly first, the incoming side fades in after it ([fadeThrough],
 *   shared axis). Movement is small (a few dp), never a full-width slide of a translucent page.
 * - Nothing waits for an animation: state changes immediately and the animation follows it. All
 *   specs are interruptible (Animatable / Transition based) and resume from the current value.
 * - Animated values are read in the draw or layout phase where possible, so a running animation
 *   doesn't recompose the screen on every frame.
 *
 * [reduced] (Android "Remove animations", or the in-app Motion setting) keeps only short fades: no
 * movement, scaling or springs.
 */
@Immutable
data class DsMotion(val reduced: Boolean = false) {
    /** Press and selection feedback. */
    val short: Int get() = if (reduced) 90 else 120
    /** Content changes, expand/collapse, completion. */
    val medium: Int get() = if (reduced) 120 else 220
    /** Screen transitions. */
    val long: Int get() = if (reduced) 150 else 300

    /** Distance of shared-axis screen transitions. Small on purpose: it hints at direction, it doesn't carry the page. */
    val axisDistance get() = 30.dp

    fun <T> enter(duration: Int = medium, delay: Int = 0): FiniteAnimationSpec<T> = tween(duration, delay, EmphasizedDecelerate)
    fun <T> exit(duration: Int = short, delay: Int = 0): FiniteAnimationSpec<T> = tween(duration, delay, EmphasizedAccelerate)
    fun <T> standard(duration: Int = medium): FiniteAnimationSpec<T> = tween(duration, easing = Standard)

    /** Immediate feedback (pressed, toggled). */
    fun <T> feedback(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(100, easing = Standard)

    /** Selection indicators (bottom-bar pill, segmented control, step dots): critically damped, no bounce. */
    fun <T> indicator(): FiniteAnimationSpec<T> =
        if (reduced) tween(short, easing = Standard) else spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)

    // ---- Primary tabs (fade through, see TabHost in DaysembleRoot) ----------------------------

    /**
     * Outgoing tab: fades from the first frame and is nearly gone by the time the new tab appears.
     * (A fade-out on an accelerating curve stays almost opaque until its last frames, which showed
     * both tabs at once.)
     */
    val tabExitMillis: Int get() = 70
    /** Incoming tab: starts as the old one finishes, with a short shift of [tabShift] in tab order. */
    val tabEnterMillis: Int get() = if (reduced) 120 else 170
    val tabEnterDelay: Int get() = if (reduced) 0 else 40
    val tabShift get() = if (reduced) 0.dp else 8.dp
    fun tabExit(): FiniteAnimationSpec<Float> = tween(tabExitMillis, easing = Standard)
    fun tabEnter(): FiniteAnimationSpec<Float> = tween(tabEnterMillis, tabEnterDelay, EmphasizedDecelerate)

    // ---- Content replaced inside a screen -------------------------------------------------------

    /**
     * Swap of content inside an existing screen. The old content leaves in 90 ms, the new content
     * arrives right after with a short shift in [direction] (+1 forward, -1 back, 0 none), so the two
     * never sit on top of each other as a double image. No size animation unless [size] is given.
     */
    fun fadeThrough(direction: Int = 0, rtl: Boolean = false, size: SizeTransform? = null): ContentTransform {
        val e = if (rtl) -direction else direction
        val outD = OUT
        val inD = medium
        val enter = if (reduced || e == 0) fadeIn(tween(inD, delayMillis = if (reduced) 0 else IN_DELAY, easing = EmphasizedDecelerate))
        else fadeIn(tween(inD, delayMillis = IN_DELAY, easing = EmphasizedDecelerate)) +
            slideInHorizontally(tween(inD + outD, easing = EmphasizedDecelerate)) { w -> e * w / 24 }
        val exit = if (reduced || e == 0) fadeOut(tween(outD, easing = Standard))
        else fadeOut(tween(outD, easing = Standard)) +
            slideOutHorizontally(tween(outD, easing = Standard)) { w -> -e * w / 48 }
        return ContentTransform(enter, exit, sizeTransform = size)
    }

    /** Size change that accompanies [fadeThrough] when the new content is taller or shorter. */
    fun contentSize(): SizeTransform = SizeTransform(clip = false) { _, _ -> if (reduced) snap() else tween(medium, easing = Standard) }

    // ---- Expand / collapse --------------------------------------------------------------------

    fun expandIn(): EnterTransition =
        if (reduced) fadeIn(standard(short)) else fadeIn(tween(medium, delayMillis = 40, easing = Standard)) + expandVertically(standard(medium))

    fun collapseOut(): ExitTransition =
        if (reduced) fadeOut(standard(short)) else fadeOut(tween(90, easing = EmphasizedAccelerate)) + shrinkVertically(standard(medium))

    // ---- Screen transitions (NavMotion) ---------------------------------------------------------

    /** Shared axis X, incoming: fades in after the outgoing page has mostly left, moving [axisDistance]. */
    fun axisIn(fromEnd: Boolean, rtl: Boolean, density: Density): EnterTransition {
        if (reduced) return fadeIn(tween(long, easing = Standard))
        val d = with(density) { axisDistance.roundToPx() }
        val sign = (if (fromEnd) 1 else -1) * (if (rtl) -1 else 1)
        return fadeIn(tween(long - IN_DELAY, delayMillis = IN_DELAY, easing = EmphasizedDecelerate)) +
            slideInHorizontally(tween(long, easing = EmphasizedDecelerate)) { sign * d }
    }

    /** Shared axis X, outgoing: quick fade while drifting [axisDistance] away. */
    fun axisOut(toStart: Boolean, rtl: Boolean, density: Density): ExitTransition {
        if (reduced) return fadeOut(tween(OUT, easing = Standard))
        val d = with(density) { axisDistance.roundToPx() }
        val sign = (if (toStart) -1 else 1) * (if (rtl) -1 else 1)
        return fadeOut(tween(OUT, easing = Standard)) +
            slideOutHorizontally(tween(long, easing = EmphasizedDecelerate)) { sign * d }
    }

    /** Editors rise in from just below; the page underneath fades out quickly ([axisOut] timing). */
    fun riseIn(density: Density): EnterTransition {
        if (reduced) return fadeIn(tween(long, easing = Standard))
        val d = with(density) { axisDistance.roundToPx() }
        return fadeIn(tween(long - IN_DELAY, delayMillis = IN_DELAY, easing = EmphasizedDecelerate)) +
            slideInVertically(tween(long, easing = EmphasizedDecelerate)) { d }
    }

    fun sinkOut(density: Density): ExitTransition {
        if (reduced) return fadeOut(tween(OUT, easing = Standard))
        val d = with(density) { axisDistance.roundToPx() }
        return fadeOut(tween(OUT, easing = Standard)) +
            slideOutVertically(tween(long, easing = EmphasizedDecelerate)) { d }
    }

    /** One-off onboarding entrance: total length; groups are staggered by [entranceStagger]. */
    val entrance: Int get() = if (reduced) 0 else 420
    val entranceStagger: Int get() = 70

    /** Fade timing shared by every "through" transition (screens, tabs, content). */
    fun underlayOut(): ExitTransition = fadeOut(tween(OUT, easing = Standard))
    fun underlayIn(): EnterTransition = fadeIn(tween(long - IN_DELAY, delayMillis = IN_DELAY, easing = EmphasizedDecelerate))

    companion object {
        /**
         * Outgoing content fades in [OUT] ms on a curve that drops early; incoming content starts at
         * [IN_DELAY] ms, when the old one is nearly gone: no double image and no blank gap between.
         */
        const val OUT = 90
        const val IN_DELAY = 60
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
        val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}

val LocalDsMotion = staticCompositionLocalOf { DsMotion() }
