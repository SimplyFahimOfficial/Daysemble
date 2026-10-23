package app.daysemble.core.time

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** What the countdown chip should say about an event relative to now. Formatting lives in the UI. */
sealed interface Relative {
    /** Starts within a minute (or just started under a minute ago). */
    data object Now : Relative
    data class StartsIn(val minutes: Long) : Relative
    /** Starts on a later calendar day; [days] = calendar days away (1 = tomorrow). */
    data class StartsInDays(val days: Long) : Relative
    data class EndsIn(val minutes: Long) : Relative
    data object Ended : Relative
    /** All-day item happening today. */
    data object AllDayToday : Relative
}

object RelativeTime {

    /**
     * Rounds minutes *up* for upcoming starts, so "in 1 min" is shown until the start minute
     * and a countdown never claims 0 minutes while the event hasn't begun.
     */
    fun of(start: Instant, end: Instant, isAllDay: Boolean, now: Instant, zone: ZoneId): Relative {
        val today = now.atZone(zone).toLocalDate()
        if (isAllDay) {
            val s = start.atZone(zone).toLocalDate()
            val e = end.atZone(zone).toLocalDate() // exclusive
            return when {
                !today.isBefore(e) -> Relative.Ended
                !today.isBefore(s) -> Relative.AllDayToday
                else -> Relative.StartsInDays(ChronoUnit.DAYS.between(today, s))
            }
        }
        if (now >= end && end > start) return Relative.Ended
        if (now >= start) {
            if (end <= start) return if (Duration.between(start, now) < Duration.ofMinutes(1)) Relative.Now else Relative.Ended
            if (Duration.between(start, now) < Duration.ofMinutes(1)) return Relative.Now
            return Relative.EndsIn(ceilMinutes(Duration.between(now, end)))
        }
        val until = Duration.between(now, start)
        val startDay: LocalDate = start.atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, startDay)
        return when {
            until < Duration.ofMinutes(1) -> Relative.Now
            // Stay in minutes/hours until it's on a later calendar day and more than 12 hours away.
            days >= 1 && until > Duration.ofHours(12) -> Relative.StartsInDays(days)
            else -> Relative.StartsIn(ceilMinutes(until))
        }
    }

    private fun ceilMinutes(d: Duration): Long = (d.seconds + 59) / 60

    /** Milliseconds until the displayed text could next change (to schedule the next refresh). */
    fun nextTickDelayMs(now: Instant): Long = 60_000 - (now.toEpochMilli() % 60_000)
}
