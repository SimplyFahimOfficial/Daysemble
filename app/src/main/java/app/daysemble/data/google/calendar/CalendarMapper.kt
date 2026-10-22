package app.daysemble.data.google.calendar

import app.daysemble.core.recurrence.Recurrence
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.recurrence.occurrenceKey
import app.daysemble.data.db.CalendarSourceEntity
import app.daysemble.data.db.EventEntity
import app.daysemble.data.db.SyncState
import app.daysemble.data.repo.EventRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Pure mapping between Google Calendar DTOs and local rows. */
object CalendarMapper {

    private val EX_TIMED = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val EX_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    /** Always includes seconds (OffsetDateTime.toString() drops ":00", which isn't valid RFC 3339). */
    private val RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    /** Client-supplied event id (base32hex alphabet ⊇ lowercase hex), making creates idempotent. */
    fun remoteIdFor(localId: String): String = localId.replace("-", "").lowercase()

    /** A fresh, valid client id (32 lowercase hex chars) for a re-create. */
    fun newClientId(): String = remoteIdFor(UUID.randomUUID().toString())

    /** The id a not-yet-confirmed create of [e] was (or will be) sent with. */
    fun clientIdOf(e: EventEntity): String = e.pendingClientId ?: remoteIdFor(e.id)

    fun parseInstant(s: String): Instant = OffsetDateTime.parse(s).toInstant()

    fun zoneOf(dto: EventDto, calendar: CalendarSourceEntity): ZoneId =
        (dto.start?.timeZone ?: calendar.timeZone)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC

    /** Original-start key of an exception instance, in the parent series' zone. */
    fun originalKey(dto: EventDto, parentZone: ZoneId): String? {
        val o = dto.originalStartTime ?: return null
        o.date?.let { return LocalDate.parse(it).toString() }
        return o.dateTime?.let { occurrenceKey(LocalDateTime.ofInstant(parseInstant(it), parentZone)) }
    }

    /** Popup reminder minutes for a remote event (calendar defaults when useDefault). */
    fun reminderMinutes(dto: EventDto, calendar: CalendarSourceEntity): List<Int> {
        val r = dto.reminders ?: return emptyList()
        return if (r.useDefault) calendar.defaultReminders.split(',').mapNotNull { it.trim().toIntOrNull() }
        else r.overrides.orEmpty().filter { it.method == "popup" }.map { it.minutes }
    }

