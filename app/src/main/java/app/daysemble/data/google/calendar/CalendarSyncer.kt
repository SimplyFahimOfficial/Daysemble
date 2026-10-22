package app.daysemble.data.google.calendar

import androidx.room.withTransaction
import app.daysemble.core.time.AppClock
import app.daysemble.data.db.CalendarSourceEntity
import app.daysemble.data.db.ConflictLogEntity
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.EventEntity
import app.daysemble.data.db.OutboxOp
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.ReminderEntity
import app.daysemble.data.db.SourceKind
import app.daysemble.data.db.SyncState
import app.daysemble.data.google.ApiError
import app.daysemble.data.repo.isWritableRole
import java.util.UUID

/**
 * Two-way Google Calendar sync for one account.
 *
 * Order: refresh calendar list → push local outbox → pull changes (syncToken, 410 → full resync).
 * A pull is fetched completely (all pages) before anything is applied, then applied in a single
 * transaction and only then is the new sync token stored, so an interrupted sync never leaves
 * half-applied state or skips changes.
 *
 * Conflict rule (design.md §7): when a remote change meets an unpushed local change, the more
 * recently edited version wins (local updatedAt vs remote `updated`); the loser is written to
 * conflict_log. A remote deletion never silently drops unpushed local edits.
 */
