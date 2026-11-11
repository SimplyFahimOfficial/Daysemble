package app.daysemble.data.repo

import androidx.room.withTransaction
import app.daysemble.core.recurrence.Recurrence
import app.daysemble.core.recurrence.RecurrenceExpander
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.recurrence.occurrenceKey
import app.daysemble.core.time.AppClock
import app.daysemble.data.db.CalendarSourceEntity
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.EventEntity
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.ReminderEntity
import app.daysemble.data.db.SourceKind
import app.daysemble.data.sync.ChangeListener
import app.daysemble.domain.model.CalendarInfo
import app.daysemble.domain.model.EventDraft
import app.daysemble.domain.model.EventOccurrence
import app.daysemble.ui.theme.EventSwatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class EventRepository(
    private val db: DaysembleDatabase,
    private val clock: AppClock,
    private val listener: ChangeListener,
) {
    private val events = db.events()
    private val calendars = db.calendars()

    val calendarList: Flow<List<CalendarInfo>> = calendars.observeAll().map { rows -> rows.map { it.toInfo() } }

    val allCalendars: Flow<List<CalendarSourceEntity>> = calendars.observeAll()

    suspend fun createCalendar(name: String, colorArgb: Int): String {
        require(name.isNotBlank())
        val id = UUID.randomUUID().toString()
        calendars.upsert(CalendarSourceEntity(id = id, kind = SourceKind.LOCAL, name = name.trim(), colorArgb = colorArgb))
        return id
    }

    suspend fun renameCalendar(id: String, name: String) {
        val c = calendars.get(id) ?: return
        require(c.kind == SourceKind.LOCAL && name.isNotBlank())
        calendars.rename(id, name.trim())
    }

    /** Colour is a display preference: allowed for device calendars too (kept in Daysemble only). */
    suspend fun setCalendarColor(id: String, colorArgb: Int) = calendars.setColor(id, colorArgb)

    /** Hidden calendars don't appear in views and don't remind. Nothing is deleted. */
    suspend fun setCalendarVisible(id: String, visible: Boolean) {
        calendars.setVisible(id, visible)
        events.allIn(id).forEach { listener.onEventChanged(it.id) }
    }

    /** Deletes an on-device calendar and its events; the last on-device calendar is kept. */
    suspend fun deleteCalendar(id: String): Boolean {
        val c = calendars.get(id) ?: return false
        if (c.kind != SourceKind.LOCAL) return false
        // Check and read ids in the transaction: two quick deletes could otherwise remove both of the last
        // calendars, and an event added in between would leave reminder rows behind.
        val ids = db.withTransaction {
            if (calendars.byKind(SourceKind.LOCAL).size <= 1) return@withTransaction null
            val ids = events.allIn(id).map { it.id }
            ids.forEach { db.reminders().deleteForOwner(OwnerType.EVENT, it) }
            calendars.delete(id)
            ids
        } ?: return false
        ids.forEach(listener::onEventChanged)
        return true
    }

    suspend fun ensureDefaultCalendar(name: String): String = db.withTransaction {
        calendars.byKind(SourceKind.LOCAL).firstOrNull()?.id ?: run {
            val id = UUID.randomUUID().toString()
            calendars.upsert(
                CalendarSourceEntity(id = id, kind = SourceKind.LOCAL, name = name, colorArgb = EventSwatch.CORAL.light.toArgbInt(), isPrimary = true),
            )
            id
        }
    }

    /** Occurrences overlapping the local dates [from, toInclusive] as seen in [zone]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun occurrences(from: LocalDate, toInclusive: LocalDate, zone: ZoneId): Flow<List<EventOccurrence>> {
        val windowStart = from.atStartOfDay(zone).toInstant()
        val windowEnd = toInclusive.plusDays(1).atStartOfDay(zone).toInstant()
        // All-day rows use a ±14 h envelope, so widen the query to catch them in any zone.
        val qs = windowStart.minus(Duration.ofHours(15)).toEpochMilli()
        val qe = windowEnd.plus(Duration.ofHours(15)).toEpochMilli()
        return combine(events.observeInRange(qs, qe), calendars.observeAll()) { rows, cals -> rows to cals.associateBy { it.id } }
            .mapLatest { (rows, cals) ->
                // An occurrence moved into this window may belong to a series whose own range ends earlier:
                // load those parents too, so the moved occurrence is rendered.
                val present = rows.map { it.id }.toSet()
                val missingParents = rows.mapNotNull { it.recurringParentId }.filter { it !in present }.distinct()
                val all = if (missingParents.isEmpty()) rows else rows + events.getAll(missingParents).filter { !it.isDeleted }
                val masters = all.filter { it.rrule != null && it.recurringParentId == null }
                val overrides = if (masters.isEmpty()) emptyList() else events.overridesFor(masters.map { it.id })
                expand(all, overrides, cals, from, toInclusive, windowStart, windowEnd, zone)
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeOccurrence(eventId: String, key: String?, zone: ZoneId): Flow<EventOccurrence?> =
        combine(events.observe(eventId), calendars.observeAll()) { row, cals -> row to cals.associateBy { it.id } }
            .mapLatest { (row, cals) ->
                if (row == null || row.isDeleted) return@mapLatest null
                if (row.rrule == null || key == null) return@mapLatest single(row, cals[row.calendarId] ?: return@mapLatest null, zone)
                val date = keyDate(key) ?: return@mapLatest null
                val ov = events.overridesFor(listOf(row.id))
                expand(listOf(row), ov, cals, date.minusDays(1), date.plusDays(1),
                    date.minusDays(1).atStartOfDay(zone).toInstant(), date.plusDays(2).atStartOfDay(zone).toInstant(), zone)
                    .firstOrNull { it.occurrenceKey == key }
            }

    /**
     * Text search across title, description and location. Each matching event or series is
     * represented by its next occurrence within a year (or its most recent past one).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun search(query: String, now: Instant, zone: ZoneId): Flow<List<EventOccurrence>> =
        combine(events.search(escapeLike(query.trim())), calendars.observeAll()) { rows, cals -> rows to cals.associateBy { it.id } }
            .mapLatest { (rows, cals) ->
                val today = now.atZone(zone).toLocalDate()
                val masters = rows.filter { it.rrule != null && it.recurringParentId == null }
                val overrides = if (masters.isEmpty()) emptyList() else events.overridesFor(masters.map { it.id })
                rows.filter { it.recurringParentId == null }.mapNotNull { row ->
                    val cal = cals[row.calendarId] ?: return@mapNotNull null
                    if (row.rrule == null) return@mapNotNull single(row, cal, zone)
                    val from = today.minusDays(365); val to = today.plusDays(365)
                    val occ = expand(listOf(row), overrides, cals, from, to, from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant(), zone)
                    occ.firstOrNull { it.end > now } ?: occ.lastOrNull()
                }.sortedBy { it.start }
            }

    suspend fun get(id: String): EventEntity? = events.get(id)?.takeIf { !it.isDeleted }

    suspend fun reminders(id: String): List<Int> = db.reminders().forOwner(OwnerType.EVENT, id).map { it.minutesBefore }

    fun observeReminders(id: String): Flow<List<Int>> =
        db.reminders().observeForOwner(OwnerType.EVENT, id).map { r -> r.map { it.minutesBefore } }

    suspend fun calendar(id: String): CalendarInfo? = calendars.get(id)?.toInfo()

    /** Builds an editable draft from a stored event (the whole series for recurring events). */
    suspend fun draftFor(id: String, deviceZone: ZoneId): EventDraft? {
        val e = get(id) ?: return null
        val zone = runCatching { ZoneId.of(e.timeZone) }.getOrDefault(deviceZone)
        return if (e.isAllDay) {
            val s = LocalDate.ofEpochDay(e.startDate)
            EventDraft(
                title = e.title, description = e.description, location = e.location, calendarId = e.calendarId,
                isAllDay = true, startDate = s, endDate = LocalDate.ofEpochDay(e.endDateExclusive).minusDays(1).coerceAtLeast(s),
                zone = zone, rrule = e.rrule?.let(RRule::parse), reminderMinutesBefore = reminders(id), colorArgb = e.colorArgb,
            )
        } else {
            val s = Instant.ofEpochMilli(e.startUtc).atZone(zone)
            val en = Instant.ofEpochMilli(e.endUtc).atZone(zone)
            EventDraft(
                title = e.title, description = e.description, location = e.location, calendarId = e.calendarId,
                isAllDay = false, startDate = s.toLocalDate(), startTime = s.toLocalTime(),
                endDate = en.toLocalDate(), endTime = en.toLocalTime(), zone = zone,
                rrule = e.rrule?.let(RRule::parse), reminderMinutesBefore = reminders(id), colorArgb = e.colorArgb,
            )
        }
    }

    /**
     * A draft of one occurrence of series [masterId]: its changed version if it has one, otherwise
     * the series at that occurrence's time. No repeat rule (it's one occurrence) and the series'
     * reminders and calendar, which stay series-wide.
     */
    suspend fun draftForOccurrence(masterId: String, key: String, deviceZone: ZoneId): EventDraft? {
        val master = get(masterId)?.takeIf { it.rrule != null } ?: return null
        val override = events.override(masterId, key)?.takeIf { !it.isDeleted && !it.isCancelled }
        val base = draftFor(override?.id ?: masterId, deviceZone) ?: return null
        val occ = observeOccurrence(masterId, key, deviceZone).first() ?: return null
        val zone = base.zone
        val timing = if (occ.isAllDay) {
            base.copy(startDate = occ.startDate, endDate = occ.endDate.minusDays(1).coerceAtLeast(occ.startDate))
        } else {
            val s = occ.start.atZone(zone); val e = occ.end.atZone(zone)
            base.copy(startDate = s.toLocalDate(), startTime = s.toLocalTime(), endDate = e.toLocalDate(), endTime = e.toLocalTime())
        }
        return timing.copy(rrule = null, calendarId = master.calendarId, reminderMinutesBefore = reminders(masterId))
    }

    /**
     * Changes only the occurrence [key] of series [masterId], by storing (or updating) an override
     * row for it. The rule, the other occurrences, the reminders and the calendar stay as they are.
     */
    suspend fun updateOccurrence(masterId: String, key: String, draft: EventDraft) {
        require(draft.validationError == null) { "Invalid event: ${draft.validationError}" }
        val changed = db.withTransaction {
            val master = get(masterId) ?: return@withTransaction false
            require(master.rrule != null && !master.isReadOnlyRecurrence) { "Not an editable series" }
            val cal = calendars.get(master.calendarId) ?: return@withTransaction false
            require(isWritableRole(cal.accessRole)) { "Calendar is read-only" }
            val now = clock.now().toEpochMilli()
            val existing = events.override(masterId, key)
            val base = existing ?: master.copy(
                id = UUID.randomUUID().toString(), rrule = null, exdates = "", recurringParentId = masterId, originalStartKey = key,
                isCancelled = false, createdAt = now,
            )
            // An override has no rule of its own and stays in its series' calendar.
            val row = applyDraft(base, draft.copy(rrule = null, calendarId = master.calendarId))
            events.upsert(row.copy(isCancelled = false, isDeleted = false, updatedAt = now))
            true
        }
        if (changed) listener.onEventChanged(masterId)
    }

    suspend fun create(draft: EventDraft, fallbackCalendarId: String): String {
        require(draft.validationError == null) { "Invalid event: ${draft.validationError}" }
        val cal = draft.calendarId?.let { calendars.get(it) } ?: calendars.get(fallbackCalendarId) ?: error("No calendar")
        require(isWritableRole(cal.accessRole)) { "Calendar is read-only" }
        val now = clock.now().toEpochMilli()
        val id = UUID.randomUUID().toString()
        val row = applyDraft(
            EventEntity(
                id = id, calendarId = cal.id, title = "", isAllDay = false, timeZone = draft.zone.id,
                rangeStartUtc = 0, rangeEndUtc = 0, createdAt = now, updatedAt = now,
            ),
            draft,
        )
        db.withTransaction {
            events.upsert(row)
            saveReminders(id, draft.reminderMinutesBefore)
        }
        listener.onEventChanged(id)
        return id
    }

    /** Updates the event (whole series for recurring events). */
    suspend fun update(id: String, draft: EventDraft) {
        require(draft.validationError == null) { "Invalid event: ${draft.validationError}" }
        // Moving to another calendar and editing are one transaction, so a failure can't leave the
        // event moved but not edited. (Room joins the nested transactions below to this one.)
        val changed = db.withTransaction {
            val current = get(id) ?: return@withTransaction false
            val target = draft.calendarId?.takeIf { it != current.calendarId }
            // The event exists, so a move that doesn't happen means a calendar is gone. Fail the save
            // (nothing is written) instead of reporting success and dropping the other edits.
            check(target == null || moveRow(id, target)) { "The calendar is no longer available" }
            updateRow(id, draft)
        }
        if (changed) listener.onEventChanged(id)
    }

    /** Applies [draft] to the row. Returns false if the event is gone. */
    private suspend fun updateRow(id: String, draft: EventDraft): Boolean =
        db.withTransaction {
            val row = get(id) ?: return@withTransaction false
            val cal = calendars.get(row.calendarId) ?: return@withTransaction false
            require(isWritableRole(cal.accessRole)) { "Calendar is read-only" }
            var updated = applyDraft(row, draft).copy(updatedAt = clock.now().toEpochMilli())
            // Keep EXDATEs only when the series timing is unchanged; otherwise their keys no longer line up.
            if (updated.startUtc != row.startUtc || updated.startDate != row.startDate || updated.isAllDay != row.isAllDay || updated.rrule != row.rrule) {
                updated = updated.copy(exdates = "")
            }
            events.upsert(updated)
            saveReminders(id, draft.reminderMinutesBefore)
            true
        }

    /** True when an event can change calendar (series with individually changed occurrences can't). */
    suspend fun canMove(id: String): Boolean {
        val e = get(id) ?: return false
        return e.rrule == null || events.overridesFor(listOf(id)).isEmpty()
    }

    /** Moves an event to another calendar. */
    suspend fun moveToCalendar(id: String, newCalendarId: String) {
        if (moveRow(id, newCalendarId)) listener.onEventChanged(id)
    }

    /** Moves the row. Returns false if nothing moved. */
    private suspend fun moveRow(id: String, newCalendarId: String): Boolean =
        db.withTransaction {
            val row = get(id) ?: return@withTransaction false
            if (row.calendarId == newCalendarId) return@withTransaction false
            require(canMove(id)) { "Series with changed occurrences can't change calendar" }
            val from = calendars.get(row.calendarId) ?: return@withTransaction false
            val to = calendars.get(newCalendarId) ?: return@withTransaction false
            require(isWritableRole(from.accessRole) && isWritableRole(to.accessRole)) { "Calendar is read-only" }
            events.upsert(row.copy(calendarId = to.id, updatedAt = clock.now().toEpochMilli()))
            true
        }

    /**
     * What a delete removed (the event, its changed occurrences and their reminders), kept in memory
     * so the user can undo. See [restore].
     */
    class Deleted internal constructor(internal val rows: List<EventEntity>, internal val reminders: List<ReminderEntity>)

    private suspend fun snapshot(id: String): Deleted? {
        val row = events.get(id) ?: return null
        val rows = listOf(row) + if (row.rrule != null) events.overridesFor(listOf(id)) else emptyList()
        return Deleted(rows, rows.flatMap { db.reminders().forOwner(OwnerType.EVENT, it.id) })
    }

    /** Undoes [delete] or [deleteOccurrence]: the rows are written back as they were. */
    suspend fun restore(deleted: Deleted) {
        val restored = mutableListOf<String>()
        db.withTransaction {
            for (r in deleted.rows) {
                if (restoreRow(r, deleted.reminders.filter { it.ownerId == r.id })) restored += r.id
            }
        }
        restored.forEach(listener::onEventChanged)
    }

    /** Restores row [r] with its [reminders]. Returns whether anything was written. */
    private suspend fun restoreRow(r: EventEntity, reminders: List<ReminderEntity>): Boolean {
        // The row is untouched (a changed occurrence of a kept series), but the series delete removed
        // its reminders: put them back.
        if (events.get(r.id) == r) return restoreReminders(r.id, reminders)
        events.upsert(r)
        db.reminders().replace(OwnerType.EVENT, r.id, reminders)
        return true
    }

    /** Puts back [reminders] of [id] if it has none now. Returns whether anything was written. */
    private suspend fun restoreReminders(id: String, reminders: List<ReminderEntity>): Boolean {
        if (reminders.isEmpty() || db.reminders().forOwner(OwnerType.EVENT, id).isNotEmpty()) return false
        db.reminders().replace(OwnerType.EVENT, id, reminders)
        return true
    }

    /** Whether the calendar of [row] can be changed (device calendars are read-only mirrors). */
    private suspend fun writable(row: EventEntity) = calendars.get(row.calendarId)?.let { isWritableRole(it.accessRole) } == true

    /** Deletes a whole event / series. */
    suspend fun delete(id: String): Deleted? {
        var removed: Deleted? = null
        val done = db.withTransaction {
            val row = events.get(id)?.takeIf { writable(it) } ?: return@withTransaction false
            removed = snapshot(id)
            events.hardDelete(id)
            // The series' changed occurrences go with it, so their reminders do too.
            removed?.rows.orEmpty().forEach { db.reminders().deleteForOwner(OwnerType.EVENT, it.id) }
            true
        }
        if (!done) return null
        listener.onEventChanged(id)
        return removed
    }

    /** Removes one instance of a series by adding an EXDATE to the master. */
    suspend fun deleteOccurrence(eventId: String, key: String): Deleted? {
        var removed: Deleted? = null
        val changed = db.withTransaction {
            val row = get(eventId)?.takeIf { it.rrule != null && writable(it) } ?: return@withTransaction false
            removed = snapshot(eventId)
            val keys = row.exdates.split(',').filter { it.isNotBlank() }.toMutableSet().apply { add(key) }
            events.upsert(row.copy(exdates = keys.sorted().joinToString(","), updatedAt = clock.now().toEpochMilli()))
            true
        }
        // Not a series: deleting "this occurrence" is deleting the event.
        if (!changed) return delete(eventId)
        listener.onEventChanged(eventId)
        return removed
    }

    private fun applyDraft(row: EventEntity, d: EventDraft): EventEntity {
        val rrule = d.rrule
        val rule = rrule?.format()
        return if (d.isAllDay) {
            val start = d.startDate
            val endEx = d.endDate.plusDays(1)
            val last = rrule?.takeIf { it.isSupported }?.let { RecurrenceExpander.lastDate(it, start) }
            val lengthDays = endEx.toEpochDay() - start.toEpochDay()
            row.copy(
                title = d.title.trim(), description = d.description.trim(), location = d.location.trim(),
                calendarId = d.calendarId ?: row.calendarId, isAllDay = true,
                startUtc = 0, endUtc = 0, startDate = start.toEpochDay(), endDateExclusive = endEx.toEpochDay(),
                timeZone = d.zone.id, rrule = rule, colorArgb = d.colorArgb,
                isReadOnlyRecurrence = rrule != null && !rrule.isSupported,
                rangeStartUtc = allDayEnvelopeStart(start),
                rangeEndUtc = when {
                    rrule == null -> allDayEnvelopeEnd(endEx)
                    last != null -> allDayEnvelopeEnd(last.plusDays(lengthDays))
                    else -> Long.MAX_VALUE
                },
            )
        } else {
            val s = d.startInstant()
            val e = d.endInstant()
            val last = rrule?.takeIf { it.isSupported }?.let { RecurrenceExpander.lastDate(it, d.startDate) }
            row.copy(
                title = d.title.trim(), description = d.description.trim(), location = d.location.trim(),
                calendarId = d.calendarId ?: row.calendarId, isAllDay = false,
                startUtc = s.toEpochMilli(), endUtc = e.toEpochMilli(), startDate = 0, endDateExclusive = 0,
                timeZone = d.zone.id, rrule = rule, colorArgb = d.colorArgb,
                isReadOnlyRecurrence = rrule != null && !rrule.isSupported,
                rangeStartUtc = s.toEpochMilli(),
                rangeEndUtc = when {
                    rrule == null -> maxOf(e.toEpochMilli(), s.toEpochMilli() + 1)
                    last != null -> last.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + Duration.between(s, e).toMillis()
                    else -> Long.MAX_VALUE
                },
            )
        }
    }

    private suspend fun saveReminders(id: String, minutes: List<Int>) {
        db.reminders().replace(
            OwnerType.EVENT, id,
            minutes.distinct().filter { it >= 0 }.take(5).map { ReminderEntity.of(OwnerType.EVENT, id, it) },
        )
    }

    companion object {
        fun allDayEnvelopeStart(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().minus(Duration.ofHours(14)).toEpochMilli()
        fun allDayEnvelopeEnd(endExclusive: LocalDate) = endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(14)).toEpochMilli()

        /** Recomputes the indexed range envelope from an entity's timing and rule (used by sync). */
        fun withEnvelope(e: EventEntity): EventEntity {
            val rule = e.rrule?.let(RRule::parse)?.takeIf { it.isSupported }
            val unboundedSeries = e.rrule != null && (rule == null || RecurrenceExpander.lastDate(rule,
                if (e.isAllDay) LocalDate.ofEpochDay(e.startDate) else Instant.ofEpochMilli(e.startUtc).atZone(ZoneOffset.UTC).toLocalDate()) == null)
            return if (e.isAllDay) {
                val start = LocalDate.ofEpochDay(e.startDate)
                val endEx = LocalDate.ofEpochDay(e.endDateExclusive)
                val last = rule?.let { RecurrenceExpander.lastDate(it, start) }
                e.copy(
                    rangeStartUtc = allDayEnvelopeStart(start),
                    rangeEndUtc = when {
                        e.rrule == null -> allDayEnvelopeEnd(endEx)
                        unboundedSeries || last == null -> Long.MAX_VALUE
                        else -> allDayEnvelopeEnd(last.plusDays(e.endDateExclusive - e.startDate))
                    },
                )
            } else {
                val last = rule?.let { RecurrenceExpander.lastDate(it, Instant.ofEpochMilli(e.startUtc).atZone(ZoneOffset.UTC).toLocalDate()) }
                e.copy(
                    rangeStartUtc = e.startUtc,
                    rangeEndUtc = when {
                        e.rrule == null -> maxOf(e.endUtc, e.startUtc + 1)
                        unboundedSeries || last == null -> Long.MAX_VALUE
                        else -> last.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + (e.endUtc - e.startUtc)
                    },
                )
            }
        }

        fun keyDate(key: String): LocalDate? = runCatching { LocalDate.parse(key.take(10)) }.getOrNull()

        private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /** Pure expansion, exposed for tests. */
        fun expand(
            rows: List<EventEntity>,
            overrides: List<EventEntity>,
            cals: Map<String, CalendarSourceEntity>,
            from: LocalDate,
            toInclusive: LocalDate,
            windowStart: Instant,
            windowEnd: Instant,
            zone: ZoneId,
        ): List<EventOccurrence> {
            val out = ArrayList<EventOccurrence>()
            val overridesByParent = overrides.groupBy { it.recurringParentId }
            for (row in rows) {
                if (row.isDeleted || row.isCancelled || row.recurringParentId != null) continue
                val cal = cals[row.calendarId] ?: continue
                if (!cal.isVisible) continue
                val rule = row.rrule?.let(RRule::parse)
                if (rule == null) {
                    single(row, cal, zone)?.takeIf { overlaps(it, from, toInclusive, windowStart, windowEnd) }?.let(out::add)
                    continue
                }
                val evZone = runCatching { ZoneId.of(row.timeZone) }.getOrDefault(zone)
                val exdates = row.exdates.split(',').filter { it.isNotBlank() }.toSet()
                val ovByKey = overridesByParent[row.id].orEmpty().associateBy { it.originalStartKey }
                val instances: List<Triple<String, Instant, Instant>> = if (!rule.isSupported || row.isReadOnlyRecurrence) {
                    // Can't expand locally: show the changed occurrences stored for it, or else the first one only.
                    if (overridesByParent[row.id].orEmpty().any { !it.isCancelled }) emptyList()
                    else listOfNotNull(single(row, cal, zone)?.let { Triple(it.occurrenceKey, it.start, it.end) })
                } else if (row.isAllDay) {
                    val s = LocalDate.ofEpochDay(row.startDate)
                    val len = row.endDateExclusive - row.startDate
                    RecurrenceExpander.allDay(rule, s, len, from, toInclusive).map {
                        Triple(it.key, it.startDate.atStartOfDay(zone).toInstant(), it.endDateExclusive.atStartOfDay(zone).toInstant())
                    }
                } else {
                    val s = Instant.ofEpochMilli(row.startUtc)
                    RecurrenceExpander.timed(rule, s, Duration.ofMillis(row.endUtc - row.startUtc), evZone, windowStart, windowEnd)
                        .map { Triple(it.key, it.start, it.end) }
                }
                for ((key, s, e) in instances) {
                    if (key in exdates) continue
                    val ov = ovByKey[key]
                    if (ov != null) {
                        if (ov.isCancelled || ov.isDeleted) continue
                        single(ov, cal, zone)?.let { it.copy(eventId = row.id, occurrenceKey = key, overrideId = ov.id, isRecurring = true, isReadOnly = it.isReadOnly || row.isReadOnlyRecurrence) }
                            ?.takeIf { overlaps(it, from, toInclusive, windowStart, windowEnd) }?.let(out::add)
                        continue
                    }
                    out += occurrence(row, cal, zone, key, s, e, recurring = true)
                }
                // Overrides moved into this window from an occurrence outside it.
                overridesByParent[row.id].orEmpty().forEach { ov ->
                    if (ov.isCancelled || ov.isDeleted || ov.originalStartKey in exdates) return@forEach
                    if (instances.any { it.first == ov.originalStartKey }) return@forEach
                    single(ov, cal, zone)?.let { it.copy(eventId = row.id, occurrenceKey = ov.originalStartKey ?: ov.id, overrideId = ov.id, isRecurring = true, isReadOnly = it.isReadOnly || row.isReadOnlyRecurrence) }
                        ?.takeIf { overlaps(it, from, toInclusive, windowStart, windowEnd) }?.let(out::add)
                }
            }
            return out.sortedWith(compareBy<EventOccurrence> { !it.isAllDay }.thenBy { it.start }.thenBy { it.end }.thenBy { it.title })
        }

        /**
         * Exclusive local end date: all-day ends are already exclusive; a timed event ending exactly at
         * midnight doesn't touch the following day.
         */
        fun exclusiveEndDate(allDay: Boolean, s: Instant, e: Instant, zone: ZoneId): LocalDate {
            val end = e.atZone(zone)
            if (allDay) return end.toLocalDate()
            return if (e > s && end.toLocalTime() == java.time.LocalTime.MIDNIGHT) end.toLocalDate() else end.toLocalDate().plusDays(1)
        }

        private fun overlaps(o: EventOccurrence, from: LocalDate, to: LocalDate, ws: Instant, we: Instant) =
            if (o.isAllDay) o.startDate <= to && o.endDate > from
            else (o.start < we && o.end > ws) || (o.start == o.end && o.start >= ws && o.start < we)

        internal fun single(row: EventEntity, cal: CalendarSourceEntity, zone: ZoneId): EventOccurrence? {
            if (row.isAllDay) {
                val s = LocalDate.ofEpochDay(row.startDate)
                val e = LocalDate.ofEpochDay(row.endDateExclusive).coerceAtLeast(s.plusDays(1))
                return occurrence(row, cal, zone, occurrenceKey(s), s.atStartOfDay(zone).toInstant(), e.atStartOfDay(zone).toInstant(), row.rrule != null)
            }
            val evZone = runCatching { ZoneId.of(row.timeZone) }.getOrDefault(zone)
            val s = Instant.ofEpochMilli(row.startUtc)
            val e = Instant.ofEpochMilli(maxOf(row.endUtc, row.startUtc))
            return occurrence(row, cal, zone, occurrenceKey(LocalDateTime.ofInstant(s, evZone)), s, e, row.rrule != null)
        }

        private fun occurrence(row: EventEntity, cal: CalendarSourceEntity, zone: ZoneId, key: String, s: Instant, e: Instant, recurring: Boolean) =
            EventOccurrence(
                eventId = row.id, occurrenceKey = key, overrideId = null, calendarId = row.calendarId,
                title = row.title, description = row.description, location = row.location, isAllDay = row.isAllDay,
                start = s, end = e,
                startDate = s.atZone(zone).toLocalDate(),
                endDate = exclusiveEndDate(row.isAllDay, s, e, zone),
                timeZone = runCatching { ZoneId.of(row.timeZone) }.getOrDefault(zone),
                colorArgb = row.colorArgb ?: cal.colorArgb,
                isRecurring = recurring,
                isDevice = cal.kind == SourceKind.DEVICE,
                isReadOnly = !isWritableRole(cal.accessRole) || row.isReadOnlyRecurrence,
            )
    }
}

fun isWritableRole(role: String) = role == "owner" || role == "writer" || role == "writerWithoutPrivateAccess"

internal fun CalendarSourceEntity.toInfo() = CalendarInfo(
    id = id, name = name, colorArgb = colorArgb, isDevice = kind == SourceKind.DEVICE,
    isVisible = isVisible, isWritable = isWritableRole(accessRole),
)

internal fun androidx.compose.ui.graphics.Color.toArgbInt(): Int {
    val a = (alpha * 255 + 0.5f).toInt(); val r = (red * 255 + 0.5f).toInt()
    val g = (green * 255 + 0.5f).toInt(); val b = (blue * 255 + 0.5f).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
