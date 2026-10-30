package app.daysemble.core.time

import app.daysemble.core.time.Availability.Busy
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class AvailabilityTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2026, 10, 27)
    private fun at(h: Int, m: Int = 0, d: LocalDate = day) = d.atTime(LocalTime.of(h, m)).atZone(zone).toInstant()
    private fun ev(h1: Int, m1: Int, h2: Int, m2: Int) = Busy(at(h1, m1), at(h2, m2))
    private val earlyMorning = at(6)
    /** The old fixed 08:00–22:00 window, now just one schedule among others. */
    private val legacy = SleepSchedule(8 * 60, 22 * 60)
    private fun w(d: LocalDate) = legacy.awakeWindow(d, zone)

    @Test fun noEvents_isFreeForTheWholeWindow() {
        val a = Availability.of(w(day), emptyList(), earlyMorning)!!
        assertThat(a.isFree).isTrue()
        assertThat(a.free).isEqualTo(Duration.ofHours(14))
        assertThat(a.freeAfter).isNull()
        assertThat(a.longestBreak).isNull()
    }

    @Test fun lastEventEndsEarly_freeAfterItsEnd() {
        val a = Availability.of(w(day), listOf(ev(9, 15, 9, 30), ev(12, 30, 13, 30)), earlyMorning)!!
        assertThat(a.freeAfter).isEqualTo(at(13, 30))
        assertThat(a.mostly).isFalse()
        assertThat(a.busy).isEqualTo(Duration.ofMinutes(75))
    }

    @Test fun smallEventLater_isMostlyFree() {
        // After 10:00, a 30 min call over 12 h left is 4 %, under the 15 % share.
        val a = Availability.of(w(day), listOf(ev(9, 0, 10, 0), ev(16, 0, 16, 30)), earlyMorning)!!
        assertThat(a.freeAfter).isEqualTo(at(10))
        assertThat(a.mostly).isTrue()
    }

    @Test fun eveningEvents_noFreeAfter_reportsLongestBreak() {
        val a = Availability.of(w(day), listOf(ev(8, 0, 12, 0), ev(13, 0, 17, 0), ev(17, 30, 21, 0)), earlyMorning)!!
        assertThat(a.freeAfter).isNull()
        assertThat(a.longestBreak).isEqualTo(at(12)..at(13))
    }

    @Test fun fullyBooked_hasNoBreak() {
        val a = Availability.of(w(day), listOf(ev(7, 0, 21, 45)), earlyMorning)!!
        assertThat(a.freeAfter).isNull()
        assertThat(a.longestBreak).isNull()
        assertThat(a.free).isEqualTo(Duration.ofMinutes(15))
    }

    @Test fun overlappingEvents_countOnce() {
        val a = Availability.of(w(day), listOf(ev(9, 0, 11, 0), ev(10, 0, 12, 0), ev(10, 30, 10, 45)), earlyMorning)!!
        assertThat(a.busy).isEqualTo(Duration.ofHours(3))
    }

    @Test fun today_countsOnlyFromNow() {
        val a = Availability.of(w(day), listOf(ev(9, 0, 11, 0), ev(15, 0, 16, 0)), at(10))!!
        assertThat(a.from).isEqualTo(at(10))
        assertThat(a.busy).isEqualTo(Duration.ofHours(2)) // 10–11 and 15–16
        assertThat(a.freeAfter).isEqualTo(at(16))
    }

    /** Only short gaps between events is not the same as no free time. */
    @Test fun shortGapsOnly_isNotBooked() {
        // 08:00–22:00: 14 cycles of a 35 min event followed by a 25 min gap.
        val events = (0 until 14).map { i -> Busy(at(8).plusSeconds(i * 3600L), at(8).plusSeconds(i * 3600L + 35 * 60)) }
        val a = Availability.of(w(day), events, earlyMorning)!!
        assertThat(a.freeAfter).isNull()
        assertThat(a.longestBreak).isNull()
        assertThat(a.free).isEqualTo(Duration.ofMinutes(350)) // 5 h 50 min
        assertThat(a.summary()).isEqualTo(AvailabilitySummary.ShortBreaks(Duration.ofMinutes(350)))
    }

    @Test fun summary_pickRulesInOrder() {
        assertThat(Availability.of(w(day), emptyList(), earlyMorning)!!.summary()).isEqualTo(AvailabilitySummary.FreeAllDay)
        assertThat(Availability.of(w(day), listOf(ev(9, 0, 10, 0)), earlyMorning)!!.summary())
            .isEqualTo(AvailabilitySummary.FreeAfter(at(10), mostly = false))
        assertThat(Availability.of(w(day), listOf(ev(8, 0, 12, 0), ev(13, 0, 21, 0)), earlyMorning)!!.summary())
            .isEqualTo(AvailabilitySummary.LongestBreak(at(12)..at(13)))
        // Booked means no free time at all (under a minute), not just no long break.
        assertThat(Availability.of(w(day), listOf(ev(7, 0, 23, 0)), earlyMorning)!!.summary()).isEqualTo(AvailabilitySummary.Booked)
        assertThat(Availability.of(w(day), listOf(ev(7, 0, 21, 45)), earlyMorning)!!.summary())
            .isEqualTo(AvailabilitySummary.ShortBreaks(Duration.ofMinutes(15)))
    }

    @Test fun pastWindow_isNull() {
        assertThat(Availability.of(w(day), emptyList(), at(22, 30))).isNull()
        assertThat(Availability.of(w(day.minusDays(1)), emptyList(), at(9))).isNull()
    }

    @Test fun eventsOutsideTheWindowOrCrossingMidnight_areClipped() {
        val crossing = Busy(at(21, 0), at(1, 0, day.plusDays(1)))
        val a = Availability.of(w(day), listOf(ev(6, 0, 7, 30), crossing), earlyMorning)!!
        assertThat(a.busy).isEqualTo(Duration.ofHours(1)) // 21:00–22:00 only
    }

    // ---- Configurable waking hours ----------------------------------------------------------

    @Test fun window_followsTheSchedule_16hForSevenToEleven() {
        val sched = SleepSchedule(7 * 60, 23 * 60)
        val a = Availability.of(sched.awakeWindow(day, zone), emptyList(), earlyMorning)!!
        assertThat(a.window.duration).isEqualTo(Duration.ofHours(16))
        assertThat(a.free).isEqualTo(Duration.ofHours(16))
    }

    @Test fun fourHoursOfNonOverlappingEvents_leaveTwelveHours() {
        val sched = SleepSchedule(7 * 60, 23 * 60)
        val a = Availability.of(sched.awakeWindow(day, zone), listOf(ev(9, 0, 11, 0), ev(14, 0, 16, 0)), earlyMorning)!!
        assertThat(a.busy).isEqualTo(Duration.ofHours(4))
        assertThat(a.free).isEqualTo(Duration.ofHours(12))
    }

    @Test fun thirtyMinuteOverlap_isCountedOnce() {
        val sched = SleepSchedule(7 * 60, 23 * 60)
        val a = Availability.of(sched.awakeWindow(day, zone), listOf(ev(9, 0, 10, 0), ev(9, 30, 10, 30)), earlyMorning)!!
        assertThat(a.busy).isEqualTo(Duration.ofMinutes(90))
        assertThat(a.free).isEqualTo(Duration.ofHours(16) - Duration.ofMinutes(90))
    }

    @Test fun taskBlocksAndEvents_unionNotSum() {
        val sched = SleepSchedule(7 * 60, 23 * 60)
        val a = Availability.of(
            sched.awakeWindow(day, zone), listOf(ev(9, 0, 10, 0)), earlyMorning,
            tasks = listOf(ev(9, 30, 10, 30), ev(18, 0, 18, 30)),
        )!!
        assertThat(a.eventBusy).isEqualTo(Duration.ofHours(1))
        assertThat(a.taskBusy).isEqualTo(Duration.ofMinutes(90))
        assertThat(a.busy).isEqualTo(Duration.ofMinutes(120)) // 9:00–10:30 and 18:00–18:30
    }

    @Test fun estimatedWork_isShownButNeverSubtracted() {
        val sched = SleepSchedule(7 * 60, 23 * 60)
        val a = Availability.of(sched.awakeWindow(day, zone), emptyList(), earlyMorning, estimatedWork = Duration.ofHours(2), unestimatedTasks = 3)!!
        assertThat(a.free).isEqualTo(Duration.ofHours(16))
        assertThat(a.freeAfterEstimates).isEqualTo(Duration.ofHours(14))
        assertThat(a.unestimatedTasks).isEqualTo(3)
        assertThat(a.isFree).isTrue()
    }

    @Test fun bedtimeAfterMidnight_windowRunsIntoTheNextDay_andEventsThereCount() {
        val sched = SleepSchedule(9 * 60, 1 * 60) // 09:00 → 01:00
        val window = sched.awakeWindow(day, zone)
        assertThat(window.end).isEqualTo(at(1, 0, day.plusDays(1)))
        assertThat(window.duration).isEqualTo(Duration.ofHours(16))
        val lateParty = Busy(at(23, 0), at(2, 0, day.plusDays(1)))
        val a = Availability.of(window, listOf(lateParty), earlyMorning)!!
        assertThat(a.busy).isEqualTo(Duration.ofHours(2)) // 23:00–01:00, clipped at bedtime
    }

    @Test fun eventFromTheEveningBefore_countsOnlyInsideTheWindow() {
        val sched = SleepSchedule(7 * 60, 23 * 60)
        val overnight = Busy(at(22, 0, day.minusDays(1)), at(8, 0))
        val a = Availability.of(sched.awakeWindow(day, zone), listOf(overnight), earlyMorning)!!
        assertThat(a.busy).isEqualTo(Duration.ofHours(1)) // 07:00–08:00
    }

    @Test fun dstChange_windowHasItsRealLength() {
        // 25 Oct 2026: clocks go back in Berlin, so 01:00 → 23:00 includes an extra hour.
        val sched = SleepSchedule(1 * 60, 23 * 60)
        val w = sched.awakeWindow(LocalDate.of(2026, 10, 25), zone)
        assertThat(w.duration).isEqualTo(Duration.ofHours(23))
    }
}
