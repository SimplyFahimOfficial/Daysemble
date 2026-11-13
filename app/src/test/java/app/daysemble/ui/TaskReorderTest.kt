package app.daysemble.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.daysemble.data.prefs.TaskSort
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.Task
import app.daysemble.ui.tasks.TaskSection
import app.daysemble.ui.tasks.TasksScreen
import app.daysemble.ui.tasks.TasksUiState
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Dragging a row in "List order": a database update while the row is held (a sync, an edit made
 * elsewhere) must not rebuild the order under the finger; it is applied when the row is let go.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class TaskReorderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun task(i: Int, title: String = "Task $i") = Task(
        id = "t$i", listId = "l", parentId = null, title = title, notes = "", dueDate = null, dueTime = null,
        priority = Priority.NONE, isCompleted = false, completedAt = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, sortKey = i.toLong(),
    )

    private fun uiState(tasks: List<Task>) = TasksUiState(
        sort = TaskSort.MANUAL, sections = listOf(TaskSection(null, tasks)), totalOpen = tasks.size, isLoading = false,
    )

    @Test fun aDataUpdateWhileARowIsHeld_doesNotUndoTheDrag() {
        var state by mutableStateOf(uiState((1..6).map { task(it) }))
        var saved: List<String>? = null
        compose.setContent {
            DaysembleTheme(ThemeMode.LIGHT, reducedMotionOverride = true) {
                TasksScreen(
                    state = state, now = Instant.parse("2026-10-27T10:00:00Z"), contentPadding = PaddingValues(bottom = 0.dp),
                    onFilter = {}, onList = {}, onSort = {}, onToggle = { _, _ -> }, onOpen = {}, onReorder = { saved = it },
                )
            }
        }
        compose.waitForIdle()
        val handle = compose.onAllNodesWithContentDescription("Reorder")[0]
        handle.performTouchInput { down(center); advanceEventTime(700); moveBy(Offset(0f, 40f)); moveBy(Offset(0f, 120f)) }
        compose.waitForIdle()
        // The data changes under the finger (another title, same rows).
        compose.runOnIdle { state = uiState((1..6).map { task(it, if (it == 6) "Task 6, edited" else "Task $it") }) }
        compose.waitForIdle()
        handle.performTouchInput { up() }
        compose.waitForIdle()
        assertThat(saved).isNotNull()
        assertThat(saved!!.first()).isNotEqualTo("t1") // the held row moved on and stayed there
        assertThat(saved!!.toSet()).containsExactlyElementsIn((1..6).map { "t$it" })
    }

    private fun titleOrder(count: Int) = (1..count).map { "Task $it" }
        .mapNotNull { t -> compose.onAllNodes(androidx.compose.ui.test.hasText(t)).fetchSemanticsNodes().firstOrNull()?.let { t to it.positionInRoot.y } }
        .sortedBy { it.second }.map { it.first }

    /** The drop is being saved: data that still has the old order must not put the row back. */
    @Test fun staleDataAfterTheDrop_doesNotPutTheRowBack() {
        var state by mutableStateOf(uiState((1..6).map { task(it) }))
        var saved: List<String>? = null
        compose.setContent {
            DaysembleTheme(ThemeMode.LIGHT, reducedMotionOverride = true) {
                TasksScreen(
                    state = state, now = Instant.parse("2026-10-27T10:00:00Z"), contentPadding = PaddingValues(bottom = 0.dp),
                    onFilter = {}, onList = {}, onSort = {}, onToggle = { _, _ -> }, onOpen = {}, onReorder = { saved = it },
                )
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Reorder")[0].performTouchInput { down(center); advanceEventTime(700); moveBy(Offset(0f, 40f)); moveBy(Offset(0f, 160f)); up() }
        compose.waitForIdle()
        val moved = saved!!
        assertThat(moved.first()).isNotEqualTo("t1")
        val shown = titleOrder(6)
        assertThat(shown.first()).isNotEqualTo("Task 1")
        // The old data arrives (an unrelated update that was already on its way): the order on screen holds.
        compose.runOnIdle { state = uiState((1..6).map { task(it, if (it == 3) "Task 3" else "Task $it") }.let { it }) }
        compose.waitForIdle()
        assertThat(titleOrder(6)).isEqualTo(shown)
        // The saved order arrives: same picture.
        val byId = (1..6).associate { "t$it" to task(it) }
        compose.runOnIdle { state = uiState(moved.map { byId.getValue(it) }) }
        compose.waitForIdle()
        assertThat(titleOrder(6)).isEqualTo(shown)
    }

    /** Hundreds of tasks: the drag still moves the row and saves every id once. */
    @Test fun aLongList_dragsAndSavesEveryTask() {
        val tasks = (1..1_500).map { task(it) }
        var saved: List<String>? = null
        compose.setContent {
            DaysembleTheme(ThemeMode.LIGHT, reducedMotionOverride = true) {
                TasksScreen(
                    state = uiState(tasks), now = Instant.parse("2026-10-27T10:00:00Z"), contentPadding = PaddingValues(bottom = 0.dp),
                    onFilter = {}, onList = {}, onSort = {}, onToggle = { _, _ -> }, onOpen = {}, onReorder = { saved = it },
                )
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Reorder")[0].performTouchInput { down(center); advanceEventTime(700); moveBy(Offset(0f, 40f)); moveBy(Offset(0f, 200f)); up() }
        compose.waitForIdle()
        assertThat(saved).isNotNull()
        assertThat(saved!!).hasSize(1_500)
        assertThat(saved!!.toSet()).hasSize(1_500)
        assertThat(saved!!.first()).isNotEqualTo("t1")
    }
}
