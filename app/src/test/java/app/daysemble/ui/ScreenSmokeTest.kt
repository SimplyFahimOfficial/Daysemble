package app.daysemble.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.daysemble.DaysembleApp
import app.daysemble.data.prefs.ClockFormat
import app.daysemble.data.SampleData
import app.daysemble.ui.calendar.CalendarRoute
import app.daysemble.ui.event.EventEditorRoute
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.rememberTimePrefs
import app.daysemble.ui.search.SearchRoute
import app.daysemble.ui.settings.AccountSettingsRoute
import app.daysemble.ui.settings.AppearanceRoute
import app.daysemble.ui.settings.NotificationSettingsRoute
import app.daysemble.ui.settings.SettingsHomeRoute
import app.daysemble.ui.task.TaskEditorRoute
import app.daysemble.ui.tasks.TasksRoute
import app.daysemble.ui.theme.DaysembleTheme
import app.daysemble.ui.today.TodayRoute
import app.daysemble.data.prefs.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Renders the real screens (real ViewModels, real Room DB, sample data) under Robolectric and
 * exercises key interactions. Not a substitute for an on-device visual check, but it proves each
 * screen composes, loads data and reacts to input without crashing, in both themes and at 2x font.
 * The clock is advanced manually: the minute ticker never lets Compose go idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class ScreenSmokeTest {
    @get:Rule val compose = createComposeRule()
    private val c get() = ApplicationProvider.getApplicationContext<DaysembleApp>().container

    @Before fun seed() {
        compose.mainClock.autoAdvance = false
        runBlocking { c.clearLocalData(); SampleData.load(c) }
    }

    /** Advances Compose's clock and drains the main looper (ViewModel coroutines resume there). */
    private fun settle() = repeat(15) {
        compose.mainClock.advanceTimeBy(100)
        Thread.sleep(15)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun show(theme: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) {
        compose.setContent {
            DaysembleTheme(themeMode = theme) {
                val prefs = rememberTimePrefs(ClockFormat.H24, null, ZoneId.systemDefault())
                CompositionLocalProvider(
                    LocalTimePrefs provides prefs, LocalAppClock provides c.clock,
                    LocalSnackbar provides remember { SnackbarHostState() },
                ) { content() }
            }
        }
        settle()
    }

    /** Vertical scrollers only; horizontal ones (date strip, 20k-page month pager) are never searched. */
    private val verticalList = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
        SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)

    /**
     * Waits for data, then scrolls the phone-height vertical list step by step until the target is
     * on screen, and asserts it is displayed (clipped/offscreen text fails). Scrolling uses the
     * ScrollBy semantics action directly: performScrollToNode waits for Compose idle, which never
     * comes with the paused test clock and the live minute ticker.
     */
    private fun waitText(text: String, substring: Boolean = false) {
        val target = hasText(text, substring = substring)
        fun visible() = runCatching { compose.onAllNodes(target)[0].assertIsDisplayed() }.isSuccess
        repeat(120) { attempt ->
            if (visible()) return
            // First give data time to load, then walk the list downwards; restart from the top once at the end.
            if (attempt >= 10) {
                compose.onAllNodes(verticalList).fetchSemanticsNodes().forEach { node ->
                    val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
                    val scrollBy = node.config[SemanticsActions.ScrollBy].action
                    val atEnd = range.value() >= range.maxValue()
                    compose.runOnUiThread {
                        if (atEnd) scrollBy?.invoke(0f, -100_000f) else scrollBy?.invoke(0f, 300f)
                    }
                }
            }
            settle()
        }
        compose.onAllNodes(target)[0].assertIsDisplayed()
    }

    @Test fun today_showsTimelineOverdueAndCountdown() {
        show { TodayRoute(PaddingValues(), { _, _ -> }, {}) }
        waitText("Maya’s birthday")
        waitText("Lunch with Sam")
        waitText("Send invoice to Northwind")
        waitText("OVERDUE")
        waitText("You have", substring = true)
    }

    @Test fun today_dark_completingTaskUpdatesState() {
        show(ThemeMode.DARK) { TodayRoute(PaddingValues(), { _, _ -> }, {}) }
        waitText("Buy groceries")
        compose.onNodeWithContentDescription("Mark Buy groceries complete").performClick()
        repeat(40) {
            settle()
            if (runBlocking { c.tasks.allTasks.let { it.first() }.first { t -> t.title == "Buy groceries" }.isCompleted }) return
        }
        error("task not completed")
    }

    @Test fun tasks_filtersAndSearch() {
        show { TasksRoute(PaddingValues(), {}) }
        waitText("Read chapter 4")
        compose.onNodeWithText("Overdue").performClick(); settle()
        waitText("Send invoice to Northwind")
        assertThat(compose.onAllNodesWithText("Read chapter 4").fetchSemanticsNodes()).isEmpty()
    }

    @Test fun tasks_manualOrderShowsHandlesAndMoveActions() {
        runBlocking { c.settings.setTaskSort(app.daysemble.data.prefs.TaskSort.MANUAL) }
        show { TasksRoute(PaddingValues(), {}) }
        waitText("Long-press the handle", substring = true)
        assertThat(compose.onAllNodesWithContentDescription("Reorder").fetchSemanticsNodes()).isNotEmpty()
        runBlocking { c.settings.setTaskSort(app.daysemble.data.prefs.TaskSort.DUE) }
    }

    @Test fun calendar_allModesRender() {
        show { CalendarRoute(PaddingValues(), { _, _ -> }, {}, { _, _ -> }) }
        waitText("Lunch with Sam") // month: selected day list
        compose.onNodeWithText("Week").performClick(); settle()
        compose.onNodeWithText("Day").performClick(); settle()
        waitText("Lunch with Sam")
        compose.onNodeWithText("Agenda").performClick(); settle()
        waitText("Yoga")
    }

    @Test fun search_findsEventsAndTasks() {
        show { SearchRoute({}, { _, _ -> }, {}) }
        compose.onNode(hasSetTextAction() and hasContentDescription("Events, tasks, places")).performTextInput("café")
        repeat(10) { settle() }
        waitText("Lunch with Sam")
    }

    @Test fun eventEditor_validatesAndSaves() {
        var closed = false
        show { EventEditorRoute(null, null, null, null) { closed = true } }
        waitText("Save")
        compose.onNodeWithText("Save").performClick(); settle()
        waitText("Add a title")
        compose.onNode(hasSetTextAction() and hasContentDescription("Event title")).performTextInput("Review")
        settle()
        compose.onNodeWithText("Save").performClick()
        repeat(40) { settle(); if (closed) return }
        error("editor did not close after save")
    }

    @Test fun taskEditor_savesNewTask() {
        var closed = false
        show { TaskEditorRoute(null, null, null) { closed = true } }
        compose.onNode(hasSetTextAction() and hasContentDescription("What needs doing?")).performTextInput("Water herbs")
        settle()
        compose.onNodeWithText("Save").performClick()
        for (i in 0 until 40) { settle(); if (closed) break }
        assertThat(closed).isTrue()
        assertThat(runBlocking { c.tasks.allTasks.first() }.any { it.title == "Water herbs" }).isTrue()
    }

    @Test fun settingsScreensRender() {
        show { SettingsHomeRoute({}, {}) }
        waitText("Google Calendar & Tasks")
        waitText("Notifications")
    }

    @Test fun appearance_notifications_accounts_render() {
        show { AppearanceRoute(onBack = {}) }
        waitText("APP LOGO")
        compose.onNodeWithContentDescription("Classic, Default").assertIsDisplayed()
        compose.onNodeWithContentDescription("Midnight").performClick(); settle()
        repeat(20) { settle(); if (runBlocking { c.settings.current().logo.key } == "midnight") return@repeat }
        assertThat(runBlocking { c.settings.current().logo.key }).isEqualTo("midnight")
    }

    @Test fun notificationAndAccountSettingsRender() {
        show {
            androidx.compose.foundation.layout.Column {
                NotificationSettingsRoute(onBack = {})
            }
        }
        waitText("Precise timing")
    }

    @Test fun accountSettings_notConnectedState() {
        show { AccountSettingsRoute(onBack = {}, onOpenConflicts = {}) }
        waitText("Connect Google account")
    }
}

