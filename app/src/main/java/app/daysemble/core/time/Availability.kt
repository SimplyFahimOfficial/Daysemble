package app.daysemble.core.time

import java.time.Duration
import java.time.Instant

/**
 * How free a day is inside the user's waking hours.
 *
 * Rules (the Today breakdown shows them next to the numbers):
 * - Only the waking window counts: the configured wake-up → bedtime of that day (Settings → Daily
 *   schedule & sleep). Time outside it is neither free nor busy. For today, only the rest of the
 *   window from now counts.
 * - Busy time is the union of the timed events and the scheduled task blocks (a task with a due
 *   time and a duration) clipped to the window: overlapping intervals count once, and an event
 *   that starts the evening before or runs past bedtime counts only for its part in the window.
 *   All-day events (birthdays, holidays, out-of-office) don't block time.
 * - A task without a time slot never takes free time. If it has a duration it is [estimatedWork]:
 *   shown next to the free time ("about 4 h after estimated work"), never placed on the clock.
 *   Tasks without a duration only count in [unestimatedTasks].
 */
data class DayAvailability(
    /** The whole waking window of the day. */
    val window: AwakeWindow,
    /** Start of the counted part of [window] (its start, or now for today). */
    val from: Instant,
    val until: Instant,
    /** Union of event intervals in the counted part. */
    val eventBusy: Duration,
    /** Union of scheduled task blocks in the counted part (may overlap events; see [busy]). */
    val taskBusy: Duration,
    /** Union of everything above: each minute once. */
    val busy: Duration,
    val free: Duration,
    /** Durations of open tasks on the day that have no time slot (not subtracted from [free]). */
    val estimatedWork: Duration = Duration.ZERO,
    /** Open tasks on the day with neither a time slot nor a duration. */
    val unestimatedTasks: Int = 0,
    /**
     * The earliest busy end after which the day is free ([mostly] = false) or nearly free
     * ([mostly] = true: what still comes totals at most [Availability.MOSTLY_FREE_MAX] and at most
     * [Availability.MOSTLY_FREE_SHARE] of what is left). At least [Availability.MIN_FREE_STRETCH] of
     * the window must remain after it, otherwise null.
     */
    val freeAfter: Instant?,
    val mostly: Boolean,
    /** Longest gap between busy blocks in the window (at least 30 min), when there is no [freeAfter]. */
    val longestBreak: ClosedRange<Instant>?,
) {
    val isFree: Boolean get() = busy.isZero

    /** Only the rest of today's window is counted (now is inside it). */
    val countsFromNow: Boolean get() = from.isAfter(window.start)

    /** The counted span: the whole window, or what is left of it today. */
    val counted: Duration get() = Duration.between(from, until)

    /** Free time once the estimated (unplaced) task work is done; never negative. */
    val freeAfterEstimates: Duration get() = (free - estimatedWork).let { if (it.isNegative) Duration.ZERO else it }

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
    /** No busy time in the (rest of the) window. */
    data object FreeAllDay : AvailabilitySummary
    data class FreeAfter(val at: Instant, val mostly: Boolean) : AvailabilitySummary
    data class LongestBreak(val range: ClosedRange<Instant>) : AvailabilitySummary
    /** Free time exists, but only in gaps shorter than 30 min. */
    data class ShortBreaks(val total: Duration) : AvailabilitySummary
    /** Under a minute free in the (rest of the) window. */
    data object Booked : AvailabilitySummary
}

object Availability {
    val MIN_FREE_STRETCH: Duration = Duration.ofHours(2)
    const val MOSTLY_FREE_SHARE = 0.15
    val MOSTLY_FREE_MAX: Duration = Duration.ofMinutes(45)
    private val MIN_BREAK: Duration = Duration.ofMinutes(30)

    /** One busy interval (an event, or a scheduled task block). End is exclusive. */
    data class Busy(val start: Instant, val end: Instant)

    /**
     * Availability inside [window]. [events] and [tasks] are busy intervals (any length, any day;
     * they are clipped). Returns null when nothing of the window is left at [now] (a past day, or
     * today after bedtime).
     */
    fun of(
        window: AwakeWindow,
        events: List<Busy>,
        now: Instant,
        tasks: List<Busy> = emptyList(),
        estimatedWork: Duration = Duration.ZERO,
        unestimatedTasks: Int = 0,
    ): DayAvailability? {
        val until = window.end
        val from = maxOf(window.start, now)
        if (!from.isBefore(until)) return null

        fun clip(list: List<Busy>) = list.mapNotNull { e ->
            val s = maxOf(e.start, from)
            val t = minOf(e.end, until)
            if (s.isBefore(t)) Busy(s, t) else null
        }
        val eventsIn = merge(clip(events))
        val tasksIn = merge(clip(tasks))
        val merged = merge(eventsIn + tasksIn)
        val busy = total(merged)
        val free = Duration.between(from, until) - busy

        val after = freeAfter(merged, until)
        val freeAfter = after?.first
        val mostly = after?.second ?: false
        val longestBreak = if (freeAfter == null && merged.isNotEmpty()) {
            val gaps = buildList {
                add(from..merged.first().start)
                merged.zipWithNext { a, b -> add(a.end..b.start) }
                add(merged.last().end..until)
            }
            gaps.maxByOrNull { Duration.between(it.start, it.endInclusive) }
                ?.takeIf { Duration.between(it.start, it.endInclusive) >= MIN_BREAK }
        } else null

        return DayAvailability(
            window = window, from = from, until = until,
            eventBusy = total(eventsIn), taskBusy = total(tasksIn), busy = busy, free = free,
            estimatedWork = if (estimatedWork.isNegative) Duration.ZERO else estimatedWork,
            unestimatedTasks = unestimatedTasks.coerceAtLeast(0),
            freeAfter = freeAfter, mostly = mostly, longestBreak = longestBreak,
        )
    }

    /**
     * The earliest busy end after which the rest of the window (at least [MIN_FREE_STRETCH]) is free
     * or nearly free, and whether "nearly" (some short busy time still follows). Busy ends are in
     * order, so once less than the stretch is left, no later one qualifies either.
     */
    private fun freeAfter(merged: List<Busy>, until: Instant): Pair<Instant, Boolean>? =
        merged.indices.asSequence()
            .takeWhile { i -> Duration.between(merged[i].end, until) >= MIN_FREE_STRETCH }
            .map { i -> i to total(merged.drop(i + 1)) }
            .firstOrNull { (i, busyAfter) ->
                val rest = Duration.between(merged[i].end, until)
                busyAfter <= MOSTLY_FREE_MAX && busyAfter.toMillis() <= rest.toMillis() * MOSTLY_FREE_SHARE
            }
            ?.let { (i, busyAfter) -> merged[i].end to !busyAfter.isZero }

    /** Sorted, non-overlapping union of [intervals] (touching intervals join). */
    fun merge(intervals: List<Busy>): List<Busy> {
        val sorted = intervals.filter { it.start.isBefore(it.end) }.sortedBy { it.start }
        val out = ArrayList<Busy>(sorted.size)
        for (b in sorted) {
            val last = out.lastOrNull()
            if (last != null && !b.start.isAfter(last.end)) out[out.lastIndex] = Busy(last.start, maxOf(last.end, b.end))
            else out += b
        }
        return out
    }

    private fun total(merged: List<Busy>): Duration = merged.fold(Duration.ZERO) { acc, b -> acc + Duration.between(b.start, b.end) }
}
