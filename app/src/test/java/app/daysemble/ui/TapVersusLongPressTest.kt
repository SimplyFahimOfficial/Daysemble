package app.daysemble.ui

import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeUp
import app.daysemble.data.prefs.ClockFormat
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.domain.model.EventOccurrence
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.Task
import app.daysemble.ui.components.AgendaEventCard
import app.daysemble.ui.components.AgendaTaskCard
import app.daysemble.ui.components.TaskRow
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.rememberTimePrefs
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The item interaction rule: a tap opens the detail, a long-press opens the menu and nothing
 * else, a scroll does neither, the menu is reachable through accessibility too, and after the
 * list re-sorts the press goes to the item that was pressed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class TapVersusLongPressTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.systemDefault()
    private val now = Instant.parse("2026-10-27T10:00:00Z")

    @Before fun setUp() { compose.mainClock.autoAdvance = true }

    private fun settle() = repeat(5) { shadowOf(Looper.getMainLooper()).idle() }

    private fun task(id: String, title: String) = Task(
        id = id, listId = "l", parentId = null, title = title, notes = "", dueDate = LocalDate.parse("2026-10-27"), dueTime = null,
        priority = Priority.NONE, isCompleted = false, completedAt = null, createdAt = now, updatedAt = now, sortKey = 0,
    )

    private fun event(title: String) = EventOccurrence(
        eventId = "e1", occurrenceKey = "k", overrideId = null, calendarId = "c", title = title, description = "", location = "",
        isAllDay = false, start = now.plusSeconds(3600), end = now.plusSeconds(7200), startDate = LocalDate.parse("2026-10-27"),
        endDate = LocalDate.parse("2026-10-27"), timeZone = zone, colorArgb = 0xFF4477FF.toInt(), isRecurring = false, isDevice = false, isReadOnly = false,
    )

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            DaysembleTheme(themeMode = ThemeMode.LIGHT) {
                CompositionLocalProvider(
                    LocalTimePrefs provides rememberTimePrefs(ClockFormat.H24, null, zone),
                    LocalSnackbar provides remember { SnackbarHostState() },
                ) { content() }
            }
        }
        settle()
    }

    @Test fun taskCard_tapOpens_longPressOnlyOpensTheMenu() {
        var taps = 0; var longs = 0
        show { AgendaTaskCard(task("t", "Water plants"), now, onToggle = {}, onClick = { taps++ }, onLongClick = { longs++ }, modifier = Modifier.testTag("card")) }
        compose.onNodeWithTag("card").performClick()
        assertThat(taps to longs).isEqualTo(1 to 0)
        compose.onNodeWithTag("card").performTouchInput { longClick() }
        compose.waitForIdle()
        assertThat(taps to longs).isEqualTo(1 to 1) // the long-press didn't also open the detail
    }

    @Test fun eventCard_and_taskRow_followTheSameRule() {
        var taps = 0; var longs = 0
        show {
            androidx.compose.foundation.layout.Column {
                AgendaEventCard(event("Standup"), now, onClick = { taps++ }, onLongClick = { longs++ }, modifier = Modifier.testTag("event"))
                TaskRow(task("t", "Write"), now, onToggle = {}, onClick = { taps++ }, onLongClick = { longs++ }, modifier = Modifier.testTag("row"))
            }
        }
        compose.onNodeWithTag("event").performTouchInput { longClick() }
        compose.onNodeWithTag("row").performTouchInput { longClick() }
        compose.waitForIdle()
        assertThat(taps to longs).isEqualTo(0 to 2)
        compose.onNodeWithTag("event").performClick()
        compose.onNodeWithTag("row").performClick()
        assertThat(taps to longs).isEqualTo(2 to 2)
    }

    @Test fun longPress_isAnAccessibilityAction_soTheMenuNeedsNoGesture() {
        var longs = 0
        show { AgendaTaskCard(task("t", "Water plants"), now, onToggle = {}, onClick = {}, onLongClick = { longs++ }, modifier = Modifier.testTag("card")) }
        compose.onNodeWithTag("card").performSemanticsAction(SemanticsActions.OnLongClick)
        assertThat(longs).isEqualTo(1)
    }

    @Test fun scrollingTheList_opensNothing() {
        val opened = mutableListOf<String>()
        show {
            LazyColumn(Modifier.fillMaxSize().testTag("list")) {
                items((1..30).map { task("t$it", "Task $it") }, key = { it.id }) { t ->
                    AgendaTaskCard(t, now, onToggle = {}, onClick = { opened += "tap:" + t.id }, onLongClick = { opened += "long:" + t.id })
                }
            }
        }
        compose.onNodeWithTag("list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertThat(opened).isEmpty()
    }

    @Test fun afterTheListReorders_theTapGoesToTheItemUnderTheFinger() {
        val opened = mutableListOf<String>()
        var order by mutableStateOf(listOf(task("a", "Alpha"), task("b", "Beta")))
        show {
            androidx.compose.foundation.layout.Column {
                order.forEach { t ->
                    androidx.compose.runtime.key(t.id) {
                        AgendaTaskCard(t, now, onToggle = {}, onClick = { opened += t.id }, onLongClick = { opened += "long:" + t.id })
                    }
                }
            }
        }
        order = order.reversed()
        compose.waitForIdle()
        compose.onNodeWithText("Beta").performClick()
        compose.onNodeWithText("Alpha").performTouchInput { longClick() }
        compose.waitForIdle()
        assertThat(opened).containsExactly("b", "long:a").inOrder()
    }
}
