package app.daysemble.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * Daysemble's motion language. Every animation in the app picks one of these roles instead of its
 * own duration and curve.
 *
 * Tokens: durations [short] 150 / [medium] 250 / [long] 350 ms, the [spring] (damping 0.8,
 * medium-low stiffness) and the [snappy] spring (damping 0.8, medium stiffness) for pills, thumbs
 * and markers. [enabled] is the one flag every animation checks.
 *
 * | Role                      | Spec                                   | Where                                    |
 * |---------------------------|----------------------------------------|------------------------------------------|
 * | Feedback                  | [feedback] 100 ms, [snappy]            | press, selection pills, toggles, chips   |
 * | Primary tabs              | [tabFade] cross-fade, old tab stays    | Today / Calendar / Tasks (TabHost)       |
 * | Open / close a page       | [sharedAxisEnter]/[sharedAxisExit]     | Settings and its pages, editors, details |
 * | Quick Add                 | one container, [spring]                | + button grows into the sheet and back   |
 * | Theme change              | snapshot cross-fade, draw phase only   | DaysembleTheme                           |
 * | Content change in place   | [fadeThrough], [titleSlide]            | day summary, calendar title and view     |
 * | List for another day      | ContentSwap: at once, fade + [contentShift] | Today agenda, month day list, Tasks filters |
 * | Rows changing in a list   | itemMotion (animateItem)               | completion, add, reorder, sort           |
 * | Selected date             | sliding disc on [snappy]               | week strip, month grid                   |
 * | Month and Week            | one [spring] value, control and drag   | Calendar grid rows collapse to the week  |
 * | Expand / collapse         | [expandIn]/[collapseOut]               | free-time card, subtasks, field errors   |
 * | Overview panel drag       | finger, then [settle] with its velocity| Today (moved in placement, not measured) |
 * | Day swipe                 | finger, then [snappy] / [spring]       | Today agenda, header trails by 20 %      |
 * | Reordered row drop        | [settle] from where it was let go      | Tasks › List order (lift [liftScale])    |
 * | Swipe actions             | finger; colour and icon follow the drag| Tasks rows (complete / reschedule)       |
 * | Bottom scroll edge        | static fade, no motion                 | content under the floating bottom bar    |
 *
 * Haptics (the system haptic setting applies, see Haptics.kt): a tick for day, chip and segment
 * selection and swipe thresholds, confirm for completing and saving, reject for deleting.
 *
 * Rules that keep motion clean:
 * - Nothing waits for an animation: state changes immediately and the animation follows it. Gesture
 *   driven and repeatable motion uses springs, so it can be interrupted and reversed from the live
 *   value and velocity. Tweens are for fades.
 * - Animated values are read in the draw or layout phase where possible, so a running animation
 *   doesn't recompose the screen on every frame. Inside lists only alpha and transforms
 *   (graphicsLayer) animate. The two exceptions that change layout are the Calendar's month rows
 *   collapsing, and the header search field growing.
 * - Only what is on screen does work. Screens kept composed for instant return (the hidden tabs,
 *   the main screen under a page) are paused through VisibilityLifecycle; infinite animations also
 *   stop when their tab is hidden ([app.daysemble.ui.components.LocalTabVisible]).
 * - One container animates a given screen: the NavHost for pages, TabHost for tabs. Content inside
 *   them never adds its own screen-sized enter/exit on top.
 * - A list whose content is replaced (another day, another filter) is swapped in one piece; per-row
 *   motion is only for rows that change within the same list. Nothing is staggered, and nothing
 *   loops while a screen is at rest.
 * - Every animated property has one writer and converges to the latest state: transient effects
 *   (a pop, a bounce) are derived from a target-driven value, not run as a separate coroutine that
 *   a new input can cancel half way and leave scaled.
 *
 * [enabled] is false when the Appearance "Motion" setting is Reduced, or Android's animator
 * duration scale is 0 ("Remove animations"); then only short fades remain: no movement, scaling or
 * springs.
 */
