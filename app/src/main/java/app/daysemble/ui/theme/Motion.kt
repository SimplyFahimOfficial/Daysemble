package app.daysemble.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The single motion vocabulary for Daysemble. Durations: short 120, medium 220, long 320 ms.
 * When [reduced] is true every spec collapses to a short linear-ish fade and movement specs
 * should be replaced by cross-fades. Callers check [reduced] for offsets.
 */
@Immutable
data class DsMotion(val reduced: Boolean = false) {
    val short: Int get() = 120
    val medium: Int get() = if (reduced) 120 else 220
    val long: Int get() = if (reduced) 120 else 320

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
