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
 * | Primary tabs              | [tabEnter] 160 ms over the old tab     | Today / Calendar / Tasks (TabHost)       |
 * | Open / close a page       | [pushIn]/[pushOut] over a still page   | Search, Settings and its pages           |
 * | Editors and event detail  | [riseIn]/[sinkOut] over a still page   | task/event editors, event detail         |
 * | Theme change              | snapshot cross-fade, draw phase only   | DaysembleTheme                           |
 * | Content change in place   | [fadeThrough]                          | day summary, calendar view, quick add    |
 * | List for another day      | ContentSwap: at once + [swapShift]     | Today agenda, selected day in Month view |
 * | Rows changing in a list   | itemMotion (animateItem)               | completion, delete, add, reorder         |
 * | Expand / collapse         | [expandIn]/[collapseOut]               | subtasks, field errors, repeat options   |
 * | Overview panel drag       | finger, then [settle] with its velocity| Today (moved in placement, not measured) |
 * | Reordered row drop        | [settle] from where it was let go      | Tasks › List order                       |
 * | Swipe actions             | finger; icon and fill arm at threshold | Tasks rows (complete / delete)           |
 * | Task completion           | `standard(short)` check, strike        | TaskRow, DsCheck                         |
 * | Bottom scroll edge        | static fade, no motion                 | content under the floating bottom bar    |
 *
 * Haptics (system haptic setting applies) are only for commits: completing or reopening a task,
 * a swipe crossing its threshold, a reordered row being picked up and changing place, long press.
 * | Calendar date             | [indicator] (colour only, no movement) | month cells, week header, date strip     |
 * | Dialogs and sheets        | Material's own motion                  | AlertDialog, ModalBottomSheet            |
 * | Onboarding entrance       | [entrance], played once                | OnboardingScreen                         |
 *
 * Rules that keep transitions clean:
 * - A screen-sized change never leaves a blank frame: the new tab or page arrives over the old
 *   one, which stays still and fully drawn until it is covered (and, going back, the page
 *   underneath is complete from the first frame). Only the arriving or leaving side moves, by a
 *   few dp, never a full-width slide. Content swaps inside a screen use [fadeThrough].
 * - Nothing waits for an animation: state changes immediately and the animation follows it. All
 *   specs are interruptible (Animatable / Transition based) and resume from the current value.
 * - Animated values are read in the draw or layout phase where possible, so a running animation
 *   doesn't recompose the screen on every frame. A moving or resizing container never re-measures
 *   its contents per frame: it is measured once and moved in placement or with a graphics layer.
 * - Only what is on screen does work. Screens kept composed for instant return (the hidden tabs,
 *   the main screen under a page) are paused through VisibilityLifecycle: no data collection, no
 *   tickers, so they cost nothing while another screen animates.
 * - One container animates a given screen: the NavHost for pages, TabHost for tabs. Content inside
 *   them never adds its own screen-sized enter/exit on top.
 * - A list whose content is replaced (another day) is swapped in one piece; per-row motion is only
 *   for rows that change within the same list, so two versions never cross-fade row by row.
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

    /** Shift of a list swapped for another day (ContentSwap): a hint of direction, drawn only. */
    val swapShift get() = 16.dp

    fun <T> enter(duration: Int = medium, delay: Int = 0): FiniteAnimationSpec<T> = tween(duration, delay, EmphasizedDecelerate)
    fun <T> exit(duration: Int = short, delay: Int = 0): FiniteAnimationSpec<T> = tween(duration, delay, EmphasizedAccelerate)
    fun <T> standard(duration: Int = medium): FiniteAnimationSpec<T> = tween(duration, easing = Standard)

    /** Immediate feedback (pressed, toggled). */
    fun <T> feedback(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(100, easing = Standard)

    /**
     * Selection indicators (bottom-bar pill, segmented control, step dots): critically damped, no
     * bounce. Response ≈ 0.16 s, so the bottom-bar pill lands with the [tabEnter] fade of the tab it
     * selects. (StiffnessMediumLow, response ≈ 0.31 s, was still gliding well after the tab had arrived.)
     */
    fun <T> indicator(): FiniteAnimationSpec<T> =
        if (reduced) tween(short, easing = Standard) else spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)

    /**
     * Something the finger let go of, settling into place (the Today panel, a reordered row): a
     * critically damped spring with a response of about 0.25 s. It starts from the live value and
     * keeps the release velocity, so there is no seam between the drag and the settle. Reduced
     * motion: a short tween.
     */
    fun <T> settle(): FiniteAnimationSpec<T> =
        if (reduced) tween(short, easing = Standard) else spring(dampingRatio = 1f, stiffness = SETTLE_STIFFNESS)

    /**
     * Press scale: 3 % for controls, 1.5 % for full-width cards (the same visual depth; a card
     * moving its edges by 3 % reads as a jump). No scaling with reduced motion.
     */
    fun pressScale(large: Boolean): Float = when {
        reduced -> 1f
        large -> PRESS_SCALE_LARGE
        else -> PRESS_SCALE
    }

    /** Loops of the loading placeholders. They stop with reduced motion. */
    val skeletonPulse: Int get() = 900
    val spinnerTurn: Int get() = 1100

    // ---- Primary tabs (see TabHost in DaysembleRoot) --------------------------------------------

    /**
     * Incoming tab: fades in over the outgoing one (which stays drawn until covered) on a curve that
     * starts fast, so the new tab is mostly there within a few frames. No delay, so a tap shows a
     * response on the next frame. Shift of [tabShift] in tab order hints at the direction.
     */
    val tabEnterMillis: Int get() = if (reduced) 0 else 160
    val tabShift get() = if (reduced) 0.dp else 8.dp
    fun tabEnter(): FiniteAnimationSpec<Float> = tween(tabEnterMillis, easing = EmphasizedDecelerate)

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
    //
    // A page opens over the one it came from and closes off it: the page underneath stays still and
    // fully drawn throughout, so no frame is ever blank and nothing under the moving page moves. The
    // same rule holds for the predictive back gesture, so the gesture and a tap on Back look alike.

    /** A page arriving: slides [axisDistance] in from the end edge while fading in quickly. */
    fun pushIn(rtl: Boolean, density: Density): EnterTransition {
        if (reduced) return fadeIn(tween(short, easing = Standard))
        val d = with(density) { axisDistance.roundToPx() } * if (rtl) -1 else 1
        return fadeIn(tween(PAGE_FADE, easing = EmphasizedDecelerate)) +
            slideInHorizontally(tween(long, easing = EmphasizedDecelerate)) { d }
    }

    /** A page leaving (Back): drifts [axisDistance] toward the end edge while fading out. */
    fun pushOut(rtl: Boolean, density: Density): ExitTransition {
        if (reduced) return fadeOut(tween(short, easing = Standard))
        val d = with(density) { axisDistance.roundToPx() } * if (rtl) -1 else 1
        return fadeOut(tween(PAGE_FADE, easing = Standard)) + slideOutHorizontally(tween(PAGE_OUT, easing = Standard)) { d }
    }

    /** Editors and the event detail rise in from just below. */
    fun riseIn(density: Density): EnterTransition {
        if (reduced) return fadeIn(tween(short, easing = Standard))
        val d = with(density) { axisDistance.roundToPx() }
        return fadeIn(tween(PAGE_FADE, easing = EmphasizedDecelerate)) +
            slideInVertically(tween(long, easing = EmphasizedDecelerate)) { d }
    }

    fun sinkOut(density: Density): ExitTransition {
        if (reduced) return fadeOut(tween(short, easing = Standard))
        val d = with(density) { axisDistance.roundToPx() }
        return fadeOut(tween(PAGE_FADE, easing = Standard)) + slideOutVertically(tween(PAGE_OUT, easing = Standard)) { d }
    }

    /**
     * The page underneath while another one opens over it: unchanged and fully drawn until the
     * new page has finished arriving (an alpha that stays 1, which keeps it in the transition).
     */
    fun holdUnderneath(): ExitTransition = fadeOut(tween(long), targetAlpha = 1f)

    /** One-off onboarding entrance: total length; groups are staggered by [entranceStagger]. */
    val entrance: Int get() = if (reduced) 0 else 420
    val entranceStagger: Int get() = 70

    companion object {
        /**
         * Outgoing content fades in [OUT] ms on a curve that drops early; incoming content starts at
         * [IN_DELAY] ms, when the old one is nearly gone: no double image and no blank gap between.
         */
        const val OUT = 90
        const val IN_DELAY = 60
        /** Fade of a page opening or closing over a still page. */
        const val PAGE_FADE = 150
        /** Drift of a page closing. */
        const val PAGE_OUT = 200
        /** Stiffness of [settle]. With mass 1, response = 2π / √650 ≈ 0.25 s. */
        const val SETTLE_STIFFNESS = 650f
        const val PRESS_SCALE = 0.97f
        const val PRESS_SCALE_LARGE = 0.985f
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
        val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}

val LocalDsMotion = staticCompositionLocalOf { DsMotion() }
