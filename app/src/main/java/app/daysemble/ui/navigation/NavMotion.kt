package app.daysemble.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import app.daysemble.ui.theme.DsMotion

/**
 * Screen transitions for the whole NavHost, chosen from where the user is coming from and going to.
 *
 * One rule keeps every transition free of blank or double-exposed frames: only the screen on top
 * fades. The screen underneath stays fully opaque and only shifts slightly (parallax), so the two
 * never fade out together and the background never shows through.
 * - Forward navigation draws the incoming screen on top: it fades in while the previous one holds.
 * - Back (including predictive back, which scrubs these same transitions with the gesture) draws
 *   the leaving screen on top: it fades out over the destination, which is already opaque.
 * - Today, Calendar and Tasks are siblings: switching moves a short distance in tab order.
 * - Editors rise from below; detail and settings pages come in from the end side.
 * - Reduced motion keeps the same layering but with short cross-fades and no movement.
 */
internal object NavMotion {

    private enum class Kind { TAB, EDITOR, DETAIL }

    private fun NavDestination.kind(): Kind = when {
        tabIndex() != null -> Kind.TAB
        hasRoute<TaskEditorRoute>() || hasRoute<EventEditorRoute>() -> Kind.EDITOR
        else -> Kind.DETAIL
    }

    fun NavDestination.tabIndex(): Int? = when {
        hasRoute<TodayRoute>() -> 0
        hasRoute<CalendarRoute>() -> 1
        hasRoute<TasksRoute>() -> 2
        else -> null
    }

    /** +1 when moving right in tab order, -1 when moving left, 0 when not a tab-to-tab change. */
    private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabDirection(): Int {
        val from = initialState.destination.tabIndex() ?: return 0
        val to = targetState.destination.tabIndex() ?: return 0
        return Integer.signum(to - from)
    }

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
        initialState.destination.kind() == Kind.TAB && targetState.destination.kind() == Kind.TAB

    private fun tabDuration(m: DsMotion) = m.medium + 40

    /** Keeps the screen underneath fully visible until the screen on top has finished. */
    private fun hold(durationMillis: Int): ExitTransition = fadeOut(tween(durationMillis = 1, delayMillis = durationMillis))

    fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(m: DsMotion): EnterTransition {
        if (m.reduced) return fadeIn(m.enter(m.short))
        if (isTabSwitch()) {
            val d = tabDuration(m); val dir = tabDirection()
            return fadeIn(m.enter(d)) + slideInHorizontally(m.enter(d)) { w -> dir * w / 24 }
        }
        return when (targetState.destination.kind()) {
            Kind.EDITOR -> fadeIn(m.enter(m.long)) + slideInVertically(m.enter(m.long)) { h -> h / 12 }
            else -> fadeIn(m.enter(m.long)) + slideInHorizontally(m.enter(m.long)) { w -> w / 10 }
        }
    }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(m: DsMotion): ExitTransition {
        if (m.reduced) return hold(m.short)
        if (isTabSwitch()) {
            val d = tabDuration(m); val dir = tabDirection()
            return slideOutHorizontally(m.enter(d)) { w -> -dir * w / 48 } + hold(d)
        }
        return when (targetState.destination.kind()) {
            Kind.EDITOR -> hold(m.long)
            else -> slideOutHorizontally(m.enter(m.long)) { w -> -w / 16 } + hold(m.long)
        }
    }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(m: DsMotion): EnterTransition {
        if (m.reduced) return EnterTransition.None
        if (isTabSwitch()) {
            val d = tabDuration(m); val dir = tabDirection()
            return slideInHorizontally(m.standard(d)) { w -> dir * w / 48 }
        }
        return when (initialState.destination.kind()) {
            Kind.EDITOR -> EnterTransition.None
            else -> slideInHorizontally(m.standard(m.long)) { w -> -w / 16 }
        }
    }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(m: DsMotion): ExitTransition {
        if (m.reduced) return fadeOut(m.exit(m.short))
        if (isTabSwitch()) {
            val d = tabDuration(m); val dir = tabDirection()
            return fadeOut(m.standard(d)) + slideOutHorizontally(m.standard(d)) { w -> -dir * w / 24 }
        }
        // Same duration and easing as popEnter underneath, so the pair moves as one.
        return when (initialState.destination.kind()) {
            Kind.EDITOR -> fadeOut(m.standard(m.long)) + slideOutVertically(m.standard(m.long)) { h -> h / 12 }
            else -> fadeOut(m.standard(m.long)) + slideOutHorizontally(m.standard(m.long)) { w -> w / 10 }
        }
    }
}
