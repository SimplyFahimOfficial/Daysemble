package app.daysemble.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.preferences.core.edit
import app.daysemble.DaysembleApp
import app.daysemble.data.SampleData
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.ui.theme.DaysembleTheme
import app.daysemble.ui.today.TodayTags
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
 * The motion fixes, checked in the real app root (settings store, Room and sample data):
 * - the Today panel moves without being re-measured (its size stays the same during a drag);
 * - another day's rows are in the agenda from the first frame they arrive, and never alongside the
 *   previous day's rows. (Rows that are only fading out are not in the semantics tree, so this
 *   can't see the old fade-over itself; it guards the swap against regressions.)
 * - the month grid keeps its height from month to month, so what is under it doesn't jump;
 * - the header search field shows every typed character at once.
 * Robolectric renders frames by hand: these prove behaviour and layout, not how motion feels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class MotionContinuityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = compose.activity.application as DaysembleApp
    private val c get() = app.container
    private val today get() = LocalDate.now(ZoneId.systemDefault())

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

    private fun settle(n: Int = 20) = repeat(n) { compose.mainClock.advanceTimeBy(100); Thread.sleep(15); shadowOf(Looper.getMainLooper()).idle() }
    private fun frame() { compose.mainClock.advanceTimeByFrame(); Thread.sleep(5); shadowOf(Looper.getMainLooper()).idle() }
    private fun count(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    /** Rows in Today's agenda list showing [text]. */
    private fun inAgenda(text: String) = compose.onAllNodes(
        hasText(text, substring = true) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(TodayTags.AGENDA)),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size

    private fun dateCell(d: LocalDate) = compose.onNode(
        hasContentDescription(DateTimeFormatter.ofPattern("MMMM d,", Locale.US).format(d), substring = true) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            SemanticsMatcher("on screen") { it.boundsInWindow.width > 0f },
    )

    private fun tab(label: String) = compose.onNode(
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and
            androidx.compose.ui.test.hasParent(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)),
    )

    private fun agendaHeight() = compose.onNodeWithTag(TodayTags.AGENDA).fetchSemanticsNode().size.height

    @Test fun draggingTheOverviewPanel_movesTheAgenda_withoutResizingIt() {
        val atRest = agendaHeight()
        val heights = mutableListOf<Int>()
        compose.onNodeWithContentDescription("Daily overview").performTouchInput {
            down(center)
            repeat(8) { moveBy(androidx.compose.ui.geometry.Offset(0f, 60f)); heights += -1 }
        }
        // Read between moves: the panel follows the finger.
        repeat(4) { frame(); heights += agendaHeight() }
        compose.onNodeWithContentDescription("Daily overview").performTouchInput { up() }
        repeat(6) { frame(); heights += agendaHeight() }
        settle()
        heights += agendaHeight()
        heights.filter { it >= 0 }.forEach { assertWithMessage("agenda height during the drag").that(it).isEqualTo(atRest) }
    }

    @Test fun anotherDay_replacesTheAgendaInOneFrame_neverTwoDaysTogether() {
        // The sample data has "Design review" today only and the weekly "Yoga" tomorrow only. The swap
        // needs both days on the one-week strip, which isn't so on the last day of the week.
        val first = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
        org.junit.Assume.assumeTrue("tomorrow is on the strip's next page today", today.plusDays(1).dayOfWeek != first)
        assertThat(inAgenda("Design review")).isEqualTo(1)
        dateCell(today.plusDays(1)).performClick()
        var sawTomorrow = false
        repeat(30) {
            frame()
            val yoga = inAgenda("Yoga")
            val review = inAgenda("Design review")
            assertWithMessage("frame $it shows today's and tomorrow's rows together").that(yoga > 0 && review > 0).isFalse()
            if (yoga > 0) sawTomorrow = true
        }
        assertThat(sawTomorrow).isTrue()
        assertThat(inAgenda("Design review")).isEqualTo(0)
    }

    @Test fun monthGrid_keepsItsHeight_fromMonthToMonth() {
        tab("Calendar").performClick(); settle()
        fun underGrid() = compose.onNodeWithText("Add event").fetchSemanticsNode().positionInRoot.y
        val ys = mutableListOf(underGrid())
        repeat(5) {
            compose.onNodeWithContentDescription("Next").performClick(); settle()
            ys += underGrid()
        }
        assertWithMessage("position of the day section under the grid, month by month").that(ys.distinct()).hasSize(1)
    }

    @Test fun headerSearch_showsEveryTypedCharacterAtOnce() {
        compose.onNodeWithContentDescription("Search").performClick(); settle()
        val field = compose.onNode(hasSetTextAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        // Typed without waiting for the list to be filtered in between.
        field.performTextInput("wa")
        frame(); frame()
        field.performTextInput("ter")
        frame(); frame()
        val text = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertThat(text).isEqualTo("water")
        // The results arrive from a background flow: wait for them rather than for a fixed time.
        repeat(10) { if (count("Water the plants") > 0) return@repeat; settle(30) }
        compose.onNodeWithText("Water the plants", substring = true).assertExists()
        // Merged tree: what a screen reader can reach. The tabs under the results are hidden from it.
        assertThat(compose.onAllNodes(hasText("Buy groceries", substring = true)).fetchSemanticsNodes()).isEmpty()
    }
}
