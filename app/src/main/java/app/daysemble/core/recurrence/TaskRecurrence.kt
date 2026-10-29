package app.daysemble.core.recurrence

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Repeat rules for tasks. Tasks repeat on dates (a due time, if any, is the same on every
 * occurrence), so this works on [LocalDate]s only. Rules are stored in the RRULE subset of
 * [RRule] (FREQ, INTERVAL, BYDAY for weekly, COUNT, UNTIL as a date), but they're interpreted for
 * to-dos, which differs from calendar events in one place:
 *
 * - Monthly and yearly: occurrence n is the first date (the anchor) plus n × interval months or
 *   years, clamped to the end of a shorter month: the 31st → Feb 28/29 → Mar 31 → Apr 30;
 *   Feb 29 → Feb 28 in common years and Feb 29 again in leap years. (RFC 5545 would skip months
 *   that have no 31st; for a to-do that silently skips months.)
 * - Daily and weekly: as RFC 5545. Weekly rules repeat on their BYDAY days every [RRule.interval]
 *   weeks, counted from the week of the anchor (weeks start on [RRule.weekStart]).
 *
 * The anchor (the series' first due date) is always occurrence 0, even if a weekly rule's days
 * don't include its weekday. COUNT counts occurrences from the anchor; UNTIL is inclusive.
 */
object TaskRecurrence {

    /** Whether [rule] is one tasks can use (the editor only produces these). */
    fun supports(rule: RRule): Boolean = rule.isSupported && rule.byMonthDay.isEmpty() && rule.byMonth == null &&
        rule.byDay.all { it.ordinal == 0 } && (rule.freq == Freq.WEEKLY || rule.byDay.isEmpty()) && rule.untilInstant == null

    /** An occurrence and its 0-based position in the series (the anchor is 0). */
    data class Occurrence(val index: Long, val date: LocalDate)

    /**
     * The first occurrence that is after [after] and not before [notBefore], or null when the
     * series has ended by then (COUNT reached or past UNTIL).
     */
    fun next(rule: RRule, anchor: LocalDate, after: LocalDate, notBefore: LocalDate? = null): LocalDate? =
        firstOnOrAfter(rule, anchor, maxOf(after.plusDays(1), notBefore ?: LocalDate.MIN))?.date

    /** The first occurrence on or after [target], with its index, or null when the series has ended by then. */
    fun firstOnOrAfter(rule: RRule, anchor: LocalDate, target: LocalDate): Occurrence? {
        val o = if (!target.isAfter(anchor)) Occurrence(0, anchor) else when (rule.freq) {
            Freq.WEEKLY -> weekly(rule, anchor, target)
            else -> stepped(rule, anchor, target)
        }
        return o.takeIf { valid(rule, it) }
    }

    /** The occurrences from the anchor, at most [limit] (for previews and tests). */
    fun occurrences(rule: RRule, anchor: LocalDate, limit: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>(limit)
        var o = firstOnOrAfter(rule, anchor, anchor)
        while (o != null && out.size < limit) {
            out += o.date
            o = firstOnOrAfter(rule, anchor, o.date.plusDays(1))
        }
        return out
    }

    /**
     * The anchor for a monthly or yearly series restarting at [date] (its rule changed, its date
     * didn't), keeping the day of month of [previousAnchor] that [date] was clamped from: a "31st"
     * series changed on its 28 Feb occurrence stays on the 31st (and a 29 Feb one on 29 Feb) instead
     * of moving to the 28th for good. Returns the anchor and how many occurrences come before [date]
     * (it is then an earlier, unclamped date that steps to [date]); otherwise [date] itself and 0.
     */
    fun restartAnchor(rule: RRule, date: LocalDate, previousAnchor: LocalDate): Pair<LocalDate, Int> {
        val day = previousAnchor.dayOfMonth
        val clamped = day > date.dayOfMonth && date.dayOfMonth == date.lengthOfMonth()
        if (!clamped) return date to 0
        val step = rule.interval.coerceAtLeast(1).toLong()
        for (k in 1..12) {
            val month = when (rule.freq) {
                Freq.MONTHLY -> YearMonth.from(date).minusMonths(k * step)
                Freq.YEARLY -> if (previousAnchor.month == date.month) YearMonth.from(date).minusYears(k * step) else return date to 0
                else -> return date to 0
            }
            if (month.lengthOfMonth() < day) continue
            val anchor = month.atDay(day)
            val back = if (rule.freq == Freq.MONTHLY) anchor.plusMonths(k * step) else anchor.plusYears(k * step)
            return if (back == date) anchor to k else date to 0
        }
        return date to 0
    }

    /** Whether [date] is an occurrence of the series, and if so its index. */
    fun indexOf(rule: RRule, anchor: LocalDate, date: LocalDate): Long? =
        firstOnOrAfter(rule, anchor, date)?.takeIf { it.date == date }?.index

    private fun valid(rule: RRule, o: Occurrence): Boolean =
        (rule.count == null || o.index < rule.count) && (rule.untilDate == null || !o.date.isAfter(rule.untilDate))

    /** Daily, monthly and yearly: occurrence k = anchor + k × interval units (clamped for months/years). */
    private fun stepped(rule: RRule, anchor: LocalDate, target: LocalDate): Occurrence {
        val step = rule.interval.coerceAtLeast(1).toLong()
        fun at(k: Long): LocalDate = when (rule.freq) {
            Freq.DAILY -> anchor.plusDays(k * step)
            Freq.MONTHLY -> anchor.plusMonths(k * step)
            Freq.YEARLY -> anchor.plusYears(k * step)
            Freq.WEEKLY -> error("weekly")
        }
        val unit = when (rule.freq) {
            Freq.DAILY -> ChronoUnit.DAYS
            Freq.MONTHLY -> ChronoUnit.MONTHS
            else -> ChronoUnit.YEARS
        }
        // Jump close to the target, then step: dates only grow with k (clamping keeps them in order).
        var k = (unit.between(anchor, target) / step - 1).coerceAtLeast(1)
        while (k > 1 && !at(k - 1).isBefore(target)) k--
        while (at(k).isBefore(target)) k++
        return Occurrence(k, at(k))
    }

    /** Weekly: the BYDAY days (or the anchor's weekday) every interval weeks from the anchor's week. */
    private fun weekly(rule: RRule, anchor: LocalDate, target: LocalDate): Occurrence {
        val step = rule.interval.coerceAtLeast(1).toLong()
        val ws = rule.weekStart
        fun offset(d: DayOfWeek) = ((d.value - ws.value) + 7) % 7
        val days = rule.byDay.map { it.day }.ifEmpty { listOf(anchor.dayOfWeek) }.distinct().sortedBy(::offset)
        val week0 = anchor.minusDays(offset(anchor.dayOfWeek).toLong())
        fun datesOf(w: Long) = days.map { week0.plusWeeks(w * step).plusDays(offset(it).toLong()) }
        // Week 0 holds only the days after the anchor (the anchor itself is occurrence 0).
        val first = datesOf(0).filter { it.isAfter(anchor) }
        first.indexOfFirst { !it.isBefore(target) }.takeIf { it >= 0 }?.let { return Occurrence(1L + it, first[it]) }
        var w = (ChronoUnit.DAYS.between(week0, target) / 7 / step).coerceAtLeast(1)
        while (true) {
            val dates = datesOf(w)
            val j = dates.indexOfFirst { !it.isBefore(target) }
            if (j >= 0) return Occurrence(1L + first.size + (w - 1) * days.size + j, dates[j])
            w++
        }
    }
}
