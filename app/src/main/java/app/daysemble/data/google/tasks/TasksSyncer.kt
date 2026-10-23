package app.daysemble.data.google.tasks

import androidx.room.withTransaction
import app.daysemble.core.time.AppClock
import app.daysemble.data.db.ConflictLogEntity
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.OutboxEntity
import app.daysemble.data.db.OutboxOp
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.SourceKind
import app.daysemble.data.db.SyncState
import app.daysemble.data.db.TaskEntity
import app.daysemble.data.db.TaskListEntity
import app.daysemble.data.google.ApiError
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Two-way Google Tasks sync. Differences from Calendar: no sync tokens (we use updatedMin with a
 * 5-minute overlap), no client-supplied ids, no etag preconditions, no priority or due time.
 * Priority and due time stay local; the due *date* syncs.
 */
class TasksSyncer(
    private val db: DaysembleDatabase,
    private val api: TasksApi,
    private val clock: AppClock,
) {
    private val tasks = db.tasks()
    private val lists = db.taskLists()

    data class Result(val pushed: Int, val pulled: Int, val conflicts: Int, val failedOps: Int)

    suspend fun sync(account: String): Result {
        refreshLists(account)
        val (pushed, conflicts, failed) = pushOutbox()
        var pulled = 0; var c = conflicts
        for (l in lists.all().filter { it.kind == SourceKind.GOOGLE && it.accountEmail == account && it.isSyncEnabled && it.remoteId != null }) {
            val r = pullList(l); pulled += r.first; c += r.second
        }
        return Result(pushed, pulled, c, failed)
    }

    suspend fun refreshLists(account: String) {
        val remote = buildList {
            var page: String? = null
            do { val p = api.lists(page); addAll(p.items); page = p.nextPageToken } while (page != null)
        }
        db.withTransaction {
            val existing = lists.all().filter { it.kind == SourceKind.GOOGLE && it.accountEmail == account }
            val byRemote = existing.associateBy { it.remoteId }
            remote.forEachIndexed { i, l ->
                val old = byRemote[l.id]
                lists.upsert(
                    TaskListEntity(
                        id = old?.id ?: UUID.randomUUID().toString(), kind = SourceKind.GOOGLE, remoteId = l.id,
                        accountEmail = account, title = l.title, sortOrder = i,
                        isSyncEnabled = old?.isSyncEnabled ?: true, lastPulledAt = old?.lastPulledAt,
                    ),
                )
            }
            val keep = remote.map { it.id }.toSet()
            existing.filter { it.remoteId !in keep }.forEach { lists.delete(it.id) }
        }
    }

    // ---- Push ---------------------------------------------------------------------------------

    suspend fun pushOutbox(): Triple<Int, Int, Int> {
        var pushed = 0; var conflicts = 0; var failed = 0
        // Parents before children: a subtask can only be created once its parent has a remote id.
        val raw = db.sync().outbox(OwnerType.TASK)
        val isChild = raw.associate { it.entityId to (tasks.get(it.entityId)?.parentId != null) }
        val ops = raw.sortedWith(compareBy({ isChild[it.entityId] == true }, { it.id }))
        for (op in ops) {
            if (op.op == OutboxOp.DELETE && op.snapshotRemoteId != null && op.snapshotContainerId != null) {
                // Remote copy left behind by a move: delete it there, keep the local row.
                if (op.permanentFailures >= app.daysemble.data.google.calendar.CalendarSyncer.MAX_ATTEMPTS) continue
                try {
                    db.sync().markAttempt(op.id, now())
                    try { api.delete(op.snapshotContainerId, op.snapshotRemoteId) } catch (_: ApiError.NotFound) { } catch (_: ApiError.Gone) { }
                    db.sync().remove(op.id); pushed++
                } catch (err: ApiError) {
                    if (err.isRetryable || err is ApiError.Auth) { db.sync().markFailed(op.id, err.message ?: "error", now()); throw err }
                    db.sync().markRejected(op.id, err.message ?: "error", now())
                    failed++
                }
                continue
            }
            val t = tasks.get(op.entityId)
            val list = t?.let { lists.get(it.listId) }
            if (t == null || list == null || list.kind != SourceKind.GOOGLE || list.remoteId == null) { db.sync().remove(op.id); continue }
            if (!list.isSyncEnabled || op.permanentFailures >= app.daysemble.data.google.calendar.CalendarSyncer.MAX_ATTEMPTS) continue
            try {
                when (op.op) {
                    OutboxOp.CREATE -> {
                        val parentRemote = t.parentId?.let { tasks.get(it)?.remoteId }
                        if (t.parentId != null && parentRemote == null) continue // parent not pushed yet
                        val adopted = if (op.attempts > 0) findPreviousCreate(list.remoteId, t, op.lastAttemptAt) else null
                        db.sync().markAttempt(op.id, now())
                        val created = adopted ?: api.insert(list.remoteId, patchOf(t), parentRemote)
                        markSynced(t, created)
                    }
                    OutboxOp.UPDATE -> {
                        val rid = t.remoteId ?: run { db.sync().remove(op.id); continue }
                        db.sync().markAttempt(op.id, now())
                        val remote = try { api.get(list.remoteId, rid) } catch (_: ApiError.NotFound) { null }
                        if (remote == null || remote.deleted) { remoteDeletedWhileEdited(t); conflicts++; continue }
                        val remoteUpdated = remote.updated?.let(::millis) ?: 0
                        if (t.remoteUpdatedAt != null && remoteUpdated > t.remoteUpdatedAt && remoteUpdated > t.updatedAt) {
                            // Newer remote edit wins over our older pending edit.
                            applyRemoteVersion(t, remote, list, logLocalLoss = true); conflicts++
                            db.sync().remove(op.id); continue
                        }
                        if (t.remoteUpdatedAt != null && remoteUpdated > t.remoteUpdatedAt) {
                            log(t, "Google version: " + (remote.title ?: ""), "LOCAL"); conflicts++
                        }
                        markSynced(t, api.patch(list.remoteId, rid, patchOf(t)))
                    }
                    OutboxOp.DELETE -> {
                        db.sync().markAttempt(op.id, now())
                        // A create whose response was lost has no remoteId: find the copy it may have made.
                        val rid = t.remoteId ?: findPreviousCreate(list.remoteId, t, op.lastAttemptAt ?: t.updatedAt)?.id
                        rid?.let { try { api.delete(list.remoteId, it) } catch (_: ApiError.NotFound) { } catch (_: ApiError.Gone) { } }
                        db.withTransaction { tasks.hardDelete(t.id); db.reminders().deleteForOwner(OwnerType.TASK, t.id) }
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
                failed++
            }
        }
        return Triple(pushed, conflicts, failed)
    }

    /**
     * Google Tasks has no client ids, so a create whose response was lost could have succeeded.
     * Before re-sending, look for an unmapped remote task with the same title, notes and due date
     * created around the earlier attempt and adopt it instead of duplicating.
     */
    private suspend fun findPreviousCreate(listRemoteId: String, t: TaskEntity, lastAttempt: Long?): TaskDto? {
        val since = Instant.ofEpochMilli(minOf(lastAttempt ?: t.createdAt, t.createdAt) - 2 * 60_000).toString()
        val items = ArrayList<TaskDto>()
        var token: String? = null
        do {
            val page = api.tasks(listRemoteId, since, token) // errors propagate: don't guess "not found" offline
            items += page.items
            token = page.nextPageToken
        } while (token != null)
        val mapped = tasks.allIn(t.listId).mapNotNull { it.remoteId }.toSet()
        val due = t.dueDate?.let { LocalDate.ofEpochDay(it).toString() }
        return items.firstOrNull {
            !it.deleted && it.id !in mapped && it.title.orEmpty() == t.title && it.notes.orEmpty() == t.notes && it.due?.take(10) == due
        }
    }

    private fun patchOf(t: TaskEntity) = TaskPatch(
        title = t.title, notes = t.notes,
        dueDate = t.dueDate?.let { LocalDate.ofEpochDay(it).toString() },
        completed = t.isCompleted,
        completedAt = t.completedAt?.let { Instant.ofEpochMilli(it).toString() },
    )

    private suspend fun markSynced(local: TaskEntity, remote: TaskDto) = db.withTransaction {
        val cur = tasks.get(local.id) ?: return@withTransaction
        val editedMeanwhile = cur.updatedAt > local.updatedAt
        tasks.upsert(
            cur.copy(
                remoteId = remote.id ?: cur.remoteId, etag = remote.etag, remotePosition = remote.position,
                remoteUpdatedAt = remote.updated?.let(::millis),
                syncState = if (editedMeanwhile) SyncState.PENDING_UPDATE else SyncState.SYNCED,
            ),
        )
        if (editedMeanwhile) db.sync().enqueue(OutboxEntity(entityType = OwnerType.TASK, entityId = cur.id, op = OutboxOp.UPDATE, createdAt = now()))
    }

    private suspend fun remoteDeletedWhileEdited(t: TaskEntity) = db.withTransaction {
        log(t, "Deleted in Google Tasks while edited here. Kept your version and re-created it.", "LOCAL")
        tasks.upsert(t.copy(remoteId = null, syncState = SyncState.PENDING_CREATE))
        db.sync().clearFor(OwnerType.TASK, t.id)
        db.sync().enqueue(OutboxEntity(entityType = OwnerType.TASK, entityId = t.id, op = OutboxOp.CREATE, createdAt = now()))
    }

    // ---- Pull ---------------------------------------------------------------------------------

    suspend fun pullList(list: TaskListEntity): Pair<Int, Int> {
        val rid = list.remoteId ?: return 0 to 0
        val startedAt = now()
        val full = list.lastPulledAt == null
        val updatedMin = list.lastPulledAt?.let { Instant.ofEpochMilli(it - OVERLAP_MS).toString() }
        val items = buildList {
            var page: String? = null
            do { val p = api.tasks(rid, updatedMin, page); addAll(p.items); page = p.nextPageToken } while (page != null)
        }
        var conflicts = 0
        db.withTransaction {
            val (children, roots) = items.partition { it.parent != null }
            val seen = HashSet<String>()
            for (dto in roots + children) {
                val id = dto.id ?: continue
                seen += id
                if (applyRemote(dto, list)) conflicts++
            }
            if (full) {
                // Rows pushed during this run may not be listed yet (eventual consistency): never treat them as deleted.
                tasks.allIn(list.id).filter {
                    it.syncState == SyncState.SYNCED && it.remoteId != null && it.remoteId !in seen && (it.remoteUpdatedAt ?: 0) < startedAt - OVERLAP_MS
                }
                    .forEach { tasks.hardDelete(it.id); db.reminders().deleteForOwner(OwnerType.TASK, it.id) }
            }
            lists.setLastPulled(list.id, startedAt)
        }
        return items.size to conflicts
    }

    private suspend fun applyRemote(dto: TaskDto, list: TaskListEntity): Boolean {
        val local = tasks.byRemoteId(list.id, dto.id!!)
        if (dto.deleted) {
            if (local == null) return false
            return when (local.syncState) {
                SyncState.PENDING_UPDATE, SyncState.PENDING_CREATE -> { remoteDeletedWhileEdited(local); true }
                SyncState.PENDING_DELETE -> { tasks.hardDelete(local.id); db.sync().clearFor(OwnerType.TASK, local.id); false }
                else -> { tasks.hardDelete(local.id); db.reminders().deleteForOwner(OwnerType.TASK, local.id); false }
            }
        }
        val remoteUpdated = dto.updated?.let(::millis) ?: 0
        if (local != null && local.remoteUpdatedAt != null && remoteUpdated <= local.remoteUpdatedAt) return false // already have it
        return when (local?.syncState) {
            null, SyncState.SYNCED, SyncState.LOCAL -> { applyRemoteVersion(local, dto, list, logLocalLoss = false); false }
            SyncState.PENDING_DELETE -> { log(local, "Edited in Google Tasks, but you deleted it here. Deleted.", "LOCAL"); true }
            else -> {
                if (remoteUpdated > local.updatedAt) {
                    applyRemoteVersion(local, dto, list, logLocalLoss = true)
                    db.sync().clearFor(OwnerType.TASK, local.id)
                } else {
                    log(local, "Google version: " + (dto.title ?: ""), "LOCAL")
                    tasks.upsert(local.copy(remoteUpdatedAt = remoteUpdated))
                }
                true
            }
        }
    }

    private suspend fun applyRemoteVersion(local: TaskEntity?, dto: TaskDto, list: TaskListEntity, logLocalLoss: Boolean) {
        if (logLocalLoss && local != null) log(local, "Your version: " + local.title, "REMOTE")
        val due = dto.due?.take(10)?.let { LocalDate.parse(it).toEpochDay() }
        val parentLocal = dto.parent?.let { tasks.byRemoteId(list.id, it)?.id }
        val now = now()
        tasks.upsert(
            TaskEntity(
                id = local?.id ?: UUID.randomUUID().toString(),
                listId = list.id,
                parentId = parentLocal,
                remoteId = dto.id, etag = dto.etag,
                title = dto.title.orEmpty(), notes = dto.notes.orEmpty(),
                dueDate = due,
                // Local-only fields survive remote edits; a due time is kept only while the date is unchanged.
                dueTimeMinutes = local?.dueTimeMinutes?.takeIf { local.dueDate == due },
                priority = local?.priority ?: 0,
                isCompleted = dto.status == "completed",
                completedAt = if (dto.status == "completed") dto.completed?.let(::millis) ?: local?.completedAt ?: now else null,
                sortKey = local?.sortKey ?: (tasks.maxSortKey(list.id) + 1),
                remotePosition = dto.position,
                createdAt = local?.createdAt ?: now,
                updatedAt = dto.updated?.let(::millis) ?: now,
                remoteUpdatedAt = dto.updated?.let(::millis),
                syncState = SyncState.SYNCED,
            ),
        )
    }

    private suspend fun log(t: TaskEntity, losing: String, winner: String) = db.sync().logConflict(
        ConflictLogEntity(entityType = OwnerType.TASK, entityId = t.id, title = t.title, losingVersion = losing, resolvedWith = winner, at = now()),
    )

    private fun millis(s: String) = runCatching { Instant.parse(s).toEpochMilli() }.getOrElse { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
    private fun now() = clock.now().toEpochMilli()

    companion object {
        const val OVERLAP_MS = 5 * 60_000L
    }
}
