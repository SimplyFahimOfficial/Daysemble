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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
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

    /**
     * How far the band under the folding grid is from the plain background, averaged over its pixels (the day list or
     * the time grid fills it at every point of the morph). Not a count of strongly coloured pixels: the time grid is
     * pale lines, and a view fading out and in must be seen to leave something, however faint.
     */
    private fun lowerInk(): Double {
        val px = compose.onRoot().captureToImage().toPixelMap()
        val bg = LightDsColors.background
        var sum = 0.0; var total = 0
        for (y in (px.height * 0.64).toInt() until (px.height * 0.80).toInt() step 2) for (x in 0 until px.width step 2) {
            val p = px[x, y]; total++
            sum += abs(p.red - bg.red) + abs(p.green - bg.green) + abs(p.blue - bg.blue)
        }
        return sum / total
    }

    /** Which of the two views under the grid are exposed to touch exploration right now. */
    private fun owners() = listOf(CalendarTags.DAY_LIST, CalendarTags.TIME_GRID).filter { shown(it) }

    private fun clickMode(m: CalendarMode) = choose(m)

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
            assertWithMessage("$from -> $to: share of content per frame ${frames.map { "%.2f".format(it) }}")
                .that(frames.count { it < floor }).isEqualTo(0)
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

    // ---- Month <-> Week -----------------------------------------------------------------------

    /**
     * The area under the grid is never empty during the morph. It used to be: the list faded out over the first
     * half and the time grid in over the second, so both were clear at the middle. The band is measured at rest
     * in both views; no frame may fall under half of the sparser one, in either direction or after a reversal.
     */
    @Test fun monthWeekMorph_neverEmptiesTheAreaUnderTheGrid() {
        goTo(CalendarMode.MONTH); val month = lowerInk()
        goTo(CalendarMode.WEEK); val week = lowerInk()
        val floor = minOf(month, week) * 0.5
        assertThat(floor).isGreaterThan(0.002)
        for (case in listOf("M>W" to listOf(CalendarMode.WEEK), "W>M" to listOf(CalendarMode.MONTH), "M>W>M at 6" to listOf(CalendarMode.WEEK, 6, CalendarMode.MONTH),
            "M>W>M at 10" to listOf(CalendarMode.WEEK, 10, CalendarMode.MONTH), "W>M>W at 8" to listOf(CalendarMode.MONTH, 8, CalendarMode.WEEK))) {
            goTo(if (case.first.startsWith("W")) CalendarMode.WEEK else CalendarMode.MONTH)
            val series = mutableListOf<Double>()
            for (step in case.second) {
                if (step is CalendarMode) clickMode(step) else repeat(step as Int) { frame(); series += lowerInk() }
            }
            repeat(40) { frame(); series += lowerInk() }
            settle()
            assertWithMessage("${case.first}: under-grid content per frame ${series.map { "%.2f".format(it) }}").that(series.count { it < floor }).isEqualTo(0)
        }
    }

    /**
     * Exactly one of the day list and the time grid is exposed (to touch exploration and to focus) in every frame,
     * the list first and the week after the middle, and a view that is drawn under the other is not exposed.
     */
    @Test fun monthWeekMorph_hasOneOwner_inEveryFrame_andHandsOverOnce() {
        for ((from, to) in listOf(CalendarMode.MONTH to CalendarMode.WEEK, CalendarMode.WEEK to CalendarMode.MONTH)) {
            goTo(from)
            choose(to)
            val series = (0 until 40).map { frame(); owners() }
            val settled = series.map { it.size }
            assertWithMessage("$from -> $to owners per frame $series").that(settled.all { it <= 1 }).isTrue()
            val seq = series.filter { it.isNotEmpty() }.map { it.single() }
            // The old owner, then the new one: at most one hand-over, and the last frame belongs to the target view.
            val changes = seq.zipWithNext().count { (a, b) -> a != b }
            assertWithMessage("$from -> $to hand-overs $seq").that(changes).isAtMost(1)
            assertThat(seq.last()).isEqualTo(if (to == CalendarMode.WEEK) CalendarTags.TIME_GRID else CalendarTags.DAY_LIST)
            settle()
        }
    }

    /** The month grid's cells stay contiguous (no gap, no overlap, so the hit targets tile the row) all through the morph. */
    @Test fun monthWeekMorph_keepsTheCellsTiledAndAlignedWithTheTimeGrid() {
        goTo(CalendarMode.MONTH)
        choose(CalendarMode.WEEK)
        repeat(40) {
            frame()
            val cells = compose.onAllNodes(SemanticsMatcher("date cell") { n -> onScreen(n) && n.config.getOrNull(SemanticsProperties.Role) == Role.Tab && n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.let { datePattern.containsMatchIn(it) } == true })
                .fetchSemanticsNodes().map { it.boundsInWindow }
            // Rows: cells with the same top; each full row has 7 cells that tile.
            cells.groupBy { it.top }.values.filter { it.size == 7 }.forEach { row ->
                val sorted = row.sortedBy { it.left }
                sorted.zipWithNext().forEach { (a, b) -> assertWithMessage("cells of a row at frame $it").that(abs(b.left - a.right)).isAtMost(1.5f) }
                assertThat(sorted.map { it.width }.max() - sorted.map { it.width }.min()).isAtMost(2f)
            }
        }
        settle()
        // At rest in Week the seven cells span the time grid's day columns: from the hour gutter (52 dp) to the edge.
        val row = compose.onAllNodes(SemanticsMatcher("date cell") { n -> onScreen(n) && n.config.getOrNull(SemanticsProperties.Role) == Role.Tab && n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.let { datePattern.containsMatchIn(it) } == true })
            .fetchSemanticsNodes().map { it.boundsInWindow }.sortedBy { it.left }
        assertThat(row).hasSize(7)
        assertThat(row.first().left).isWithin(2f).of(52f * 3)
        assertThat(row.last().right).isWithin(2f).of(393f * 3)
    }

    /** A vertical drag on the grid changes the mode: up folds to Week, down unfolds to Month. */
    @Test fun draggingTheGrid_changesTheMode() {
        goTo(CalendarMode.MONTH)
        compose.onNodeWithTag(CalendarTags.MONTH_GRID).performTouchInput { swipeUp(startY = bottom * 0.9f, endY = top, durationMillis = 250) }
        settle()
        assertOnlyShowing(CalendarMode.WEEK, "drag up")
        compose.onNodeWithTag(CalendarTags.MONTH_GRID).performTouchInput { swipeDown(startY = top, endY = bottom, durationMillis = 250) }
        settle()
        assertOnlyShowing(CalendarMode.MONTH, "drag down")
    }

    /** The time grid is composed one page first (the pages beside it follow a moment after the morph is at rest), cold and warm. */
    @Test fun weekGrid_composesOnePageFirst_thenItsNeighbours_coldAndWarm() {
        fun pages() = compose.onAllNodes(hasTestTag(CalendarTags.TIME_GRID)).fetchSemanticsNodes().size
        repeat(2) { round ->
            goTo(CalendarMode.MONTH)
            choose(CalendarMode.WEEK)
            val during = (0 until 30).map { frame(); pages() }
            // The first frames of the morph, the ones that start it, have the shown page only.
            assertWithMessage("round $round: pages in the first frames $during").that(during.take(10).max()).isAtMost(1)
            settle()
            assertWithMessage("round $round: pages at rest").that(pages()).isEqualTo(3)
        }
    }

    /** Through Day and Agenda and back, quickly: always ends in the last mode. */
    @Test fun throughDayAndAgenda_andQuickReversals_endCorrectly() {
        val orders = listOf(
            listOf(CalendarMode.DAY, CalendarMode.WEEK, CalendarMode.AGENDA, CalendarMode.MONTH),
            listOf(CalendarMode.WEEK, CalendarMode.DAY, CalendarMode.MONTH, CalendarMode.WEEK, CalendarMode.MONTH),
            listOf(CalendarMode.AGENDA, CalendarMode.MONTH, CalendarMode.WEEK, CalendarMode.DAY, CalendarMode.WEEK),
        )
        for (order in orders) {
            goTo(CalendarMode.MONTH)
            order.forEach { choose(it); repeat(3) { frame() } }
            settle()
            assertOnlyShowing(order.last(), "through ${order.joinToString(">")}")
        }
    }

    /** A busier calendar (forty recurring events) switches the same way: nothing empties, it ends in the chosen view. */
    @Test fun aBusyCalendar_morphsWithoutEmptyingTheAreaUnderTheGrid() {
        runBlocking {
            val cal = c.defaults().calendarId
            val today = java.time.LocalDate.now()
            repeat(40) { i ->
                c.events.create(
                    app.daysemble.domain.model.EventDraft(
                        title = "Busy $i", startDate = today, startTime = java.time.LocalTime.of(i % 20, 0), endTime = java.time.LocalTime.of(i % 20, 45),
                        zone = java.time.ZoneId.systemDefault(), rrule = app.daysemble.core.recurrence.RRule.parse("FREQ=DAILY"),
                    ),
                    cal,
                )
            }
        }
        settle(40)
        goTo(CalendarMode.MONTH); val month = lowerInk()
        goTo(CalendarMode.WEEK); val week = lowerInk()
        val floor = minOf(month, week) * 0.5
        repeat(2) {
            choose(CalendarMode.MONTH)
            val a = (0 until 40).map { frame(); lowerInk() }
            settle(); choose(CalendarMode.WEEK)
            val b = (0 until 40).map { frame(); lowerInk() }
            settle()
            assertWithMessage("busy calendar, under-grid content ${(a + b).map { "%.2f".format(it) }}").that((a + b).count { it < floor }).isEqualTo(0)
        }
        assertOnlyShowing(CalendarMode.WEEK, "busy")
    }
}
