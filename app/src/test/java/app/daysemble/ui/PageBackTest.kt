package app.daysemble.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.edit
import app.daysemble.DaysembleApp
import app.daysemble.data.SampleData
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Back and Cancel on a page that has only just opened work at once (the page under it, which is
 * covered and not leaving, must not hold them back), and repeated Back taps close one page each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class PageBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val c get() = (compose.activity.application as DaysembleApp).container

    @Before fun setUp() {
        compose.mainClock.autoAdvance = false
        runBlocking {
            compose.activity.application.settingsDataStore.edit { it.clear() }
            c.clearLocalData(); SampleData.load(c); c.settings.completeOnboarding("Sam")
        }
        compose.setContent {
            val s by c.settings.settings.collectAsState(initial = null)
            val settings = s ?: return@setContent
            DaysembleTheme(settings.themeMode, settings.logo, reducedMotionOverride = false) { DaysembleRoot(c, settings) }
        }
        settle()
    }

    private fun settle(n: Int = 25) = repeat(n) { compose.mainClock.advanceTimeBy(100); Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle() }
    private fun frame() { compose.mainClock.advanceTimeByFrame(); shadowOf(Looper.getMainLooper()).idle() }
    // While a page slides in, the page under it is still composed with its own Back; the newest is the last.
    private fun back() = compose.onAllNodes(hasContentDescription("Back") and hasClickAction()).onLast().performClick()
    private fun appearancePageShown() = compose.onAllNodes(hasText("Dark")).fetchSemanticsNodes().isNotEmpty()

    @Test fun backOnAPageThatJustOpened_closesIt() {
        compose.onNodeWithContentDescription("Settings").performClick(); settle()
        compose.onAllNodesWithText("Appearance").onFirst().performClick()
        repeat(2) { frame() } // the page is still sliding in, Settings under it is only covered
        assertThat(appearancePageShown()).isTrue()
        back()
        settle()
        assertThat(appearancePageShown()).isFalse()
        // Settings is still there, one page closed.
        assertThat(compose.onAllNodesWithText("Appearance").fetchSemanticsNodes()).isNotEmpty()
    }

    /** Four quick Back taps close the page they were meant for, not the one under it as well. */
    @Test fun repeatedBackTaps_closeOnlyTheTopPage() {
        compose.onNodeWithContentDescription("Settings").performClick(); settle()
        compose.onAllNodesWithText("Appearance").onFirst().performClick(); settle()
        repeat(4) { back(); frame() }
        settle()
        assertThat(appearancePageShown()).isFalse()
        // Settings itself is still open: its list has the Appearance row and the main screen's Settings button is covered.
        assertThat(compose.onAllNodesWithText("Appearance").fetchSemanticsNodes()).isNotEmpty()
        assertThat(compose.onAllNodes(hasContentDescription("Back") and hasClickAction()).fetchSemanticsNodes()).isNotEmpty()
    }
}
