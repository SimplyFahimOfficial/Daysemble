package app.daysemble.core.recurrence

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

/** A BYDAY entry, e.g. `MO`, `2TU` (second Tuesday), `-1FR` (last Friday). */
data class WeekdayNum(val day: DayOfWeek, val ordinal: Int = 0) {
    override fun toString() = (if (ordinal != 0) ordinal.toString() else "") + DAY_CODES.getValue(day)
}

/**
 * The subset of RFC 5545 RRULE that Daysemble can expand and edit. Rules using other parts are
 * parsed with [isSupported] = false and their raw text kept in [raw]; such series are read-only
 * in the editor.
 */
data class RRule(
    val freq: Freq,
    val interval: Int = 1,
    val count: Int? = null,
    /** Inclusive end. Date-only for all-day series; for timed series the instant in UTC. */
    val untilDate: LocalDate? = null,
    val untilInstant: Instant? = null,
    val byDay: List<WeekdayNum> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: Int? = null,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val isSupported: Boolean = true,
    val raw: String? = null,
) {
    fun format(): String = raw?.takeIf { !isSupported } ?: buildString {
        append("RRULE:FREQ=").append(freq.name)
        if (interval > 1) append(";INTERVAL=").append(interval)
        if (count != null) append(";COUNT=").append(count)
        untilInstant?.let { append(";UNTIL=").append(UNTIL_UTC.format(it.atOffset(ZoneOffset.UTC))) }
        if (untilInstant == null) untilDate?.let { append(";UNTIL=").append(UNTIL_DATE.format(it)) }
        if (byDay.isNotEmpty()) append(";BYDAY=").append(byDay.joinToString(","))
        if (byMonthDay.isNotEmpty()) append(";BYMONTHDAY=").append(byMonthDay.joinToString(","))
        byMonth?.let { append(";BYMONTH=").append(it) }
        if (weekStart != DayOfWeek.MONDAY) append(";WKST=").append(DAY_CODES.getValue(weekStart))
    }

    companion object {
        private val SUPPORTED_KEYS = setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "WKST")

        /** Parses one `RRULE:` line (prefix optional). Returns null if it isn't an RRULE at all. */
        fun parse(line: String): RRule? {
            val body = line.trim().removePrefix("RRULE:").trim()
            if (body.isEmpty()) return null
            val parts = body.split(';').filter { it.isNotBlank() }.associate {
                val i = it.indexOf('=')
                if (i < 0) it.uppercase() to "" else it.substring(0, i).uppercase() to it.substring(i + 1)
            }
            val unsupported = { RRule(Freq.DAILY, isSupported = false, raw = "RRULE:$body") }
            val freq = parts["FREQ"]?.let { f -> Freq.entries.firstOrNull { it.name == f.uppercase() } } ?: return unsupported()
            if (parts.keys.any { it !in SUPPORTED_KEYS }) return unsupported()
            return runCatching {
                val (untilDate, untilInstant) = parts["UNTIL"]?.let(::parseUntil) ?: (null to null)
                val byDay = parts["BYDAY"]?.split(',')?.filter { it.isNotBlank() }?.map(::parseWeekdayNum).orEmpty()
                val byMonthDay = parts["BYMONTHDAY"]?.split(',')?.filter { it.isNotBlank() }?.map { it.trim().toInt() }.orEmpty()
                val byMonth = parts["BYMONTH"]?.let { v ->
                    val months = v.split(',').map { it.trim().toInt() }
                    if (months.size != 1) return unsupported()
                    months.single()
                }
                val rule = RRule(
                    freq = freq,
                    interval = parts["INTERVAL"]?.toInt()?.coerceAtLeast(1) ?: 1,
                    count = parts["COUNT"]?.toInt()?.takeIf { it > 0 },
                    untilDate = untilDate, untilInstant = untilInstant,
                    byDay = byDay, byMonthDay = byMonthDay, byMonth = byMonth,
                    weekStart = parts["WKST"]?.let(::parseDay) ?: DayOfWeek.MONDAY,
                )
                if (!rule.isExpandable()) unsupported() else rule
            }.getOrElse { unsupported() }
        }

        private fun RRule.isExpandable(): Boolean = when (freq) {
            Freq.DAILY -> byDay.isEmpty() && byMonthDay.isEmpty() && byMonth == null
            Freq.WEEKLY -> byDay.all { it.ordinal == 0 } && byMonthDay.isEmpty() && byMonth == null
            Freq.MONTHLY -> byMonth == null && !(byDay.isNotEmpty() && byMonthDay.isNotEmpty()) &&
                byMonthDay.all { it in -31..31 && it != 0 }
            Freq.YEARLY -> byDay.isEmpty() && byMonthDay.size <= 1
        }

        private fun parseUntil(v: String): Pair<LocalDate?, Instant?> {
            val s = v.trim()
            return when {
                s.length == 8 -> LocalDate.parse(s, UNTIL_DATE) to null
                s.endsWith("Z") -> null to LocalDateTime.parse(s, UNTIL_UTC_PARSE).toInstant(ZoneOffset.UTC)
                // Floating UNTIL: interpreted as UTC (timed series normally end in Z).
                else -> null to LocalDateTime.parse(s, UNTIL_FLOAT).toInstant(ZoneOffset.UTC)
            }
        }

        private fun parseWeekdayNum(s: String): WeekdayNum {
            val t = s.trim().uppercase()
            val code = t.takeLast(2)
            val ord = t.dropLast(2).let { if (it.isEmpty() || it == "+") 0 else it.removePrefix("+").toInt() }
            require(ord in -5..5)
            return WeekdayNum(parseDay(code), ord)
        }

        private fun parseDay(code: String): DayOfWeek =
            DAY_CODES.entries.first { it.value == code.trim().uppercase() }.key
    }
}

