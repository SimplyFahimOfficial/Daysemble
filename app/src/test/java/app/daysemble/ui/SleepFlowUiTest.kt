package app.daysemble.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.daysemble.DaysembleApp
import app.daysemble.data.prefs.ClockFormat
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.rememberTimePrefs
import app.daysemble.ui.sleep.SleepButtons
import app.daysemble.ui.sleep.SleepHistory
import app.daysemble.ui.sleep.SleepPrompts
import app.daysemble.ui.sleep.rememberSleepActions
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * The sleep buttons as the user sees them, against the real database: a second "I'm going to
 * sleep" asks instead of adding a record, "Move to now" keeps one record, and deleting a record
 * from its editor can be undone from the snackbar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class SleepFlowUiTest {
    @get:Rule val compose = createComposeRule()
    private val c get() = ApplicationProvider.getApplicationContext<DaysembleApp>().container

    @Before fun setUp() { runBlocking { c.clearLocalData() } }

    private fun records() = runBlocking { c.sleep.recent.first() }

    private fun show() {
        compose.setContent {
            DaysembleTheme(themeMode = ThemeMode.LIGHT) {
                val snackbar = remember { SnackbarHostState() }
                CompositionLocalProvider(
                    LocalTimePrefs provides rememberTimePrefs(ClockFormat.H24, null, ZoneId.systemDefault()),
                    LocalAppClock provides c.clock, LocalSnackbar provides snackbar,
                ) {
                    val actions = rememberSleepActions()
                    val recs by c.sleep.recent.collectAsState(initial = emptyList())
                    Box(Modifier.fillMaxSize()) {
                        Column {
                            SleepButtons(actions, asleep = false)
                            SleepHistory(recs, actions)
                        }
                        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                    }
                    SleepPrompts(actions)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun waitForText(t: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun secondTap_asks_andMoveToNow_keepsOneRecord() {
        show()
        compose.onNodeWithText("I’m going to sleep").performClick()
        compose.waitUntil(5_000) { records().size == 1 }
        compose.onNodeWithText("I’m going to sleep").performClick()
        waitForText("You’re already marked as asleep")
        compose.onNodeWithText("Move to now").performClick()
        compose.waitForIdle()
        assertThat(records()).hasSize(1)
        assertThat(records().single().isOpen).isTrue()
    }

    @Test fun deleteFromTheEditor_thenUndo_bringsTheRecordBack() {
        show()
        compose.onNodeWithText("I’m going to sleep").performClick()
        compose.waitUntil(5_000) { records().size == 1 }
        val before = records().single()
        // The database has the record before the history row is on screen: wait for the row's button.
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Edit times").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Edit times").performClick()
        waitForText("Edit sleep")
        compose.onNodeWithText("Delete").performClick()
        waitForText("Delete this sleep record?")
        // The confirmation's button: the newest "Delete" on screen (the editor's is under the dialog).
        compose.onAllNodesWithText("Delete").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        compose.waitUntil(5_000) { records().isEmpty() }
        waitForText("Sleep record deleted")
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { records().size == 1 }
        assertThat(records().single().bedtime).isEqualTo(before.bedtime)
    }
}
