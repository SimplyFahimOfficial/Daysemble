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
            if (op.op == OutboxOp.DELETE && op.snapshotRemoteId != null && op.snapshotContainerId != null) {
                // Remote copy left behind by a calendar move: delete it there, keep the local row.
                if (op.permanentFailures >= MAX_ATTEMPTS) continue
                try {
                    db.sync().markAttempt(op.id, now())
                    try { api.delete(op.snapshotContainerId, op.snapshotRemoteId) } catch (_: ApiError.NotFound) { } catch (_: ApiError.Gone) { }
                    db.sync().remove(op.id); pushed++
                } catch (err: ApiError) {
                    if (err.isRetryable || err is ApiError.Auth) { db.sync().markFailed(op.id, err.message ?: "error", now()); throw err }
                    db.sync().markRejected(op.id, err.message ?: "error", now()); failed++
                }
                continue
            }
            val e = events.get(op.entityId)
            val cal = e?.let { cals.get(it.calendarId) }
            if (e == null || cal == null || cal.kind != SourceKind.GOOGLE || cal.remoteId == null) { db.sync().remove(op.id); continue }
            if (!cal.isSyncEnabled || op.permanentFailures >= MAX_ATTEMPTS) continue
            // Google will never accept it: a permanent rejection, so it is parked after MAX_ATTEMPTS and
            // shown under "rejected changes" with Retry, instead of failing every sync run forever.
            if (!isWritableRole(cal.accessRole)) { db.sync().markRejected(op.id, "read_only", now()); failed++; continue }
            try {
                db.sync().markAttempt(op.id, now())
                when (op.op) {
                    OutboxOp.CREATE -> {
                        val dto = CalendarMapper.toDto(e, reminders(e.id), forInsert = true)
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
                        // A create whose response was lost has no remoteId yet, but used our deterministic client id.
                        val rid = e.remoteId ?: CalendarMapper.clientIdOf(e)
                        try { api.delete(cal.remoteId, rid) } catch (_: ApiError.NotFound) { } catch (_: ApiError.Gone) { }
                        remoteDeleted(e.id)
                    }
                }
                db.sync().remove(op.id)
                pushed++
            } catch (err: ApiError) {
                if (err.isRetryable || err is ApiError.Auth) {
                    db.sync().markFailed(op.id, err.message ?: "error", now())
                    throw err // stop this run; WorkManager retries with backoff
                }
                db.sync().markRejected(op.id, err.message ?: "error", now())
                failed++ // stop this run; WorkManager retries with backoff
            }
        }
        return Triple(pushed, conflicts, failed)
    }

    /** The pushed delete went through on Google: remove the row, unless the delete was undone meanwhile. */
    private suspend fun remoteDeleted(id: String) = db.withTransaction {
        val cur = events.get(id)
        if (cur == null || cur.syncState == SyncState.PENDING_DELETE) {
            events.hardDelete(id); db.reminders().deleteForOwner(OwnerType.EVENT, id)
            return@withTransaction
        }
        // Undone while the request ran: the restored row stays. Its Google copy is gone, so it is
        // created again, with a new client id (the old one may still be held by the deleted copy).
        events.upsert(
            cur.copy(
                remoteId = null, etag = null, remoteUpdatedAt = null,
                pendingClientId = CalendarMapper.newClientId(), syncState = SyncState.PENDING_CREATE,
            ),
        )
        // Changed occurrences of the deleted Google series can't follow (as in EventRepository.restore).
        events.overridesFor(listOf(id)).filter { it.remoteId != null }.forEach { o ->
            events.hardDelete(o.id); db.reminders().deleteForOwner(OwnerType.EVENT, o.id); db.sync().clearFor(OwnerType.EVENT, o.id)
        }
        db.sync().clearFor(OwnerType.EVENT, id)
        db.sync().enqueue(app.daysemble.data.db.OutboxEntity(entityType = OwnerType.EVENT, entityId = id, op = OutboxOp.CREATE, createdAt = now()))
    }

    private suspend fun markSynced(local: EventEntity, remote: EventDto) {
        db.withTransaction {
            val cur = events.get(local.id) ?: return@withTransaction
            // Moved to another calendar during the request: [remote] is the copy in the old calendar,
            // which the move's queued delete removes. Don't give the moved row that copy's identity.
            if (cur.calendarId != local.calendarId) return@withTransaction
            // If the user edited again during the request, keep it pending (outbox still has it next round).
            val editedMeanwhile = cur.updatedAt > local.updatedAt
            events.upsert(
                cur.copy(
                    remoteId = remote.id ?: cur.remoteId, etag = remote.etag, pendingClientId = null,
                    remoteUpdatedAt = remote.updated?.let { CalendarMapper.parseInstant(it).toEpochMilli() },
                    syncState = if (editedMeanwhile) SyncState.PENDING_UPDATE else SyncState.SYNCED,
                ),
            )
            if (editedMeanwhile) db.sync().enqueue(app.daysemble.data.db.OutboxEntity(entityType = OwnerType.EVENT, entityId = cur.id, op = OutboxOp.UPDATE, createdAt = now()))
        }
    }

    private suspend fun remoteDeletedWhileEdited(snapshot: EventEntity) {
        // Keep the user's edits: re-create remotely with a server-assigned id.
        db.withTransaction {
            // Re-read: on the push path [snapshot] was read before a network call, and the user may
            // have edited again since. Writing the snapshot back would drop that edit.
            val e = events.get(snapshot.id) ?: return@withTransaction
            // Moved to another calendar meanwhile: the move queued its own ops for the new calendar.
            if (e.calendarId != snapshot.calendarId) return@withTransaction
            if (e.syncState == SyncState.PENDING_DELETE) {
                // Deleted on both sides: nothing to keep.
                events.hardDelete(e.id); db.reminders().deleteForOwner(OwnerType.EVENT, e.id); db.sync().clearFor(OwnerType.EVENT, e.id)
                return@withTransaction
            }
            log(e, "Deleted on Google while edited here. Kept your version and re-created it.", "LOCAL")
            // New stable client id: the old one may still be held by the deleted copy.
            events.upsert(e.copy(remoteId = null, etag = null, pendingClientId = CalendarMapper.newClientId(), syncState = SyncState.PENDING_CREATE))
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
        // Series whose rule we can't expand: materialise Google's own instances as rows. Tracked per
        // series (instancesUntil) rather than via the sync token, so a failed request is retried on the
        // next run and the window keeps moving forward for unchanged series.
        val changed = items.mapNotNull { it.id }.toSet()
        val horizon = clock.now().plusSeconds(300L * 86400).toEpochMilli()
        val due = events.allIn(cal.id).filter {
            it.isReadOnlyRecurrence && it.recurringParentId == null && !it.isDeleted &&
                (it.remoteId in changed || (it.instancesUntil ?: 0) < horizon)
        }
        for (m in due) {
            try {
                refreshInstances(cal, m)
            } catch (e: ApiError) {
                if (e.isRetryable || e is ApiError.Auth) throw e
                // Permanent error for this series (e.g. deleted meanwhile): skip, others continue.
            }
        }
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
            events.upsert(parent.copy(instancesUntil = now.plusSeconds(365L * 86400).toEpochMilli()))
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
    private suspend fun resolveConflict(snapshot: EventEntity, remote: EventDto, cal: CalendarSourceEntity): Boolean {
        val remoteUpdated = remote.updated?.let { CalendarMapper.parseInstant(it).toEpochMilli() } ?: 0L
        val parent = remote.recurringEventId?.let { events.byRemoteId(cal.id, it) }
        return db.withTransaction {
            // Decide on the current row, not [snapshot]: after a 412 the snapshot was read before two
            // network calls, and an edit made meanwhile must neither be overwritten nor lose its push.
            val local = events.get(snapshot.id) ?: return@withTransaction false
            // Deleted meanwhile: the local delete stands (its DELETE op is queued).
            if (local.syncState == SyncState.PENDING_DELETE) return@withTransaction false
            // Moved to another calendar meanwhile: [remote] and [cal] describe the old copy, which the
            // move's queued delete removes. Applying either would pull the event back or misplace its etag.
            if (local.calendarId != cal.id) return@withTransaction false
            if (local.remoteUpdatedAt != null && remoteUpdated <= local.remoteUpdatedAt) return@withTransaction false // nothing new remotely
            if (remoteUpdated > local.updatedAt) {
                val incoming = CalendarMapper.toEntity(remote, cal, local, parent, now()) ?: return@withTransaction true
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
            true
        }
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
        OwnerType.EVENT, id, minutes.distinct().take(5).map { ReminderEntity.of(OwnerType.EVENT, id, it) },
    )

    private fun now() = clock.now().toEpochMilli()

    companion object {
        /** After this many failed pushes an op is parked and shown in Troubleshooting. */
        const val MAX_ATTEMPTS = 5
        private const val DEFAULT_COLOR = 0xFF2F4FA8.toInt()
    }
}

private typealias EventRepository = app.daysemble.data.repo.EventRepository
