package app.daysemble.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * LOCAL: created in Daysemble and editable. DEVICE: a read-only mirror of a calendar from the
 * phone's Calendar Provider (see data/device/DeviceCalendarSync).
 */
enum class SourceKind { LOCAL, DEVICE }

/**
 * Legacy column from the removed Google sync; always LOCAL. SQLite on API 26 can't drop a column,
 * so the column stays (with the other legacy ones: remoteId, etag, remotePosition, remoteUpdatedAt,
 * pendingClientId, instancesUntil, syncToken, timeZone, defaultReminders, lastPulledAt,
 * isSyncEnabled) and is never read.
 */
enum class SyncState { LOCAL }

enum class OwnerType { EVENT, TASK }

@Entity(
    tableName = "calendar_source",
    indices = [Index(value = ["kind", "accountEmail", "remoteId"], unique = true)],
)
// Also the backup file format (data/backup): new fields need a default.
@Serializable
data class CalendarSourceEntity(
    @PrimaryKey val id: String,
    val kind: SourceKind = SourceKind.LOCAL,
    /** DEVICE: the Calendar Provider's calendar id. */
    val remoteId: String? = null,
    /** DEVICE: the account the calendar belongs to (shown as its subtitle). */
    val accountEmail: String? = null,
    val name: String,
    val colorArgb: Int,
    val isVisible: Boolean = true,
    val isSyncEnabled: Boolean = true,
    val isPrimary: Boolean = false,
    /** "owner" for local calendars, "reader" for DEVICE ones (see [app.daysemble.data.repo.isWritableRole]). */
    val accessRole: String = "owner",
    val syncToken: String? = null,
    val timeZone: String? = null,
    val defaultReminders: String = "",
)

/**
 * An event or recurring-series master, or (when [recurringParentId] is set) an override of one
 * occurrence of a series.
 *
 * Timed events: [startUtc]/[endUtc] epoch millis + [timeZone].
 * All-day events: [startDate]/[endDateExclusive] epoch days, never zone-shifted.
 * [rangeStartUtc]/[rangeEndUtc] are a conservative envelope used only for indexed window queries
 * (all-day ±14 h, series masters extend to their UNTIL / end of time).
 */
