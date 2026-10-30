package app.daysemble.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.ui.components.ChildLifecycleOwner
import app.daysemble.ui.components.VisibilityLifecycle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Content kept composed while hidden (tabs, the main screen under a page) pauses: it stops
 * collecting while hidden, keeps showing its last value (nothing goes blank), and catches up as
 * soon as it is visible again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VisibilityLifecycleTest {
    @get:Rule val compose = createComposeRule()

    private class Parent : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun followsTheParent_cappedAtCreatedWhileHidden() {
        val parent = Parent().apply { registry.currentState = Lifecycle.State.RESUMED }
        val child = ChildLifecycleOwner(parent.registry)
        assertThat(child.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
        child.setVisible(false)
        assertThat(child.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
        // The app going to the background while hidden doesn't make it come back early.
        parent.registry.currentState = Lifecycle.State.CREATED
        parent.registry.currentState = Lifecycle.State.RESUMED
        assertThat(child.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
        child.setVisible(true)
        assertThat(child.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
        // Visible, but the app is in the background: follows the parent down.
        parent.registry.currentState = Lifecycle.State.CREATED
        assertThat(child.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
        child.dispose()
        assertThat(child.lifecycle.currentState).isEqualTo(Lifecycle.State.DESTROYED)
        parent.registry.currentState = Lifecycle.State.RESUMED
        assertThat(child.lifecycle.currentState).isEqualTo(Lifecycle.State.DESTROYED)
    }

    @Test fun hiddenContent_keepsItsLastValue_andCatchesUpWhenShown() {
        val data = MutableStateFlow("first")
        var visible by mutableStateOf(true)
        compose.setContent {
            VisibilityLifecycle(visible) {
                val v by data.collectAsStateWithLifecycle()
                Text("value: $v")
            }
        }
        compose.onNodeWithText("value: first").assertExists()

        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        data.value = "second"
        compose.waitForIdle()
        // Paused: no recomposition with the new data, and the old value is still shown (not blank).
        compose.onNodeWithText("value: first").assertExists()

        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        compose.onNodeWithText("value: second").assertExists()
    }
}
