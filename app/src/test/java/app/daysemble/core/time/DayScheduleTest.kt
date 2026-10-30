package app.daysemble.core.time

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

class DayScheduleTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val tue = LocalDate.of(2026, 10, 27)

    @Test fun elevenPmToSevenAm_isEightHoursOfSleep_notNegative() {
        val s = SleepSchedule(7 * 60, 23 * 60)
        assertThat(s.sleepMinutes).isEqualTo(8 * 60)
        assertThat(s.awakeMinutes).isEqualTo(16 * 60)
        assertThat(s.bedAfterMidnight).isFalse()
    }

    @Test fun bedtimeAfterMidnight_isTheNextMorning() {
        val s = SleepSchedule(8 * 60, 30) // up 08:00, bed 00:30
        assertThat(s.bedAfterMidnight).isTrue()
        assertThat(s.sleepMinutes).isEqualTo(7 * 60 + 30)
        val w = s.awakeWindow(tue, zone)
        assertThat(w.start).isEqualTo(tue.atTime(8, 0).atZone(zone).toInstant())
        assertThat(w.end).isEqualTo(tue.plusDays(1).atTime(0, 30).atZone(zone).toInstant())
        assertThat(w.duration).isEqualTo(Duration.ofMinutes(16 * 60 + 30))
    }

    @Test fun scheduledSleep_usesYesterdaysBedtimeAndTodaysWake() {
        val prefs = DaySchedulePrefs(
            default = SleepSchedule(7 * 60, 23 * 60),
            perWeekday = true,
            overrides = mapOf(DayOfWeek.SATURDAY to SleepSchedule(9 * 60, 1 * 60)), // Sat: up 9, bed 1 (Sun morning)
        )
        // Night Mon→Tue: 23:00 → 07:00.
        assertThat(prefs.scheduledSleepBefore(tue, zone)).isEqualTo(Duration.ofHours(8))
        // Night Fri→Sat: Friday's 23:00 → Saturday's 09:00.
        val sat = LocalDate.of(2026, 10, 31)
        assertThat(prefs.scheduledSleepBefore(sat, zone)).isEqualTo(Duration.ofHours(10))
        // Night Sat→Sun: Saturday's bedtime is 01:00 Sunday → Sunday's default 07:00.
        assertThat(prefs.scheduledSleepBefore(sat.plusDays(1), zone)).isEqualTo(Duration.ofHours(6))
    }

    @Test fun weekdayOverrides_onlyApplyWhenTurnedOn() {
        val o = mapOf(DayOfWeek.TUESDAY to SleepSchedule(6 * 60, 22 * 60))
        assertThat(DaySchedulePrefs(perWeekday = false, overrides = o).forDate(tue)).isEqualTo(SleepSchedule.DEFAULT)
        assertThat(DaySchedulePrefs(perWeekday = true, overrides = o).forDate(tue)).isEqualTo(SleepSchedule(6 * 60, 22 * 60))
        assertThat(DaySchedulePrefs(perWeekday = true, overrides = o).forDate(tue.plusDays(1))).isEqualTo(SleepSchedule.DEFAULT)
    }

    @Test fun overrides_roundTrip_andIgnoreGarbage() {
        val o = mapOf(DayOfWeek.MONDAY to SleepSchedule(420, 1380), DayOfWeek.SATURDAY to SleepSchedule(540, 60))
        val raw = DaySchedulePrefs.encodeOverrides(o)
        assertThat(raw).isEqualTo("1=420-1380;6=540-60")
        assertThat(DaySchedulePrefs.decodeOverrides(raw)).isEqualTo(o)
        assertThat(DaySchedulePrefs.decodeOverrides("x;9=1-2;2=600-600;3=abc;4=420-1380")).containsExactly(DayOfWeek.THURSDAY, SleepSchedule(420, 1380))
        assertThat(DaySchedulePrefs.decodeOverrides(null)).isEmpty()
    }

    @Test fun springForward_shortensTheNight() {
        // 29 Mar 2026: clocks go forward 02:00 → 03:00 in Berlin.
        val prefs = DaySchedulePrefs(default = SleepSchedule(7 * 60, 23 * 60))
        assertThat(prefs.scheduledSleepBefore(LocalDate.of(2026, 3, 29), zone)).isEqualTo(Duration.ofHours(7))
    }
}
