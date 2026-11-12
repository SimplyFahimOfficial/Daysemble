package app.daysemble.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.edit
import app.daysemble.DaysembleApp
import app.daysemble.data.SampleData
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.ui.calendar.CalendarMode
import app.daysemble.ui.calendar.CalendarTags
import app.daysemble.ui.theme.DaysembleTheme
import app.daysemble.ui.theme.LightDsColors
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
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Switching the Calendar between Month, Week, Day and Agenda, frame by frame in the real app root:
 * - the control and the content agree as soon as the mode has been applied, and after the
 *   transition only the chosen view is left (no earlier view reappearing);
 * - no frame has noticeably less content than the screens at rest (no blank or see-through frame);
 * - Month and Week are one layout that morphs, so coming back from Day or Agenda they appear in their
 *   final layout (no fold from the layout the morph was last left in), and a Month to Week switch only
 *   ever removes month cells, a Week to Month switch only adds them (nothing flashes back);
 * - rapid taps end on the last mode, in every order.
 * Robolectric renders frames by hand: this proves what is drawn, not how smooth it is on a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class CalendarModeSwitchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val c get() = (compose.activity.application as DaysembleApp).container

    @Before fun setUp() {
        compose.mainClock.autoAdvance = false
        runBlocking {
            compose.activity.application.settingsDataStore.edit { it.clear() }
            c.clearLocalData(); SampleData.load(c)
            c.settings.completeOnboarding("Sam")
        }
        compose.setContent {
            val s by c.settings.settings.collectAsState(initial = null)
            val settings = s ?: return@setContent
            DaysembleTheme(settings.themeMode, settings.logo, reducedMotionOverride = false) { DaysembleRoot(c, settings) }
        }
        settle()
        compose.onNode(
            hasText("Calendar") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
        ).performClick()
        settle()
    }

    private fun settle(n: Int = 25) = repeat(n) { compose.mainClock.advanceTimeBy(100); Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle() }
    private fun frame() { compose.mainClock.advanceTimeByFrame(); Thread.sleep(3); shadowOf(Looper.getMainLooper()).idle() }

    private fun label(m: CalendarMode) = when (m) { CalendarMode.MONTH -> "Month"; CalendarMode.WEEK -> "Week"; CalendarMode.DAY -> "Day"; CalendarMode.AGENDA -> "Agenda" }

    private fun option(m: CalendarMode) = compose.onNode(
        hasText(label(m)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
    )

    private fun choose(m: CalendarMode) = option(m).performClick()

    private fun selectedMode(): CalendarMode? = CalendarMode.entries.firstOrNull { m ->
        runCatching { option(m).fetchSemanticsNode().config[SemanticsProperties.Selected] }.getOrDefault(false)
    }

    private val datePattern = Regex("[A-Z][a-z]+ \\d{1,2}, \\d{4}")
    private fun onScreen(n: SemanticsNode) =
        generateSequence(n) { it.parent }.none { SemanticsProperties.InvisibleToUser in it.config } &&
            n.boundsInWindow.let { it.height > 1f && it.width > 1f && it.left >= 0f && it.right <= 393f * 3 + 1 }

    /** The month grid's date cells that are on screen with a height (collapsed rows have none). */
    private fun monthCells(): Int = compose.onAllNodes(SemanticsMatcher("date cell") { n ->
        n.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.let { datePattern.containsMatchIn(it) } == true &&
            onScreen(n)
    }, useUnmergedTree = false).fetchSemanticsNodes().size

    private fun shown(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().any { onScreen(it) }

    private fun ink(): Double {
        val px = compose.onRoot().captureToImage().toPixelMap()
        val bg = LightDsColors.background
        var n = 0; var total = 0
        for (y in (px.height * 0.2).toInt() until (px.height * 0.8).toInt() step 2) for (x in 0 until px.width step 2) {
            val p = px[x, y]; total++
            if (abs(p.red - bg.red) + abs(p.green - bg.green) + abs(p.blue - bg.blue) > 0.06f) n++
        }
        return n.toDouble() / total
    }

    /** What is on screen once [m] has settled. */
    private fun assertOnlyShowing(m: CalendarMode, what: String) {
        assertWithMessage("$what: control").that(selectedMode()).isEqualTo(m)
        assertWithMessage("$what: month day list").that(shown(CalendarTags.DAY_LIST)).isEqualTo(m == CalendarMode.MONTH)
        assertWithMessage("$what: time grid").that(shown(CalendarTags.TIME_GRID)).isEqualTo(m == CalendarMode.WEEK || m == CalendarMode.DAY)
        assertWithMessage("$what: agenda").that(shown(CalendarTags.AGENDA)).isEqualTo(m == CalendarMode.AGENDA)
        val cells = monthCells()
        when (m) {
            CalendarMode.MONTH -> assertWithMessage("$what: month cells").that(cells).isAtLeast(28)
            CalendarMode.WEEK -> assertWithMessage("$what: week row cells").that(cells).isEqualTo(7)
            else -> assertWithMessage("$what: month cells").that(cells).isEqualTo(0)
        }
    }

    private fun goTo(m: CalendarMode) { choose(m); settle() }

    @Test fun everyDirection_endsOnlyShowingTheChosenView_withoutABlankFrame() {
        for (from in CalendarMode.entries) for (to in CalendarMode.entries) {
            if (from == to) continue
            goTo(from)
            assertOnlyShowing(from, "$from")
            val rest = ink()
            choose(to)
            val frames = (0 until 30).map { frame(); ink() }
            settle()
            assertOnlyShowing(to, "$from -> $to")
            val floor = minOf(rest, ink()) * 0.6
            // Month and Week are one morphing layout: the day list fades out and the time grid in under the
            // folding grid (that is the design), so their two alphas meet at the middle for a few frames.
            val morph = setOf(from, to) == setOf(CalendarMode.MONTH, CalendarMode.WEEK)
            assertWithMessage("$from -> $to: share of content per frame ${frames.map { "%.2f".format(it) }}")
                .that(frames.count { it < floor }).isAtMost(if (morph) 4 else 0)
        }
    }

    @Test fun monthToWeek_onlyRemovesMonthCells_andWeekToMonth_onlyAddsThem() {
        goTo(CalendarMode.MONTH)
        choose(CalendarMode.WEEK)
        val toWeek = (0 until 40).map { frame(); monthCells() }
        assertWithMessage("month cells while folding to a week $toWeek").that(toWeek.zipWithNext().all { (a, b) -> b <= a }).isTrue()
        settle()
        choose(CalendarMode.MONTH)
        val toMonth = (0 until 40).map { frame(); monthCells() }
        assertWithMessage("month cells while unfolding to a month $toMonth").that(toMonth.zipWithNext().all { (a, b) -> b >= a }).isTrue()
    }

    /** After Week, going to Day and then Month must not show the week row first and unfold it under the fade. */
    @Test fun comingBackFromDayOrAgenda_showsMonthAndWeekInTheirFinalLayout() {
        for (own in listOf(CalendarMode.DAY, CalendarMode.AGENDA)) {
            for (target in listOf(CalendarMode.MONTH, CalendarMode.WEEK)) {
                goTo(CalendarMode.WEEK); goTo(own)
                choose(target)
                val seen = (0 until 40).map { frame(); monthCells() }.filter { it > 0 }.toSet()
                val final = if (target == CalendarMode.WEEK) setOf(7) else seen.filter { it >= 28 }.toSet()
                assertWithMessage("$own -> $target: month cell counts while it appears $seen").that(seen).isEqualTo(final)
                settle()
                assertOnlyShowing(target, "$own -> $target")
            }
        }
    }

    @Test fun rapidTaps_endOnTheLastMode() {
        val orders = listOf(
            listOf(CalendarMode.WEEK, CalendarMode.MONTH, CalendarMode.WEEK, CalendarMode.MONTH, CalendarMode.WEEK),
            listOf(CalendarMode.DAY, CalendarMode.MONTH, CalendarMode.AGENDA, CalendarMode.WEEK, CalendarMode.DAY),
            listOf(CalendarMode.AGENDA, CalendarMode.WEEK, CalendarMode.DAY, CalendarMode.MONTH, CalendarMode.AGENDA, CalendarMode.MONTH),
            listOf(CalendarMode.WEEK, CalendarMode.DAY, CalendarMode.MONTH),
        )
        for (order in orders) {
            goTo(CalendarMode.MONTH)
            order.forEach { choose(it); repeat(2) { frame() } }
            settle()
            assertOnlyShowing(order.last(), "rapid ${order.joinToString(">")}")
        }
    }
}
