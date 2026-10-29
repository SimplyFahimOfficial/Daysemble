package app.daysemble.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.edit
import app.daysemble.DaysembleApp
import app.daysemble.core.recurrence.RRule
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.domain.model.TaskDraft
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
 * Checking off a repeating task in Today, through the real app root (as MainActivity composes it):
 * the next occurrence is created once, the snackbar says when it's due, and Undo puts the series
 * back exactly as it was. Repeated taps don't add more.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class RepeatCompletionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val c get() = (compose.activity.application as DaysembleApp).container

    @Before fun setUp() {
        compose.mainClock.autoAdvance = false
        runBlocking {
            compose.activity.application.settingsDataStore.edit { it.clear() }
            c.clearLocalData()
            c.settings.completeOnboarding("Sam")
        }
    }

    private fun settle(n: Int = 20) = repeat(n) {
        compose.mainClock.advanceTimeBy(100); Thread.sleep(15); shadowOf(Looper.getMainLooper()).idle()
    }

    private fun showApp() {
        compose.setContent {
            val s by c.settings.settings.collectAsState(initial = null)
            val settings = s ?: return@setContent
            DaysembleTheme(settings.themeMode, settings.logo, settings.reducedMotionOverride) { DaysembleRoot(c, settings) }
        }
        settle()
    }

    private fun open() = runBlocking { c.tasks.allTasks.first() }.filter { it.title == "Stretch" && !it.isCompleted }

    private fun waitFor(text: String, substring: Boolean = false) {
        repeat(40) { if (compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()) return; settle(2) }
        compose.onNodeWithText(text, substring = substring).assertExists()
    }

    @Test fun completingInToday_showsTheNextDate_andUndoRestoresTheSeries() {
        val today = c.clock.today()
        val id = runBlocking { c.tasks.create(TaskDraft(title = "Stretch", dueDate = today, repeat = RRule.parse("FREQ=DAILY")), c.defaults().taskListId) }
        showApp()
        waitFor("Stretch")
        compose.onNodeWithContentDescription("Mark Stretch complete").performClick()
        settle()
        waitFor("Done · next one", substring = true)
        assertThat(open().map { it.dueDate }).containsExactly(today.plusDays(1))
        // A second tap on the (now completed) row reopens it; tapping "complete" again still leaves one open occurrence.
        compose.onNodeWithText("Undo").performClick()
        settle()
        repeat(20) { if (open().singleOrNull()?.id == id) return@repeat; settle(2) }
        assertThat(open().map { it.id }).containsExactly(id) // the generated one was removed again
        assertThat(runBlocking { c.tasks.allTasks.first() }.count { it.title == "Stretch" }).isEqualTo(1)
    }

    /**
     * Review issue: completing an older occurrence again while the next one is still open (it was
     * edited, so reopening kept it) creates nothing, but the series hasn't ended. The snackbar must
     * point at the open one, not say "that was the last one".
     */
    @Test fun completingAgain_withTheNextOneStillOpen_pointsAtIt() {
        val today = c.clock.today()
        val id = runBlocking { c.tasks.create(TaskDraft(title = "Stretch", dueDate = today, repeat = RRule.parse("FREQ=DAILY")), c.defaults().taskListId) }
        runBlocking {
            c.tasks.setCompleted(id, true)
            val next = c.tasks.allTasks.first().single { it.title == "Stretch" && !it.isCompleted }
            Thread.sleep(5) // a later updatedAt: the next one counts as edited
            c.tasks.update(next.id, TaskDraft(title = "Stretch longer", dueDate = next.dueDate, repeat = next.repeat))
            c.tasks.setCompleted(id, false)
        }
        showApp()
        waitFor("Stretch")
        compose.onNodeWithContentDescription("Mark Stretch complete").performClick()
        settle()
        waitFor("Done · next one", substring = true)
        assertThat(compose.onAllNodesWithText("Done · that was the last one").fetchSemanticsNodes()).isEmpty()
        assertThat(runBlocking { c.tasks.allTasks.first() }.filter { !it.isCompleted }.map { it.title }).containsExactly("Stretch longer")
    }

    @Test fun completingTheLastOccurrence_saysSo() {
        val id = runBlocking {
            c.tasks.create(TaskDraft(title = "Stretch", dueDate = c.clock.today(), repeat = RRule.parse("FREQ=DAILY;COUNT=1")), c.defaults().taskListId)
        }
        showApp()
        waitFor("Stretch")
        compose.onNodeWithContentDescription("Mark Stretch complete").performClick()
        settle()
        waitFor("Done · that was the last one")
        assertThat(runBlocking { c.tasks.get(id) }!!.isCompleted).isTrue()
    }

    @Test fun openingTheSameTaskFromANotificationTwice_stacksOnePage() {
        val id = runBlocking { c.tasks.create(TaskDraft(title = "Stretch", dueDate = c.clock.today()), c.defaults().taskListId) }
        val requests = kotlinx.coroutines.flow.MutableStateFlow<app.daysemble.OpenRequest?>(null)
        compose.setContent {
            val s by c.settings.settings.collectAsState(initial = null)
            val settings = s ?: return@setContent
            DaysembleTheme(settings.themeMode, settings.logo, settings.reducedMotionOverride) {
                DaysembleRoot(c, settings, requests, onOpenHandled = { requests.value = null })
            }
        }
        settle()
        repeat(2) {
            compose.runOnUiThread { requests.value = app.daysemble.OpenRequest(app.daysemble.data.db.OwnerType.TASK, id, null) }
            settle()
        }
        // A tap on the notification opens the task's detail screen, once.
        assertThat(compose.onAllNodesWithTag(app.daysemble.ui.task.TaskDetailTags.SCREEN).fetchSemanticsNodes()).hasSize(1)
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        settle()
        // One Back is enough: there was only one copy of the page.
        assertThat(compose.onAllNodesWithTag(app.daysemble.ui.task.TaskDetailTags.SCREEN).fetchSemanticsNodes()).isEmpty()
    }

    @Test fun completingTwice_neverCreatesTwoNextOccurrences() {
        val today = c.clock.today()
        val id = runBlocking { c.tasks.create(TaskDraft(title = "Stretch", dueDate = today, repeat = RRule.parse("FREQ=WEEKLY")), c.defaults().taskListId) }
        runBlocking { c.tasks.setCompleted(id, true); c.tasks.setCompleted(id, true) }
        // As after an app restart: the notification's Complete action on the same occurrence.
        runBlocking { c.tasks.setCompleted(id, true) }
        assertThat(open().map { it.dueDate }).containsExactly(today.plusWeeks(1))
    }
}
