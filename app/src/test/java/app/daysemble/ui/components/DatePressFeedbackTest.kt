package app.daysemble.ui.components

import android.os.Looper
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.daysemble.DaysembleApp
import app.daysemble.data.SampleData
import app.daysemble.data.prefs.ClockFormat
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.ui.LocalSnackbar
import app.daysemble.ui.calendar.CalendarRoute
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.rememberTimePrefs
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The square press shadow on calendar dates came from the date cell using the ambient press
 * indication (Material's bounded ripple), which fills the cell's rectangle. Date cells must take
 * their press feedback only from the round [DateDisc]. Robolectric can't draw ripples, so this
 * replaces the ambient indication with a recorder: pressing a date must never reach it, while
 * pressing an ordinary card still does (proving the recorder works).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class DatePressFeedbackTest {
    @get:Rule val compose = createComposeRule()
    private val c get() = ApplicationProvider.getApplicationContext<DaysembleApp>().container

    /** Counts presses that reach the ambient indication. */
    private class Recorder : IndicationNodeFactory {
        var presses = 0
        override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {
            override fun onAttach() {
                coroutineScope.launch { interactionSource.interactions.collect { if (it is PressInteraction.Press) presses++ } }
            }
        }
        override fun equals(other: Any?) = other === this
        override fun hashCode() = System.identityHashCode(this)
    }

    private val recorder = Recorder()

    @Before fun setUp() {
        compose.mainClock.autoAdvance = false
        runBlocking { c.clearLocalData(); SampleData.load(c) }
    }

    private fun settle(n: Int = 15) = repeat(n) {
        compose.mainClock.advanceTimeBy(100); Thread.sleep(15); shadowOf(Looper.getMainLooper()).idle()
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            DaysembleTheme(themeMode = ThemeMode.LIGHT) {
                val prefs = rememberTimePrefs(ClockFormat.H24, null, ZoneId.systemDefault())
                CompositionLocalProvider(
                    LocalTimePrefs provides prefs, LocalAppClock provides c.clock,
                    LocalSnackbar provides remember { SnackbarHostState() },
                    LocalIndication provides recorder,
                ) { content() }
            }
        }
        settle()
    }

    private fun press(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300); shadowOf(Looper.getMainLooper()).idle()
        node.performTouchInput { up() }
        settle(3)
    }

    @Test fun monthCell_pressFeedbackIsOnlyTheCircle() {
        show { CalendarRoute(PaddingValues(), { _, _ -> }, {}, { _, _ -> }) }
        val today = c.clock.today()
        val day = today.withDayOfMonth(if (today.dayOfMonth == 15) 16 else 15)
        val label = DateTimeFormatter.ofPattern("MMMM d,", Locale.US).format(day)
        press(compose.onNode(hasContentDescription(label, substring = true) and hasClickAction()))
        assertThat(recorder.presses).isEqualTo(0)
        // Control: an ordinary button does use the ambient indication (the recorder works).
        press(compose.onNode(hasText("Add event") and hasClickAction()))
        assertThat(recorder.presses).isAtLeast(1)
    }

    @Test fun weekStripDate_pressFeedbackIsOnlyTheCircle() {
        val today = c.clock.today()
        // A day of the current week other than today (the strip shows this week).
        show { WeekStrip(selected = today, today = today, onSelect = {}) }
        val p = app.daysemble.ui.format.TimePrefs(true, java.time.DayOfWeek.SUNDAY, ZoneId.systemDefault(), Locale.US)
        val other = Fmt.weekStart(today, p).let { if (it == today) it.plusDays(1) else it }
        val label = DateTimeFormatter.ofPattern("MMMM d,", Locale.US).format(other)
        press(compose.onNode(hasContentDescription(label, substring = true) and hasClickAction()))
        assertThat(recorder.presses).isEqualTo(0)
    }
}
