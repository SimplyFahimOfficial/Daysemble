package app.daysemble.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Content that stays composed while it is not on screen (the hidden tabs, and the main screen while
 * a page covers it) gets its own lifecycle: the parent's state while [visible], at most CREATED
 * while hidden. Everything lifecycle-aware inside it then pauses by itself while hidden:
 * `collectAsStateWithLifecycle` stops collecting (the last value stays on screen, so nothing goes
 * blank), and the minute and second tickers stop. Without this, every database change and every
 * minute rebuilt and recomposed all three tabs plus the screens under Settings, on the main
 * thread, during whatever animation the visible screen was running.
 *
 * ViewModels are unaffected (they belong to the activity's ViewModelStore), so state and scroll
 * positions are kept; coming back resumes collection and shows the latest data.
 */
@Composable
fun VisibilityLifecycle(visible: Boolean, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current
    val owner = remember(parent) { ChildLifecycleOwner(parent.lifecycle) }
    SideEffect { owner.setVisible(visible) }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}

/** A lifecycle that follows [parent], capped at CREATED while not visible. */
internal class ChildLifecycleOwner(private val parent: Lifecycle) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private var visible = true
    private var disposed = false
    private val observer = LifecycleEventObserver { _, _ -> sync() }

    override val lifecycle: Lifecycle get() = registry

    init {
        parent.addObserver(observer)
        sync()
    }

    fun setVisible(v: Boolean) {
        if (v == visible) return
        visible = v
        sync()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        parent.removeObserver(observer)
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.DESTROYED
    }

    private fun sync() {
        if (disposed) return
        val p = parent.currentState
        val target = when {
            p == Lifecycle.State.DESTROYED -> Lifecycle.State.DESTROYED
            !visible && p.isAtLeast(Lifecycle.State.CREATED) -> Lifecycle.State.CREATED
            else -> p
        }
        // A registry can't leave DESTROYED or go back to INITIALIZED.
        if (registry.currentState == Lifecycle.State.DESTROYED || target == Lifecycle.State.INITIALIZED) return
        registry.currentState = target
    }
}
