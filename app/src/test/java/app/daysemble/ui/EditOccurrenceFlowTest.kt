package app.daysemble.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.preferences.core.edit
import app.daysemble.DaysembleApp
import app.daysemble.core.recurrence.RRule
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.domain.model.EventDraft
import app.daysemble.ui.actions.ItemMenuTags
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.time.ZoneId

/** Long-press a repeating event → Edit → "This event": only that occurrence changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class EditOccurrenceFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val c get() = (compose.activity.application as DaysembleApp).container
    private val zone = ZoneId.systemDefault()

    @Before fun setUp() {
        compose.mainClock.autoAdvance = false
        runBlocking {
            compose.activity.application.settingsDataStore.edit { it.clear() }
            c.clearLocalData()
            c.settings.completeOnboarding("Sam")
        }
    }

    private fun settle(n: Int = 20) = repeat(n) {
        compose.mainClock.advanceTimeBy(100); Thread.sleep(15); shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitFor(text: String) {
        repeat(40) { if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return; settle(2) }
        compose.onNodeWithText(text).assertExists()
    }

    @Test fun editingThisEvent_changesOnlyTodaysOccurrence() {
        val today = c.clock.today()
        val id = runBlocking {
            c.events.create(
                EventDraft(
                    title = "Gym", startDate = today.minusDays(1), startTime = LocalTime.of(23, 0), endDate = today.minusDays(1), endTime = LocalTime.of(23, 30),
                    zone = zone, rrule = RRule.parse("FREQ=DAILY"),
                ),
                c.defaults().calendarId,
            )
        }
        compose.setContent {
            val s by c.settings.settings.collectAsState(initial = null)
            val settings = s ?: return@setContent
            DaysembleTheme(settings.themeMode, settings.logo, settings.reducedMotionOverride) { DaysembleRoot(c, settings) }
        }
        settle()
        waitFor("Gym")
        compose.onAllNodesWithText("Gym").onFirst().performTouchInput { longClick() }; settle()
        compose.onNodeWithTag(ItemMenuTags.EDIT).performClick(); settle()
        compose.onNode(hasText("This event") and hasClickAction()).performClick(); settle(30)
        waitFor("Edit this event")
        // One occurrence has no repeat of its own, and its reminders are the series'. (Headers are in capitals.)
        assertThat(compose.onAllNodesWithText("REPEAT").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("REMINDERS").fetchSemanticsNodes()).isNotEmpty()
        assertThat(compose.onAllNodesWithText("Reminders are the same for every event", substring = true).fetchSemanticsNodes()).isNotEmpty()
        compose.onNode(hasSetTextAction() and hasText("Gym")).performTextReplacement("Gym (short)"); settle()
        compose.onNode(hasText("Save") and hasClickAction()).performClick(); settle(30)

        fun titleOn(d: java.time.LocalDate) = runBlocking { c.events.occurrences(d, d, zone).first() }.single { it.eventId == id }.title
        repeat(40) { if (titleOn(today) == "Gym (short)") return@repeat; settle(2) }
        assertThat(titleOn(today)).isEqualTo("Gym (short)")
        assertThat(titleOn(today.plusDays(1))).isEqualTo("Gym")
        assertThat(titleOn(today.minusDays(1))).isEqualTo("Gym")
        assertThat(runBlocking { c.events.get(id) }!!.title).isEqualTo("Gym")
    }
}
