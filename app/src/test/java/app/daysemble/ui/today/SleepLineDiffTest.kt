package app.daysemble.ui.today

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.daysemble.core.time.SleepRecord
import app.daysemble.ui.format.TimePrefs
import app.daysemble.ui.theme.DaysembleTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The line under the sleep summary on Today, as it is read: the schedule runs 07:00 → 23:00, so the
 * plan is 8 h, and a complete record of last night is compared with it in whole minutes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class SleepLineDiffTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.parse("2026-10-28")

    private fun at(h: Int, m: Int = 0): Instant = day.atTime(h, m).atZone(zone).toInstant()

    /** A complete record from 23:00 the night before to today, with the 8 h plan from 23:00 to 07:00. */
    private fun showRecordedSleep(hours: Long, minutes: Long = 0) {
        val bedtime = at(23).minus(Duration.ofDays(1))
        val state = TodayUiState(
            date = day,
            today = day,
            scheduledSleep = Duration.ofHours(8),
            sleep = SleepSummary(SleepRecord("1", bedtime, bedtime.plus(Duration.ofMinutes(hours * 60 + minutes))), null, null),
            isLoading = false,
        )
        val prefs = TimePrefs(use24h = true, firstDayOfWeek = java.time.DayOfWeek.MONDAY, zone = zone, locale = Locale.US)
        compose.setContent {
            DaysembleTheme {
                DayBalance(state, at(8), prefs, interactive = true, onInfo = {}, onSleep = {}, sleepActions = null)
            }
        }
    }

    @Test
    fun `seven recorded hours against the eight hour plan reads one hour less`() {
        showRecordedSleep(hours = 7)
        compose.onNodeWithTag(BalanceTags.SLEEP).assertTextContains("1 h less than planned", substring = true)
    }

    @Test
    fun `half an hour short reads thirty minutes less`() {
        showRecordedSleep(hours = 7, minutes = 30)
        compose.onNodeWithTag(BalanceTags.SLEEP).assertTextContains("30 min less than planned", substring = true)
    }
}
