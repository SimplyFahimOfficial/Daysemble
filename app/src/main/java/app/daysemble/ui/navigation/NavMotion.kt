package app.daysemble.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.Density
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import app.daysemble.ui.theme.DsMotion

/**
 * Transitions for pages opened over the main screen (details, Search, Settings and its pages,
 * editors) and closed again. Primary tabs don't use these: they switch inside the main screen
 * (TabHost in DaysembleRoot).
 *
 * One rule for every page: it opens over the page it came from and closes off it, and the page
 * underneath stays still and fully drawn the whole time.
 * - Forward: the new page fades in over the old one in 150 ms while sliding 30 dp in from the end
 *   edge (editors and the event detail: from below). The old page doesn't move or fade.
 * - Back: the page underneath is fully there from the first frame; the top page fades out while
 *   drifting back toward the edge it came from.
 * - Predictive back (Android 14+ gesture) follows the same rule, scrubbed by the finger: the page
 *   underneath is static and complete, the leaving page shrinks a little and follows the gesture.
 *   (Navigation's defaults would show the leaving page shrinking over a page fading in from
 *   nothing, i.e. a blank screen under the gesture.)
 *
 * This replaces a shared-axis fade-through in which the old page faded out before the new one
 * faded in: both were nearly transparent at the same moment, so every open and close (Settings
 * included) flashed the bare background for a frame or two, and the page underneath moved too.
 *
 * Offsets are mirrored in right-to-left layouts. Reduced motion: short fades only.
 */
internal object NavMotion {

    private enum class Kind { MAIN, EDITOR, PAGE }

    private fun NavDestination.kind(): Kind = when {
        hasRoute<MainRoute>() -> Kind.MAIN
        // The event detail is a sheet-like view of one item (closed with an X), so it rises like the editors.
        hasRoute<TaskEditorRoute>() || hasRoute<EventEditorRoute>() || hasRoute<EventDetailRoute>() || hasRoute<TaskDetailRoute>() -> Kind.EDITOR
        else -> Kind.PAGE
    }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(m: DsMotion, rtl: Boolean, d: Density): EnterTransition =
        when (targetState.destination.kind()) {
            Kind.EDITOR -> m.riseIn(d)
            else -> m.pushIn(rtl, d)
        }

    /** The page being covered keeps still underneath. */
    @Suppress("UnusedReceiverParameter")
    fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(m: DsMotion): ExitTransition = m.holdUnderneath()

    /** The page being uncovered is already complete underneath. */
    @Suppress("UnusedReceiverParameter")
    fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition = EnterTransition.None

    fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(m: DsMotion, rtl: Boolean, d: Density): ExitTransition =
        when (initialState.destination.kind()) {
            Kind.EDITOR -> m.sinkOut(d)
            else -> m.pushOut(rtl, d)
        }

    /** The page being revealed by the back gesture: complete and still from the first frame. */
    fun predictivePopEnter(): EnterTransition = EnterTransition.None

    /**
     * The page leaving under the back gesture: shrinks to 92 % and moves away from the edge the
     * gesture started at ([swipeEdge]: 0 = left, 1 = right), fading only in the last part.
     */
    fun predictivePopExit(m: DsMotion, d: Density, swipeEdge: Int): ExitTransition {
        val duration = m.long
        if (m.reduced) return fadeOut(tween(duration))
        val shift = with(d) { m.axisDistance.roundToPx() } * if (swipeEdge == 1) -1 else 1
        return scaleOut(tween(duration, easing = DsMotion.Standard), targetScale = 0.92f) +
            slideOutHorizontally(tween(duration, easing = DsMotion.Standard)) { shift } +
            fadeOut(keyframes { durationMillis = duration; 1f at (duration * 0.6f).toInt() using DsMotion.Standard; 0f at duration })
    }
}
