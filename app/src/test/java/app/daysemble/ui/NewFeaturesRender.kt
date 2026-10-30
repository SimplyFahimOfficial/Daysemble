package app.daysemble.ui

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.foundation.layout.PaddingValues
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import app.daysemble.DaysembleApp
import app.daysemble.core.recurrence.RRule
import app.daysemble.data.db.SyncState
import app.daysemble.data.db.TaskEntity
import app.daysemble.data.prefs.ClockFormat
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.domain.model.TaskDraft
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.rememberTimePrefs
import app.daysemble.ui.settings.ScheduleSettingsRoute
import app.daysemble.ui.task.TaskDetailRoute
import app.daysemble.ui.task.TaskDetailTags
import app.daysemble.ui.today.BalanceTags
import app.daysemble.ui.today.TodayTags
import app.daysemble.data.SampleData
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
import java.time.LocalTime
import java.time.ZoneId

/**
 * Developer visual QA of the new screens (task detail with history, Today's free-time and sleep
 * block, the schedule settings), rendered off-device to build/visual-audit. Not screenshots for
 * the README and not evidence of motion. The history rows here are seeded straight into the
 * database for the render only. Opt-in like [VisualAuditRender]:
 * ./gradlew testDebugUnitTest -Pdaysemble.visualAudit=true --tests '*NewFeaturesRender*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class NewFeaturesRender {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<DaysembleApp>()
    private val c get() = app.container
    private val out = File(System.getProperty("daysemble.visualAuditDir") ?: "build/visual-audit").apply { mkdirs() }

    private fun enabled() = assumeTrue(System.getProperty("daysemble.visualAudit") == "true")
    private fun settle(n: Int = 30) = repeat(n) { compose.mainClock.advanceTimeBy(100); Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle() }
    private fun capture(name: String) {
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(theme: ThemeMode, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DaysembleTheme(themeMode = theme) {
                CompositionLocalProvider(
                    LocalTimePrefs provides rememberTimePrefs(ClockFormat.H12, null, ZoneId.systemDefault()), LocalAppClock provides c.clock,
                    LocalSnackbar provides remember { SnackbarHostState() },
                ) { content() }
            }
        }
        settle()
    }

    /** A daily series started 24 days ago with a realistic mix of done and missed days. */
    private fun seedSeries(): String = runBlocking {
        app.settingsDataStore.edit { it.clear() }
        c.clearLocalData()
        val list = c.tasks.ensureDefaultList("My tasks")
        val today = c.clock.today()
        val start = today.minusDays(24)
        val first = c.tasks.create(
            TaskDraft(title = "Morning stretch", notes = "10 minutes, before coffee", dueDate = start, dueTime = LocalTime.of(7, 30), durationMinutes = 15, repeat = RRule.parse("FREQ=DAILY"), countdown = true),
            list,
        )
        val row = c.database.tasks().get(first)!!
        val missed = setOf(3, 4, 11, 17)
        val now = c.clock.now().toEpochMilli()
        (1 until 24).filter { it !in missed }.forEach { k ->
            val d = start.plusDays(k.toLong())
            c.database.tasks().insert(
                row.copy(id = "${row.seriesId}:$d", dueDate = d.toEpochDay(), occurrenceDate = d.toEpochDay(), isCompleted = true, completedAt = now - (24 - k) * 86_400_000L, sortKey = 100L + k),
            )
        }
        c.database.tasks().upsert(row.copy(isCompleted = true, completedAt = now - 24 * 86_400_000L))
        c.database.tasks().insert(
            TaskEntity(
                id = "${row.seriesId}:$today", listId = list, title = row.title, notes = row.notes, dueDate = today.toEpochDay(), dueTimeMinutes = 23 * 60,
                sortKey = 999, createdAt = now, updatedAt = now, syncState = SyncState.LOCAL, countdownEnabled = true,
                seriesId = row.seriesId, occurrenceDate = today.toEpochDay(), durationMinutes = 15,
            ),
        )
        "${row.seriesId}:$today"
    }

    @Test fun taskDetailLight() {
        enabled(); val id = seedSeries()
        show(ThemeMode.LIGHT) { TaskDetailRoute(id, onEdit = {}, onClose = {}) }
        capture("detail-light-top")
        compose.onNodeWithTag(TaskDetailTags.SCREEN).performTouchInput { swipeUp() }; settle()
        compose.onNodeWithTag(TaskDetailTags.SCREEN).performTouchInput { swipeUp() }; settle()
        capture("detail-light-history")
    }

    @Test fun taskDetailDark() {
        enabled(); val id = seedSeries()
        show(ThemeMode.DARK) { TaskDetailRoute(id, onEdit = {}, onClose = {}) }
        compose.onNodeWithTag(TaskDetailTags.SCREEN).performTouchInput { swipeUp() }; settle()
        compose.onNodeWithTag(TaskDetailTags.SCREEN).performTouchInput { swipeUp() }; settle()
        capture("detail-dark-history")
    }

    @Test fun todayBalance() {
        enabled()
        runBlocking { app.settingsDataStore.edit { it.clear() }; c.clearLocalData(); SampleData.load(c) }
        show(ThemeMode.LIGHT) { TodayRoute(PaddingValues(), { _, _ -> }, {}) }
        // Open the overview with its handle (a button with the overview label).
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Daily overview")).performClick(); settle()
        capture("today-overview-light")
        compose.onNodeWithTag(BalanceTags.INFO).performClick(); settle()
        capture("today-breakdown-light")
    }

    @Test fun scheduleSettings() {
        enabled()
        runBlocking { app.settingsDataStore.edit { it.clear() }; c.clearLocalData() }
        show(ThemeMode.LIGHT) { ScheduleSettingsRoute(onBack = {}) }
        capture("schedule-light")
    }
}
