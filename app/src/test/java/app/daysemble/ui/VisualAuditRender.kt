package app.daysemble.ui

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import app.daysemble.DaysembleApp
import app.daysemble.data.SampleData
import app.daysemble.data.prefs.ClockFormat
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.ui.calendar.CalendarRoute
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.rememberTimePrefs
import app.daysemble.ui.settings.AppearanceRoute
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
        tab("Calendar").performClick(); settle(); capture("main-calendar-$t")
        tab("Tasks").performClick(); settle(); capture("main-tasks-$t")
        compose.onNodeWithContentDescription("Settings").performClick(); settle(); capture("settings-$t")
    }

    @Test fun mainScreensLight() { enabled(); mainScreens(ThemeMode.LIGHT) }
    @Test fun mainScreensDark() { enabled(); mainScreens(ThemeMode.DARK) }

    /** Frames from a tab switch, a push to Settings, and the way back. */
    @Test fun transitionFrames() {
        enabled()
        app(ThemeMode.LIGHT, onboarded = true)
        tab("Calendar").performClick()
        listOf(16L, 32L, 32L, 32L, 48L, 80L).forEachIndexed { i, ms -> frame("tab-$i", ms) }
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
