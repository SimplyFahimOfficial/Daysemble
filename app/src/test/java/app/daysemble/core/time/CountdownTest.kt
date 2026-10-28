package app.daysemble.core.time

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class CountdownTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val utc = ZoneId.of("UTC")
    private fun at(s: String, z: ZoneId = berlin): Instant = LocalDateTime.parse(s).atZone(z).toInstant()
    private fun p(y: Long, mo: Long, d: Long, h: Long, mi: Long, s: Long) = CountdownParts(y, mo, d, h, mi, s)

    @Test fun mixedUnits() {
        assertThat(Countdown.parts(at("2026-10-27T10:00:00"), at("2027-01-01T13:12:45"), berlin)).isEqualTo(p(0, 2, 5, 3, 12, 45))
    }

    @Test fun monthsAreCalendarMonths_notThirtyDays() {
        // 15 Jan → 15 Mar is 2 months, though only 59 days apart (and 60 in a leap year).
        assertThat(Countdown.parts(at("2027-01-15T09:00"), at("2027-03-15T09:00"), berlin)).isEqualTo(p(0, 2, 0, 0, 0, 0))
        assertThat(Countdown.parts(at("2028-01-15T09:00"), at("2028-03-15T09:00"), berlin)).isEqualTo(p(0, 2, 0, 0, 0, 0))
    }

    @Test fun monthEnd_isNotAFullMonth() {
        // 31 Jan → 28 Feb: no "28 February 31st" exists, so it's 28 days, not one month.
        assertThat(Countdown.parts(at("2027-01-31T12:00"), at("2027-02-28T12:00"), berlin)).isEqualTo(p(0, 0, 28, 0, 0, 0))
        // 31 Jan → 31 Mar is two months.
        assertThat(Countdown.parts(at("2027-01-31T12:00"), at("2027-03-31T12:00"), berlin)).isEqualTo(p(0, 2, 0, 0, 0, 0))
        // 31 Jan → 30 Apr: two months to 31 Mar, then 30 days.
        assertThat(Countdown.parts(at("2027-01-31T12:00"), at("2027-04-30T12:00"), berlin)).isEqualTo(p(0, 2, 30, 0, 0, 0))
    }

    @Test fun leapYears() {
        // 29 Feb 2028 → 28 Feb 2029: not a full year (there's no 29 Feb 2029).
        assertThat(Countdown.parts(at("2028-02-29T00:00"), at("2029-02-28T00:00"), berlin)).isEqualTo(p(0, 11, 30, 0, 0, 0))
        // 29 Feb 2028 → 1 Mar 2029: one year (to 28 Feb 2029, clamped) and a day.
        assertThat(Countdown.parts(at("2028-02-29T00:00"), at("2029-03-01T00:00"), berlin)).isEqualTo(p(1, 0, 1, 0, 0, 0))
        // A leap day inside the span is just another day.
        assertThat(Countdown.parts(at("2028-02-28T08:00"), at("2028-03-01T08:00"), berlin)).isEqualTo(p(0, 0, 2, 0, 0, 0))
    }

    @Test fun daylightSaving_aDayIsADay() {
        // Berlin springs forward on 28 Mar 2027 (23 h day) and falls back on 31 Oct 2027 (25 h day).
        assertThat(Countdown.parts(at("2027-03-27T12:00"), at("2027-03-28T12:00"), berlin)).isEqualTo(p(0, 0, 1, 0, 0, 0))
        assertThat(Countdown.parts(at("2027-10-30T12:00"), at("2027-10-31T12:00"), berlin)).isEqualTo(p(0, 0, 1, 0, 0, 0))
        // Exact time across the change: 01:00 → 04:00 on spring-forward day is 2 hours.
        assertThat(Countdown.parts(at("2027-03-28T01:00"), at("2027-03-28T04:00"), berlin)).isEqualTo(p(0, 0, 0, 2, 0, 0))
        // Less than a calendar day across the fall-back is still exact time: Saturday noon to
        // Sunday 11:30 is 24 h 30 min (the countdown never jumps an hour when the clocks change).
        assertThat(Countdown.parts(at("2027-10-30T12:00"), at("2027-10-31T11:30"), berlin)).isEqualTo(p(0, 0, 0, 24, 30, 0))
    }

    @Test fun daylightSaving_dayStepIntoTheGap_isTakenBack() {
        // 02:30 on 27 Mar plus one day falls in the gap (02:00–03:00 doesn't exist on 28 Mar) and
        // resolves to 03:30, past the end at 03:10: no whole day fits, the rest is exact time.
        val from = at("2027-03-27T02:30")
        val to = Instant.parse("2027-03-28T01:10:00Z") // 03:10 local, after the change
        val r = Countdown.parts(from, to, berlin)
        assertThat(r.days).isEqualTo(0)
        assertThat(r.hours * 3600 + r.minutes * 60 + r.seconds).isEqualTo((to.epochSecond - from.epochSecond))
    }

    @Test fun deadlineAtMidnight() {
        val midnight = at("2026-11-01T00:00")
        assertThat(Countdown.parts(at("2026-10-31T23:59:30"), midnight, berlin)).isEqualTo(p(0, 0, 0, 0, 0, 30))
        assertThat(Countdown.state(midnight, false, midnight, berlin)).isEqualTo(CountdownState.Overdue(p(0, 0, 0, 0, 0, 0)))
    }

    @Test fun overdue_isPositiveTimeSince() {
        val deadline = at("2026-10-25T18:00")
        val s = Countdown.state(deadline, false, at("2026-10-27T19:30:05"), berlin)
        assertThat(s).isEqualTo(CountdownState.Overdue(p(0, 0, 2, 1, 30, 5)))
    }

    @Test fun states() {
        val now = at("2026-10-27T10:00")
        assertThat(Countdown.state(null, false, now, berlin)).isEqualTo(CountdownState.NoDeadline)
        assertThat(Countdown.state(at("2026-10-28T10:00"), true, now, berlin)).isEqualTo(CountdownState.Completed)
        assertThat(Countdown.state(at("2026-10-28T10:00"), false, now, berlin)).isEqualTo(CountdownState.Remaining(p(0, 0, 1, 0, 0, 0)))
    }

    @Test fun zoneMatters_forCalendarUnits() {
        // The same two instants: 31 Jan → 28 Feb in UTC (28 days), 1 Feb → 1 Mar in Berlin (1 month).
        // Calendar units are counted in the given zone, not the JVM default.
        val a = Instant.parse("2027-01-31T23:30:00Z") // 1 Feb 00:30 in Berlin
        val b = Instant.parse("2027-02-28T23:30:00Z") // 1 Mar 00:30 in Berlin
        assertThat(Countdown.parts(a, b, utc)).isEqualTo(p(0, 0, 28, 0, 0, 0)) // 31 Jan → 28 Feb
        assertThat(Countdown.parts(a, b, berlin)).isEqualTo(p(0, 1, 0, 0, 0, 0)) // 1 Feb → 1 Mar
    }

    @Test fun msToNextSecond() {
        assertThat(Countdown.msToNextSecond(Instant.ofEpochMilli(10_250))).isEqualTo(750)
        assertThat(Countdown.msToNextSecond(Instant.ofEpochMilli(10_000))).isEqualTo(1_000)
    }
}
