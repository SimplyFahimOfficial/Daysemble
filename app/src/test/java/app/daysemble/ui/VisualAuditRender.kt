package app.daysemble.ui

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.daysemble.DaysembleApp
import app.daysemble.data.SampleData
import app.daysemble.data.prefs.ClockFormat
import app.daysemble.data.prefs.ThemeMode
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
 * build/visual-audit. These are NOT device screenshots and must never be used in the README.
 * Opt-in: ./gradlew testDebugUnitTest -Pdaysemble.visualAudit=true --tests '*VisualAuditRender*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class VisualAuditRender {
    @get:Rule val compose = createComposeRule()
    private val c get() = ApplicationProvider.getApplicationContext<DaysembleApp>().container
    private val out = File(System.getProperty("daysemble.visualAuditDir") ?: "build/visual-audit").apply { mkdirs() }

    private fun settle(n: Int = 25) = repeat(n) {
        compose.mainClock.advanceTimeBy(100); Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle()
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
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun enabled() = assumeTrue(System.getProperty("daysemble.visualAudit") == "true")

    @Test fun todayLight() { enabled(); render("today-light", ThemeMode.LIGHT) { TodayRoute(PaddingValues(), { _, _ -> }, {}) } }
    @Test fun todayDark() { enabled(); render("today-dark", ThemeMode.DARK) { TodayRoute(PaddingValues(), { _, _ -> }, {}) } }
    @Test fun calendarLight() { enabled(); render("calendar-light", ThemeMode.LIGHT) { CalendarRoute(PaddingValues(), { _, _ -> }, {}, { _, _ -> }) } }
    @Test fun tasksDark() { enabled(); render("tasks-dark", ThemeMode.DARK) { TasksRoute(PaddingValues(), {}) } }
    @Test fun appearanceLight() { enabled(); render("appearance-light", ThemeMode.LIGHT) { AppearanceRoute(onBack = {}) } }
}
