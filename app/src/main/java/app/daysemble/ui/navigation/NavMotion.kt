package app.daysemble.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
 * Transitions for screens pushed over the main screen and popped back to it. Primary tabs don't
 * use these: they switch inside [MainRoute] (see DaysembleRoot, `DsMotion.cover`).
 *
 * One rule keeps every transition free of blank or double-exposed frames: only the screen on top
 * fades. The screen underneath stays fully opaque and only shifts slightly (parallax).
 * - Forward: the incoming screen is drawn on top and fades in while the previous one holds.
 * - Back (including predictive back, which scrubs these same transitions with the gesture): the
 *   leaving screen is on top and fades out over the destination, which is already opaque.
 *   Forward and back use the same distance, duration and easing, so the pair moves as one.
 * - Editors rise from below; detail and Settings pages come in from the end side.
 * - Offsets are mirrored in right-to-left layouts. Reduced motion: short fades, no movement.
 */
internal object NavMotion {

    private enum class Kind { MAIN, EDITOR, DETAIL }

    private fun NavDestination.kind(): Kind = when {
        hasRoute<MainRoute>() -> Kind.MAIN
        hasRoute<TaskEditorRoute>() || hasRoute<EventEditorRoute>() -> Kind.EDITOR
        else -> Kind.DETAIL
    }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(m: DsMotion, rtl: Boolean): EnterTransition {
        if (m.reduced) return fadeIn(m.enter(m.short))
        val e = if (rtl) -1 else 1
        return when (targetState.destination.kind()) {
            Kind.EDITOR -> fadeIn(m.enter(m.long)) + slideInVertically(m.enter(m.long)) { h -> h / 12 }
            else -> fadeIn(m.enter(m.long)) + slideInHorizontally(m.enter(m.long)) { w -> e * w / 10 }
        }
    }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(m: DsMotion, rtl: Boolean): ExitTransition {
        if (m.reduced) return m.hold(m.short)
        val e = if (rtl) -1 else 1
        return when (targetState.destination.kind()) {
            Kind.EDITOR -> m.hold(m.long)
            else -> slideOutHorizontally(m.enter(m.long)) { w -> -e * w / 16 } + m.hold(m.long)
        }
    }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(m: DsMotion, rtl: Boolean): EnterTransition {
        if (m.reduced) return EnterTransition.None
        val e = if (rtl) -1 else 1
        return when (initialState.destination.kind()) {
            Kind.EDITOR -> EnterTransition.None
            else -> slideInHorizontally(m.standard(m.long)) { w -> -e * w / 16 }
        }
    }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(m: DsMotion, rtl: Boolean): ExitTransition {
        if (m.reduced) return fadeOut(m.exit(m.short))
        val e = if (rtl) -1 else 1
        return when (initialState.destination.kind()) {
            Kind.EDITOR -> fadeOut(m.standard(m.long)) + slideOutVertically(m.standard(m.long)) { h -> h / 12 }
            else -> fadeOut(m.standard(m.long)) + slideOutHorizontally(m.standard(m.long)) { w -> e * w / 10 }
        }
    }
}