@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi", fontScale = 2.0f)
@RunWith(RobolectricTestRunner::class)
class LargeFontSmokeTest {
    @get:Rule val compose = createComposeRule()
    private val c get() = ApplicationProvider.getApplicationContext<DaysembleApp>().container

    @Test fun mainScreensComposeAt200PercentOn320dp() {
        compose.mainClock.autoAdvance = false
        runBlocking { c.clearLocalData(); SampleData.load(c) }
        compose.setContent {
            DaysembleTheme {
                val prefs = rememberTimePrefs(ClockFormat.H12, null, ZoneId.systemDefault())
                CompositionLocalProvider(LocalTimePrefs provides prefs, LocalAppClock provides c.clock) {
                    androidx.compose.foundation.layout.Column {
                        MainHeader(null, null, {}, {}, {})
                        TodayRoute(PaddingValues(), { _, _ -> }, {})
                    }
                }
            }
        }
        repeat(40) { compose.mainClock.advanceTimeBy(100); Thread.sleep(15) }
        // At 2x font the timeline is below the fold; the header, summary and actions must still be present.
        repeat(80) {
            if (compose.onAllNodesWithText("You have", substring = true).fetchSemanticsNodes().isNotEmpty()) return@repeat
            compose.mainClock.advanceTimeBy(100); Thread.sleep(25)
        }
        assertThat(compose.onAllNodesWithText("You have", substring = true).fetchSemanticsNodes()).isNotEmpty()
        compose.onNodeWithContentDescription("Search").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }
}
