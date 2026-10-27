package app.daysemble.core.time

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * How free a day is, computed only from the calendar events of that day.
 *
 * Assumptions (shown to the user next to the numbers):
 * - Only the planning window [DAY_START]–[DAY_END] (local time) counts. Time before and after it
 *   is neither free nor busy.
 * - Timed events are busy time; overlapping events count once. All-day events (birthdays,
 *   holidays, out-of-office markers) don't block time. Tasks don't either: they have no duration.
 * - For today, only the rest of the window from now counts.
 */
data class DayAvailability(
    /** Start of the counted window (the window start, or now for today). */
    val from: Instant,
    val until: Instant,
    val busy: Duration,
    val free: Duration,
    /**
     * The earliest event end after which the day is free ([mostly] = false) or nearly free
     * ([mostly] = true: the events still to come total at most [MOSTLY_FREE_MAX] and at most
     * [MOSTLY_FREE_SHARE] of what is left). At least [MIN_FREE_STRETCH] of the window must remain
     * after it, otherwise null.
     */
    val freeAfter: Instant?,
    val mostly: Boolean,
    /** Longest gap between events in the window (at least 30 min), when there is no [freeAfter]. */
    val longestBreak: ClosedRange<Instant>?,
) {
    val isFree: Boolean get() = busy.isZero

    /** The one statement the briefing makes about this day's free time. */
    fun summary(): AvailabilitySummary = when {
        isFree -> AvailabilitySummary.FreeAllDay
        freeAfter != null -> AvailabilitySummary.FreeAfter(freeAfter, mostly)
        longestBreak != null -> AvailabilitySummary.LongestBreak(longestBreak)
        // No long break doesn't mean no free time: short gaps can add up to hours.
        free >= Duration.ofMinutes(1) -> AvailabilitySummary.ShortBreaks(free)
        else -> AvailabilitySummary.Booked
    }
}

/** What can truthfully be said about a day's free time, in order of preference. */
sealed interface AvailabilitySummary {
    /** No timed events in the (rest of the) window. */
    data object FreeAllDay : AvailabilitySummary
    data class FreeAfter(val at: Instant, val mostly: Boolean) : AvailabilitySummary
    data class LongestBreak(val range: ClosedRange<Instant>) : AvailabilitySummary
    /** Free time exists, but only in gaps shorter than 30 min. */
    data class ShortBreaks(val total: Duration) : AvailabilitySummary
    /** Under a minute free in the (rest of the) window. */
    data object Booked : AvailabilitySummary
}

object Availability {
    val DAY_START: LocalTime = LocalTime.of(8, 0)
    val DAY_END: LocalTime = LocalTime.of(22, 0)
    val MIN_FREE_STRETCH: Duration = Duration.ofHours(2)
    const val MOSTLY_FREE_SHARE = 0.15
    val MOSTLY_FREE_MAX: Duration = Duration.ofMinutes(45)
    private val MIN_BREAK: Duration = Duration.ofMinutes(30)

    /** One event as busy time. */
    data class Busy(val start: Instant, val end: Instant)

    /**
     * Availability of [date] in [zone]. Returns null when nothing of the window is left (a past
     * date, or today after [DAY_END]).
     */
    fun of(date: LocalDate, events: List<Busy>, now: Instant, zone: ZoneId): DayAvailability? {
        val windowStart = date.atTime(DAY_START).atZone(zone).toInstant()
        val until = date.atTime(DAY_END).atZone(zone).toInstant()
        val from = maxOf(windowStart, now)
        if (!from.isBefore(until)) return null

        val merged = merge(events.mapNotNull { e ->
            val s = maxOf(e.start, from)
            val t = minOf(e.end, until)
            if (s.isBefore(t)) Busy(s, t) else null
        })
        val busy = merged.fold(Duration.ZERO) { acc, b -> acc + Duration.between(b.start, b.end) }
        val free = Duration.between(from, until) - busy

        var freeAfter: Instant? = null
        var mostly = false
        if (merged.isNotEmpty()) {
            for ((i, b) in merged.withIndex()) {
                val rest = Duration.between(b.end, until)
                if (rest < MIN_FREE_STRETCH) break
                val busyAfter = merged.drop(i + 1).fold(Duration.ZERO) { acc, x -> acc + Duration.between(x.start, x.end) }
                if (busyAfter <= MOSTLY_FREE_MAX && busyAfter.toMillis() <= rest.toMillis() * MOSTLY_FREE_SHARE) {
                    freeAfter = b.end
                    mostly = !busyAfter.isZero
                    break
                }
            }
        }
        val longestBreak = if (freeAfter == null && merged.isNotEmpty()) {
            val gaps = buildList {
                add(from..merged.first().start)
                merged.zipWithNext { a, b -> add(a.end..b.start) }
                add(merged.last().end..until)
            }
            gaps.maxByOrNull { Duration.between(it.start, it.endInclusive) }
                ?.takeIf { Duration.between(it.start, it.endInclusive) >= MIN_BREAK }
        } else null

        return DayAvailability(from, until, busy, free, freeAfter, mostly, longestBreak)
    }

    private fun merge(intervals: List<Busy>): List<Busy> {
        val sorted = intervals.sortedBy { it.start }
        val out = ArrayList<Busy>(sorted.size)
        for (b in sorted) {
            val last = out.lastOrNull()
            if (last != null && !b.start.isAfter(last.end)) out[out.lastIndex] = Busy(last.start, maxOf(last.end, b.end))
            else out += b
        }
        return out
    }
}
