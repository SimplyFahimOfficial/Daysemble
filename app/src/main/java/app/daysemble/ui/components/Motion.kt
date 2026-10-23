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
