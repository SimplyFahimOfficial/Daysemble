package app.daysemble.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.preferences.core.edit
import app.daysemble.DaysembleApp
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.domain.model.EventDraft
import app.daysemble.domain.model.TaskDraft
import app.daysemble.ui.actions.ItemMenuTags
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
import java.time.LocalTime
import java.time.ZoneId

/**
 * Quick actions through the real app root: a due time from Quick Add, Reschedule from the item
 * menu (with Undo), and Undo after deleting an event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class QuickActionsFlowTest {
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

    private fun waitFor(text: String, substring: Boolean = false) {
        repeat(40) { if (compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()) return; settle(2) }
        compose.onNodeWithText(text, substring = substring).assertExists()
    }

    private fun <T> eventually(read: () -> T, done: (T) -> Boolean): T {
        repeat(40) { val v = read(); if (done(v)) return v; settle(2) }
        return read()
    }

    private fun task(title: String) = runBlocking { c.tasks.allTasks.first() }.singleOrNull { it.title == title }

    @Test fun quickAdd_setsADueTimeForATask() {
        showApp()
        compose.onNodeWithContentDescription("Add task or event").performClick(); settle()
        compose.onNode(hasSetTextAction()).performTextInput("Pay bills")
        compose.onNode(hasText("Add time") and hasClickAction()).performClick(); settle()
        compose.onNode(hasText("Done") and hasClickAction()).performClick(); settle()
        compose.onNode(hasSetTextAction()).performImeAction(); settle() // the keyboard's Done adds it
        val t = eventually({ task("Pay bills") }) { it != null }!!
        // No date was picked: choosing a time sets the task for today.
        assertThat(t.dueDate).isEqualTo(c.clock.today())
        assertThat(t.dueTime).isNotNull()
    }

    @Test fun reschedule_movesTheDate_keepsTheTime_andUndoMovesItBack() {
        val today = c.clock.today()
        runBlocking { c.tasks.create(TaskDraft(title = "Call Sam", dueDate = today, dueTime = LocalTime.of(17, 30)), c.defaults().taskListId) }
        showApp()
        waitFor("Call Sam")
        compose.onAllNodesWithText("Call Sam").onFirst().performTouchInput { longClick() }; settle()
        compose.onNodeWithTag(ItemMenuTags.RESCHEDULE).performClick(); settle()
        compose.onNode(hasText("Tomorrow") and hasClickAction()).performClick(); settle()
        val moved = eventually({ task("Call Sam") }) { it?.dueDate == today.plusDays(1) }!!
        assertThat(moved.dueDate).isEqualTo(today.plusDays(1))
        assertThat(moved.dueTime).isEqualTo(LocalTime.of(17, 30))
        waitFor("Moved to ", substring = true)
        compose.onNodeWithText("Undo").performClick(); settle()
        assertThat(eventually({ task("Call Sam") }) { it?.dueDate == today }!!.dueDate).isEqualTo(today)
    }

    @Test fun deletingAnEvent_canBeUndone() {
        val today = c.clock.today()
        val zone = ZoneId.systemDefault()
        runBlocking {
            c.events.create(
                EventDraft(
                    title = "Dentist", startDate = today, startTime = LocalTime.of(23, 0), endDate = today, endTime = LocalTime.of(23, 30),
                    zone = zone, reminderMinutesBefore = listOf(15),
                ),
                c.defaults().calendarId,
            )
        }
        fun dentist() = runBlocking { c.events.occurrences(today, today, zone).first() }.filter { it.title == "Dentist" }
        showApp()
        waitFor("Dentist")
        compose.onAllNodesWithText("Dentist").onFirst().performTouchInput { longClick() }; settle()
        compose.onNodeWithTag(ItemMenuTags.DELETE).performClick(); settle()
        compose.onNode(hasText("Delete") and hasClickAction()).performClick(); settle()
        assertThat(eventually({ dentist() }) { it.isEmpty() }).isEmpty()
        waitFor("Event deleted")
        compose.onNodeWithText("Undo").performClick(); settle()
        val back = eventually({ dentist() }) { it.isNotEmpty() }
        assertThat(back).hasSize(1)
        assertThat(runBlocking { c.events.reminders(back.single().eventId) }).containsExactly(15)
    }
}
