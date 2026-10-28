package app.daysemble.core.recurrence

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate

class TaskRecurrenceTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun rule(s: String) = RRule.parse(s)!!
    private fun occ(r: String, anchor: String, n: Int) = TaskRecurrence.occurrences(rule(r), d(anchor), n).map { it.toString() }
    private val weekdays = listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)

    @Test fun daily_andEveryN() {
        assertThat(occ("FREQ=DAILY", "2026-10-30", 3)).containsExactly("2026-10-30", "2026-10-31", "2026-11-01").inOrder()
        assertThat(occ("FREQ=DAILY;INTERVAL=3", "2026-12-30", 3)).containsExactly("2026-12-30", "2027-01-02", "2027-01-05").inOrder()
    }

    @Test fun weekdays_skipTheWeekend() {
        // Fri 30 Oct 2026 → Mon, Tue.
        assertThat(occ("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", "2026-10-30", 3)).containsExactly("2026-10-30", "2026-11-02", "2026-11-03").inOrder()
    }

    @Test fun weekly_selectedDays_andEveryTwoWeeks() {
        // Anchor Tue 27 Oct; Tue and Thu.
        assertThat(occ("FREQ=WEEKLY;BYDAY=TU,TH", "2026-10-27", 4))
            .containsExactly("2026-10-27", "2026-10-29", "2026-11-03", "2026-11-05").inOrder()
        assertThat(occ("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU", "2026-10-27", 3))
            .containsExactly("2026-10-27", "2026-11-10", "2026-11-24").inOrder()
        // The anchor counts even when its weekday isn't selected (Tue anchor, Mon/Wed rule).
        assertThat(occ("FREQ=WEEKLY;BYDAY=MO,WE", "2026-10-27", 3)).containsExactly("2026-10-27", "2026-10-28", "2026-11-02").inOrder()
    }

    @Test fun monthly_monthEnd_clampsAndRecovers() {
        assertThat(occ("FREQ=MONTHLY", "2027-01-31", 5))
            .containsExactly("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30", "2027-05-31").inOrder()
        assertThat(occ("FREQ=MONTHLY", "2027-12-31", 3)).containsExactly("2027-12-31", "2028-01-31", "2028-02-29").inOrder()
        assertThat(occ("FREQ=MONTHLY;INTERVAL=3", "2026-11-30", 3)).containsExactly("2026-11-30", "2027-02-28", "2027-05-30").inOrder()
    }

    @Test fun yearly_leapDay() {
        assertThat(occ("FREQ=YEARLY", "2028-02-29", 5))
            .containsExactly("2028-02-29", "2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29").inOrder()
    }

    @Test fun count_and_until() {
        assertThat(occ("FREQ=DAILY;COUNT=3", "2026-10-27", 10)).hasSize(3)
        assertThat(occ("FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=4", "2026-10-26", 10))
            .containsExactly("2026-10-26", "2026-10-28", "2026-10-30", "2026-11-02").inOrder()
        assertThat(occ("FREQ=DAILY;UNTIL=20261029", "2026-10-27", 10)).containsExactly("2026-10-27", "2026-10-28", "2026-10-29").inOrder()
    }

    @Test fun next_skipsMissedDates_whenNotBeforeIsToday() {
        val r = rule("FREQ=DAILY")
        // Occurrence of 20 Oct completed on 27 Oct: next is today, not the backlog of 21–26 Oct.
        assertThat(TaskRecurrence.next(r, d("2026-10-01"), after = d("2026-10-20"), notBefore = d("2026-10-27"))).isEqualTo(d("2026-10-27"))
        // Completed early (future occurrence): the one after it.
        assertThat(TaskRecurrence.next(r, d("2026-10-01"), after = d("2026-10-30"), notBefore = d("2026-10-27"))).isEqualTo(d("2026-10-31"))
        val weekly = rule("FREQ=WEEKLY;BYDAY=MO")
        assertThat(TaskRecurrence.next(weekly, d("2026-01-05"), after = d("2026-03-02"), notBefore = d("2026-10-27"))).isEqualTo(d("2026-11-02"))
    }

    @Test fun next_isNullWhenTheSeriesHasEnded() {
        assertThat(TaskRecurrence.next(rule("FREQ=DAILY;COUNT=2"), d("2026-10-27"), d("2026-10-28"))).isNull()
        assertThat(TaskRecurrence.next(rule("FREQ=DAILY;UNTIL=20261028"), d("2026-10-27"), d("2026-10-28"))).isNull()
        // Skipping missed dates can also run past the end.
        assertThat(TaskRecurrence.next(rule("FREQ=DAILY;UNTIL=20261028"), d("2026-10-01"), d("2026-10-02"), notBefore = d("2026-11-01"))).isNull()
    }

    @Test fun farJumps_matchStepping() {
        // The jump to a far target must give the same occurrence (and index) as walking there.
        for (r in listOf("FREQ=DAILY;INTERVAL=3", "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH", "FREQ=MONTHLY", "FREQ=MONTHLY;INTERVAL=5", "FREQ=YEARLY")) {
            val anchor = d("2024-01-31")
            val walked = TaskRecurrence.occurrences(rule(r), anchor, 400)
            for ((i, date) in walked.withIndex()) {
                assertThat(TaskRecurrence.indexOf(rule(r), anchor, date)).isEqualTo(i.toLong())
                assertThat(TaskRecurrence.next(rule(r), anchor, date.minusDays(1))).isEqualTo(date)
            }
        }
    }

    @Test fun supports_onlyTheTaskSubset() {
        assertThat(TaskRecurrence.supports(rule("FREQ=WEEKLY;BYDAY=MO"))).isTrue()
        assertThat(TaskRecurrence.supports(rule("FREQ=MONTHLY;BYMONTHDAY=-1"))).isFalse()
        assertThat(TaskRecurrence.supports(rule("FREQ=MONTHLY;BYDAY=2TU"))).isFalse()
        assertThat(TaskRecurrence.supports(rule("FREQ=DAILY;UNTIL=20261029T120000Z"))).isFalse()
        assertThat(weekdays.map(DayOfWeek::getValue)).containsExactly(1, 2, 3, 4, 5).inOrder()
    }
}