@Immutable
data class DsMotion(val reduced: Boolean = false) {
    /**
     * The one switch every animation checks: true only when the Appearance "Motion" setting allows
     * it AND Android's animator duration scale is not 0 (see DaysembleTheme). When false, state
     * changes are instant or a short fade; nothing moves, scales or springs.
     */
    val enabled: Boolean get() = !reduced

    /** Press and selection feedback, fades. */
    val short: Int get() = if (reduced) 90 else 150
    /** Content changes, expand/collapse, completion. */
    val medium: Int get() = if (reduced) 120 else 250
    /** Screen transitions. */
    val long: Int get() = if (reduced) 150 else 350

    /** Distance of shared-axis screen transitions. */
    val axisDistance get() = 30.dp

    /** Content that changes in place (another day, month, filter, date) slides up by this much while it fades in. */
    val contentShift get() = 12.dp
    /** The same for a list whose filter changed. */
    val listShift get() = 8.dp

    /** The lift of a held row. */
    val liftScale: Float get() = 1.03f

    /**
     * Standard spring: layout-ish movement (cards, sheets, chips sliding). Damping 0.8, medium-low
     * stiffness (response about 0.3 s). Instant when motion is off. Springs, not tweens, so a
     * gesture or a second tap can interrupt and reverse it from the live value and velocity.
     */
    fun <T> spring(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = DAMPING, stiffness = Spring.StiffnessMediumLow)

    /** Snappy spring for pills, thumbs and small markers: same damping, faster (response about 0.16 s). */
    fun <T> snappy(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = DAMPING, stiffness = Spring.StiffnessMedium)

    fun <T> enter(duration: Int = medium, delay: Int = 0): FiniteAnimationSpec<T> = tween(duration, delay, EmphasizedDecelerate)
    fun <T> exit(duration: Int = short, delay: Int = 0): FiniteAnimationSpec<T> = tween(duration, delay, EmphasizedAccelerate)
    fun <T> standard(duration: Int = medium): FiniteAnimationSpec<T> = tween(duration, easing = Standard)

