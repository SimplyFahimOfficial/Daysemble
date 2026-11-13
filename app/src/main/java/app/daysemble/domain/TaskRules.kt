package app.daysemble.domain

import app.daysemble.data.prefs.TaskSort
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class TaskBucket { OVERDUE, TODAY, UPCOMING, NO_DATE, COMPLETED }

enum class TaskFilter { ALL, TODAY, UPCOMING, OVERDUE, COMPLETED }

/** Pure task rules: no Android, no I/O. */
object TaskRules {

    /**
     * Overdue = incomplete and past its due moment. Date-only tasks are overdue from the
     * start of the following day (a task due today is never overdue today).
     */
    fun isOverdue(task: Task, now: Instant, zone: ZoneId): Boolean {
        if (task.isCompleted) return false
        val due = task.dueMoment(zone) ?: return false
        return now >= due
    }

    fun bucket(task: Task, now: Instant, zone: ZoneId): TaskBucket {
        if (task.isCompleted) return TaskBucket.COMPLETED
        val due = task.dueDate ?: return TaskBucket.NO_DATE
        val today = now.atZone(zone).toLocalDate()
        return when {
            isOverdue(task, now, zone) -> TaskBucket.OVERDUE
            due == today -> TaskBucket.TODAY
            due.isAfter(today) -> TaskBucket.UPCOMING
            else -> TaskBucket.OVERDUE
        }
    }

    fun matches(task: Task, filter: TaskFilter, now: Instant, zone: ZoneId): Boolean = when (filter) {
        TaskFilter.ALL -> !task.isCompleted
        TaskFilter.TODAY -> bucket(task, now, zone).let { it == TaskBucket.TODAY || it == TaskBucket.OVERDUE }
        TaskFilter.UPCOMING -> bucket(task, now, zone) == TaskBucket.UPCOMING
        TaskFilter.OVERDUE -> bucket(task, now, zone) == TaskBucket.OVERDUE
        TaskFilter.COMPLETED -> task.isCompleted
    }

    fun matchesQuery(task: Task, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return task.title.contains(q, ignoreCase = true) || task.notes.contains(q, ignoreCase = true)
    }

    fun comparator(sort: TaskSort, zone: ZoneId): Comparator<Task> {
        val byDue = compareBy<Task, Instant?>(nullsLast()) { it.dueMoment(zone) }
        val byPriority = compareByDescending<Task> { it.priority.level }
        val byManual = compareBy<Task> { it.sortKey }
        val byCreated = compareByDescending<Task> { it.createdAt }
        val primary = when (sort) {
            TaskSort.DUE -> byDue.then(byPriority)
            TaskSort.PRIORITY -> byPriority.then(byDue)
            TaskSort.CREATED -> byCreated
            TaskSort.MANUAL -> byManual
        }
        // Completed items always sink, most recently completed first. Ties break on id for stability.
        return compareBy<Task> { it.isCompleted }
            .then(Comparator { a, b ->
                if (a.isCompleted && b.isCompleted) compareValues(b.completedAt, a.completedAt) else 0
            })
            .then(primary)
            .then(compareBy { it.id })
    }
}
