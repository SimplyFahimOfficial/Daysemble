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

    @Test fun noEvents_isFreeForTheWholeWindow() {
        val a = Availability.of(day, emptyList(), earlyMorning, zone)!!
        assertThat(a.isFree).isTrue()
        assertThat(a.free).isEqualTo(Duration.ofHours(14))
        assertThat(a.freeAfter).isNull()
        assertThat(a.longestBreak).isNull()
    }

    @Test fun lastEventEndsEarly_freeAfterItsEnd() {
        val a = Availability.of(day, listOf(ev(9, 15, 9, 30), ev(12, 30, 13, 30)), earlyMorning, zone)!!
        assertThat(a.freeAfter).isEqualTo(at(13, 30))
        assertThat(a.mostly).isFalse()
        assertThat(a.busy).isEqualTo(Duration.ofMinutes(75))
    }

    @Test fun smallEventLater_isMostlyFree() {
        // After 10:00, a 30 min call over 12 h left is 4 %, under the 15 % share.
        val a = Availability.of(day, listOf(ev(9, 0, 10, 0), ev(16, 0, 16, 30)), earlyMorning, zone)!!
        assertThat(a.freeAfter).isEqualTo(at(10))
        assertThat(a.mostly).isTrue()
    }

    @Test fun eveningEvents_noFreeAfter_reportsLongestBreak() {
        val a = Availability.of(day, listOf(ev(8, 0, 12, 0), ev(13, 0, 17, 0), ev(17, 30, 21, 0)), earlyMorning, zone)!!
        assertThat(a.freeAfter).isNull()
        assertThat(a.longestBreak).isEqualTo(at(12)..at(13))
    }

    @Test fun fullyBooked_hasNoBreak() {
        val a = Availability.of(day, listOf(ev(7, 0, 21, 45)), earlyMorning, zone)!!
        assertThat(a.freeAfter).isNull()
        assertThat(a.longestBreak).isNull()
        assertThat(a.free).isEqualTo(Duration.ofMinutes(15))
    }

    @Test fun overlappingEvents_countOnce() {
        val a = Availability.of(day, listOf(ev(9, 0, 11, 0), ev(10, 0, 12, 0), ev(10, 30, 10, 45)), earlyMorning, zone)!!
        assertThat(a.busy).isEqualTo(Duration.ofHours(3))
    }

    @Test fun today_countsOnlyFromNow() {
        val a = Availability.of(day, listOf(ev(9, 0, 11, 0), ev(15, 0, 16, 0)), at(10), zone)!!
        assertThat(a.from).isEqualTo(at(10))
        assertThat(a.busy).isEqualTo(Duration.ofHours(2)) // 10–11 and 15–16
        assertThat(a.freeAfter).isEqualTo(at(16))
    }

    /** Only short gaps between events is not the same as no free time. */
    @Test fun shortGapsOnly_isNotBooked() {
        // 08:00–22:00: 14 cycles of a 35 min event followed by a 25 min gap.
        val events = (0 until 14).map { i -> Busy(at(8).plusSeconds(i * 3600L), at(8).plusSeconds(i * 3600L + 35 * 60)) }
        val a = Availability.of(day, events, earlyMorning, zone)!!
        assertThat(a.freeAfter).isNull()
        assertThat(a.longestBreak).isNull()
        assertThat(a.free).isEqualTo(Duration.ofMinutes(350)) // 5 h 50 min
        assertThat(a.summary()).isEqualTo(AvailabilitySummary.ShortBreaks(Duration.ofMinutes(350)))
    }

    @Test fun summary_pickRulesInOrder() {
        assertThat(Availability.of(day, emptyList(), earlyMorning, zone)!!.summary()).isEqualTo(AvailabilitySummary.FreeAllDay)
        assertThat(Availability.of(day, listOf(ev(9, 0, 10, 0)), earlyMorning, zone)!!.summary())
            .isEqualTo(AvailabilitySummary.FreeAfter(at(10), mostly = false))
        assertThat(Availability.of(day, listOf(ev(8, 0, 12, 0), ev(13, 0, 21, 0)), earlyMorning, zone)!!.summary())
            .isEqualTo(AvailabilitySummary.LongestBreak(at(12)..at(13)))
        // Booked means no free time at all (under a minute), not just no long break.
        assertThat(Availability.of(day, listOf(ev(7, 0, 23, 0)), earlyMorning, zone)!!.summary()).isEqualTo(AvailabilitySummary.Booked)
        assertThat(Availability.of(day, listOf(ev(7, 0, 21, 45)), earlyMorning, zone)!!.summary())
            .isEqualTo(AvailabilitySummary.ShortBreaks(Duration.ofMinutes(15)))
    }

    @Test fun pastWindow_isNull() {
        assertThat(Availability.of(day, emptyList(), at(22, 30), zone)).isNull()
        assertThat(Availability.of(day.minusDays(1), emptyList(), at(9), zone)).isNull()
    }

    @Test fun eventsOutsideTheWindowOrCrossingMidnight_areClipped() {
        val crossing = Busy(at(21, 0), at(1, 0, day.plusDays(1)))
        val a = Availability.of(day, listOf(ev(6, 0, 7, 30), crossing), earlyMorning, zone)!!
        assertThat(a.busy).isEqualTo(Duration.ofHours(1)) // 21:00–22:00 only
    }
}
