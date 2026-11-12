package app.daysemble.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.edit
import app.daysemble.DaysembleApp
import app.daysemble.data.SampleData
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Completing a task in the Tasks tab commits on the tap. The row used to show a strike sweep for
 * 400 ms of animation time first and only then completed the task, so a quick tap on Undo, a
 * second tap or leaving the screen could race the commit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class TaskCompletionTimingTest {
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
        repeat(30) { compose.mainClock.advanceTimeBy(100); Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun frame() { compose.mainClock.advanceTimeByFrame(); Thread.sleep(5); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun completingATask_commitsWithinAFewFrames_notAfterAnAnimation() {
        compose.onNode(
            hasText("Tasks") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
        ).performClick()
        repeat(30) { frame() }
        val id = runBlocking { c.tasks.allTasks.first().first { it.title == "Call the plumber" }.id }
        assertThat(runBlocking { c.tasks.get(id)!!.isCompleted }).isFalse()

        compose.onNodeWithContentDescription("Mark Call the plumber complete").performClick()
        var frames = 0
        // The database write runs on its own thread, so each frame also gives it real time.
        while (frames < 60 && !runBlocking { c.tasks.get(id)!!.isCompleted }) { frame(); frames++ }
        assertWithMessage("frames (16 ms each) until the task was completed").that(frames).isAtMost(12)
    }
}
