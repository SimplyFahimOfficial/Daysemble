package app.daysemble.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.datastore.preferences.core.edit
import app.daysemble.DaysembleApp
import app.daysemble.core.recurrence.RRule
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.domain.model.TaskDraft
import app.daysemble.ui.actions.ItemMenuTags
import app.daysemble.ui.task.HistoryTags
import app.daysemble.ui.task.TaskDetailTags
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tasks in the real app root: a tap on Today opens the detail and countdown screen (not the
 * editor), a long-press opens the item menu whose Edit opens the editor, and completing a
 * repeating task in its detail moves the screen to the next occurrence with the history updated
 * from the database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class TaskDetailFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = compose.activity.application as DaysembleApp
    private val c get() = app.container

    @Before fun reset() {
        compose.mainClock.autoAdvance = false
        runBlocking {
            app.settingsDataStore.edit { it.clear() }
            c.clearLocalData()
            c.settings.completeOnboarding("Sam")
            // A short agenda, so every item is on screen.
            val list = c.tasks.ensureDefaultList("My tasks")
            val today = c.clock.today()
            c.tasks.create(TaskDraft(title = "Book train tickets", dueDate = today, dueTime = java.time.LocalTime.of(23, 59), countdown = true), list)
            c.tasks.create(TaskDraft(title = "Buy groceries", dueDate = today), list)
        }
    }

    private fun start() {
        compose.setContent {
            val s by c.settings.settings.collectAsState(initial = null)
            val settings = s ?: return@setContent
            DaysembleTheme(settings.themeMode, settings.logo, settings.reducedMotionOverride) { DaysembleRoot(c, settings) }
        }
        settle(30)
    }

    private fun settle(n: Int = 20) = repeat(n) {
        compose.mainClock.advanceTimeBy(100)
        Thread.sleep(15)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val editorTitle = hasSetTextAction() and hasContentDescription("What needs doing?")

    @Test fun tapOpensTheDetail_notTheEditor() {
        start()
        compose.onNodeWithText("Book train tickets").performClick()
        settle()
        compose.onNodeWithTag(TaskDetailTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(TaskDetailTags.TITLE).assert(hasText("Book train tickets"))
        // Its countdown is on, so the live countdown is the centre of the screen.
        compose.onNodeWithTag(TaskDetailTags.COUNTDOWN).assertIsDisplayed()
        assertThat(compose.onAllNodes(editorTitle).fetchSemanticsNodes()).isEmpty()
        // Editing is one step further: the pencil.
        compose.onNodeWithTag(TaskDetailTags.EDIT).performClick()
        settle()
        compose.onNode(editorTitle).assertIsDisplayed()
    }

    @Test fun longPressOpensTheMenu_andEditOpensTheEditorForThatTask() {
        start()
        compose.onNodeWithText("Buy groceries").performTouchInput { longClick() }
        settle()
        compose.onNodeWithTag(ItemMenuTags.SHEET).assertIsDisplayed()
        assertThat(compose.onAllNodesWithTag(TaskDetailTags.SCREEN).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithTag(ItemMenuTags.EDIT).performClick()
        settle()
        compose.onNode(editorTitle).assert(hasText("Buy groceries"))
    }

    @Test fun longPressComplete_completesThePressedTask() {
        start()
        compose.onNodeWithText("Buy groceries").performTouchInput { longClick() }
        settle()
        compose.onNodeWithTag(ItemMenuTags.COMPLETE).performClick()
        settle()
        val t = runBlocking { c.tasks.allTasks.first() }
        assertThat(t.single { it.title == "Buy groceries" }.isCompleted).isTrue()
        assertThat(t.single { it.title == "Book train tickets" }.isCompleted).isFalse()
    }

    @Test fun completingARepeatingTaskInItsDetail_movesToTheNextOccurrence_andRecordsHistory() {
        val today = c.clock.today()
        runBlocking {
            c.tasks.create(
                TaskDraft(title = "Stretch", dueDate = today, repeat = RRule.parse("FREQ=DAILY"), countdown = true),
                c.tasks.ensureDefaultList("My tasks"),
            )
        }
        start()
        compose.onNodeWithText("Stretch").performClick()
        settle()
        compose.onNodeWithTag(TaskDetailTags.HISTORY).assertExists()
        compose.onNodeWithTag(HistoryTags.EMPTY).assertExists()
        compose.onNodeWithTag(TaskDetailTags.COMPLETE).performClick()
        settle(30)
        val rows = runBlocking { c.tasks.allTasks.first() }.filter { it.title == "Stretch" }.sortedBy { it.dueDate }
        assertThat(rows.map { it.dueDate to it.isCompleted }).containsExactly(today to true, today.plusDays(1) to false).inOrder()
        // The screen now shows the next occurrence: still open, its countdown on, today's cell completed.
        compose.onNodeWithTag(TaskDetailTags.COMPLETE).assert(hasText("Complete"))
        compose.onNodeWithTag(HistoryTags.cell(today)).assertExists()
        compose.onNodeWithTag(HistoryTags.STATS).assert(hasText("1", substring = false).let { androidx.compose.ui.test.hasAnyDescendant(it) })
    }
}