@Entity(
    tableName = "event",
    foreignKeys = [
        ForeignKey(
            entity = CalendarSourceEntity::class,
            parentColumns = ["id"], childColumns = ["calendarId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("calendarId"),
        Index(value = ["rangeStartUtc", "rangeEndUtc"]),
        Index(value = ["calendarId", "remoteId"], unique = true),
        Index("recurringParentId"),
    ],
)
// Also the backup file format (data/backup): new fields need a default.
@Serializable
data class EventEntity(
    @PrimaryKey val id: String,
    val calendarId: String,
    val remoteId: String? = null,
    val etag: String? = null,
    val title: String,
    val description: String = "",
    val location: String = "",
    val isAllDay: Boolean,
    val startUtc: Long = 0,
    val endUtc: Long = 0,
    val startDate: Long = 0,
    val endDateExclusive: Long = 0,
    val timeZone: String,
    val rrule: String? = null,
    /** Comma-separated occurrence keys removed from the series. */
    val exdates: String = "",
    val recurringParentId: String? = null,
    val originalStartKey: String? = null,
    val isCancelled: Boolean = false,
    val colorArgb: Int? = null,
    val rangeStartUtc: Long,
    val rangeEndUtc: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val remoteUpdatedAt: Long? = null,
    val syncState: SyncState = SyncState.LOCAL,
    val isDeleted: Boolean = false,
    /** A repeat rule Daysemble can't expand locally; the series is shown read-only. */
    val isReadOnlyRecurrence: Boolean = false,
    val pendingClientId: String? = null,
    val instancesUntil: Long? = null,
)

@Entity(
    tableName = "task_list",
    indices = [Index(value = ["kind", "accountEmail", "remoteId"], unique = true)],
)
// Also the backup file format (data/backup): new fields need a default.
@Serializable
data class TaskListEntity(
    @PrimaryKey val id: String,
    val kind: SourceKind = SourceKind.LOCAL,
    val remoteId: String? = null,
    val accountEmail: String? = null,
    val title: String,
    val sortOrder: Int = 0,
    val isSyncEnabled: Boolean = true,
    val lastPulledAt: Long? = null,
)

@Entity(
    tableName = "task",
    foreignKeys = [
        ForeignKey(
            entity = TaskListEntity::class,
            parentColumns = ["id"], childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("listId"),
        Index("parentId"),
        Index("dueDate"),
        Index("isCompleted"),
        Index(value = ["listId", "remoteId"], unique = true),
        // One row per series date: a second "next occurrence" for the same date can't be inserted.
        Index(value = ["seriesId", "occurrenceDate"], unique = true),
    ],
)
// Also the backup file format (data/backup): new fields need a default.
@Serializable
data class TaskEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val parentId: String? = null,
    val remoteId: String? = null,
    val etag: String? = null,
    val title: String,
    val notes: String = "",
    /** Epoch day. */
    val dueDate: Long? = null,
    /** Minute of day in the device zone. */
    val dueTimeMinutes: Int? = null,
    /** 0 none … 3 high. */
    val priority: Int = 0,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val sortKey: Long,
    val remotePosition: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val remoteUpdatedAt: Long? = null,
    val syncState: SyncState = SyncState.LOCAL,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    /** Show a live countdown to the due moment. */
    @ColumnInfo(defaultValue = "0") val countdownEnabled: Boolean = false,
    /** The [TaskSeriesEntity] this row is an occurrence of, or null for a one-off task. */
    val seriesId: String? = null,
    /**
     * Epoch day the series scheduled this occurrence for: its identity within the series. [dueDate]
     * starts equal to it and can be moved for this occurrence alone; the next one is still counted
     * from here.
     */
    val occurrenceDate: Long? = null,
    /**
     * Minutes the task takes. With a due time it is a time block (due time → + duration)
     * that takes free time on Today; without one it counts as estimated work and is never placed on
     * the clock. Null = no estimate: the task takes no time.
     */
    val durationMinutes: Int? = null,
)

/**
 * A repeating task's rule and template. Only the current occurrence (and completed history) exist
 * as [TaskEntity] rows; the next row is created when the current one is completed or skipped, so
 * a series never fans out into unbounded future rows. Fields here are what new occurrences copy.
 */
@Entity(
    tableName = "task_series",
    foreignKeys = [
        ForeignKey(
            entity = TaskListEntity::class,
            parentColumns = ["id"], childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("listId")],
)
// Also the backup file format (data/backup): new fields need a default.
@Serializable
data class TaskSeriesEntity(
    @PrimaryKey val id: String,
    val listId: String,
    /** RRULE subset, interpreted by [app.daysemble.core.recurrence.TaskRecurrence]. */
    val rrule: String,
    /** Epoch day of occurrence 0. Counting (COUNT, intervals) starts here. */
    val anchorDate: Long,
    val title: String,
    val notes: String = "",
    val dueTimeMinutes: Int? = null,
    val priority: Int = 0,
    @ColumnInfo(defaultValue = "0") val countdownEnabled: Boolean = false,
    /** Reminder offsets (minutes before) for new occurrences, comma-separated. */
    @ColumnInfo(defaultValue = "''") val reminders: String = "",
    /** Set when the series stops repeating (deleted from here on, or repeat turned off). */
    val endedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Duration new occurrences copy (see [TaskEntity.durationMinutes]). */
    val durationMinutes: Int? = null,
)

/**
 * A scheduled date of a repeating task that was skipped (its occurrence deleted on its own), kept
 * after the deleted row itself is purged, so the history shows it as skipped rather than missed.
 * One row per series date; removed again if the delete is undone. Goes with its series.
 */
@Entity(
    tableName = "task_occurrence_skip",
    primaryKeys = ["seriesId", "occurrenceDate"],
    foreignKeys = [
        ForeignKey(
            entity = TaskSeriesEntity::class,
            parentColumns = ["id"], childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
// Also the backup file format (data/backup): new fields need a default.
@Serializable
data class TaskOccurrenceSkipEntity(
    val seriesId: String,
    /** Epoch day of the skipped scheduled date. */
    val occurrenceDate: Long,
    val skippedAt: Long,
)

/**
 * A manually recorded sleep period: "I'm going to sleep" sets [startUtc], "I just woke up" sets
 * [endUtc]. Either can be missing (a wake-up recorded without a bedtime, or a bedtime still open or
 * never closed); only a record with both has a duration. The zones are the device zone at each tap,
 * so the times read the same after travelling. Not health or sensor data.
 */
@Entity(tableName = "sleep_session", indices = [Index("startUtc"), Index("endUtc")])
// Also the backup file format (data/backup): new fields need a default.
@Serializable
data class SleepSessionEntity(
    @PrimaryKey val id: String,
    val startUtc: Long? = null,
    val endUtc: Long? = null,
    val startZone: String? = null,
    val endZone: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Minutes before the item's start (events) or due moment (tasks; date-only tasks use the
 * default task reminder time from settings).
 */
@Entity(
    tableName = "reminder",
    indices = [Index(value = ["ownerType", "ownerId"]), Index(value = ["ownerType", "ownerId", "minutesBefore"], unique = true)],
)
// Also the backup file format (data/backup): new fields need a default.
@Serializable
data class ReminderEntity(
    @PrimaryKey val id: String,
    val ownerType: OwnerType,
    val ownerId: String,
    val minutesBefore: Int,
) {
    companion object {
        /**
         * Deterministic id: the same item + offset always gets the same id, so saving an unchanged
         * reminder keeps scheduled alarms and delivery records valid.
         */
        fun idFor(type: OwnerType, ownerId: String, minutesBefore: Int) = "${type.name}:$ownerId:$minutesBefore"

        fun of(type: OwnerType, ownerId: String, minutesBefore: Int) = ReminderEntity(idFor(type, ownerId, minutesBefore), type, ownerId, minutesBefore)
    }
}

/** Source of truth for alarms registered with AlarmManager (used to cancel and to dedupe). */
@Entity(
    tableName = "scheduled_alarm",
    indices = [Index(value = ["reminderId", "occurrenceKey"], unique = true), Index("ownerId")],
)
data class ScheduledAlarmEntity(
    /** Deterministic per (reminderId, occurrenceKey); see ReminderScheduler.requestCodeFor. */
    @PrimaryKey val requestCode: Int,
    val reminderId: String,
    val ownerType: OwnerType,
    val ownerId: String,
    val occurrenceKey: String,
    val triggerAt: Long,
    val isExact: Boolean,
)

@Entity(tableName = "delivered_reminder")
data class DeliveredReminderEntity(
    /** "$reminderId|$occurrenceKey" */
    @PrimaryKey val key: String,
    val deliveredAt: Long,
)
