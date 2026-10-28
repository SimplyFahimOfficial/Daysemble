package app.daysemble.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SourceKind { LOCAL, GOOGLE }

/**
 * Sync lifecycle of a record. LOCAL records never sync. For Google-backed records the
 * PENDING_* states mean an outbox operation exists. CONFLICT means the local copy was kept
 * after a remote change collided with it (see conflict_log).
 */
enum class SyncState { LOCAL, SYNCED, PENDING_CREATE, PENDING_UPDATE, PENDING_DELETE, CONFLICT }

enum class OwnerType { EVENT, TASK }

@Entity(
    tableName = "calendar_source",
    indices = [Index(value = ["kind", "accountEmail", "remoteId"], unique = true)],
)
data class CalendarSourceEntity(
    @PrimaryKey val id: String,
    val kind: SourceKind,
    val remoteId: String? = null,
    val accountEmail: String? = null,
    val name: String,
    val colorArgb: Int,
    val isVisible: Boolean = true,
    val isSyncEnabled: Boolean = true,
    val isPrimary: Boolean = false,
    /** "owner" | "writer" | "reader" | "freeBusyReader" for Google; "owner" for local. */
    val accessRole: String = "owner",
    val syncToken: String? = null,
    /** IANA zone of the calendar (Google), used when an event has no zone of its own. */
    val timeZone: String? = null,
    /** Google calendar default popup reminders, comma-separated minutes. */
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
    val syncState: SyncState,
    val isDeleted: Boolean = false,
    /** Google recurrence rules we can't expand locally; the series is shown read-only. */
    val isReadOnlyRecurrence: Boolean = false,
    /**
     * Client-supplied Google id to use for the next create when the default (derived from [id]) can't be
     * used because an earlier remote copy with that id was deleted (moves, re-creates). Stable across
     * retries, so a create whose response was lost is adopted on retry instead of duplicated.
     */
    val pendingClientId: String? = null,
    /** For read-only series: Google instances are stored up to this instant; refreshed before it runs out. */
    val instancesUntil: Long? = null,
)

@Entity(
    tableName = "task_list",
    indices = [Index(value = ["kind", "accountEmail", "remoteId"], unique = true)],
)
data class TaskListEntity(
    @PrimaryKey val id: String,
    val kind: SourceKind,
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
    /** Minute of day in the device zone. Local only (Google Tasks has no due time). */
    val dueTimeMinutes: Int? = null,
    /** 0 none … 3 high. Local only. */
    val priority: Int = 0,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val sortKey: Long,
    val remotePosition: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val remoteUpdatedAt: Long? = null,
    val syncState: SyncState,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    /** Show a live countdown to the due moment. Local only (not a Google Tasks field). */
    @ColumnInfo(defaultValue = "0") val countdownEnabled: Boolean = false,
    /** The [TaskSeriesEntity] this row is an occurrence of, or null for a one-off task. */
    val seriesId: String? = null,
    /**
     * Epoch day the series scheduled this occurrence for: its identity within the series. [dueDate]
     * starts equal to it and can be moved for this occurrence alone; the next one is still counted
     * from here.
     */
    val occurrenceDate: Long? = null,
)

/**
 * A repeating task's rule and template. Only the current occurrence (and completed history) exist
 * as [TaskEntity] rows; the next row is created when the current one is completed or skipped, so
 * a series never fans out into unbounded future rows. Fields here are what new occurrences copy.
 * On-device lists only: the Google Tasks API has no repeat rules.
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
)

/**
 * Minutes before the item's start (events) or due moment (tasks; date-only tasks use the
 * default task reminder time from settings).
 */
@Entity(
    tableName = "reminder",
    indices = [Index(value = ["ownerType", "ownerId"]), Index(value = ["ownerType", "ownerId", "minutesBefore"], unique = true)],
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    val ownerType: OwnerType,
    val ownerId: String,
    val minutesBefore: Int,
) {
    companion object {
        /**
         * Deterministic id: the same item + offset always gets the same id, so saving an unchanged
         * reminder (local edit or Google pull) keeps scheduled alarms and delivery records valid.
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

enum class OutboxOp { CREATE, UPDATE, DELETE }

@Entity(tableName = "outbox", indices = [Index(value = ["entityType", "entityId"])])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: OwnerType,
    val entityId: String,
    val op: OutboxOp,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
    val lastAttemptAt: Long? = null,
    /** Non-retryable rejections only (network/rate-limit/auth errors don't count). Parked at MAX_ATTEMPTS. */
    val permanentFailures: Int = 0,
    /**
     * For a DELETE of a record that has since moved elsewhere (e.g. a task moved to another
     * list): the remote id and remote container (list/calendar) to delete from. The local row is kept.
     */
    val snapshotRemoteId: String? = null,
    val snapshotContainerId: String? = null,
)

@Entity(tableName = "conflict_log", indices = [Index("at")])
data class ConflictLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: OwnerType,
    val entityId: String,
    val title: String,
    /** Human-readable summary of the version that lost. Never logged. */
    val losingVersion: String,
    val resolvedWith: String,
    val at: Long,
)

enum class SyncService { CALENDAR, TASKS }

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val service: SyncService,
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    val needsReauth: Boolean = false,
)
