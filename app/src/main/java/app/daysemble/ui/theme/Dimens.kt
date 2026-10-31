package app.daysemble.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 4-pt spacing grid. */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val x3 = 32.dp
    val x4 = 48.dp

    /** Horizontal screen gutter. */
    val gutter = 20.dp
}

object Radii {
    val chip = RoundedCornerShape(8.dp)
    val control = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(16.dp)
    /** Agenda cards (Today, the calendar's day lists): larger tiles than [card]. */
    val tile = RoundedCornerShape(20.dp)
    /** Top of the Today agenda panel. */
    val panel = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val dialog = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(percent = 50)
}

object Sizes {
    val touch = 48.dp
    val iconSmall = 18.dp
    val icon = 22.dp
    val rowMin = 56.dp
    val fab = 56.dp
    val hairline = 1.dp
    val checkbox = 22.dp
    val bottomBar = 72.dp
}

/**
 * Shadows, for the few surfaces that float over content. Elevation is otherwise tonal (surface
 * colours), so these stay soft: they separate a layer, they don't lift it off the page.
 */
object Elevation {
    /** Bottom bar and add button, over a list that scrolls under them (with the scroll-edge fade). */
    val floating = 6.dp
    /** The Today agenda panel's top edge over the overview (light theme only). */
    val panel = 8.dp
    /** A row being dragged. */
    val lifted = 6.dp
    /** Shadow colour strength, as a fraction of the theme's scrim. */
    const val SHADOW_ALPHA = 0.5f
}
