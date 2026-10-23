package app.daysemble.domain.model

import androidx.compose.runtime.Immutable
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
) {
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
) {
    val isValid: Boolean get() = title.isNotBlank()
}
