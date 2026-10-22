package app.daysemble.reminders

import app.daysemble.core.recurrence.RRule
import app.daysemble.core.recurrence.RecurrenceExpander
import app.daysemble.core.recurrence.occurrenceKey
import app.daysemble.data.db.EventEntity
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.ReminderEntity
import app.daysemble.data.db.TaskEntity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** One notification that should fire at [triggerAt]. */
data class PlannedAlarm(
    val reminderId: String,
    val ownerType: OwnerType,
    val ownerId: String,
    val occurrenceKey: String,
    val triggerAt: Instant,
)

/**
 * Pure reminder timing. Only future triggers inside [HORIZON] are planned; a periodic refresh
 * and every change/boot/time-zone event re-plan, so the horizon keeps rolling forward.
 *
 * Rules:
 *  - Timed events: start − minutesBefore, per occurrence (EXDATEs skipped).
 *  - All-day events and date-only tasks: [defaultMinuteOfDay] on the date (device zone) − minutesBefore.
 *  - Timed tasks: due time − minutesBefore. Completed/deleted items get nothing.
 */
object ReminderPlanner {
    val HORIZON: Duration = Duration.ofDays(8)
    const val MAX_ALARMS = 400

    fun forEvent(
        event: EventEntity,
        reminders: List<ReminderEntity>,
        overrides: List<EventEntity>,
        now: Instant,
        deviceZone: ZoneId,
        defaultMinuteOfDay: Int,
    ): List<PlannedAlarm> {
        if (event.isDeleted || event.isCancelled || reminders.isEmpty() || event.recurringParentId != null) return emptyList()
        val maxLead = Duration.ofMinutes(reminders.maxOf { it.minutesBefore }.toLong())
        val windowStart = now
        val windowEnd = now.plus(HORIZON).plus(maxLead)
        val exdates = event.exdates.split(',').filter { it.isNotBlank() }.toSet()
        val ovByKey = overrides.associateBy { it.originalStartKey }
        val evZone = runCatching { ZoneId.of(event.timeZone) }.getOrDefault(deviceZone)

        // (occurrenceKey, start instant used for reminders)
        val unsupportedSeries = event.rrule != null && RRule.parse(event.rrule)?.isSupported != true
        val starts: List<Pair<String, Instant>> = if (unsupportedSeries && overrides.any { !it.isCancelled }) {
            // Google-expanded instances stored by sync; their own start times apply below.
            overrides.mapNotNull { o -> o.originalStartKey?.let { it to Instant.ofEpochMilli(o.startUtc) } }
        } else if (event.isAllDay) {
            val s = LocalDate.ofEpochDay(event.startDate)
            val len = (event.endDateExclusive - event.startDate).coerceAtLeast(1)
            val rule = event.rrule?.let(RRule::parse)?.takeIf { it.isSupported }
            val from = windowStart.atZone(deviceZone).toLocalDate().minusDays(1)
            val to = windowEnd.atZone(deviceZone).toLocalDate()
            val dates = if (rule != null) RecurrenceExpander.allDay(rule, s, len, from, to).map { it.startDate } else listOf(s)
            dates.map { d -> occurrenceKey(d) to atMinute(d, defaultMinuteOfDay, deviceZone) }
        } else {
            val s = Instant.ofEpochMilli(event.startUtc)
            val rule = event.rrule?.let(RRule::parse)?.takeIf { it.isSupported }
            if (rule != null) {
                RecurrenceExpander.timed(rule, s, Duration.ofMillis(event.endUtc - event.startUtc), evZone, windowStart, windowEnd)
                    .map { it.key to it.start }
            } else listOf(occurrenceKey(LocalDateTime.ofInstant(s, evZone)) to s)
        }

        return starts.flatMap { (key, start) ->
            if (key in exdates) return@flatMap emptyList()
            val ov = ovByKey[key]
            if (ov != null && (ov.isCancelled || ov.isDeleted)) return@flatMap emptyList()
            val actualStart = when {
                ov == null -> start
                ov.isAllDay -> atMinute(LocalDate.ofEpochDay(ov.startDate), defaultMinuteOfDay, deviceZone)
                else -> Instant.ofEpochMilli(ov.startUtc)
            }
            reminders.mapNotNull { r ->
                val t = actualStart.minus(Duration.ofMinutes(r.minutesBefore.toLong()))
                if (t > now && t <= now.plus(HORIZON)) PlannedAlarm(r.id, OwnerType.EVENT, event.id, key, t) else null
            }
        }
    }

    fun forTask(task: TaskEntity, reminders: List<ReminderEntity>, now: Instant, zone: ZoneId, defaultMinuteOfDay: Int): List<PlannedAlarm> {
        if (task.isDeleted || task.isCompleted || reminders.isEmpty()) return emptyList()
        val date = task.dueDate?.let(LocalDate::ofEpochDay) ?: return emptyList()
        val due = atMinute(date, task.dueTimeMinutes ?: defaultMinuteOfDay, zone)
        val key = occurrenceKey(date)
        return reminders.mapNotNull { r ->
            val t = due.minus(Duration.ofMinutes(r.minutesBefore.toLong()))
            if (t > now && t <= now.plus(HORIZON)) PlannedAlarm(r.id, OwnerType.TASK, task.id, key, t) else null
        }
    }

    /** Wall-clock minute on a date; a DST gap moves the time forward, as java.time does. */
    fun atMinute(date: LocalDate, minuteOfDay: Int, zone: ZoneId): Instant =
        date.atTime(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)).atZone(zone).toInstant()
}
