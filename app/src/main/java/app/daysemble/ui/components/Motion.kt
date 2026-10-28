package app.daysemble.ui.components

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.daysemble.ui.theme.DsTheme

/** List item enter/exit/reorder motion; with reduced motion items only cross-fade (no sliding). */
@Composable
fun LazyItemScope.itemMotion(): Modifier {
    val m = DsTheme.motion
    return if (m.reduced) Modifier.animateItem(fadeInSpec = m.standard(m.short), placementSpec = null, fadeOutSpec = m.standard(m.short))
    else Modifier.animateItem(fadeInSpec = m.enter(), placementSpec = m.standard(m.medium), fadeOutSpec = m.exit())
}

/**
 * A component's colour that animates when its own state changes (selected, completed, focused…)
 * and follows a theme change at once. The theme change itself is one cross-fade of the whole app
 * (see DaysembleTheme). With plain animateColorAsState every themed colour also ran its own
 * 220–400 ms animation to the new theme, so cards, date cells and chips lagged behind the rest of
 * the screen and recomposed on every frame of it.
 */
@androidx.compose.runtime.Composable
fun animateStateColor(
    targetValue: androidx.compose.ui.graphics.Color,
    animationSpec: androidx.compose.animation.core.AnimationSpec<androidx.compose.ui.graphics.Color>,
    @Suppress("UNUSED_PARAMETER") label: String = "stateColor",
): androidx.compose.ui.graphics.Color {
    val dark = app.daysemble.ui.theme.DsTheme.colors.isDark
    val anim = androidx.compose.runtime.remember { androidx.compose.animation.Animatable(targetValue) }
    // The theme the animated value belongs to.
    val valueTheme = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(dark) }
    androidx.compose.runtime.LaunchedEffect(targetValue, dark) {
        if (valueTheme.value != dark) {
            anim.snapTo(targetValue)
            valueTheme.value = dark
        } else {
            anim.animateTo(targetValue, animationSpec)
        }
    }
    return if (valueTheme.value != dark) targetValue else anim.value
}
