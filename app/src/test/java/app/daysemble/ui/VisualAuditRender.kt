package app.daysemble.ui

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import app.daysemble.DaysembleApp
import app.daysemble.ui.today.TodayTags
import app.daysemble.domain.model.EventOccurrence
import app.daysemble.domain.model.Task
import app.daysemble.domain.model.Priority
import app.daysemble.data.db.SyncState
import app.daysemble.core.recurrence.RRule
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.components.AgendaEventCard
import app.daysemble.ui.components.AgendaTaskCard
import app.daysemble.ui.components.TaskRow
import app.daysemble.data.SampleData
import app.daysemble.data.prefs.ClockFormat
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.ui.calendar.CalendarRoute
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.rememberTimePrefs
import app.daysemble.ui.settings.AppearanceRoute
import app.daysemble.ui.settings.SupportRoute
import app.daysemble.ui.tasks.TasksRoute
import app.daysemble.ui.theme.DaysembleTheme
import app.daysemble.ui.today.TodayRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId

/**
 * Developer visual QA only: renders real screens off-device (Robolectric native graphics) to PNGs in
 * build/visual-audit, including single frames in the middle of transitions (the test clock is
 * stepped by hand). These are NOT device screenshots, don't show real-time smoothness, and must
 * never be used in the README.
 * Opt-in: ./gradlew testDebugUnitTest -Pdaysemble.visualAudit=true --tests '*VisualAuditRender*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
open class VisualAuditRender {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<DaysembleApp>()
    private val c get() = app.container
    private val out = File(System.getProperty("daysemble.visualAuditDir") ?: "build/visual-audit").apply { mkdirs() }
    protected open val prefix = ""

    private fun settle(n: Int = 25) = repeat(n) {
        compose.mainClock.advanceTimeBy(100); Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle()
    }

    private fun capture(name: String) {
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$prefix$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Advances the test clock by [ms] (frame by frame) and captures one frame. */
    private fun frame(name: String, ms: Long) {
        compose.mainClock.advanceTimeBy(ms); shadowOf(Looper.getMainLooper()).idle(); capture(name)
    }

    private fun render(name: String, theme: ThemeMode, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        runBlocking { c.clearLocalData(); SampleData.load(c) }
        compose.setContent {
            DaysembleTheme(themeMode = theme) {
                val prefs = rememberTimePrefs(ClockFormat.H24, null, ZoneId.systemDefault())
                CompositionLocalProvider(
                    LocalTimePrefs provides prefs, LocalAppClock provides c.clock,
                    LocalSnackbar provides remember { SnackbarHostState() },
                ) { content() }
            }
        }
        settle(40)
        capture(name)
    }

    /** The real app root, as MainActivity composes it. */
    private fun app(theme: ThemeMode, onboarded: Boolean, name: String? = "Fahim") {
        compose.mainClock.autoAdvance = false
        runBlocking {
            app.settingsDataStore.edit { it.clear() }
            c.clearLocalData(); SampleData.load(c)
            c.settings.setThemeMode(theme)
            if (onboarded) c.settings.completeOnboarding(name)
        }
        compose.setContent {
            val s by c.settings.settings.collectAsState(initial = null)
            val settings = s ?: return@setContent
            DaysembleTheme(settings.themeMode, settings.logo, settings.reducedMotionOverride) { DaysembleRoot(c, settings) }
        }
        settle(40)
    }

    private fun tab(label: String) = compose.onNode(
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
    )

    private fun enabled() = assumeTrue(System.getProperty("daysemble.visualAudit") == "true")

    // The whole page in one tall, low-density frame (scrolling plus several full-size captures runs out of heap).
    @Config(qualifiers = "w393dp-h1700dp-mdpi") @Test fun supportLight() { enabled(); render("support-light", ThemeMode.LIGHT) { SupportRoute(onBack = {}) } }
    @Config(qualifiers = "w393dp-h1700dp-mdpi") @Test fun supportDark() { enabled(); render("support-dark", ThemeMode.DARK) { SupportRoute(onBack = {}) } }
    @Test fun todayLight() { enabled(); render("today-light", ThemeMode.LIGHT) { TodayRoute(PaddingValues(), { _, _ -> }, {}) } }
    @Test fun calendarLight() { enabled(); render("calendar-light", ThemeMode.LIGHT) { CalendarRoute(PaddingValues(), { _, _ -> }, {}, { _, _ -> }) } }
    @Test fun tasksDark() { enabled(); render("tasks-dark", ThemeMode.DARK) { TasksRoute(PaddingValues(), {}) } }
    @Test fun appearanceLight() { enabled(); render("appearance-light", ThemeMode.LIGHT) { AppearanceRoute(onBack = {}) } }

    private fun onboardingSteps(theme: ThemeMode) {
        app(theme, onboarded = false)
        val t = theme.name.lowercase()
        capture("onboarding-1-$t")
        compose.onNodeWithText("Get started").performClick(); settle()
        capture("onboarding-2-$t")
        compose.onNodeWithText("Continue").performClick(); settle()
        capture("onboarding-3-$t")
    }

    @Test fun onboardingLight() { enabled(); onboardingSteps(ThemeMode.LIGHT) }
    @Test fun onboardingDark() { enabled(); onboardingSteps(ThemeMode.DARK) }

    @Test fun onboardingEntrance() {
        enabled()
        compose.mainClock.autoAdvance = false
        runBlocking { app.settingsDataStore.edit { it.clear() }; c.settings.setThemeMode(ThemeMode.LIGHT) }
        compose.setContent {
            val s by c.settings.settings.collectAsState(initial = null)
            val settings = s ?: return@setContent
            DaysembleTheme(settings.themeMode, settings.logo, settings.reducedMotionOverride) { DaysembleRoot(c, settings) }
        }
        shadowOf(Looper.getMainLooper()).idle()
        listOf(16L, 100L, 100L, 100L, 150L).forEachIndexed { i, ms -> frame("entrance-$i", ms) }
    }

    private fun mainScreens(theme: ThemeMode) {
        app(theme, onboarded = true)
        val t = theme.name.lowercase()
        capture("main-today-$t")
        compose.onNodeWithContentDescription("Daily overview").performClick(); settle(); capture("main-today-expanded-$t")
        // Mid-gesture: the handle row dragged halfway back up, finger still down.
        compose.onNodeWithContentDescription("Daily overview").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, -40f)); moveBy(androidx.compose.ui.geometry.Offset(0f, -120f)) }
        compose.mainClock.advanceTimeBy(32); shadowOf(Looper.getMainLooper()).idle(); capture("main-today-drag-$t")
        compose.onNodeWithContentDescription("Daily overview").performTouchInput { up() }
        settle(); capture("main-today-collapsed-again-$t")
        tab("Calendar").performClick(); settle(); capture("main-calendar-$t")
        tab("Tasks").performClick(); settle(); capture("main-tasks-$t")
        compose.onNodeWithContentDescription("Settings").performClick(); settle(); capture("settings-$t")
    }

    @Test fun mainScreensLight() { enabled(); mainScreens(ThemeMode.LIGHT) }
    @Test fun mainScreensDark() { enabled(); mainScreens(ThemeMode.DARK) }

    /** The redesigned cards, the event detail, a task's live countdown and its repeat options. */
    private fun details(theme: ThemeMode) {
        app(theme, onboarded = true)
        val t = theme.name.lowercase()
        compose.onNode(hasText("Design review") and hasClickAction()).performClick(); settle(); capture("event-detail-$t")
        compose.onNodeWithContentDescription("Close").performClick(); settle()
        // Scrolling animates: let the clock run for it (the renders otherwise step it by hand).
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag(TodayTags.AGENDA).performScrollToNode(hasText("Book train tickets"))
        compose.mainClock.autoAdvance = false; settle()
        capture("today-cards-scrolled-$t")
        compose.onNode(hasText("Book train tickets") and hasClickAction()).performClick(); settle(); capture("task-countdown-$t")
        compose.onNodeWithContentDescription("Back").performClick(); settle()
        tab("Tasks").performClick(); settle(); capture("tasks-rows-$t")
        compose.mainClock.autoAdvance = true
        val scrollables = compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction())
        for (i in scrollables.fetchSemanticsNodes().indices) {
            if (runCatching { scrollables[i].performScrollToNode(hasText("Weekly review")) }.isSuccess) break
        }
        compose.mainClock.autoAdvance = false; settle()
        compose.onNode(hasText("Weekly review") and hasClickAction()).performClick(); settle()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("REPEAT").performScrollTo()
        compose.mainClock.autoAdvance = false; settle(); capture("task-repeat-$t")
        compose.onNodeWithText("Save").performClick() // nothing changed: disabled, stays
        compose.onNodeWithContentDescription("Delete").performClick(); settle(); capture("task-scope-delete-$t")
    }

    /** The cards in every state at a fixed moment: the orange focus card, countdown pills, overdue. */
    private fun cards(theme: ThemeMode) = render("cards-${theme.name.lowercase()}", theme) {
        val now = remember { java.time.Instant.now() }
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        fun ev(title: String, from: Long, to: Long, loc: String = "", recurring: Boolean = false) = EventOccurrence(
            eventId = title, occurrenceKey = title, overrideId = null, calendarId = "c", title = title, description = "", location = loc,
            isAllDay = false, start = now.plusSeconds(from * 60), end = now.plusSeconds(to * 60), startDate = today, endDate = today,
            timeZone = zone, colorArgb = 0xFF5B3FE0.toInt(), isRecurring = recurring, isGoogle = false, isReadOnly = false,
        )
        fun task(title: String, dueDays: Long, time: java.time.LocalTime?, countdown: Boolean, rule: String? = null, notes: String = "", subs: Int = 0) = Task(
            id = title, listId = "l", parentId = null, title = title, notes = notes, dueDate = today.plusDays(dueDays), dueTime = time,
            priority = Priority.NONE, isCompleted = false, completedAt = null, createdAt = now, updatedAt = now,
            sortKey = 0, isGoogle = false, syncState = SyncState.LOCAL, subtaskCount = subs, subtasksDone = if (subs > 0) 1 else 0,
            countdownEnabled = countdown, repeat = rule?.let(RRule::parse),
        )
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxSize().background(DsTheme.colors.surface).padding(top = 24.dp),
        ) {
            AgendaEventCard(ev("Design review", -20, 40, "Studio 3"), now, {}, focused = true)
            AgendaEventCard(ev("Team standup", 90, 105, recurring = true), now, {}, showCountdown = true)
            AgendaTaskCard(task("Weekly review", 3, java.time.LocalTime.of(17, 0), true, "FREQ=WEEKLY;BYDAY=FR", "Inbox, calendar, next week’s plan", subs = 2), now, {}, {})
            AgendaTaskCard(task("Renew passport", 64, null, true, "FREQ=YEARLY"), now, {}, {})
            AgendaTaskCard(task("Send invoice", -1, java.time.LocalTime.of(9, 0), true), now, {}, {}, showDate = true)
            TaskRow(task("Water the plants", 0, java.time.LocalTime.of(23, 59), true, "FREQ=DAILY;INTERVAL=3"), now, {}, {})
        }
    }

    @Test fun cardsLight() { enabled(); cards(ThemeMode.LIGHT) }
    @Test fun cardsDark() { enabled(); cards(ThemeMode.DARK) }

    @Test fun detailsLight() { enabled(); details(ThemeMode.LIGHT) }
    @Test fun detailsDark() { enabled(); details(ThemeMode.DARK) }

    /** Frames from a tab switch, a push to Settings, and the way back. */
    @Test fun transitionFrames() {
        enabled()
        app(ThemeMode.LIGHT, onboarded = true)
        tab("Calendar").performClick()
        listOf(16L, 32L, 32L, 32L, 48L, 80L).forEachIndexed { i, ms -> frame("tab-$i", ms) }
        settle()
        // A two-tab jump: the pill passes over Calendar on its way from Today to Tasks.
        tab("Today").performClick(); settle()
        tab("Tasks").performClick()
        listOf(16L, 48L, 48L, 48L, 64L, 96L).forEachIndexed { i, ms -> frame("jump-$i", ms) }
        settle()
        compose.onNodeWithContentDescription("Settings").performClick()
        listOf(16L, 48L, 48L, 64L, 128L).forEachIndexed { i, ms -> frame("push-$i", ms) }
        settle()
        compose.onNodeWithContentDescription("Back").performClick()
        listOf(16L, 48L, 48L, 64L, 128L).forEachIndexed { i, ms -> frame("pop-$i", ms) }
    }

    /** A month cell held down: the press feedback must be the circle, not the cell's rectangle. */
    @Test fun calendarPressedDate() {
        enabled()
        app(ThemeMode.LIGHT, onboarded = true)
        tab("Calendar").performClick(); settle()
        val today = java.time.LocalDate.now()
        val day = today.withDayOfMonth(if (today.dayOfMonth == 15) 16 else 15)
        val label = java.time.format.DateTimeFormatter.ofPattern("MMMM d,", java.util.Locale.US).format(day)
        compose.onNode(androidx.compose.ui.test.hasContentDescription(label, substring = true))
            .performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300); shadowOf(Looper.getMainLooper()).idle()
        capture("calendar-pressed")
    }
}

/** Narrowest supported phone with 200 % text. */
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi", fontScale = 2.0f)
class VisualAuditRenderSmallLargeFont : VisualAuditRender() {
    override val prefix = "small-"
}
