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
import app.daysemble.data.db.SyncState
import app.daysemble.data.sync.ChangeListener
import app.daysemble.data.sync.Outbox
import app.daysemble.domain.model.CalendarInfo
import app.daysemble.domain.model.EventDraft
import app.daysemble.domain.model.EventOccurrence
import app.daysemble.ui.theme.EventSwatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    private val outbox: Outbox,
    private val clock: AppClock,
    private val listener: ChangeListener,
) {
    private val events = db.events()
    private val calendars = db.calendars()

    val calendarList: Flow<List<CalendarInfo>> = calendars.observeAll().map { rows ->
        rows.filter { it.kind == SourceKind.LOCAL || it.isSyncEnabled }.map { it.toInfo() }
    }

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

    /** Colour is a display preference: allowed for Google calendars too (local only, never pushed). */
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
        if (calendars.byKind(SourceKind.LOCAL).size <= 1) return false
        // Read the ids in the transaction, so an event added in between doesn't leave reminder rows behind.
        val ids = db.withTransaction {
            val ids = events.allIn(id).map { it.id }
            ids.forEach { db.reminders().deleteForOwner(OwnerType.EVENT, it) }
            calendars.delete(id)
            ids
        }
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

    suspend fun create(draft: EventDraft, fallbackCalendarId: String): String {
        require(draft.validationError == null) { "Invalid event: ${draft.validationError}" }
        val cal = draft.calendarId?.let { calendars.get(it) } ?: calendars.get(fallbackCalendarId) ?: error("No calendar")
        require(isWritableRole(cal.accessRole)) { "Calendar is read-only" }
        val google = cal.kind == SourceKind.GOOGLE
        val now = clock.now().toEpochMilli()
        val id = UUID.randomUUID().toString()
        val row = applyDraft(
            EventEntity(
                id = id, calendarId = cal.id, title = "", isAllDay = false, timeZone = draft.zone.id,
                rangeStartUtc = 0, rangeEndUtc = 0, createdAt = now, updatedAt = now,
                syncState = if (google) SyncState.PENDING_CREATE else SyncState.LOCAL,
            ),
            draft,
        )
        db.withTransaction {
            events.upsert(row)
            saveReminders(id, draft.reminderMinutesBefore)
            if (google) outbox.recordCreate(OwnerType.EVENT, id)
        }
        notify(id, google)
        return id
    }

    /** Updates the event (whole series for recurring events). */
    suspend fun update(id: String, draft: EventDraft) {
        require(draft.validationError == null) { "Invalid event: ${draft.validationError}" }
        // Moving to another calendar and editing are one transaction, so a failure can't leave the
        // event moved but not edited. (Room joins the nested transactions below to this one.)
        val google = db.withTransaction {
            val current = get(id) ?: return@withTransaction null
            val target = draft.calendarId?.takeIf { it != current.calendarId }
            // The event exists, so a move that doesn't happen means a calendar is gone. Fail the save
            // (nothing is written) instead of reporting success and dropping the other edits.
            val moved = if (target != null) checkNotNull(moveRow(id, target)) { "The calendar is no longer available" } else false
            val edited = updateRow(id, draft) ?: return@withTransaction null
            moved || edited
        } ?: return
        notify(id, google)
    }

    /** Applies [draft] to the row. Returns whether it is a Google event, or null if it's gone. */
    private suspend fun updateRow(id: String, draft: EventDraft): Boolean? =
        db.withTransaction {
            val row = get(id) ?: return@withTransaction null
            val cal = calendars.get(row.calendarId) ?: return@withTransaction null
            val g = cal.kind == SourceKind.GOOGLE
            val state = if (g) outbox.recordUpdate(OwnerType.EVENT, id, row.syncState) else SyncState.LOCAL
            var updated = applyDraft(row, draft).copy(updatedAt = clock.now().toEpochMilli(), syncState = state)
            // Keep EXDATEs only when the series timing is unchanged; otherwise their keys no longer line up.
            if (updated.startUtc != row.startUtc || updated.startDate != row.startDate || updated.isAllDay != row.isAllDay || updated.rrule != row.rrule) {
                updated = updated.copy(exdates = "")
            }
            events.upsert(updated)
            saveReminders(id, draft.reminderMinutesBefore)
            g
        }

    /** True when an event can change calendar (series with individually changed occurrences can't). */
    suspend fun canMove(id: String): Boolean {
        val e = get(id) ?: return false
        return e.rrule == null || events.overridesFor(listOf(id)).isEmpty()
    }

    /**
     * Moves an event to another calendar. Google: the copy in the old calendar is deleted (by snapshot)
     * and the event is created in the new one with a server-assigned id, so there's no leftover remote
     * copy to resurrect on the next pull.
     */
    suspend fun moveToCalendar(id: String, newCalendarId: String) {
        val google = moveRow(id, newCalendarId) ?: return
        notify(id, google)
    }

    /** Moves the row. Returns whether Google is involved, or null if nothing moved. */
    private suspend fun moveRow(id: String, newCalendarId: String): Boolean? =
        db.withTransaction {
            val row = get(id) ?: return@withTransaction null
            if (row.calendarId == newCalendarId) return@withTransaction null
            require(canMove(id)) { "Series with changed occurrences can't change calendar" }
            val from = calendars.get(row.calendarId) ?: return@withTransaction null
            val to = calendars.get(newCalendarId) ?: return@withTransaction null
            require(isWritableRole(to.accessRole)) { "Calendar is read-only" }
            val google = recordMoveForSync(row, from, to)
            events.upsert(movedRow(row, to))
            google
        }

    /**
     * Queues the remote side of moving [row] from [from] to [to]: delete the old Google copy, create
     * the new one. Call inside [moveRow]'s transaction. Returns whether Google is involved.
     */
    private suspend fun recordMoveForSync(row: EventEntity, from: CalendarSourceEntity, to: CalendarSourceEntity): Boolean {
        val fromG = from.kind == SourceKind.GOOGLE
        val toG = to.kind == SourceKind.GOOGLE
        if (!fromG && !toG) return false
        // Remote id of a create with a lost response is our deterministic client id.
        val oldRemote = when {
            !fromG -> null
            row.remoteId != null -> row.remoteId
            row.syncState == SyncState.PENDING_CREATE -> app.daysemble.data.google.calendar.CalendarMapper.clientIdOf(row)
            else -> null
        }
        outbox.recordMove(OwnerType.EVENT, row.id, oldRemote, if (fromG) from.remoteId else null, toG)
        return true
    }

    /** [row] as it is stored in [to]: no remote identity yet, to be created there if [to] is a Google calendar. */
    private fun movedRow(row: EventEntity, to: CalendarSourceEntity): EventEntity {
        val toG = to.kind == SourceKind.GOOGLE
        return row.copy(
            calendarId = to.id, remoteId = null, etag = null,
            // New stable client id per move: the previous one may still be held by the deleted copy,
            // and reusing it on retries means a lost create response can't produce a duplicate.
            pendingClientId = if (toG) app.daysemble.data.google.calendar.CalendarMapper.newClientId() else null,
            remoteUpdatedAt = null, updatedAt = clock.now().toEpochMilli(),
            syncState = if (toG) SyncState.PENDING_CREATE else SyncState.LOCAL,
        )
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

    /**
     * Undoes [delete] or [deleteOccurrence]. On-device rows are written back as they were. A Google
     * event whose delete hasn't been pushed gets its pending delete cancelled; one that is already
     * gone from Google is created again (with a new id). Occurrences of a Google series that was
     * deleted remotely meanwhile can't be brought back and are left out.
     */
    suspend fun restore(deleted: Deleted) {
        val now = clock.now().toEpochMilli()
        val restored = mutableListOf<String>()
        var google = false
        db.withTransaction {
            for (r in deleted.rows) {
                val g = restoreRow(r, deleted.reminders.filter { it.ownerId == r.id }, now) ?: continue
                restored += r.id
                google = google || g
            }
        }
        restored.forEach(listener::onEventChanged)
        if (google) listener.onPendingSync()
    }

    /** Restores row [r] with its [reminders]. Returns null if nothing was written, else whether it is a Google event. */
    private suspend fun restoreRow(r: EventEntity, reminders: List<ReminderEntity>, now: Long): Boolean? {
        val cur = events.get(r.id)
        // The row is untouched (a changed occurrence of a kept series, or of a Google series whose
        // delete isn't pushed yet), but the series delete removed its reminders: put them back.
        if (cur == r) return if (restoreReminders(r.id, reminders)) false else null
        val g = calendars.get(r.calendarId)?.kind == SourceKind.GOOGLE
        val row = (if (g) restoredGoogleRow(r, cur, now) else r) ?: return null
        events.upsert(row)
        db.reminders().replace(OwnerType.EVENT, r.id, reminders)
        return g
    }

    /** Puts back [reminders] of [id] if it has none now. Returns whether anything was written. */
    private suspend fun restoreReminders(id: String, reminders: List<ReminderEntity>): Boolean {
        if (reminders.isEmpty() || db.reminders().forOwner(OwnerType.EVENT, id).isNotEmpty()) return false
        db.reminders().replace(OwnerType.EVENT, id, reminders)
        return true
    }

    /**
     * The Google row [r] as restored, given what is stored now ([cur]), with its sync queued; null if it
     * can't come back (a changed occurrence of a series already deleted on Google). Call inside [restore].
     */
    private suspend fun restoredGoogleRow(r: EventEntity, cur: EventEntity?, now: Long): EventEntity? = when {
        cur != null && cur.syncState == SyncState.PENDING_DELETE -> {
            outbox.cancelDelete(OwnerType.EVENT, r.id, r.remoteId != null)
            r.copy(isDeleted = false, updatedAt = now, syncState = if (r.remoteId != null) SyncState.PENDING_UPDATE else SyncState.PENDING_CREATE)
        }
        cur != null -> r.copy(
            remoteId = cur.remoteId, etag = cur.etag, remoteUpdatedAt = cur.remoteUpdatedAt, updatedAt = now,
            syncState = outbox.recordUpdate(OwnerType.EVENT, r.id, cur.syncState),
        )
        r.recurringParentId != null && r.remoteId != null -> null
        else -> {
            outbox.recordCreate(OwnerType.EVENT, r.id)
            r.copy(
                remoteId = null, etag = null, remoteUpdatedAt = null, isDeleted = false, updatedAt = now,
                pendingClientId = app.daysemble.data.google.calendar.CalendarMapper.newClientId(),
                syncState = SyncState.PENDING_CREATE,
            )
        }
    }

    /** Deletes a whole event / series (soft delete for Google until the remote delete succeeds). */
    suspend fun delete(id: String): Deleted? {
        var removed: Deleted? = null
        val google = db.withTransaction {
            removed = snapshot(id)
            val row = events.get(id) ?: return@withTransaction null
            val g = calendars.get(row.calendarId)?.kind == SourceKind.GOOGLE
            if (!g) {
                events.hardDelete(id)
            } else when (outbox.recordDelete(OwnerType.EVENT, id, row.remoteId != null)) {
                Outbox.DeleteResult.HARD_DELETE_LOCALLY -> events.hardDelete(id)
                Outbox.DeleteResult.PUSH_DELETE -> events.upsert(row.copy(isDeleted = true, syncState = SyncState.PENDING_DELETE))
            }
            // The series' changed occurrences go with it, so their reminders do too.
            removed?.rows.orEmpty().forEach { db.reminders().deleteForOwner(OwnerType.EVENT, it.id) }
            g
        } ?: return null
        notify(id, google)
        return removed
    }

    /** Removes one instance of a series by adding an EXDATE to the master. */
    suspend fun deleteOccurrence(eventId: String, key: String): Deleted? {
        var removed: Deleted? = null
        val google = db.withTransaction {
            removed = snapshot(eventId)
            val row = get(eventId) ?: return@withTransaction null
            if (row.rrule == null) return@withTransaction null
            val g = calendars.get(row.calendarId)?.kind == SourceKind.GOOGLE
            val keys = row.exdates.split(',').filter { it.isNotBlank() }.toMutableSet().apply { add(key) }
            val state = if (g) outbox.recordUpdate(OwnerType.EVENT, eventId, row.syncState) else SyncState.LOCAL
            events.upsert(row.copy(exdates = keys.sorted().joinToString(","), updatedAt = clock.now().toEpochMilli(), syncState = state))
            g
        } ?: run {
            // Not a series: deleting "this occurrence" is deleting the event.
            return delete(eventId)
        }
        notify(eventId, google)
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

    private fun notify(id: String, google: Boolean) {
        listener.onEventChanged(id)
        if (google) listener.onPendingSync()
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
                    // Can't expand locally. For Google series, sync stores Google's instances as override
                    // rows (picked up below); otherwise show the first occurrence only.
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
                isGoogle = cal.kind == SourceKind.GOOGLE,
                isReadOnly = !isWritableRole(cal.accessRole) || row.isReadOnlyRecurrence,
            )
    }
}

fun isWritableRole(role: String) = role == "owner" || role == "writer" || role == "writerWithoutPrivateAccess"

internal fun CalendarSourceEntity.toInfo() = CalendarInfo(
    id = id, name = name, colorArgb = colorArgb, isGoogle = kind == SourceKind.GOOGLE, accountEmail = accountEmail,
    isVisible = isVisible, isWritable = isWritableRole(accessRole), isSyncEnabled = isSyncEnabled,
)

internal fun androidx.compose.ui.graphics.Color.toArgbInt(): Int {
    val a = (alpha * 255 + 0.5f).toInt(); val r = (red * 255 + 0.5f).toInt()
    val g = (green * 255 + 0.5f).toInt(); val b = (blue * 255 + 0.5f).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