internal val DAY_CODES = mapOf(
    DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
    DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
)
private val UNTIL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
private val UNTIL_UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
private val UNTIL_UTC_PARSE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
private val UNTIL_FLOAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

/**
 * An ICS recurrence array split into what we understand: the RRULE plus EXDATE keys.
 * RDATE or multiple RRULEs make the series unsupported.
 */
data class Recurrence(val rule: RRule?, val exdateKeys: Set<String>, val isSupported: Boolean, val rawLines: List<String>) {
    companion object {
        fun parse(lines: List<String>, isAllDay: Boolean, zone: java.time.ZoneId): Recurrence {
            val rrules = lines.filter { it.startsWith("RRULE", ignoreCase = true) }
            val exdates = mutableSetOf<String>()
            var supported = rrules.size <= 1 && lines.none { it.startsWith("RDATE", ignoreCase = true) || it.startsWith("EXRULE", ignoreCase = true) }
            lines.filter { it.startsWith("EXDATE", ignoreCase = true) }.forEach { line ->
                runCatching { exdates += parseExdate(line, isAllDay, zone) }.onFailure { supported = false }
            }
            val rule = rrules.firstOrNull()?.let(RRule::parse)
            if (rule != null && !rule.isSupported) supported = false
            return Recurrence(rule, exdates, supported, lines)
        }

        /** `EXDATE;TZID=Europe/Berlin:20261022T090000,20261029T090000` or `EXDATE;VALUE=DATE:20261022`. */
        private fun parseExdate(line: String, isAllDay: Boolean, zone: java.time.ZoneId): List<String> {
            val colon = line.indexOf(':')
            val params = line.substring(0, colon).split(';').drop(1).associate {
                val (k, v) = it.split('=', limit = 2); k.uppercase() to v
            }
            val tz = params["TZID"]?.let { java.time.ZoneId.of(it) }
            return line.substring(colon + 1).split(',').map { v ->
                val s = v.trim()
                when {
                    s.length == 8 -> LocalDate.parse(s, UNTIL_DATE).toString()
                    isAllDay -> LocalDate.parse(s.take(8), UNTIL_DATE).toString()
                    s.endsWith("Z") -> occurrenceKey(LocalDateTime.parse(s, UNTIL_UTC_PARSE).atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDateTime())
                    else -> occurrenceKey(LocalDateTime.parse(s, UNTIL_FLOAT).atZone(tz ?: zone).withZoneSameInstant(zone).toLocalDateTime())
                }
            }
        }
    }
}

private val KEY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

/** Stable occurrence identity: original start in the event's own zone (minute precision) or the date. */
fun occurrenceKey(start: LocalDateTime): String = KEY_FORMAT.format(start)
fun occurrenceKey(date: LocalDate): String = date.toString()
