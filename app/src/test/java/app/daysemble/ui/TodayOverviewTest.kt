package app.daysemble.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.datastore.preferences.core.edit
import app.daysemble.DaysembleApp
import app.daysemble.data.SampleData
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.ui.theme.DaysembleTheme
import app.daysemble.ui.today.TodayTags
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Today overview and agenda panel in the real app root (real settings store, Room DB and
 * sample data): default state, the handle, swipes on the overview, the handle row and the agenda
 * list, date selection, state across tabs and Settings, the counts against the database, and the
 * theme choice. Robolectric: it proves behaviour and state, not how the motion looks or feels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class TodayOverviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = compose.activity.application as DaysembleApp
    private val c get() = app.container
    private val zone = ZoneId.systemDefault()
    private val today get() = LocalDate.now(zone)

    @Before fun reset() {
        compose.mainClock.autoAdvance = false
        runBlocking {
            app.settingsDataStore.edit { it.clear() }
            c.clearLocalData(); SampleData.load(c)
            c.settings.completeOnboarding("Sam")
        }
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

    private fun handle() = compose.onNodeWithContentDescription("Daily overview")
    private fun assertOverview(state: String) =
        handle().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))

    private fun dateCell(d: LocalDate) = compose.onNode(
        hasContentDescription(DateTimeFormatter.ofPattern("MMMM d,", Locale.US).format(d), substring = true) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
    )

    private fun tab(label: String) = compose.onNode(
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and
            androidx.compose.ui.test.hasParent(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)),
    )

    private fun exists(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun counts(date: LocalDate): Pair<Int, Int> = runBlocking {
        val events = c.events.occurrences(date, date, zone).first().size
        val tasks = c.tasks.allTasks.first().count { it.parentId == null && !it.isCompleted && it.dueDate == date }
        events to tasks
    }

    private fun plural(n: Int, one: String) = if (n == 1) "$n $one" else "$n ${one}s"

    @Test fun collapsedByDefault_showsTheDayInOneLine_withCountsFromTheDatabase() {
        assertOverview("Collapsed")
        val (events, tasks) = counts(today)
        assertThat(events + tasks).isGreaterThan(0) // the sample data has items today
        if (events > 0) assertThat(exists(plural(events, "event"))).isTrue()
        if (tasks > 0) assertThat(exists(plural(tasks, "task"))).isTrue()
        // The briefing is part of the full overview only, hidden (and not read out) while collapsed.
        assertThat(exists("Sam.")).isFalse()
        dateCell(today).assertIsSelected()
    }

    @Test fun handle_togglesTheOverview_withGreetingAndBriefing() {
        handle().performClick(); settle()
        assertOverview("Expanded")
        assertThat(exists("Sam.")).isTrue() // "Good morning, Sam." (the greeting word depends on the hour)
        val (events, tasks) = counts(today)
        if (events > 0) compose.onNodeWithText(plural(events, "event"), substring = true).assertIsDisplayed()
        if (tasks > 0) assertThat(exists(plural(tasks, "task"))).isTrue()
        handle().performClick(); settle()
        assertOverview("Collapsed")
        assertThat(exists("Sam.")).isFalse()
    }

    @Test fun swipeDownOnTheOverview_expands_swipeUpOnTheHandleRow_collapses() {
        compose.onNodeWithTag(TodayTags.OVERVIEW, useUnmergedTree = true).performTouchInput { swipeDown(startY = top + 10f, endY = top + 600f) }
        settle()
        assertOverview("Expanded")
        handle().performTouchInput { swipeUp(startY = centerY, endY = centerY - 700f) }
        settle()
        assertOverview("Collapsed")
    }

    @Test fun agendaPull_atTheTop_expands_andScrollingUp_collapsesFirst() {
        compose.onNodeWithTag(TodayTags.AGENDA).performTouchInput { swipeDown(startY = top + 20f, endY = top + 700f) }
        settle()
        assertOverview("Expanded")
        compose.onNodeWithTag(TodayTags.AGENDA).performTouchInput { swipeUp(startY = bottom - 40f, endY = bottom - 400f) }
        settle()
        assertOverview("Collapsed")
    }

    @Test fun agendaPull_thatStartsMidList_onlyScrollsTheList() {
        val agenda = compose.onNodeWithTag(TodayTags.AGENDA)
        agenda.performTouchInput { swipeUp(startY = bottom - 40f, endY = top + 40f) }
        settle()
        val scrolled = compose.onNodeWithTag(TodayTags.AGENDA).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f
        assertThat(scrolled).isTrue() // precondition: the sample day is longer than the panel
        agenda.performTouchInput { swipeDown(startY = top + 40f, endY = bottom - 40f) }
        settle()
        assertOverview("Collapsed")
    }

    @Test fun selectingADate_updatesTheAgenda_andKeepsTheOverviewOpen() {
        handle().performClick(); settle()
        val tomorrow = today.plusDays(1)
        dateCell(tomorrow).performClick(); settle()
        dateCell(tomorrow).assertIsSelected()
        assertOverview("Expanded")
        val (events, tasks) = counts(tomorrow)
        if (events + tasks == 0) {
            assertThat(exists("Nothing is scheduled for tomorrow.")).isTrue()
            assertThat(exists("A clear day")).isTrue()
        } else {
            assertThat(exists("Tomorrow you have")).isTrue()
            val title = runBlocking { c.events.occurrences(tomorrow, tomorrow, zone).first().firstOrNull()?.title }
            if (title != null) assertThat(exists(title)).isTrue()
        }
        compose.onNodeWithText("Back to today").performClick(); settle()
        dateCell(today).assertIsSelected()
    }

    @Test fun swipingTheWeekStrip_selectsTheSameWeekdayInTheNextWeek() {
        dateCell(today).performTouchInput { swipeLeft(startX = right, endX = left - 900f) }
        settle(30)
        dateCell(today.plusWeeks(1)).assertIsSelected()
        compose.onNodeWithText("Back to today").performClick(); settle(30)
        dateCell(today).assertIsSelected()
    }

    /** Another day in today's week (the strip shows one week; a later week's cells are off-screen). */
    private val otherDayThisWeek: LocalDate get() {
        val first = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
        val start = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(first))
        return if (today.plusDays(1).isBefore(start.plusDays(7))) today.plusDays(1) else today.minusDays(1)
    }

    @Test fun overviewAndDate_surviveTabsAndSettings() {
        handle().performClick(); settle()
        val other = otherDayThisWeek
        dateCell(other).performClick(); settle()
        tab("Tasks").performClick(); settle()
        tab("Today").performClick(); settle()
        compose.onNodeWithContentDescription("Settings").performClick(); settle()
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        settle()
        assertOverview("Expanded")
        dateCell(other).assertIsSelected()
    }

    @Test fun themeChoice_isSavedAndHandedToTheSystemForTheSplash() {
        compose.onNodeWithContentDescription("Settings").performClick(); settle()
        compose.onNodeWithText("Appearance").performClick(); settle()
        compose.onNodeWithContentDescription("Dark, Deep black").performClick(); settle()
        assertThat(runBlocking { c.settings.current().themeMode }).isEqualTo(ThemeMode.DARK)
        val ui = compose.activity.getSystemService(android.app.UiModeManager::class.java)
        assertThat(shadowOf(ui).applicationNightMode).isEqualTo(android.app.UiModeManager.MODE_NIGHT_YES)
        compose.onNodeWithContentDescription("Light, Clean white").performClick(); settle()
        assertThat(runBlocking { c.settings.current().themeMode }).isEqualTo(ThemeMode.LIGHT)
        assertThat(shadowOf(ui).applicationNightMode).isEqualTo(android.app.UiModeManager.MODE_NIGHT_NO)
    }
}
