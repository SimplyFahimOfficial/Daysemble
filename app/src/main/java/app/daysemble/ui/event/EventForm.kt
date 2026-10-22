package app.daysemble.ui.event

import app.daysemble.core.recurrence.Freq
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.recurrence.WeekdayNum
import app.daysemble.domain.model.EventDraft
import app.daysemble.domain.model.RepeatEnd
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Repeat settings as edited in the UI. [freq] null = does not repeat. */
@Serializable
data class RepeatForm(
    val freq: String? = null,
    val interval: Int = 1,
    /** ISO day numbers 1..7, weekly only. */
    val weekdays: Set<Int> = emptySet(),
    val end: String = RepeatEnd.NEVER.name,
    val count: Int = 10,
    val untilEpochDay: Long? = null,
    /** Raw rule we can't edit; kept verbatim. */
    val unsupportedRaw: String? = null,
) {
    val frequency: Freq? get() = freq?.let { runCatching { Freq.valueOf(it) }.getOrNull() }
    val repeatEnd: RepeatEnd get() = runCatching { RepeatEnd.valueOf(end) }.getOrDefault(RepeatEnd.NEVER)

    fun toRRule(isAllDay: Boolean, startDate: LocalDate, zone: ZoneId): RRule? {
        unsupportedRaw?.let { return RRule.parse(it) }
        val f = frequency ?: return null
        val days = if (f == Freq.WEEKLY) weekdays.ifEmpty { setOf(startDate.dayOfWeek.value) }.sorted().map { WeekdayNum(DayOfWeek.of(it)) } else emptyList()
        val until = untilEpochDay?.let(LocalDate::ofEpochDay)?.takeIf { repeatEnd == RepeatEnd.UNTIL }
        return RRule(
            freq = f,
            interval = interval.coerceIn(1, 99),
            count = count.coerceIn(1, 999).takeIf { repeatEnd == RepeatEnd.COUNT },
            untilDate = if (isAllDay) until else null,
            // Timed series: inclusive through the end of the chosen day in the event's zone.
            untilInstant = if (!isAllDay) until?.plusDays(1)?.atStartOfDay(zone)?.minusSeconds(1)?.toInstant() else null,
            byDay = days,
        )
    }

    companion object {
        fun from(rule: RRule?, zone: ZoneId): RepeatForm {
            if (rule == null) return RepeatForm()
            if (!rule.isSupported || rule.byMonthDay.isNotEmpty() || rule.byDay.any { it.ordinal != 0 } ||
                (rule.freq != Freq.WEEKLY && rule.byDay.isNotEmpty()) || rule.byMonth != null
            ) return RepeatForm(freq = rule.freq.name, unsupportedRaw = rule.format())
            val until = rule.untilDate ?: rule.untilInstant?.atZone(zone)?.toLocalDate()
            return RepeatForm(
                freq = rule.freq.name, interval = rule.interval,
                weekdays = rule.byDay.map { it.day.value }.toSet(),
                end = when {
                    rule.count != null -> RepeatEnd.COUNT.name
                    until != null -> RepeatEnd.UNTIL.name
                    else -> RepeatEnd.NEVER.name
                },
                count = rule.count ?: 10,
                untilEpochDay = until?.toEpochDay(),
            )
        }
    }
}

@Serializable
data class EventForm(
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val calendarId: String? = null,
    val isAllDay: Boolean = false,
    val startDay: Long,
    val startMinute: Int,
    val endDay: Long,
    val endMinute: Int,
    val zone: String,
    val repeat: RepeatForm = RepeatForm(),
    val reminders: List<Int> = emptyList(),
    val colorArgb: Int? = null,
) {
    val startDate: LocalDate get() = LocalDate.ofEpochDay(startDay)
    val endDate: LocalDate get() = LocalDate.ofEpochDay(endDay)
    val startTime: LocalTime get() = LocalTime.of(startMinute / 60, startMinute % 60)
    val endTime: LocalTime get() = LocalTime.of(endMinute / 60, endMinute % 60)
    val zoneId: ZoneId get() = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneOffset.UTC)

    fun toDraft() = EventDraft(
        title = title, description = description, location = location, calendarId = calendarId,
        isAllDay = isAllDay, startDate = startDate, startTime = startTime, endDate = endDate, endTime = endTime,
        zone = zoneId, rrule = repeat.toRRule(isAllDay, startDate, zoneId), reminderMinutesBefore = reminders, colorArgb = colorArgb,
    )

    /** Moves the start while keeping the duration (the common expectation in calendar editors). */
    fun withStart(date: LocalDate, time: LocalTime): EventForm {
        val oldStart = startDate.atTime(startTime)
        val oldEnd = endDate.atTime(endTime)
        val dur = java.time.Duration.between(oldStart, oldEnd).coerceAtLeast(java.time.Duration.ZERO)
        val ns = date.atTime(time)
        val ne = ns.plus(dur)
        return copy(
            startDay = ns.toLocalDate().toEpochDay(), startMinute = ns.hour * 60 + ns.minute,
            endDay = ne.toLocalDate().toEpochDay(), endMinute = ne.hour * 60 + ne.minute,
        )
    }

    companion object {
        fun from(d: EventDraft) = EventForm(
            title = d.title, description = d.description, location = d.location, calendarId = d.calendarId,
            isAllDay = d.isAllDay, startDay = d.startDate.toEpochDay(), startMinute = d.startTime.hour * 60 + d.startTime.minute,
            endDay = d.endDate.toEpochDay(), endMinute = d.endTime.hour * 60 + d.endTime.minute, zone = d.zone.id,
            repeat = RepeatForm.from(d.rrule, d.zone), reminders = d.reminderMinutesBefore, colorArgb = d.colorArgb,
        )
    }
}
