package app.daysemble.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.TimePrefs
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class WeekStripTest {
    @get:Rule val compose = createComposeRule()
    private val prefs = TimePrefs(use24h = true, firstDayOfWeek = DayOfWeek.MONDAY, zone = ZoneId.of("UTC"), locale = Locale.US)

    private fun cell(d: LocalDate) = compose.onNode(
        hasContentDescription(DateTimeFormatter.ofPattern("MMMM d,", Locale.US).format(d), substring = true) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
    )

    /**
     * Midnight in the middle of a week changes "today" but not the week, so nothing the strip
     * depends on restarts. Paging away and back must still select the new today, not yesterday.
     */
    @Test fun midweekMidnight_pagingBackSelectsTheNewToday() {
        val tuesday = LocalDate.of(2026, 10, 27)
        val wednesday = tuesday.plusDays(1)
        var today by mutableStateOf(tuesday)
        var selected by mutableStateOf(tuesday)
        compose.setContent {
            DaysembleTheme {
                CompositionLocalProvider(LocalTimePrefs provides prefs) {
                    WeekStrip(selected = selected, today = today, onSelect = { selected = it })
                }
            }
        }
        compose.waitForIdle()

        // The day rolls over; Today follows it (TodayViewModel.onTick does the same).
        compose.runOnIdle { today = wednesday; selected = wednesday }
        compose.waitForIdle()

        cell(wednesday).performTouchInput { swipeLeft(startX = right, endX = left - 900f) }
        compose.waitForIdle()
        assertThat(selected).isEqualTo(wednesday.plusWeeks(1)) // same weekday, next week

        cell(wednesday.plusWeeks(1)).performTouchInput { swipeRight(startX = left, endX = right + 900f) }
        compose.waitForIdle()
        assertThat(selected).isEqualTo(wednesday)
    }
}