    /**
     * Builds the local row for a non-cancelled remote event. [existing] supplies the stable local id
     * (and creation time) so re-syncs update the same row instead of duplicating it.
     */
    fun toEntity(
        dto: EventDto,
        calendar: CalendarSourceEntity,
        existing: EventEntity?,
        parent: EventEntity?,
        now: Long,
    ): EventEntity? {
        val start = dto.start ?: return null
        val end = dto.end ?: start
        val zone = zoneOf(dto, calendar)
        val allDay = start.date != null
        val rec = dto.recurrence?.let { Recurrence.parse(it, allDay, zone) }
        val ruleText = rec?.rule?.let { r -> if (r.isSupported) r.format() else r.raw } ?: dto.recurrence?.firstOrNull { it.startsWith("RRULE") }
        val base = EventEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            calendarId = calendar.id,
            remoteId = dto.id,
            etag = dto.etag,
            title = dto.summary.orEmpty(),
            description = dto.description.orEmpty(),
            location = dto.location.orEmpty(),
            isAllDay = allDay,
            startUtc = if (allDay) 0 else parseInstant(start.dateTime!!).toEpochMilli(),
            endUtc = if (allDay) 0 else parseInstant(end.dateTime ?: start.dateTime!!).toEpochMilli(),
            startDate = if (allDay) LocalDate.parse(start.date).toEpochDay() else 0,
            endDateExclusive = if (allDay) LocalDate.parse(end.date ?: start.date).toEpochDay().coerceAtLeast(LocalDate.parse(start.date).toEpochDay() + 1) else 0,
            timeZone = zone.id,
            rrule = if (dto.recurringEventId == null) ruleText else null,
            exdates = rec?.exdateKeys?.sorted()?.joinToString(",").orEmpty(),
            recurringParentId = parent?.id,
            originalStartKey = parent?.let { p -> originalKey(dto, runCatching { ZoneId.of(p.timeZone) }.getOrDefault(zone)) },
            isCancelled = false,
            colorArgb = eventColor(dto.colorId) ?: existing?.colorArgb,
            rangeStartUtc = 0, rangeEndUtc = 0,
            createdAt = existing?.createdAt ?: now,
            updatedAt = dto.updated?.let { runCatching { parseInstant(it).toEpochMilli() }.getOrNull() } ?: now,
            remoteUpdatedAt = dto.updated?.let { runCatching { parseInstant(it).toEpochMilli() }.getOrNull() },
            syncState = SyncState.SYNCED,
            isReadOnlyRecurrence = rec != null && !rec.isSupported,
        )
        return EventRepository.withEnvelope(base)
    }

    /** Remote body for insert/patch. Unsupported recurrence is never sent, so Google keeps its own. */
    fun toDto(e: EventEntity, reminders: List<Int>, forInsert: Boolean): EventDto {
        val zone = runCatching { ZoneId.of(e.timeZone) }.getOrDefault(ZoneOffset.UTC)
        val start: EventDateTime
        val end: EventDateTime
        if (e.isAllDay) {
            start = EventDateTime(date = LocalDate.ofEpochDay(e.startDate).toString())
            end = EventDateTime(date = LocalDate.ofEpochDay(e.endDateExclusive).toString())
        } else {
            start = EventDateTime(dateTime = RFC3339.format(Instant.ofEpochMilli(e.startUtc).atZone(zone)), timeZone = zone.id)
            end = EventDateTime(dateTime = RFC3339.format(Instant.ofEpochMilli(e.endUtc).atZone(zone)), timeZone = zone.id)
        }
        val recurrence = if (e.rrule != null && !e.isReadOnlyRecurrence) {
            val rule = RRule.parse(e.rrule)
            if (rule == null || !rule.isSupported) null
            else listOf(rule.format()) + exdateLines(e, zone)
        } else null
        return EventDto(
            id = if (forInsert) e.remoteId ?: clientIdOf(e) else null,
            summary = e.title,
            description = e.description,
            location = e.location,
            start = start,
            end = end,
            recurrence = recurrence ?: if (!forInsert && e.rrule == null) emptyList() else null,
            reminders = RemindersDto(useDefault = false, overrides = reminders.distinct().take(5).map { ReminderDto("popup", it) }),
        )
    }

    private fun exdateLines(e: EventEntity, zone: ZoneId): List<String> {
        val keys = e.exdates.split(',').filter { it.isNotBlank() }
        if (keys.isEmpty()) return emptyList()
        return if (e.isAllDay) listOf("EXDATE;VALUE=DATE:" + keys.joinToString(",") { EX_DATE.format(LocalDate.parse(it)) })
        else listOf("EXDATE;TZID=${zone.id}:" + keys.joinToString(",") { EX_TIMED.format(LocalDateTime.parse(it)) })
    }

    /** Google Calendar's fixed event palette (colors.get "event"), ids 1–11. Read-only mapping; we never write colorId. */
    private val EVENT_COLORS = mapOf(
        "1" to "a4bdfc", "2" to "7ae7bf", "3" to "dbadff", "4" to "ff887c", "5" to "fbd75b", "6" to "ffb878",
        "7" to "46d6db", "8" to "e1e1e1", "9" to "5484ed", "10" to "51b749", "11" to "dc2127",
    )

    fun eventColor(colorId: String?): Int? = colorId?.let(EVENT_COLORS::get)?.let(::parseColor)

    fun parseColor(hex: String?): Int? = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
}
