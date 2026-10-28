package app.daysemble.domain.model

import androidx.compose.runtime.Immutable
import app.daysemble.core.recurrence.RRule
import app.daysemble.data.db.SyncState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

enum class Priority(val level: Int) {
    NONE(0), LOW(1), MEDIUM(2), HIGH(3);

    companion object {
        fun of(level: Int) = entries.firstOrNull { it.level == level } ?: NONE
    }
}

@Immutable
data class TaskList(
    val id: String,
    val title: String,
    val isGoogle: Boolean,
    val accountEmail: String?,
)

@Immutable
data class Task(
    val id: String,
    val listId: String,
    val parentId: String?,
    val title: String,
    val notes: String,
    val dueDate: LocalDate?,
    /** Local wall time; only meaningful with [dueDate]. Local-only for Google tasks. */
    val dueTime: LocalTime?,
    val priority: Priority,
    val isCompleted: Boolean,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val sortKey: Long,
    val isGoogle: Boolean,
    val syncState: SyncState,
    val subtaskCount: Int = 0,
    val subtasksDone: Int = 0,
    /** Live countdown to [dueMoment] is shown for this task. */
    val countdownEnabled: Boolean = false,
    /** Series this task is an occurrence of (see TaskSeriesEntity), even if it no longer repeats. */
    val seriesId: String? = null,
    /** The date the series scheduled this occurrence for (its identity; [dueDate] can be moved). */
    val occurrenceDate: LocalDate? = null,
    /** The active repeat rule of its series; null for one-off tasks and ended series. */
    val repeat: RRule? = null,
) {
    val isRecurring: Boolean get() = repeat != null

    /** The moment the task becomes overdue: due time, or the end of the due date. */
    fun dueMoment(zone: ZoneId): Instant? {
        val d = dueDate ?: return null
        return if (dueTime != null) LocalDateTime.of(d, dueTime).atZone(zone).toInstant()
        else d.plusDays(1).atStartOfDay(zone).toInstant()
    }
}

/** Editable fields of a task (create and update share this). */
data class TaskDraft(
    val title: String = "",
    val notes: String = "",
    val listId: String? = null,
    val parentId: String? = null,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val priority: Priority = Priority.NONE,
    val reminderMinutesBefore: List<Int> = emptyList(),
    /** Repeat rule (TaskRecurrence subset); needs a [dueDate] and an on-device list. */
    val repeat: RRule? = null,
    /** Show a live countdown; needs a [dueDate]. */
    val countdown: Boolean = false,
) {
    val isValid: Boolean get() = title.isNotBlank()
}

/** Which occurrences an edit or delete of a repeating task applies to. */
enum class SeriesScope {
    /** Only this occurrence. */
    THIS,
    /** This occurrence and the ones after it (completed ones are left as they were). */
    FUTURE,
    /** Every occurrence, including completed ones. */
    ALL,
}
