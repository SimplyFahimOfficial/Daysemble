package app.daysemble.data.repo

import androidx.room.withTransaction
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.recurrence.TaskRecurrence
import app.daysemble.core.time.AppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.ReminderEntity
import app.daysemble.data.db.TaskEntity
import app.daysemble.data.db.TaskListEntity
import app.daysemble.data.db.TaskSeriesEntity
import app.daysemble.data.sync.ChangeListener
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.Task
import app.daysemble.domain.model.TaskDraft
import app.daysemble.domain.model.TaskList
import app.daysemble.domain.model.SeriesScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class TaskRepository(
    private val db: DaysembleDatabase,
    private val clock: AppClock,
    private val listener: ChangeListener,
) {
    private val tasks = db.tasks()
    private val lists = db.taskLists()
    private val series = db.taskSeries()
    private val skips = db.skips()

    val taskLists: Flow<List<TaskList>> = lists.observeAll().map { all -> all.map { it.toDomain() } }

    /** All non-deleted tasks (top-level and subtasks) with subtask progress filled in. */
    val allTasks: Flow<List<Task>> = mapTasks(tasks.observeAll())

    /** Open top-level tasks due in [from, to], retaining their complete subtask progress. */
    fun openTasksInRange(from: LocalDate, to: LocalDate): Flow<List<Task>> =
        mapTasks(tasks.observeOpenInRange(from.toEpochDay(), to.toEpochDay()))
            .map { rows -> rows.filter { it.parentId == null } }

    private fun mapTasks(source: Flow<List<TaskEntity>>): Flow<List<Task>> =
        combine(source, lists.observeAll(), series.observeAll()) { rows, listRows, seriesRows ->
            val listById = listRows.associateBy { it.id }
            val rules = seriesRows.associate { it.id to it.activeRule() }
            val visible = rows.filter { it.listId in listById }
            val children = visible.filter { it.parentId != null }.groupBy { it.parentId }
            visible.map { row ->
                val kids = children[row.id].orEmpty()
                row.toDomain(kids.size, kids.count { it.isCompleted }, rules[row.seriesId])
            }
        }

    /** One task, following only its own row and its own series (not every list and series in the database). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeTask(id: String): Flow<Task?> = tasks.observe(id).flatMapLatest { row ->
        if (row == null || row.isDeleted) flowOf(null)
        else (row.seriesId?.let(series::observe) ?: flowOf(null)).map { s -> row.toDomain(repeat = s?.activeRule()) }
    }

    fun observeSubtasks(parentId: String): Flow<List<Task>> = tasks.observeSubtasks(parentId).map { rows -> rows.map { it.toDomain() } }

    fun observeReminders(taskId: String): Flow<List<Int>> =
        db.reminders().observeForOwner(OwnerType.TASK, taskId).map { r -> r.map { it.minutesBefore } }

    /** All lists (for management screens). */
    val allLists: Flow<List<TaskListEntity>> = lists.observeAll()

    suspend fun createList(title: String): String {
        require(title.isNotBlank())
        val id = UUID.randomUUID().toString()
        val order = lists.all().maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
        lists.upsert(TaskListEntity(id = id, title = title.trim(), sortOrder = order))
        return id
    }

    suspend fun renameList(id: String, title: String) {
        lists.get(id) ?: return
        require(title.isNotBlank())
        lists.rename(id, title.trim())
    }

    /**
     * Deletes a list and its tasks. The last list can't be deleted (new tasks need somewhere to go).
     * Returns false if refused.
     */
    suspend fun deleteList(id: String): Boolean {
        lists.get(id) ?: return false
        // Check and read ids in the transaction: two quick deletes could otherwise remove both of the
        // last lists, and a task added in between would be cascade-deleted with its reminder rows left behind.
        val ids = db.withTransaction {
            if (lists.all().size <= 1) return@withTransaction null
            val ids = tasks.allIn(id).map { it.id }
            ids.forEach { db.reminders().deleteForOwner(OwnerType.TASK, it) }
            lists.delete(id) // cascades to tasks
            ids
        } ?: return false
        ids.forEach(listener::onTaskChanged) // cancels their alarms
        return true
    }

    /** Creates the "My tasks" list on first run. Returns its id. */
    suspend fun ensureDefaultList(title: String): String = db.withTransaction {
        lists.all().firstOrNull()?.id ?: run {
            val id = UUID.randomUUID().toString()
            lists.upsert(TaskListEntity(id = id, title = title))
            id
        }
    }

    suspend fun create(draft: TaskDraft, fallbackListId: String): String {
        require(draft.isValid) { "Task title is required" }
        val now = clock.now().toEpochMilli()
        val id = UUID.randomUUID().toString()
        // One level of nesting: a subtask of a subtask attaches to the top-level parent.
        val parent = draft.parentId?.let { tasks.get(it) }?.let { p -> p.parentId?.let { tasks.get(it) } ?: p }
        val listId = parent?.listId ?: draft.listId ?: fallbackListId
        val list = lists.get(listId) ?: lists.get(fallbackListId) ?: error("No task list")
        val rule = draft.repeat?.takeIf { canRepeat(it, isSubtask = parent != null, due = draft.dueDate) }
        db.withTransaction {
            val seriesId = rule?.let { r -> newSeries(r, draft, list.id, now) }
            tasks.upsert(
                TaskEntity(
                    id = id, listId = list.id, parentId = parent?.id,
                    title = draft.title.trim(), notes = draft.notes.trim(),
                    dueDate = draft.dueDate?.toEpochDay(), dueTimeMinutes = draft.dueTime?.toMinutes(),
                    priority = draft.priority.level, sortKey = tasks.maxSortKey(list.id) + 1,
                    createdAt = now, updatedAt = now,
                    countdownEnabled = draft.countdown && draft.dueDate != null,
                    seriesId = seriesId, occurrenceDate = if (seriesId != null) draft.dueDate?.toEpochDay() else null,
                    durationMinutes = draft.durationMinutes?.takeIf { it > 0 },
                ),
            )
            saveReminders(id, draft)
        }
        listener.onTaskChanged(id)
        return id
    }

    /**
     * Saves [draft] to task [id]. For an occurrence of a repeating task, [scope] says what else
     * changes: [SeriesScope.THIS] only this occurrence (its date can move; the next one is still
     * counted from its scheduled date), [SeriesScope.FUTURE] this one and the series template that
     * future occurrences copy, [SeriesScope.ALL] that plus the title, notes and priority of completed
     * occurrences. A changed rule or moved date (FUTURE/ALL) restarts the series at this occurrence,
     * keeping the remaining COUNT. Turning repeat off ends the series; its history is kept.
     */
    suspend fun update(id: String, draft: TaskDraft, scope: SeriesScope = SeriesScope.THIS) {
        require(draft.isValid) { "Task title is required" }
        var generated: List<String> = emptyList()
        // Read in the transaction: a concurrent write (a notification's Complete, a delete) must not be
        // overwritten with what this method read before it.
        val found = db.withTransaction {
            val before = tasks.get(id)?.takeIf { !it.isDeleted } ?: return@withTransaction false
            val now = clock.now().toEpochMilli()
            val active = before.seriesId?.let { series.get(it) }?.takeIf { it.endedAt == null }
            val rule = draft.repeat?.takeIf { canRepeat(it, isSubtask = before.parentId != null, due = draft.dueDate) }
            val edited = edit(id) { row ->
                row.copy(
                    title = draft.title.trim(), notes = draft.notes.trim(),
                    dueDate = draft.dueDate?.toEpochDay(), dueTimeMinutes = draft.dueTime?.toMinutes(),
                    priority = draft.priority.level, countdownEnabled = draft.countdown && draft.dueDate != null, durationMinutes = draft.positiveDuration,
                )
            }
            if (!edited) return@withTransaction true
            saveReminders(id, draft)
            when {
                // A one-off task (or one whose series ended) that should now repeat: start a series here.
                active == null -> if (rule != null) {
                    val sid = newSeries(rule, draft, before.listId, now)
                    tasks.get(id)?.let { tasks.upsert(it.copy(seriesId = sid, occurrenceDate = draft.dueDate!!.toEpochDay())) }
                    // Made to repeat when it's already done: its next occurrence is due now.
                    if (before.isCompleted) {
                        val s = series.get(sid); val row = tasks.get(id)
                        if (s != null && row != null) generated = generateNext(s, row, now)
                    }
                }
                // Only the current (open) occurrence changes the series: a completed one is history,
                // and the editor doesn't offer the scopes for it.
                scope == SeriesScope.THIS || before.isCompleted -> Unit
                rule == null -> series.upsert(active.copy(endedAt = now, updatedAt = now))
                else -> {
                    val oldRule = active.activeRule()
                    val due = draft.dueDate!!.toEpochDay()
                    val moved = due != before.dueDate
                    // A new end (COUNT/UNTIL) alone doesn't restart the series: it applies from its start,
                    // as the editor shows it. A new date, frequency, interval or weekdays restarts it here.
                    val reshaped = oldRule == null || rule.freq != oldRule.freq || rule.interval != oldRule.interval ||
                        rule.byDay.map { it.day }.toSet() != oldRule.byDay.map { it.day }.toSet()
                    var anchor = active.anchorDate
                    var r = rule
                    if (moved || reshaped) {
                        // Keep the remaining COUNT when the count itself wasn't changed.
                        val done = oldRule?.let { o ->
                            TaskRecurrence.indexOf(o, LocalDate.ofEpochDay(active.anchorDate), LocalDate.ofEpochDay(before.occurrenceDate ?: due))
                        }
                        if (r.count != null && oldRule?.count == r.count && done != null) r = r.copy(count = (r.count - done).toInt().coerceAtLeast(1))
                        // Same date and kind of repeat: keep the day of month this occurrence was clamped from.
                        val (a, earlier) = if (!moved && oldRule?.freq == r.freq) {
                            TaskRecurrence.restartAnchor(r, LocalDate.ofEpochDay(due), LocalDate.ofEpochDay(active.anchorDate))
                        } else {
                            LocalDate.ofEpochDay(due) to 0
                        }
                        anchor = a.toEpochDay()
                        if (r.count != null && earlier > 0) r = r.copy(count = r.count + earlier)
                        // This occurrence is occurrence 0 of the restarted schedule: its identity is the
                        // new anchor, so the remaining COUNT and the dates after it line up. A row of the
                        // old schedule already on that date (one done, or skipped, before the move) is
                        // history: it leaves the schedule (no occurrence date) instead of keeping this one
                        // on its old date, where it would take the second slot and drop a repeat.
                        val clash = tasks.bySeriesDate(active.id, due)
                        if (clash != null && clash.id != id) tasks.upsert(clash.copy(occurrenceDate = null))
                        tasks.get(id)?.let { tasks.upsert(it.copy(occurrenceDate = due)) }
                    }
                    series.upsert(active.withTemplate(draft).copy(rrule = r.format(), anchorDate = anchor, updatedAt = now))
                    if (scope == SeriesScope.ALL) {
                        tasks.inSeries(active.id).filter { it.id != id && !it.isDeleted }.forEach {
                            tasks.upsert(it.copy(title = draft.title.trim(), notes = draft.notes.trim(), priority = draft.priority.level))
                        }
                    }
                }
            }
            true
        }
        if (!found) return
        listener.onTaskChanged(id)
        generated.forEach(listener::onTaskChanged)
    }

    /**
     * Completes or reopens a task. Completing the current occurrence of a repeating task records it
     * as done and creates the next one: the first date of the rule after this occurrence's
     * scheduled date that isn't before today (missed dates are skipped, not created as a backlog).
     * Repeated calls change nothing (the first one already completed it, and the next occurrence's
     * id and date are fixed, so it can't be created twice). Reopening the latest completed
     * occurrence removes the next one again if it hasn't been changed.
     */
    suspend fun setCompleted(id: String, completed: Boolean): Completion? {
        val now = clock.now().toEpochMilli()
        var changed: List<String> = emptyList()
        var nextDue: LocalDate? = null
        var nextId: String? = null
        var repeating = false
        var seriesEnded = false
        val found = db.withTransaction {
            val row = tasks.get(id)?.takeIf { !it.isDeleted } ?: return@withTransaction false
            if (row.isCompleted == completed) return@withTransaction false
            if (!edit(id) { it.copy(isCompleted = completed, completedAt = if (completed) now else null) }) return@withTransaction false
            val s = row.seriesId?.let { series.get(it) }
            if (s != null) {
                repeating = s.activeRule() != null
                changed = if (completed) generateNext(s, row, now) else removeUntouchedSuccessors(s, row)
                if (completed && repeating) {
                    // The series' current occurrence after this one: the one just created, or one that
                    // was already open (kept when an older occurrence was reopened), in which case
                    // nothing was created but the series hasn't ended.
                    val open = tasks.inSeries(s.id).filter { it.id != id && !it.isDeleted && !it.isCompleted }
                    seriesEnded = open.isEmpty()
                    val next = open.filter { it.dueDate != null }.minByOrNull { it.dueDate!! }
                    nextDue = next?.dueDate?.let(LocalDate::ofEpochDay)
                    nextId = next?.id
                }
            }
            true
        }
        if (!found) return null
        listener.onTaskChanged(id)
        changed.forEach(listener::onTaskChanged)
        return Completion(repeating, nextDue, seriesEnded, nextId)
    }

    /**
     * What [setCompleted] changed, for its confirmation. [repeating]: the task is an occurrence of an
     * active series. [nextDue]: when the series' next open occurrence is due, whether completing
     * created it or it was already open. [seriesEnded]: completing it left the series with no open
     * occurrence (its COUNT or UNTIL is reached), so this was the last one.
     */
    data class Completion(val repeating: Boolean, val nextDue: LocalDate?, val seriesEnded: Boolean = false, val nextId: String? = null)

    suspend fun setPriority(id: String, priority: Priority) {
        // A targeted update: a whole-row write of an older copy could undo a concurrent sync write.
        tasks.get(id) ?: return
        tasks.setPriority(id, priority.level)
        listener.onTaskChanged(id)
    }

    /**
     * Turns the countdown of task [id] on or off (it needs a due date). For the open occurrence of a
     * repeating task the series template follows, so the next occurrences keep the choice; completed
     * occurrences are left as they were.
     */
    suspend fun setCountdown(id: String, on: Boolean) {
        db.withTransaction {
            val row = tasks.get(id)?.takeIf { !it.isDeleted } ?: return@withTransaction
            val value = on && row.dueDate != null
            tasks.setCountdown(id, value)
            if (!row.isCompleted) row.seriesId?.let { series.get(it) }?.takeIf { it.endedAt == null }?.let {
                series.upsert(it.copy(countdownEnabled = value, updatedAt = clock.now().toEpochMilli()))
            }
        }
        listener.onTaskChanged(id)
    }

    suspend fun setDue(id: String, date: LocalDate?, time: LocalTime?) {
        if (edit(id) { it.copy(dueDate = date?.toEpochDay(), dueTimeMinutes = if (date == null) null else time?.toMinutes()) }) listener.onTaskChanged(id)
    }

    /** Moves a top-level task (and its subtasks) to another list. */
    suspend fun moveToList(id: String, newListId: String, scope: SeriesScope = SeriesScope.THIS) {
        val now = clock.now().toEpochMilli()
        // One transaction for the checks, the series and the rows: a failure part-way can't leave
        // the series in the new list with its rows still in the old one.
        val moved = db.withTransaction {
            val row = tasks.get(id)?.takeIf { !it.isDeleted && it.parentId == null } ?: return@withTransaction false
            if (row.listId == newListId) return@withTransaction false
            val to = lists.get(newListId) ?: return@withTransaction false
            // For an occurrence of a repeating task, [scope] says whether the series moves too:
            // "this occurrence" moves only this one (the next ones still appear in the series' list),
            // "this and future" / "the entire series" move the series, so the next ones appear in the
            // new list. Completed occurrences stay where they are. Moving a completed occurrence only
            // files that one away, whatever the scope (the editor offers no scope for it).
            val active = row.seriesId?.let { series.get(it) }?.takeIf { it.endedAt == null }
            if (!row.isCompleted && scope != SeriesScope.THIS) {
                active?.let { series.upsert(it.copy(listId = newListId, updatedAt = now)) }
            }
            var sort = tasks.maxSortKey(to.id)
            for (t in listOf(row) + tasks.subtasks(row.id).filter { !it.isDeleted }) {
                tasks.upsert(t.copy(listId = to.id, sortKey = ++sort, updatedAt = now))
            }
            true
        }
        if (moved) listener.onTaskChanged(id)
    }

    /**
     * Applies a manual order: [orderedIds] get increasing sort keys in the given order.
     */
    suspend fun reorder(orderedIds: List<String>) {
        if (orderedIds.size < 2) return
        db.withTransaction {
            val rows = orderedIds.mapNotNull { tasks.get(it) }
            val base = rows.minOf { it.sortKey }
            rows.forEachIndexed { i, t -> if (t.sortKey != base + i) tasks.upsert(t.copy(sortKey = base + i)) }
        }
    }

    /**
     * Soft delete: the row (and its subtasks) stays as a tombstone so [restore] can undo it.
     *
     * For an occurrence of a repeating task: [SeriesScope.THIS] deletes this occurrence and, if it was
     * still open, creates the next one (a skip); [SeriesScope.FUTURE] deletes it and ends the series,
     * keeping completed ones; [SeriesScope.ALL] deletes every occurrence and ends the series.
     */
    suspend fun delete(id: String, scope: SeriesScope = SeriesScope.THIS) {
        val row = tasks.get(id) ?: return
        val now = clock.now().toEpochMilli()
        val s = row.seriesId?.let { series.get(it) }?.takeIf { it.endedAt == null }
        var generated: List<String> = emptyList()
        // Open tasks this delete removes, besides [id]: their alarms are cancelled at once (deleting
        // the series from a completed occurrence takes the current one with it).
        val deleted = mutableListOf<String>()
        db.withTransaction {
            // Deleting one occurrence on its own is a skip: its date stays in the history as skipped
            // after the deleted row itself is purged. A completed one too: without the record its date
            // would show as missed, which the user never did.
            recordSkip(row, scope, now)
            if (s != null) when (scope) {
                SeriesScope.THIS -> if (!row.isCompleted) generated = generateNext(s, row, now)
                SeriesScope.FUTURE, SeriesScope.ALL -> {
                    series.upsert(s.copy(endedAt = now, updatedAt = now))
                    tasks.inSeries(s.id).filter { !it.isDeleted && it.id != id && (scope == SeriesScope.ALL || !it.isCompleted) }.forEach {
                        // With its subtasks, stamped alike so undo brings them back together.
                        for (t in listOf(it) + tasks.subtasks(it.id).filter { sub -> !sub.isDeleted }) {
                            tasks.upsert(t.copy(isDeleted = true, deletedAt = now))
                            if (!t.isCompleted) deleted += t.id
                        }
                    }
                }
            }
            val family = listOf(row) + tasks.subtasks(id)
            for (t in family) {
                if (t.id != id && !t.isDeleted && !t.isCompleted) deleted += t.id
                tasks.upsert(t.copy(isDeleted = true, deletedAt = now))
            }
        }
        listener.onTaskChanged(id)
        deleted.forEach(listener::onTaskChanged)
        generated.forEach(listener::onTaskChanged)
    }

    /**
     * Undo of [delete]. Brings back what that delete removed (for a repeating task, the other
     * occurrences and subtasks deleted with it) and reports every open task it restores, so their
     * reminders are scheduled again: while they were deleted, a full reschedule (after a sync, a
     * reboot or a time change) dropped their alarms.
     */
    suspend fun restore(id: String) {
        val row = tasks.get(id) ?: return
        if (!row.isDeleted) return
        var removed: List<String> = emptyList()
        val restored = mutableListOf<String>()
        db.withTransaction {
            forgetSkip(row)
            row.seriesId?.let { series.get(it) }?.let { s ->
                if (s.endedAt != null && s.endedAt == row.deletedAt) {
                    // Undo of "this and future" / "all": the series repeats again, with what it deleted.
                    series.upsert(s.copy(endedAt = null))
                    tasks.inSeries(s.id).filter { it.isDeleted && it.deletedAt == row.deletedAt && it.id != id }.forEach {
                        for (t in listOf(it) + tasks.subtasks(it.id).filter { sub -> sub.deletedAt == row.deletedAt }) {
                            tasks.upsert(t.copy(isDeleted = false, deletedAt = null))
                            if (!t.isCompleted) restored += t.id
                        }
                    }
                } else if (s.endedAt == null && !row.isCompleted) {
                    // Undo of skipping an open occurrence: it's the current one again; drop the one its
                    // delete created. (Deleting a completed one created nothing, so nothing is dropped.)
                    removed = removeUntouchedSuccessors(s, row)
                }
            }
            for (t in listOf(row) + tasks.subtasks(id).filter { it.deletedAt == row.deletedAt }) {
                if (t.id != id && !t.isCompleted) restored += t.id
                tasks.upsert(t.copy(isDeleted = false, deletedAt = null))
            }
        }
        listener.onTaskChanged(id)
        restored.forEach(listener::onTaskChanged)
        removed.forEach(listener::onTaskChanged)
    }

    /** Removes tombstones older than the undo window. */
    suspend fun purgeExpiredTombstones(undoWindowMs: Long = 10_000) {
        val before = clock.now().toEpochMilli() - undoWindowMs
        tasks.purgeLocalTombstones(before)
        series.purgeEnded(before)
        db.reminders().deleteOrphans()
    }

    suspend fun get(id: String): Task? = tasks.get(id)?.takeIf { !it.isDeleted }?.let { r ->
        r.toDomain(repeat = r.seriesId?.let { series.get(it) }?.activeRule())
    }

    suspend fun reminders(id: String): List<Int> = db.reminders().forOwner(OwnerType.TASK, id).map { it.minutesBefore }

    /** Applies [change] and stamps updatedAt. Returns false if the task is missing. */
    private suspend fun edit(id: String, change: (TaskEntity) -> TaskEntity): Boolean = db.withTransaction {
        val row = tasks.get(id)?.takeIf { !it.isDeleted } ?: return@withTransaction false
        tasks.upsert(change(row).copy(updatedAt = clock.now().toEpochMilli()))
        true
    }

    private suspend fun saveReminders(id: String, draft: TaskDraft) {
        val reminders = draft.reminderMinutesBefore.distinct().take(5).map {
            ReminderEntity.of(OwnerType.TASK, id, it)
        }
        db.reminders().replace(OwnerType.TASK, id, if (draft.dueDate == null) emptyList() else reminders)
    }

    /** Repeating needs a due date to count from and a top-level task. */
    private fun canRepeat(rule: RRule, isSubtask: Boolean, due: LocalDate?) =
        !isSubtask && due != null && TaskRecurrence.supports(rule)

    private suspend fun newSeries(rule: RRule, draft: TaskDraft, listId: String, now: Long): String {
        val id = UUID.randomUUID().toString()
        series.upsert(
            TaskSeriesEntity(
                id = id, listId = listId, rrule = rule.format(), anchorDate = draft.dueDate!!.toEpochDay(),
                title = "", createdAt = now, updatedAt = now,
            ).withTemplate(draft),
        )
        return id
    }

    /**
     * Creates the occurrence after [from] (see [setCompleted]), unless the series already has an
     * open one. The (seriesId, occurrenceDate) index is unique and taken dates are passed over, so
     * a date is never created twice, even after a restart of the app. Returns the new ids to
     * schedule reminders for (the occurrence first, then copied subtasks with a due date); none if
     * the series has ended.
     */
    private suspend fun generateNext(s: TaskSeriesEntity, from: TaskEntity, now: Long): List<String> {
        val rule = s.activeRule() ?: return emptyList()
        val fromDate = from.occurrenceDate ?: from.dueDate ?: return emptyList()
        val rows = tasks.inSeries(s.id)
        // The series already has an open occurrence (one kept after an older one was reopened):
        // that is still the current one, so nothing is added.
        if (rows.any { it.id != from.id && !it.isDeleted && !it.isCompleted }) return emptyList()
        val today = Instant.ofEpochMilli(now).atZone(clock.zone()).toLocalDate()
        // Not before the day after this one was due, either: an occurrence postponed (for this time
        // only) onto a later date of the rule doesn't get a twin due the same day.
        val notBefore = maxOf(today, LocalDate.ofEpochDay(from.dueDate ?: fromDate).plusDays(1))
        // Dates that already have a row (done, skipped, or moved there before a restart) are passed
        // over, so the series never stalls on one.
        val taken = rows.mapNotNull { it.occurrenceDate }.toSet()
        var after = LocalDate.ofEpochDay(fromDate)
        var next: LocalDate
        var guard = 0
        do {
            next = TaskRecurrence.next(rule, LocalDate.ofEpochDay(s.anchorDate), after, notBefore) ?: return emptyList()
            after = next
        } while (next.toEpochDay() in taken && ++guard < 1000)
        if (next.toEpochDay() in taken) return emptyList()
        // Normally "seriesId:date"; a row moved off its date by a restart keeps its old id, so a
        // suffix keeps the new one unique.
        var id = "${s.id}:$next"
        var n = 2
        while (tasks.get(id) != null) id = "${s.id}:$next~${n++}"
        tasks.insert(
            TaskEntity(
                id = id, listId = s.listId, title = s.title, notes = s.notes,
                dueDate = next.toEpochDay(), dueTimeMinutes = s.dueTimeMinutes, priority = s.priority,
                sortKey = tasks.maxSortKey(s.listId) + 1, createdAt = now, updatedAt = now,
                countdownEnabled = s.countdownEnabled, seriesId = s.id, occurrenceDate = next.toEpochDay(),
                durationMinutes = s.durationMinutes,
            ),
        )
        db.reminders().replace(OwnerType.TASK, id, s.reminderList().map { ReminderEntity.of(OwnerType.TASK, id, it) })
        // The checklist carries forward: the subtasks of this occurrence, unticked, with due dates
        // moved by as much as the occurrence moved. Their ids follow the new occurrence's id, so they
        // can't be copied twice either.
        val shift = next.toEpochDay() - fromDate
        var sort = tasks.maxSortKey(s.listId)
        val created = mutableListOf(id)
        tasks.subtasks(from.id).filter { !it.isDeleted }.sortedBy { it.sortKey }.forEachIndexed { i, sub ->
            val subId = "$id/$i"
            tasks.insert(
                TaskEntity(
                    id = subId, listId = s.listId, parentId = id, title = sub.title, notes = sub.notes,
                    dueDate = sub.dueDate?.plus(shift), dueTimeMinutes = sub.dueTimeMinutes, priority = sub.priority,
                    sortKey = ++sort, createdAt = now, updatedAt = now,
                    countdownEnabled = sub.countdownEnabled,
                ),
            )
            if (sub.dueDate != null) {
                created += subId
                db.reminders().replace(OwnerType.TASK, subId, db.reminders().forOwner(OwnerType.TASK, sub.id).map { ReminderEntity.of(OwnerType.TASK, subId, it.minutesBefore) })
            }
        }
        return created
    }

    /**
     * Keeps [row]'s scheduled date as skipped when one occurrence of a series is deleted on its own
     * (see [delete]).
     */
    private suspend fun recordSkip(row: TaskEntity, scope: SeriesScope, now: Long) {
        if (scope != SeriesScope.THIS) return
        val sid = row.seriesId ?: return
        val date = row.occurrenceDate ?: return
        if (series.get(sid) != null) skips.insert(app.daysemble.data.db.TaskOccurrenceSkipEntity(sid, date, now))
    }

    /** Undo of [recordSkip]: the restored occurrence is back on its date. */
    private suspend fun forgetSkip(row: TaskEntity) {
        val sid = row.seriesId ?: return
        row.occurrenceDate?.let { skips.delete(sid, it) }
    }

    /**
     * After [row] is reopened or restored: removes the occurrence its completion or skip created, if
     * that is still open and unchanged (generated, never edited) and [row] is the latest occurrence
     * that was done. Reopening an older completed occurrence leaves the current one alone.
     */
    private suspend fun removeUntouchedSuccessors(s: TaskSeriesEntity, row: TaskEntity): List<String> {
        val date = row.occurrenceDate ?: return emptyList()
        val all = tasks.inSeries(s.id)
        if (all.any { it.id != row.id && !it.isDeleted && it.isCompleted && (it.occurrenceDate ?: Long.MIN_VALUE) > date }) return emptyList()
        val successors = all.filter {
            it.id != row.id && !it.isDeleted && !it.isCompleted && it.id.startsWith("${s.id}:") &&
                it.updatedAt == it.createdAt && (it.occurrenceDate ?: Long.MIN_VALUE) > date
        }.map { it to tasks.subtasks(it.id) }.filter { (succ, subs) ->
            // Its copied subtasks are untouched too: none ticked, edited, deleted or added since.
            subs.all { !it.isDeleted && !it.isCompleted && it.updatedAt == it.createdAt && it.createdAt == succ.createdAt }
        }
        val removed = successors.flatMap { (succ, subs) -> listOf(succ.id) + subs.map { it.id } }
        removed.forEach { db.reminders().deleteForOwner(OwnerType.TASK, it) }
        successors.forEach { (succ, _) -> tasks.hardDelete(succ.id) } // with its subtasks
        return removed
    }
}

