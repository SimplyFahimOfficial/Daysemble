package app.daysemble.ui

import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import app.daysemble.DaysembleApp
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.SourceKind
import app.daysemble.data.db.SyncState
import app.daysemble.data.db.TaskListEntity
import app.daysemble.data.prefs.ClockFormat
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.Task
import app.daysemble.domain.model.TaskDraft
import app.daysemble.ui.components.LiveCountdown
import app.daysemble.ui.components.TaskRow
import app.daysemble.ui.components.rememberSecondTicker
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.rememberTimePrefs
import app.daysemble.ui.task.TaskEditorRoute
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The task editor's repeat, countdown and scope flows against the real ViewModel and Room
 * database, plus the countdown components: the compact pill on rows, the per-second ticker that
 * stops with the lifecycle, and spoken text that changes once a minute, not every second.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class RecurringTaskUiTest {
    @get:Rule val compose = createComposeRule()
    private val c get() = ApplicationProvider.getApplicationContext<DaysembleApp>().container
    private val zone = ZoneId.systemDefault()
    private val today get() = c.clock.today()

    @Before fun setUp() {
        compose.mainClock.autoAdvance = false
        runBlocking { c.clearLocalData() }
    }

    private fun settle(n: Int = 15) = repeat(n) {
        compose.mainClock.advanceTimeBy(100); Thread.sleep(15); shadowOf(Looper.getMainLooper()).idle()
    }

    private fun show(clock: app.daysemble.core.time.AppClock = c.clock, content: @Composable () -> Unit) {
        compose.setContent {
            DaysembleTheme(themeMode = ThemeMode.LIGHT) {
                val prefs = rememberTimePrefs(ClockFormat.H24, null, zone)
                CompositionLocalProvider(
                    LocalTimePrefs provides prefs, LocalAppClock provides clock,
                    LocalSnackbar provides remember { SnackbarHostState() },
                ) { content() }
            }
        }
        settle()
    }

    private val verticalList = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
        SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)

    /** Scrolls the editor until [text] is on screen (performScrollTo waits for an idle that never comes). */
    private fun reveal(text: String) {
        repeat(40) {
            if (runCatching { compose.onAllNodesWithText(text)[0].assertIsDisplayed() }.isSuccess) return
            compose.onAllNodes(verticalList).fetchSemanticsNodes().forEach { n ->
                compose.runOnUiThread { n.config[SemanticsActions.ScrollBy].action?.invoke(0f, 250f) }
            }
            settle(3)
        }
        compose.onAllNodesWithText(text)[0].assertIsDisplayed()
    }

    private suspend fun listId() = c.tasks.ensureDefaultList("My tasks")
    private fun tasks() = runBlocking { c.tasks.allTasks.first() }

    @Test fun newTask_choosingARepeatDatesItToday_andTheCountdownShowsLive() {
        var closed = false
        show { TaskEditorRoute(null, null, null) { closed = true } }
        compose.onNode(hasSetTextAction() and hasContentDescription("What needs doing?")).performTextInput("Stretch")
        reveal("Weekdays")
        compose.onNodeWithText("Weekdays").performClick(); settle()
        reveal("Live countdown")
        compose.onNodeWithText("Live countdown").performClick(); settle()
        // The live countdown appears with the task (the date was set to today by choosing a repeat).
        compose.onNode(hasContentDescription("Due in", substring = true)).assertExists()
        compose.onNodeWithText("Save").performClick()
        repeat(40) { settle(); if (closed) return@repeat }
        assertThat(closed).isTrue()
        val t = tasks().single { it.title == "Stretch" }
        assertThat(t.dueDate).isEqualTo(today)
        assertThat(t.repeat!!.format()).isEqualTo("RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
        assertThat(t.countdownEnabled).isTrue()
    }

    @Test fun editingARepeatingTask_asksWhichOccurrences_andAppliesTheChoice() {
        val id = runBlocking { c.tasks.create(TaskDraft(title = "Water plants", dueDate = today, repeat = RRule.parse("FREQ=DAILY")), listId()) }
        runBlocking { c.tasks.setCompleted(id, true) } // history: one done occurrence
        val current = tasks().single { it.title == "Water plants" && !it.isCompleted }
        var closed = false
        show { TaskEditorRoute(current.id, null, null) { closed = true } }
        settle()
        compose.onNode(hasSetTextAction() and hasContentDescription("What needs doing?")).performTextReplacement("Water the herbs")
        settle()
        compose.onNodeWithText("Save").performClick(); settle()
        compose.onNodeWithText("This occurrence").assertIsDisplayed()
        compose.onNodeWithText("This and future occurrences").assertIsDisplayed()
        compose.onNodeWithText("The entire series").performClick()
        repeat(40) { settle(); if (closed) return@repeat }
        assertThat(closed).isTrue()
        // "The entire series" renamed the completed occurrence too.
        assertThat(tasks().filter { it.seriesId == current.seriesId }.map { it.title }).containsExactly("Water the herbs", "Water the herbs")
    }

    @Test fun changingTheRule_hidesThisOccurrenceOnly() {
        val id = runBlocking { c.tasks.create(TaskDraft(title = "Report", dueDate = today, repeat = RRule.parse("FREQ=DAILY")), listId()) }
        show { TaskEditorRoute(id, null, null) {} }
        settle()
        reveal("Monthly")
        compose.onNodeWithText("Monthly").performClick(); settle()
        compose.onNodeWithText("Save").performClick(); settle()
        assertThat(compose.onAllNodesWithText("This occurrence").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("This and future occurrences").performClick()
        repeat(20) { settle() }
        assertThat(runBlocking { c.tasks.get(id) }!!.repeat!!.format()).isEqualTo("RRULE:FREQ=MONTHLY")
    }

    @Test fun movingAWeeklyOccurrencesDate_isNotARuleChange() {
        // The form's one-day weekly repeat follows a moved date (Tuesday → Wednesday), but that alone
        // mustn't hide "This occurrence" in the save dialog.
        val tue = LocalDate.of(2026, 10, 27)
        val original = app.daysemble.ui.task.TaskForm(
            title = "Review", dueEpochDay = tue.toEpochDay(),
            repeat = app.daysemble.ui.task.TaskRepeatForm.from(RRule.parse("FREQ=WEEKLY;BYDAY=TU")),
        )
        val moved = original.withDue(tue.plusDays(1).toEpochDay())
        assertThat(moved.repeat.weekdays).containsExactly(3)
        val state = app.daysemble.ui.task.TaskEditorUiState(isNew = false, form = moved, original = original, task = null)
        assertThat(state.repeatChanged).isFalse()
        // Choosing another repeat is a rule change.
        val changed = moved.copy(repeat = moved.repeat.copy(preset = app.daysemble.ui.task.RepeatPreset.DAILY.name))
        assertThat(state.copy(form = changed).repeatChanged).isTrue()
    }

    @Test fun turningRepeatOff_andPickingAGoogleList_inOneSave_movesTheTask() {
        runBlocking {
            c.database.taskLists().upsert(TaskListEntity(id = "g", kind = SourceKind.GOOGLE, remoteId = "r", accountEmail = "a@example.com", title = "Google"))
        }
        val id = runBlocking { c.tasks.create(TaskDraft(title = "Pay rent", dueDate = today, repeat = RRule.parse("FREQ=MONTHLY")), listId()) }
        show { TaskEditorRoute(id, null, null) {} }
        settle()
        reveal("Doesn’t repeat")
        compose.onNodeWithText("Doesn’t repeat").performClick(); settle()
        reveal("Google")
        // By semantics: the editor grew (Time needed), and the chip can sit at the very bottom edge,
        // where a touch at its centre would land outside the viewport.
        compose.onNodeWithText("Google").performSemanticsAction(SemanticsActions.OnClick); settle()
        compose.onNodeWithText("Save").performClick(); settle()
        compose.onNodeWithText("This and future occurrences").performClick()
        repeat(20) { settle() }
        val t = runBlocking { c.tasks.get(id) }!!
        assertThat(t.listId).isEqualTo("g")
        assertThat(t.isRecurring).isFalse()
    }

    @Test fun aCompletedOccurrence_isHistory_itsRepeatIsntEditedThere() {
        val id = runBlocking { c.tasks.create(TaskDraft(title = "Water plants", dueDate = today, repeat = RRule.parse("FREQ=DAILY")), listId()) }
        runBlocking { c.tasks.setCompleted(id, true) }
        show { TaskEditorRoute(id, null, null) {} }
        reveal("This occurrence is done, so changes here apply to it alone. To change how the task repeats, open its current occurrence.")
        assertThat(compose.onAllNodesWithText("Weekdays").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithContentDescription("Delete").performClick(); settle()
        compose.onNodeWithText("Removes this one from the history. The repeat goes on.").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("This and future occurrences").fetchSemanticsNodes()).isEmpty()
    }

    @Test fun deletingAnOccurrence_skipsToTheNextOne() {
        val id = runBlocking { c.tasks.create(TaskDraft(title = "Stand-up notes", dueDate = today, repeat = RRule.parse("FREQ=DAILY")), listId()) }
        var closed = false
        show { TaskEditorRoute(id, null, null) { closed = true } }
        compose.onNodeWithContentDescription("Delete").performClick(); settle()
        compose.onNodeWithText("Delete a repeating task").assertIsDisplayed()
        compose.onNodeWithText("This occurrence").performClick()
        repeat(40) { settle(); if (closed) return@repeat }
        assertThat(closed).isTrue()
        assertThat(tasks().map { it.dueDate }).containsExactly(today.plusDays(1))
    }

    @Test fun googleList_explainsWhyItCantRepeat() {
        runBlocking {
            c.database.taskLists().upsert(TaskListEntity(id = "g", kind = SourceKind.GOOGLE, remoteId = "r", accountEmail = "a@example.com", title = "Google"))
        }
        val id = runBlocking { c.tasks.create(TaskDraft(title = "Synced", listId = "g", dueDate = today), listId()) }
        show { TaskEditorRoute(id, null, null) {} }
        reveal("Google Tasks can’t store repeat rules, so tasks in Google lists don’t repeat. Choose a list on this device to repeat this task.")
        assertThat(compose.onAllNodesWithText("Weekdays").fetchSemanticsNodes()).isEmpty()
    }

    @Test fun countdownNeedsADueDate() {
        show { TaskEditorRoute(null, null, null) {} }
        reveal("Live countdown")
        compose.onNodeWithText("Add a due date to count down to.").assertIsDisplayed()
        compose.onNode(hasText("Live countdown") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertIsNotEnabled()
    }

    private fun task(due: LocalDate, time: LocalTime?, countdown: Boolean, rule: String?) = Task(
        id = "t", listId = "l", parentId = null, title = "Exam", notes = "", dueDate = due, dueTime = time,
        priority = Priority.NONE, isCompleted = false, completedAt = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        sortKey = 0, isGoogle = false, syncState = SyncState.LOCAL, countdownEnabled = countdown, repeat = rule?.let(RRule::parse),
    )

    @Test fun taskRow_showsTheRepeatAndACompactCountdown_spokenAtMinutePrecision() {
        val now = java.time.ZonedDateTime.of(2026, 10, 27, 10, 0, 0, 0, zone)
        val row = task(LocalDate.of(2026, 10, 29), LocalTime.of(14, 30), countdown = true, rule = "FREQ=WEEKLY;BYDAY=TH")
        show { TaskRow(row, now.toInstant(), {}, {}) }
        compose.onNodeWithText("Weekly").assertIsDisplayed()
        compose.onNode(hasContentDescription("Due in 2 days, 4 hours and 30 minutes")).assertIsDisplayed()
    }

    @Test fun liveCountdown_spokenTextDoesNotChangeEverySecond() {
        val clock = FixedAppClock(Instant.parse("2026-10-27T10:00:00Z"), zone)
        val deadline = clock.instant.plusSeconds(3 * 3600 + 4 * 60 + 30)
        show(clock) { LiveCountdown(deadline, completed = false) }
        val spoken = "Due in 3 hours and 4 minutes"
        compose.onNode(hasContentDescription(spoken)).assertExists()
        clock.instant = clock.instant.plusSeconds(1); settle(10)
        compose.onNode(hasContentDescription(spoken)).assertExists()
        // 3 h 3 min 59 s left: the spoken text changes once the minute is crossed.
        clock.instant = clock.instant.plusSeconds(30); settle(10)
        compose.onNode(hasContentDescription("Due in 3 hours and 3 minutes")).assertExists()
    }

    @Test fun liveCountdown_followsAChangedDeadline_overdue_andCompleted() {
        val clock = FixedAppClock(Instant.parse("2026-10-27T10:00:00Z"), zone)
        var deadline by androidx.compose.runtime.mutableStateOf(clock.instant.plusSeconds(3600))
        var done by androidx.compose.runtime.mutableStateOf(false)
        show(clock) { LiveCountdown(deadline, completed = done) }
        compose.onNode(hasContentDescription("Due in 1 hour")).assertExists()
        // The deadline is edited (e.g. in the editor's date picker): the countdown follows at once.
        compose.runOnUiThread { deadline = clock.instant.minusSeconds(120) }
        settle(3)
        compose.onNode(hasContentDescription("Overdue by 2 minutes")).assertExists()
        compose.runOnUiThread { done = true }
        settle(3)
        compose.onNode(hasContentDescription("Completed")).assertExists()
    }

    @Test fun secondTicker_ticksWhileStarted_andStopsInTheBackground() {
        val clock = FixedAppClock(Instant.parse("2026-10-27T10:00:00Z"), zone)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        show(clock) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Text(rememberSecondTicker(true, clock).epochSecond.toString(), Modifier.testTag("tick"))
            }
        }
        fun shown() = compose.onNodeWithTag("tick").fetchSemanticsNode().config[SemanticsProperties.Text].single().text.toLong()
        clock.instant = clock.instant.plusSeconds(1); settle(12)
        assertThat(shown()).isEqualTo(clock.instant.epochSecond)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED } // backgrounded
        settle(2)
        val frozen = shown()
        clock.instant = clock.instant.plusSeconds(5); settle(30)
        assertThat(shown()).isEqualTo(frozen) // no ticking while stopped
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        settle(2)
        assertThat(shown()).isEqualTo(clock.instant.epochSecond) // fresh reading on return
    }
}
