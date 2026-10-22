package app.daysemble.core.recurrence

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Expands a recurrence rule into occurrence *start dates* (date-level), which callers combine with
 * the series' wall-clock start time in the series' own zone. Working at the date level keeps DST
 * correct: "09:00 every Monday in Berlin" stays 09:00 local across the switch.
 *
 * COUNT counts every generated occurrence (before EXDATE removal), per RFC 5545.
 */
object RecurrenceExpander {

    /** Hard limits so a malformed rule can never hang the UI. */
    const val MAX_ITERATIONS = 200_000
    const val MAX_RESULTS = 1_000

    /**
     * Occurrence dates of the series starting on [dtStart], whose dates fall in
     * [fromDate, toDate] (inclusive), honouring COUNT and UNTIL.
     *
     * @param untilCheck returns false once an occurrence date is past the rule's UNTIL.
     */
    fun dates(
        rule: RRule,
        dtStart: LocalDate,
        fromDate: LocalDate,
        toDate: LocalDate,
        untilCheck: (LocalDate) -> Boolean = { d -> rule.untilDate == null || !d.isAfter(rule.untilDate) },
    ): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var generated = 0
        var iterations = 0
        val seq = candidates(rule, dtStart)
        for (d in seq) {
            if (++iterations > MAX_ITERATIONS) break
            if (d.isBefore(dtStart)) continue
            if (!untilCheck(d)) break
            generated++
            if (rule.count != null && generated > rule.count) break
            if (d.isAfter(toDate)) break
            if (!d.isBefore(fromDate)) {
                out += d
                if (out.size >= MAX_RESULTS) break
            }
        }
        return out
    }

    /** Lazily generates candidate dates in ascending order. */
    private fun candidates(rule: RRule, start: LocalDate): Sequence<LocalDate> = when (rule.freq) {
        Freq.DAILY -> generateSequence(start) { it.plusDays(rule.interval.toLong()) }

        Freq.WEEKLY -> {
            val days = (rule.byDay.map { it.day }.ifEmpty { listOf(start.dayOfWeek) }).distinct()
            val weekStart = start.with(TemporalAdjusters.previousOrSame(rule.weekStart))
            generateSequence(weekStart) { it.plusWeeks(rule.interval.toLong()) }.flatMap { ws ->
                days.map { d -> ws.plusDays(offsetFrom(rule.weekStart, d).toLong()) }.sorted().asSequence()
            }
        }

        Freq.MONTHLY -> {
            val first = YearMonth.from(start)
            generateSequence(first) { it.plusMonths(rule.interval.toLong()) }.flatMap { ym ->
                monthDays(rule, ym, start).asSequence()
            }
        }

        Freq.YEARLY -> {
            val month = rule.byMonth ?: start.monthValue
            val day = rule.byMonthDay.firstOrNull() ?: start.dayOfMonth
            generateSequence(start.year) { it + rule.interval }.mapNotNull { y ->
                val ym = YearMonth.of(y, month)
                resolveMonthDay(ym, day)
            }
        }
    }

    private fun monthDays(rule: RRule, ym: YearMonth, start: LocalDate): List<LocalDate> {
        if (rule.byDay.isNotEmpty()) {
            return rule.byDay.flatMap { wd ->
                if (wd.ordinal == 0) {
                    // Every such weekday in the month.
                    var d = ym.atDay(1).with(TemporalAdjusters.firstInMonth(wd.day))
                    buildList { while (d.month == ym.month) { add(d); d = d.plusWeeks(1) } }
                } else {
                    listOfNotNull(nthWeekday(ym, wd.day, wd.ordinal))
                }
            }.distinct().sorted()
        }
        val days = rule.byMonthDay.ifEmpty { listOf(start.dayOfMonth) }
        return days.mapNotNull { resolveMonthDay(ym, it) }.distinct().sorted()
    }

    /** Day-of-month, negative from the end. Invalid dates (e.g. 31 Feb) are skipped per RFC 5545. */
    private fun resolveMonthDay(ym: YearMonth, day: Int): LocalDate? {
        val len = ym.lengthOfMonth()
        val d = if (day > 0) day else len + day + 1
        return if (d in 1..len) ym.atDay(d) else null
    }

    private fun nthWeekday(ym: YearMonth, day: DayOfWeek, n: Int): LocalDate? {
        val d = if (n > 0) ym.atDay(1).with(TemporalAdjusters.firstInMonth(day)).plusWeeks((n - 1).toLong())
        else ym.atEndOfMonth().with(TemporalAdjusters.lastInMonth(day)).minusWeeks((-n - 1).toLong())
        return d.takeIf { YearMonth.from(it) == ym }
    }

    private fun offsetFrom(weekStart: DayOfWeek, d: DayOfWeek) = ((d.value - weekStart.value) + 7) % 7

    // ---- Convenience for timed series --------------------------------------------------------

    data class TimedOccurrence(val key: String, val start: Instant, val end: Instant)
    data class AllDayOccurrence(val key: String, val startDate: LocalDate, val endDateExclusive: LocalDate)

    /**
     * Timed occurrences of a series overlapping [windowStart, windowEnd).
     * [seriesStart] is the first occurrence; duration is preserved as an absolute length.
     */
    fun timed(
        rule: RRule,
        seriesStart: Instant,
        duration: java.time.Duration,
        zone: ZoneId,
        windowStart: Instant,
        windowEnd: Instant,
    ): List<TimedOccurrence> {
        val startLocal = seriesStart.atZone(zone).toLocalDateTime()
        val time: LocalTime = startLocal.toLocalTime()
        // Pad the window by the duration so occurrences that began earlier but still overlap are included.
        val from = windowStart.minus(duration).atZone(zone).toLocalDate().minusDays(1)
        val to = windowEnd.atZone(zone).toLocalDate().plusDays(1)
        val until = rule.untilInstant
        val dates = dates(rule, startLocal.toLocalDate(), from, to) { d ->
            when {
                until != null -> !LocalDateTime.of(d, time).atZone(zone).toInstant().isAfter(until)
                rule.untilDate != null -> !d.isAfter(rule.untilDate)
                else -> true
            }
        }
        return dates.mapNotNull { d ->
            val ldt = LocalDateTime.of(d, time)
            val s = ldt.atZone(zone).toInstant()
            val e = s.plus(duration)
            if (s < windowEnd && e > windowStart || (duration.isZero && s >= windowStart && s < windowEnd)) {
                TimedOccurrence(occurrenceKey(ldt), s, e)
            } else null
        }
    }

    fun allDay(
        rule: RRule,
        seriesStart: LocalDate,
        lengthDays: Long,
        fromDate: LocalDate,
        toDateInclusive: LocalDate,
    ): List<AllDayOccurrence> {
        val dates = dates(rule, seriesStart, fromDate.minusDays(lengthDays), toDateInclusive)
        return dates.mapNotNull { d ->
            val end = d.plusDays(lengthDays.coerceAtLeast(1))
            if (d <= toDateInclusive && end > fromDate) AllDayOccurrence(occurrenceKey(d), d, end) else null
        }
    }

    /** Last occurrence date if the rule is bounded (COUNT/UNTIL), else null. Used for index envelopes. */
    fun lastDate(rule: RRule, dtStart: LocalDate): LocalDate? {
        if (rule.untilDate != null) return rule.untilDate
        if (rule.untilInstant != null) return rule.untilInstant.atZone(java.time.ZoneOffset.UTC).toLocalDate().plusDays(1)
        if (rule.count == null || rule.count > MAX_RESULTS) return null
        return dates(rule, dtStart, dtStart, dtStart.plus(500, ChronoUnit.YEARS)).lastOrNull() ?: dtStart
    }
}