class CalendarSyncer(
    private val db: DaysembleDatabase,
    private val api: CalendarApi,
    private val clock: AppClock,
) {
    private val events = db.events()
    private val cals = db.calendars()

    data class Result(val pushed: Int, val pulled: Int, val conflicts: Int, val failedOps: Int)

    suspend fun sync(account: String): Result {
        refreshCalendars(account)
        val push = pushOutbox()
        var pulled = 0
        var conflicts = push.second
        for (cal in cals.byKind(SourceKind.GOOGLE).filter { it.accountEmail == account && it.isSyncEnabled && it.remoteId != null }) {
            val r = pullCalendar(cal)
            pulled += r.first; conflicts += r.second
        }
        return Result(push.first, pulled, conflicts, push.third)
    }

    // ---- Calendars ----------------------------------------------------------------------------

    suspend fun refreshCalendars(account: String) {
        val remote = buildList {
            var page: String? = null
            do { val p = api.calendarList(page); addAll(p.items); page = p.nextPageToken } while (page != null)
        }.filter { !it.deleted && it.accessRole != "freeBusyReader" && it.accessRole != "none" }
        db.withTransaction {
            val existing = cals.byKind(SourceKind.GOOGLE).filter { it.accountEmail == account }
            val byRemote = existing.associateBy { it.remoteId }
            for (c in remote) {
                val old = byRemote[c.id]
                cals.upsert(
                    CalendarSourceEntity(
                        id = old?.id ?: UUID.randomUUID().toString(),
                        kind = SourceKind.GOOGLE, remoteId = c.id, accountEmail = account,
                        name = c.summaryOverride ?: c.summary ?: c.id,
                        // A colour the user picked locally wins; new calendars start with Google's colour.
                        colorArgb = old?.colorArgb ?: CalendarMapper.parseColor(c.backgroundColor) ?: DEFAULT_COLOR,
                        isVisible = old?.isVisible ?: true,
                        isSyncEnabled = old?.isSyncEnabled ?: true,
                        isPrimary = c.primary, accessRole = c.accessRole,
                        syncToken = old?.syncToken, timeZone = c.timeZone,
                        defaultReminders = c.defaultReminders.filter { it.method == "popup" }.joinToString(",") { it.minutes.toString() },
                    ),
                )
            }
            // Calendars removed on Google (unsubscribed/deleted) disappear locally with their events.
            val keep = remote.map { it.id }.toSet()
            existing.filter { it.remoteId !in keep }.forEach { cals.delete(it.id) }
        }
    }

    // ---- Push ---------------------------------------------------------------------------------

    /** Returns (pushed, conflicts, failed). Failures stay in the outbox with their error for retry. */
    suspend fun pushOutbox(): Triple<Int, Int, Int> {
        var pushed = 0; var conflicts = 0; var failed = 0
        for (op in db.sync().outbox(OwnerType.EVENT)) {
            val e = events.get(op.entityId)
            val cal = e?.let { cals.get(it.calendarId) }
            if (e == null || cal == null || cal.kind != SourceKind.GOOGLE || cal.remoteId == null) { db.sync().remove(op.id); continue }
            if (!cal.isSyncEnabled || op.attempts >= MAX_ATTEMPTS) continue
            if (!isWritableRole(cal.accessRole)) { db.sync().markFailed(op.id, "read_only", now()); failed++; continue }
            try {
                db.sync().markAttempt(op.id, now())
                when (op.op) {
                    OutboxOp.CREATE -> {
                        val dto = CalendarMapper.toDto(e, reminders(e.id), forInsert = true)
                            .let { if (e.etag == RECREATE) it.copy(id = null) else it }
                        val created = try { api.insert(cal.remoteId, dto) } catch (_: ApiError.Conflict) {
                            // Same client id already exists: an earlier attempt succeeded. Adopt it.
                            api.get(cal.remoteId, dto.id!!)
                        }
                        markSynced(e, created)
                    }
                    OutboxOp.UPDATE -> {
                        val rid = e.remoteId ?: run { db.sync().remove(op.id); continue }
                        try {
                            markSynced(e, api.patch(cal.remoteId, rid, CalendarMapper.toDto(e, reminders(e.id), false), e.etag))
                        } catch (_: ApiError.Precondition) {
                            // Changed remotely since our last pull: resolve now.
                            if (resolveConflict(e, api.get(cal.remoteId, rid), cal)) conflicts++
                            continue
                        } catch (_: ApiError.NotFound) {
                            remoteDeletedWhileEdited(e); conflicts++; continue
                        } catch (_: ApiError.Gone) {
                            remoteDeletedWhileEdited(e); conflicts++; continue
                        }
                    }
                    OutboxOp.DELETE -> {
                        e.remoteId?.let { rid ->
                            try { api.delete(cal.remoteId, rid) } catch (_: ApiError.NotFound) { } catch (_: ApiError.Gone) { }
                        }
                        db.withTransaction { events.hardDelete(e.id); db.reminders().deleteForOwner(OwnerType.EVENT, e.id) }
                    }
                }
                db.sync().remove(op.id)
                pushed++
            } catch (err: ApiError) {
                if (!err.isRetryable && err !is ApiError.Auth) failed++
                db.sync().markFailed(op.id, err.message ?: "error", now())
                if (err.isRetryable || err is ApiError.Auth) throw err // stop this run; WorkManager retries with backoff
            }
        }
        return Triple(pushed, conflicts, failed)
    }

    private suspend fun markSynced(local: EventEntity, remote: EventDto) {
        db.withTransaction {
            val cur = events.get(local.id) ?: return@withTransaction
            // If the user edited again during the request, keep it pending (outbox still has it next round).
            val editedMeanwhile = cur.updatedAt > local.updatedAt
            events.upsert(
                cur.copy(
                    remoteId = remote.id ?: cur.remoteId, etag = remote.etag,
                    remoteUpdatedAt = remote.updated?.let { CalendarMapper.parseInstant(it).toEpochMilli() },
                    syncState = if (editedMeanwhile) SyncState.PENDING_UPDATE else SyncState.SYNCED,
                ),
            )
            if (editedMeanwhile) db.sync().enqueue(app.daysemble.data.db.OutboxEntity(entityType = OwnerType.EVENT, entityId = cur.id, op = OutboxOp.UPDATE, createdAt = now()))
        }
    }

    private suspend fun remoteDeletedWhileEdited(e: EventEntity) {
        // Keep the user's edits: re-create remotely with a server-assigned id.
        db.withTransaction {
            log(e, "Deleted on Google while edited here. Kept your version and re-created it.", "LOCAL")
            events.upsert(e.copy(remoteId = null, etag = RECREATE, syncState = SyncState.PENDING_CREATE))
            db.sync().clearFor(OwnerType.EVENT, e.id)
            db.sync().enqueue(app.daysemble.data.db.OutboxEntity(entityType = OwnerType.EVENT, entityId = e.id, op = OutboxOp.CREATE, createdAt = now()))
        }
    }

    // ---- Pull ---------------------------------------------------------------------------------

    /** Returns (applied, conflicts). */
    suspend fun pullCalendar(cal: CalendarSourceEntity): Pair<Int, Int> {
        val remoteId = cal.remoteId ?: return 0 to 0
        val startedAt = now()
        var token = cal.syncToken
        var full = token == null
        val items = ArrayList<EventDto>()
        var nextToken: String?
        while (true) {
            try {
                var page: String? = null
                items.clear()
                do {
                    val p = api.events(remoteId, token, page)
                    items += p.items
                    page = p.nextPageToken
                    nextToken = p.nextSyncToken
                } while (page != null)
                break
            } catch (_: ApiError.Gone) {
                if (full) throw ApiError.Server(410)
                token = null; full = true // token expired: full resync, reconciled by remoteId below
            }
        }
        var conflicts = 0
        db.withTransaction {
            val fresh = cals.get(cal.id) ?: return@withTransaction
            // Masters and single events first, so exceptions can find their parent.
            val (exceptions, primaries) = items.partition { it.recurringEventId != null }
            val seenRemote = HashSet<String>()
            for (dto in primaries + exceptions) {
                if (dto.id == null) continue
                seenRemote += dto.id
                if (applyRemote(dto, fresh)) conflicts++
            }
            if (full) {
                // After a full listing, synced local rows Google no longer has were deleted remotely.
                // Rows pushed during this run may not be listed yet: never treat them as deleted.
                allSynced(cal.id).filter { it.remoteId != null && it.remoteId !in seenRemote && (it.remoteUpdatedAt ?: 0) < startedAt - 5 * 60_000 }.forEach {
                    events.hardDelete(it.id); db.reminders().deleteForOwner(OwnerType.EVENT, it.id)
                }
            }
            cals.setSyncToken(cal.id, nextToken)
        }
        // Series whose rule we can't expand: materialise Google's own instances as rows.
        val readOnlyMasters = items.filter { it.recurringEventId == null && it.status != "cancelled" && it.id != null }
            .mapNotNull { dto -> events.byRemoteId(cal.id, dto.id!!)?.takeIf { it.isReadOnlyRecurrence } }
        for (m in readOnlyMasters) refreshInstances(cal, m)
        return items.size to conflicts
    }

    /**
     * Stores Google-expanded instances (−30 d … +365 d) of a series with an unsupported rule as
     * override rows, so every occurrence displays. Called when the series changes.
     */
    suspend fun refreshInstances(cal: CalendarSourceEntity, master: EventEntity) {
        val calRemote = cal.remoteId ?: return
        val masterRemote = master.remoteId ?: return
        val now = clock.now()
        val min = now.minusSeconds(30L * 86400).toString()
        val max = now.plusSeconds(365L * 86400).toString()
        val all = buildList {
            var page: String? = null
            do { val p = api.instances(calRemote, masterRemote, min, max, page); addAll(p.items); page = p.nextPageToken } while (page != null && size < 2000)
        }
        db.withTransaction {
            val fresh = cals.get(cal.id) ?: return@withTransaction
            val parent = events.get(master.id) ?: return@withTransaction
            val keep = HashSet<String>()
            for (dto in all) {
                val id = dto.id ?: continue
                keep += id
                val existing = events.byRemoteId(cal.id, id)
                if (existing != null && existing.syncState != SyncState.SYNCED) continue
                CalendarMapper.toEntity(dto.copy(recurringEventId = masterRemote), fresh, existing, parent, now())?.let { events.upsert(it) }
            }
            // Instances that no longer exist in the window (rule changed) are removed.
            events.overridesFor(listOf(parent.id)).filter { it.remoteId != null && it.remoteId !in keep && !it.isCancelled && it.syncState == SyncState.SYNCED }
                .forEach { events.hardDelete(it.id) }
        }
    }

    private suspend fun allSynced(calendarId: String): List<EventEntity> =
        events.allIn(calendarId).filter { it.syncState == SyncState.SYNCED }

    /** Applies one remote item. Returns true if it was a conflict. */
    private suspend fun applyRemote(dto: EventDto, cal: CalendarSourceEntity): Boolean {
        val local = events.byRemoteId(cal.id, dto.id!!)
        val parent = dto.recurringEventId?.let { events.byRemoteId(cal.id, it) }
        if (dto.recurringEventId != null && parent == null) return false // parent outside our data; ignore orphan exception

        if (dto.status == "cancelled") {
            if (dto.recurringEventId != null && parent != null) {
                // Cancelled instance of a series → cancelled override row (suppresses that occurrence).
                val key = CalendarMapper.originalKey(dto, java.time.ZoneId.of(parent.timeZone)) ?: return false
                val existing = local ?: events.override(parent.id, key)
                events.upsert(
                    EventRepository.withEnvelope(
                        (existing ?: parent.copy(id = UUID.randomUUID().toString(), rrule = null, exdates = "", createdAt = now()))
                            .copy(remoteId = dto.id, recurringParentId = parent.id, originalStartKey = key, isCancelled = true, syncState = SyncState.SYNCED),
                    ),
                )
                return false
            }
            if (local == null) return false
            return if (local.syncState == SyncState.PENDING_UPDATE || local.syncState == SyncState.PENDING_CREATE) {
                remoteDeletedWhileEdited(local); true
            } else {
                events.hardDelete(local.id); db.reminders().deleteForOwner(OwnerType.EVENT, local.id); false
            }
        }

        val incoming = CalendarMapper.toEntity(dto, cal, local, parent, now()) ?: return false
        if (local == null || local.syncState == SyncState.SYNCED || local.syncState == SyncState.LOCAL) {
            events.upsert(incoming)
            replaceReminders(incoming.id, CalendarMapper.reminderMinutes(dto, cal))
            return false
        }
        if (local.syncState == SyncState.PENDING_DELETE) {
            // The user deleted it here; that intent stands. Note the remote edit that loses.
            log(local, "Edited on Google, but you deleted it here. Deleted.", "LOCAL")
            return true
        }
        return resolveConflict(local, dto, cal)
    }

    /** Last-writer-wins between an unpushed local edit and a newer remote version. Returns true (a conflict was logged). */
    private suspend fun resolveConflict(local: EventEntity, remote: EventDto, cal: CalendarSourceEntity): Boolean {
        val remoteUpdated = remote.updated?.let { CalendarMapper.parseInstant(it).toEpochMilli() } ?: 0L
        if (local.remoteUpdatedAt != null && remoteUpdated <= local.remoteUpdatedAt) return false // nothing new remotely
        val parent = remote.recurringEventId?.let { events.byRemoteId(cal.id, it) }
        db.withTransaction {
            if (remoteUpdated > local.updatedAt) {
                val incoming = CalendarMapper.toEntity(remote, cal, local, parent, now()) ?: return@withTransaction
                log(local, summary(local), "REMOTE")
                events.upsert(incoming)
                replaceReminders(local.id, CalendarMapper.reminderMinutes(remote, cal))
                db.sync().clearFor(OwnerType.EVENT, local.id)
            } else {
                log(local, "Google version: " + (remote.summary ?: ""), "LOCAL")
                // Keep ours; adopt the new etag so the next PATCH applies.
                events.upsert(local.copy(etag = remote.etag, remoteUpdatedAt = remoteUpdated))
                if (db.sync().outbox(OwnerType.EVENT).none { it.entityId == local.id }) {
                    db.sync().enqueue(app.daysemble.data.db.OutboxEntity(entityType = OwnerType.EVENT, entityId = local.id, op = OutboxOp.UPDATE, createdAt = now()))
                }
            }
        }
        return true
    }

    private fun summary(e: EventEntity) = buildString {
        append("Your version: ").append(e.title)
        if (e.location.isNotBlank()) append(" · ").append(e.location)
    }

    private suspend fun log(e: EventEntity, losing: String, winner: String) = db.sync().logConflict(
        ConflictLogEntity(entityType = OwnerType.EVENT, entityId = e.id, title = e.title, losingVersion = losing, resolvedWith = winner, at = now()),
    )

    private suspend fun reminders(id: String) = db.reminders().forOwner(OwnerType.EVENT, id).map { it.minutesBefore }

    private suspend fun replaceReminders(id: String, minutes: List<Int>) = db.reminders().replace(
        OwnerType.EVENT, id, minutes.distinct().take(5).map { ReminderEntity(UUID.randomUUID().toString(), OwnerType.EVENT, id, it) },
    )

    private fun now() = clock.now().toEpochMilli()

    companion object {
        /** Marks a row to be re-created with a server-assigned id (its old id was deleted remotely). */
        const val RECREATE = "daysemble:recreate"
        /** After this many failed pushes an op is parked and shown in Troubleshooting. */
        const val MAX_ATTEMPTS = 5
        private const val DEFAULT_COLOR = 0xFF2F4FA8.toInt()
    }
}

private typealias EventRepository = app.daysemble.data.repo.EventRepository
