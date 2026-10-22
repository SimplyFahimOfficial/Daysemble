package app.daysemble.data.repo

import androidx.room.withTransaction
import app.daysemble.core.time.AppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.ReminderEntity
import app.daysemble.data.db.SourceKind
import app.daysemble.data.db.SyncState
import app.daysemble.data.db.TaskEntity
import app.daysemble.data.db.TaskListEntity
import app.daysemble.data.sync.ChangeListener
import app.daysemble.data.sync.Outbox
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.Task
import app.daysemble.domain.model.TaskDraft
import app.daysemble.domain.model.TaskList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class TaskRepository(
    private val db: DaysembleDatabase,
    private val outbox: Outbox,
    private val clock: AppClock,
    private val listener: ChangeListener,
) {
    private val tasks = db.tasks()
    private val lists = db.taskLists()

    val taskLists: Flow<List<TaskList>> = lists.observeAll().map { all -> all.filter { it.isSyncEnabled || it.kind == SourceKind.LOCAL }.map { it.toDomain() } }

    /** All non-deleted tasks (top-level and subtasks) with subtask progress filled in. */
    val allTasks: Flow<List<Task>> = combine(tasks.observeAll(), lists.observeAll()) { rows, listRows ->
        val listById = listRows.associateBy { it.id }
        val visible = rows.filter { row -> listById[row.listId]?.let { it.kind == SourceKind.LOCAL || it.isSyncEnabled } == true }
        val children = visible.filter { it.parentId != null }.groupBy { it.parentId }
        visible.map { row ->
            val kids = children[row.id].orEmpty()
            row.toDomain(listById[row.listId]?.kind == SourceKind.GOOGLE, kids.size, kids.count { it.isCompleted })
        }
    }

    fun observeTask(id: String): Flow<Task?> = combine(tasks.observe(id), lists.observeAll()) { row, listRows ->
        row?.takeIf { !it.isDeleted }?.let { r -> r.toDomain(listRows.firstOrNull { it.id == r.listId }?.kind == SourceKind.GOOGLE) }
    }

    fun observeSubtasks(parentId: String): Flow<List<Task>> = tasks.observeSubtasks(parentId).map { rows ->
        val google = rows.firstOrNull()?.let { lists.get(it.listId)?.kind == SourceKind.GOOGLE } ?: false
        rows.map { it.toDomain(google) }
    }

    fun observeReminders(taskId: String): Flow<List<Int>> =
        db.reminders().observeForOwner(OwnerType.TASK, taskId).map { r -> r.map { it.minutesBefore } }

    /** All lists including Google ones that aren't synced (for management screens). */
    val allLists: Flow<List<TaskListEntity>> = lists.observeAll()

    suspend fun createList(title: String): String {
        require(title.isNotBlank())
        val id = UUID.randomUUID().toString()
        val order = lists.all().filter { it.kind == SourceKind.LOCAL }.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
        lists.upsert(TaskListEntity(id = id, kind = SourceKind.LOCAL, title = title.trim(), sortOrder = order))
        return id
    }

    /** Renames an on-device list. Google lists are managed in Google Tasks. */
    suspend fun renameList(id: String, title: String) {
        val l = lists.get(id) ?: return
        require(l.kind == SourceKind.LOCAL && title.isNotBlank())
        lists.rename(id, title.trim())
    }

    /**
     * Deletes an on-device list and its tasks. The last on-device list can't be deleted
     * (new tasks need somewhere to go). Returns false if refused.
     */
    suspend fun deleteList(id: String): Boolean {
        val l = lists.get(id) ?: return false
        if (l.kind != SourceKind.LOCAL) return false
        if (lists.all().count { it.kind == SourceKind.LOCAL } <= 1) return false
        val ids = tasks.allIn(id).map { it.id }
        db.withTransaction {
            ids.forEach { db.reminders().deleteForOwner(OwnerType.TASK, it) }
            lists.delete(id) // cascades to tasks
        }
        ids.forEach(listener::onTaskChanged) // cancels their alarms
        return true
    }

    suspend fun localTaskCount(listId: String) = tasks.allIn(listId).count { !it.isDeleted }

    /** Creates the local "My tasks" list on first run. Returns its id. */
    suspend fun ensureDefaultList(title: String): String = db.withTransaction {
        lists.all().firstOrNull { it.kind == SourceKind.LOCAL }?.id ?: run {
            val id = UUID.randomUUID().toString()
            lists.upsert(TaskListEntity(id = id, kind = SourceKind.LOCAL, title = title))
            id
        }
    }

    suspend fun create(draft: TaskDraft, fallbackListId: String): String {
        require(draft.isValid) { "Task title is required" }
        val now = clock.now().toEpochMilli()
        val id = UUID.randomUUID().toString()
        // One level of nesting, like Google Tasks: a subtask of a subtask attaches to the top-level parent.
        val parent = draft.parentId?.let { tasks.get(it) }?.let { p -> p.parentId?.let { tasks.get(it) } ?: p }
        val listId = parent?.listId ?: draft.listId ?: fallbackListId
        val list = lists.get(listId) ?: lists.get(fallbackListId) ?: error("No task list")
        val google = list.kind == SourceKind.GOOGLE
        db.withTransaction {
            tasks.upsert(
                TaskEntity(
                    id = id, listId = list.id, parentId = parent?.id,
                    title = draft.title.trim(), notes = draft.notes.trim(),
                    dueDate = draft.dueDate?.toEpochDay(), dueTimeMinutes = draft.dueTime?.toMinutes(),
                    priority = draft.priority.level, sortKey = tasks.maxSortKey(list.id) + 1,
                    createdAt = now, updatedAt = now,
                    syncState = if (google) SyncState.PENDING_CREATE else SyncState.LOCAL,
                ),
            )
            saveReminders(id, draft)
            if (google) outbox.recordCreate(OwnerType.TASK, id)
        }
        notify(id, google)
        return id
    }

    suspend fun update(id: String, draft: TaskDraft) {
        require(draft.isValid) { "Task title is required" }
        val google = edit(id) { row ->
            row.copy(
                title = draft.title.trim(), notes = draft.notes.trim(),
                dueDate = draft.dueDate?.toEpochDay(), dueTimeMinutes = draft.dueTime?.toMinutes(),
                priority = draft.priority.level,
            )
        } ?: return
        db.withTransaction { saveReminders(id, draft) }
        notify(id, google)
    }

    suspend fun setCompleted(id: String, completed: Boolean) {
        val now = clock.now().toEpochMilli()
        val google = edit(id) { it.copy(isCompleted = completed, completedAt = if (completed) now else null) } ?: return
        notify(id, google)
    }

    suspend fun setPriority(id: String, priority: Priority) {
        // Priority is local-only for Google tasks, so no push is needed.
        val row = tasks.get(id) ?: return
        tasks.upsert(row.copy(priority = priority.level, updatedAt = clock.now().toEpochMilli()))
        listener.onTaskChanged(id)
    }

    suspend fun setDue(id: String, date: LocalDate?, time: LocalTime?) {
        val google = edit(id) { it.copy(dueDate = date?.toEpochDay(), dueTimeMinutes = if (date == null) null else time?.toMinutes()) } ?: return
        notify(id, google)
    }

    /**
     * Moves a top-level task (and its subtasks) to another list. For Google lists the old remote copy
     * is deleted and a new one created in the destination (the Tasks API has no cross-list move).
     */
    suspend fun moveToList(id: String, newListId: String) {
        val row = tasks.get(id)?.takeIf { !it.isDeleted && it.parentId == null } ?: return
        if (row.listId == newListId) return
        val from = lists.get(row.listId) ?: return
        val to = lists.get(newListId) ?: return
        val toGoogle = to.kind == SourceKind.GOOGLE
        val now = clock.now().toEpochMilli()
        db.withTransaction {
            var sort = tasks.maxSortKey(newListId)
            for (t in listOf(row) + tasks.subtasks(id).filter { !it.isDeleted }) {
                val oldRemoteList = if (from.kind == SourceKind.GOOGLE) from.remoteId else null
                if (from.kind == SourceKind.GOOGLE || toGoogle) {
                    outbox.recordMove(OwnerType.TASK, t.id, t.remoteId, oldRemoteList, toGoogle)
                }
                tasks.upsert(
                    t.copy(
                        listId = newListId, remoteId = null, etag = null, remotePosition = null, remoteUpdatedAt = null,
                        sortKey = ++sort, updatedAt = now,
                        syncState = if (toGoogle) SyncState.PENDING_CREATE else SyncState.LOCAL,
                    ),
                )
            }
        }
        listener.onTaskChanged(id)
        if (from.kind == SourceKind.GOOGLE || toGoogle) listener.onPendingSync()
    }

    /**
     * Applies a manual order: [orderedIds] get increasing sort keys in the given order.
     * The order is kept on this device; Google Tasks' own order isn't changed.
     */
    suspend fun reorder(orderedIds: List<String>) {
        if (orderedIds.size < 2) return
        db.withTransaction {
            val rows = orderedIds.mapNotNull { tasks.get(it) }
            val base = rows.minOf { it.sortKey }
            rows.forEachIndexed { i, t -> if (t.sortKey != base + i) tasks.upsert(t.copy(sortKey = base + i)) }
        }
    }

    /** Soft delete: the row (and its subtasks) stays as a tombstone so [restore] can undo it. */
    suspend fun delete(id: String) {
        val row = tasks.get(id) ?: return
        val now = clock.now().toEpochMilli()
        val google = lists.get(row.listId)?.kind == SourceKind.GOOGLE
        db.withTransaction {
            val family = listOf(row) + tasks.subtasks(id)
            for (t in family) {
                if (google) {
                    when (outbox.recordDelete(OwnerType.TASK, t.id, t.remoteId != null)) {
                        Outbox.DeleteResult.HARD_DELETE_LOCALLY ->
                            tasks.upsert(t.copy(isDeleted = true, deletedAt = now, syncState = SyncState.LOCAL))
                        Outbox.DeleteResult.PUSH_DELETE ->
                            tasks.upsert(t.copy(isDeleted = true, deletedAt = now, syncState = SyncState.PENDING_DELETE))
                    }
                } else {
                    tasks.upsert(t.copy(isDeleted = true, deletedAt = now))
                }
            }
        }
        listener.onTaskChanged(id)
        if (google) listener.onPendingSync()
    }

    suspend fun restore(id: String) {
        val row = tasks.get(id) ?: return
        if (!row.isDeleted) return
        val google = lists.get(row.listId)?.kind == SourceKind.GOOGLE
        db.withTransaction {
            for (t in listOf(row) + tasks.subtasks(id).filter { it.deletedAt == row.deletedAt }) {
                if (google) {
                    outbox.cancelDelete(OwnerType.TASK, t.id, t.remoteId != null)
                    tasks.upsert(
                        t.copy(
                            isDeleted = false, deletedAt = null,
                            syncState = if (t.remoteId != null) SyncState.PENDING_UPDATE else SyncState.PENDING_CREATE,
                        ),
                    )
                } else {
                    tasks.upsert(t.copy(isDeleted = false, deletedAt = null))
                }
            }
        }
        notify(id, google)
    }

    /** Removes local tombstones older than the undo window. Google tombstones are removed by sync. */
    suspend fun purgeExpiredTombstones(undoWindowMs: Long = 10_000) {
        tasks.purgeLocalTombstones(clock.now().toEpochMilli() - undoWindowMs)
    }

    suspend fun get(id: String): Task? = tasks.get(id)?.takeIf { !it.isDeleted }?.let { r ->
        r.toDomain(lists.get(r.listId)?.kind == SourceKind.GOOGLE)
    }

    suspend fun reminders(id: String): List<Int> = db.reminders().forOwner(OwnerType.TASK, id).map { it.minutesBefore }

    /** Applies [change] and stamps updatedAt + sync state. Returns whether the task is Google-backed, or null if missing. */
    private suspend fun edit(id: String, change: (TaskEntity) -> TaskEntity): Boolean? = db.withTransaction {
        val row = tasks.get(id)?.takeIf { !it.isDeleted } ?: return@withTransaction null
        val google = lists.get(row.listId)?.kind == SourceKind.GOOGLE
        val changed = change(row).copy(updatedAt = clock.now().toEpochMilli())
        val state = if (google) outbox.recordUpdate(OwnerType.TASK, id, row.syncState) else SyncState.LOCAL
        tasks.upsert(changed.copy(syncState = if (row.syncState == SyncState.CONFLICT && google) SyncState.PENDING_UPDATE else state))
        google
    }

    private suspend fun saveReminders(id: String, draft: TaskDraft) {
        val reminders = draft.reminderMinutesBefore.distinct().take(5).map {
            ReminderEntity(id = UUID.randomUUID().toString(), ownerType = OwnerType.TASK, ownerId = id, minutesBefore = it)
        }
        db.reminders().replace(OwnerType.TASK, id, if (draft.dueDate == null) emptyList() else reminders)
    }

    private fun notify(id: String, google: Boolean) {
        listener.onTaskChanged(id)
        if (google) listener.onPendingSync()
    }
}

internal fun LocalTime.toMinutes() = hour * 60 + minute

internal fun TaskListEntity.toDomain() = TaskList(id = id, title = title, isGoogle = kind == SourceKind.GOOGLE, accountEmail = accountEmail)

internal fun TaskEntity.toDomain(isGoogle: Boolean, subtaskCount: Int = 0, subtasksDone: Int = 0) = Task(
    id = id, listId = listId, parentId = parentId, title = title, notes = notes,
    dueDate = dueDate?.let(LocalDate::ofEpochDay),
    dueTime = dueTimeMinutes?.let { LocalTime.of(it / 60, it % 60) },
    priority = Priority.of(priority),
    isCompleted = isCompleted,
    completedAt = completedAt?.let(Instant::ofEpochMilli),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    sortKey = sortKey,
    isGoogle = isGoogle,
    syncState = syncState,
    subtaskCount = subtaskCount,
    subtasksDone = subtasksDone,
)
