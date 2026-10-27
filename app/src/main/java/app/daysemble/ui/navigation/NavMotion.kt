package app.daysemble.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.Density
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import app.daysemble.ui.theme.DsMotion

/**
 * Transitions for screens pushed over the main screen (details, Search, Settings and its pages,
 * editors) and popped back. Primary tabs don't use these: they switch inside the main screen
 * (TabHost in DaysembleRoot).
 *
 * - Forward and back use Material's shared axis: the page that leaves fades out in 90 ms while it
 *   drifts 30 dp, the page that arrives fades in right after while it settles from 30 dp. The two
 *   are never both half-transparent over each other, and no page edge slides across the screen.
 * - Editors rise from just below and sink back down; the page underneath only fades.
 * - Predictive back (Android 14+ back gesture) has its own pair, scrubbed by the gesture: the page
 *   underneath is fully visible from the first frame (only a slight parallax), the leaving page
 *   shrinks a little and moves with the finger, and only fades near the end. Without these,
 *   Navigation's defaults apply (the leaving page shrinks to 70 % over a page that fades in from
 *   nothing), which showed a blank screen under the gesture.
 * - Offsets are mirrored in right-to-left layouts. Reduced motion: fades only.
 */
internal object NavMotion {

    private enum class Kind { MAIN, EDITOR, PAGE }

    private fun NavDestination.kind(): Kind = when {
        hasRoute<MainRoute>() -> Kind.MAIN
        hasRoute<TaskEditorRoute>() || hasRoute<EventEditorRoute>() -> Kind.EDITOR
        else -> Kind.PAGE
    }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(m: DsMotion, rtl: Boolean, d: Density): EnterTransition =
        when (targetState.destination.kind()) {
            Kind.EDITOR -> m.riseIn(d)
            else -> m.axisIn(fromEnd = true, rtl = rtl, density = d)
        }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(m: DsMotion, rtl: Boolean, d: Density): ExitTransition =
        when (targetState.destination.kind()) {
            Kind.EDITOR -> m.underlayOut()
            else -> m.axisOut(toStart = true, rtl = rtl, density = d)
        }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(m: DsMotion, rtl: Boolean, d: Density): EnterTransition =
        when (initialState.destination.kind()) {
            Kind.EDITOR -> m.underlayIn()
            else -> m.axisIn(fromEnd = false, rtl = rtl, density = d)
        }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(m: DsMotion, rtl: Boolean, d: Density): ExitTransition =
        when (initialState.destination.kind()) {
            Kind.EDITOR -> m.sinkOut(d)
            else -> m.axisOut(toStart = false, rtl = rtl, density = d)
        }

    /** The page being revealed by the back gesture: visible at once, with a slight parallax. */
    fun predictivePopEnter(m: DsMotion, rtl: Boolean, d: Density): EnterTransition {
        if (m.reduced) return EnterTransition.None
        val shift = with(d) { (m.axisDistance / 2).roundToPx() } * if (rtl) 1 else -1
        return slideInHorizontally(tween(m.long, easing = DsMotion.Standard)) { shift }
    }

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