/** The rule new occurrences follow, or null once the series has ended (or its rule isn't usable). */
internal fun TaskSeriesEntity.activeRule(): RRule? =
    if (endedAt != null) null else RRule.parse(rrule)?.takeIf(TaskRecurrence::supports)

internal fun TaskSeriesEntity.reminderList(): List<Int> = reminders.split(',').mapNotNull { it.trim().toIntOrNull() }

/** The series template from an edited occurrence: what future occurrences copy. */
internal fun TaskSeriesEntity.withTemplate(d: TaskDraft) = copy(
    title = d.title.trim(), notes = d.notes.trim(), dueTimeMinutes = d.dueTime?.toMinutes(), priority = d.priority.level,
    countdownEnabled = d.countdown, reminders = d.reminderMinutesBefore.distinct().take(5).joinToString(","),
    durationMinutes = d.durationMinutes?.takeIf { it > 0 },
)

internal fun LocalTime.toMinutes() = hour * 60 + minute

internal fun TaskListEntity.toDomain() = TaskList(id = id, title = title)

internal fun TaskEntity.toDomain(subtaskCount: Int = 0, subtasksDone: Int = 0, repeat: RRule? = null) = Task(
    id = id, listId = listId, parentId = parentId, title = title, notes = notes,
    dueDate = dueDate?.let(LocalDate::ofEpochDay),
    dueTime = dueTimeMinutes?.let { LocalTime.of(it / 60, it % 60) },
    priority = Priority.of(priority),
    isCompleted = isCompleted,
    completedAt = completedAt?.let(Instant::ofEpochMilli),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    sortKey = sortKey,
    subtaskCount = subtaskCount,
    subtasksDone = subtasksDone,
    countdownEnabled = countdownEnabled,
    seriesId = seriesId,
    occurrenceDate = occurrenceDate?.let(LocalDate::ofEpochDay),
    repeat = repeat,
    durationMinutes = durationMinutes?.takeIf { it > 0 },
)
