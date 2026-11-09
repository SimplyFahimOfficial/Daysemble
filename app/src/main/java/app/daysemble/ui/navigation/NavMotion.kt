package app.daysemble.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.unit.Density
import androidx.navigation.NavBackStackEntry
import app.daysemble.ui.theme.DsMotion

/**
 * Transitions for pages opened over the main screen (details, Search, Settings and its pages,
 * editors) and closed again: one shared axis X for all of them (see [DsMotion.sharedAxisEnter]).
 * Primary tabs don't use these: they switch inside the main screen (TabHost in DaysembleRoot).
 *
 * Predictive back (Android 14+ gesture) shrinks the leaving page under the finger and reveals the
 * page beneath, which is complete and still from the first frame ([predictivePopEnter]). Navigation's
 * defaults would show the leaving page shrinking over a page fading in from nothing.
 */
internal object NavMotion {
    @Suppress("UnusedReceiverParameter")
    fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(m: DsMotion, rtl: Boolean, d: Density): EnterTransition =
        m.sharedAxisEnter(forward = true, rtl, d)

    @Suppress("UnusedReceiverParameter")
    fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(m: DsMotion, rtl: Boolean, d: Density): ExitTransition =
        m.sharedAxisExit(forward = true, rtl, d)

    @Suppress("UnusedReceiverParameter")
    fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(m: DsMotion, rtl: Boolean, d: Density): EnterTransition =
        m.sharedAxisEnter(forward = false, rtl, d)

    @Suppress("UnusedReceiverParameter")
    fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(m: DsMotion, rtl: Boolean, d: Density): ExitTransition =
        m.sharedAxisExit(forward = false, rtl, d)

    /** The page being revealed by the back gesture: complete and still from the first frame. */
    fun predictivePopEnter(): EnterTransition = EnterTransition.None

    fun predictivePopExit(m: DsMotion, d: Density, swipeEdge: Int): ExitTransition = m.predictiveBackExit(d, swipeEdge)
}
