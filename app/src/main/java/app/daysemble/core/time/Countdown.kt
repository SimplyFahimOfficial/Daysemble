package app.daysemble.core.time

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * The time between now and a deadline, split into calendar units.
 *
 * Years, months and days are counted on the calendar in the user's zone (from the earlier moment,
 * add whole years while that doesn't pass the later one, then whole months, then whole days), so a
 * month is a real month: 31 Jan → 28 Feb is 28 days, not "1 month"; 15 Jan → 15 Mar is 2 months
 * whatever the month lengths. Hours, minutes and seconds are the exact time left after the last
 * whole calendar day, so the countdown always runs at one second per second and never jumps at a
 * clock change. On the day the clocks go back that remainder can reach 24 h ("24 h 30 min" from
 * Saturday noon to Sunday 11:30): that day really is 25 hours long.
 */
data class CountdownParts(
    val years: Long,
    val months: Long,
    val days: Long,
    val hours: Long,
    val minutes: Long,
    val seconds: Long,
) {
    val totalIsZero: Boolean get() = years == 0L && months == 0L && days == 0L && hours == 0L && minutes == 0L && seconds == 0L
}

/** What a task's countdown shows. */
sealed interface CountdownState {
    /** No due date: a countdown needs one. */
    data object NoDeadline : CountdownState
    data object Completed : CountdownState
    data class Remaining(val parts: CountdownParts) : CountdownState
    /** Past the deadline: how long ago it was (never a negative countdown). */
    data class Overdue(val parts: CountdownParts) : CountdownState
}

object Countdown {
    /** Calendar-aware split of the time between [from] and [to] (in either order) in [zone]. */
    fun parts(from: Instant, to: Instant, zone: ZoneId): CountdownParts {
        val (a, b) = if (from.isAfter(to)) to to from else from to to
        val start = a.atZone(zone)
        val end = b.atZone(zone)
        val years = ChronoUnit.YEARS.between(start, end)
        val afterYears = start.plusYears(years)
        val months = ChronoUnit.MONTHS.between(afterYears, end)
        val afterMonths = afterYears.plusMonths(months)
        var days = ChronoUnit.DAYS.between(afterMonths, end)
        var cursor = afterMonths.plusDays(days)
        // A day added across a daylight-saving gap can land past the end: take one day back.
        while (days > 0 && cursor.isAfter(end)) { days--; cursor = afterMonths.plusDays(days) }
        val rest = Duration.between(cursor.toInstant(), end.toInstant()).coerceAtLeast(Duration.ZERO)
        // (Duration.toMinutesPart/toSecondsPart would need API 31; minSdk is 26.)
        return CountdownParts(years, months, days, rest.toHours(), rest.toMinutes() % 60, rest.seconds % 60)
    }

    /** The countdown for a deadline: remaining before it, overdue after it (the deadline itself counts as due). */
    fun state(deadline: Instant?, completed: Boolean, now: Instant, zone: ZoneId): CountdownState = when {
        completed -> CountdownState.Completed
        deadline == null -> CountdownState.NoDeadline
        now.isBefore(deadline) -> CountdownState.Remaining(parts(now, deadline, zone))
        else -> CountdownState.Overdue(parts(deadline, now, zone))
    }

    /** Milliseconds until the next whole second of [now], so a ticking display changes on the second. */
    fun msToNextSecond(now: Instant): Long = 1_000L - (now.toEpochMilli() % 1_000L)

    private fun Duration.coerceAtLeast(min: Duration) = if (this < min) min else this

    /** [ZonedDateTime] helper for tests and callers that already have one. */
    fun parts(from: ZonedDateTime, to: ZonedDateTime): CountdownParts = parts(from.toInstant(), to.toInstant(), from.zone)
}