    /** Immediate feedback (pressed, toggled). */
    fun <T> feedback(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(100, easing = Standard)

    /**
     * Selection indicators (bottom-bar pill, segmented control, step dots): the [snappy] spring.
     * Response ≈ 0.16 s, so the bottom-bar pill lands with the fade of the tab it selects.
     */
    fun <T> indicator(): FiniteAnimationSpec<T> =
        if (reduced) tween(short, easing = Standard) else snappy()

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

    /** The incoming tab fades in over the outgoing one, which stays fully drawn underneath: no frame is emptier than the screens at rest. */
    fun tabFade(): FiniteAnimationSpec<Float> = tween(short, easing = Standard)

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

    /**
     * A title that follows a swipe: the new one slides in a little ([TITLE_SHIFT]) from the side the
     * content comes from and fades in, the old one leaves the other way, so the title trails the
     * grid beneath it, which slides a full page. [direction] +1 forward, -1 back, 0 none.
     */
    fun titleSlide(direction: Int, rtl: Boolean): ContentTransform {
        val e = if (rtl) -direction else direction
        if (reduced || e == 0) return ContentTransform(fadeIn(tween(short)), fadeOut(tween(short)))
        return ContentTransform(
            fadeIn(tween(medium, delayMillis = OUT, easing = EmphasizedDecelerate)) + slideInHorizontally(spring()) { e * TITLE_SHIFT },
            fadeOut(tween(OUT, easing = Standard)) + slideOutHorizontally(spring()) { -e * TITLE_SHIFT },
        )
    }

    /** Size change that accompanies [fadeThrough] when the new content is taller or shorter. */
    fun contentSize(): SizeTransform = SizeTransform(clip = false) { _, _ -> if (reduced) snap() else tween(medium, easing = Standard) }

    // ---- Expand / collapse --------------------------------------------------------------------

    fun expandIn(): EnterTransition =
        if (reduced) fadeIn(standard(short)) else fadeIn(tween(medium, delayMillis = 40, easing = Standard)) + expandVertically(standard(medium))

    fun collapseOut(): ExitTransition =
        if (reduced) fadeOut(standard(short)) else fadeOut(tween(90, easing = EmphasizedAccelerate)) + shrinkVertically(standard(medium))

    // ---- Pages (NavMotion) -------------------------------------------------------------------------
    //
    // A page opens as a push over a still page: the new page slides in [axisDistance] from the end edge
    // while it fades in, and the page under it doesn't move or fade, so no frame is emptier than the
    // screens at rest. Back mirrors it: the closing page slides out and fades over the page beneath,
    // which was complete all along. The slide is a spring, so a tap during the transition reverses it
    // from where it is. Offsets flip in right-to-left layouts. Reduced motion: a short fade in, and
    // the page leaves at once.

    /** A page arriving [forward]; going back, the page being revealed is already complete and still. */
    fun sharedAxisEnter(forward: Boolean, rtl: Boolean, density: Density): EnterTransition {
        if (reduced) return if (forward) fadeIn(tween(short, easing = Standard)) else EnterTransition.None
        if (!forward) return EnterTransition.None
        return fadeIn(tween(short, easing = Standard)) + slideInHorizontally(spring()) { axisPx(rtl, density) }
    }

    /** A page leaving: covered by a new page ([forward]), which it waits for unchanged, or closed by Back. */
    fun sharedAxisExit(forward: Boolean, rtl: Boolean, density: Density): ExitTransition {
        // An alpha that stays 1 keeps the page composed and drawn until the new one has faded in over it.
        if (forward || reduced) return fadeOut(tween(short), targetAlpha = 1f)
        return fadeOut(tween(short, easing = Standard)) + slideOutHorizontally(spring()) { axisPx(rtl, density) }
    }

    /** The end edge: where a page arrives from and leaves toward. */
    private fun axisPx(rtl: Boolean, density: Density): Int =
        with(density) { axisDistance.roundToPx() } * if (rtl) -1 else 1

    /**
     * The page under the back gesture: shrinks to [backScale] and drifts away from the edge the
     * gesture started at (0 = left, 1 = right), fading in its last part, while the page beneath
     * (already complete) is revealed. Scrubbed by the finger; this spec only plays on commit.
     */
    val backScale: Float get() = 0.92f
    fun predictiveBackExit(density: Density, swipeEdge: Int): ExitTransition {
        if (reduced) return fadeOut(tween(short))
        val shift = with(density) { axisDistance.roundToPx() } * if (swipeEdge == 1) -1 else 1
        return scaleOut(spring(), targetScale = backScale) + slideOutHorizontally(spring()) { shift } +
            fadeOut(keyframes { durationMillis = long; 1f at (long * PREDICTIVE_BACK_FADE_START).toInt() using Standard; 0f at long })
    }

    /** One-off onboarding entrance: total length; groups are staggered by [entranceStagger]. */
    val entrance: Int get() = if (reduced) 0 else 420
    val entranceStagger: Int get() = 70

    companion object {
        /**
         * Outgoing content fades in [OUT] ms on a curve that drops early; incoming content starts at
         * [IN_DELAY] ms, when the old one is nearly gone: no double image and no blank gap between.
         */
        /** How far (px, density independent enough for a title) a title slides on a month change. */
        const val TITLE_SHIFT = 48
        const val OUT = 90
        const val IN_DELAY = 60
        /** Stiffness of [settle]. With mass 1, response = 2π / √650 ≈ 0.25 s. */
        const val SETTLE_STIFFNESS = 650f
        const val PRESS_SCALE = 0.96f
        const val PRESS_SCALE_LARGE = 0.985f
        const val PREDICTIVE_BACK_FADE_START = 0.6f
        /** Damping ratio of the standard and snappy springs. */
        const val DAMPING = 0.8f
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
        val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}

val LocalDsMotion = staticCompositionLocalOf { DsMotion() }
