package app.daysemble.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarSourceDao {
    @Query("SELECT * FROM calendar_source ORDER BY kind, isPrimary DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<CalendarSourceEntity>>

    @Query("SELECT * FROM calendar_source")
    suspend fun all(): List<CalendarSourceEntity>

    @Query("SELECT * FROM calendar_source WHERE id = :id")
    suspend fun get(id: String): CalendarSourceEntity?

    @Query("SELECT * FROM calendar_source WHERE kind = 'GOOGLE' AND remoteId = :remoteId AND accountEmail = :account")
    suspend fun getGoogle(remoteId: String, account: String): CalendarSourceEntity?

    @Query("SELECT * FROM calendar_source WHERE kind = :kind")
    suspend fun byKind(kind: SourceKind): List<CalendarSourceEntity>

    @Upsert suspend fun upsert(source: CalendarSourceEntity)

    @Query("UPDATE calendar_source SET isVisible = :visible WHERE id = :id")
    suspend fun setVisible(id: String, visible: Boolean)

    @Query("UPDATE calendar_source SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE calendar_source SET colorArgb = :color WHERE id = :id")
    suspend fun setColor(id: String, color: Int)

    @Query("UPDATE calendar_source SET isSyncEnabled = :enabled WHERE id = :id")
    suspend fun setSyncEnabled(id: String, enabled: Boolean)

    @Query("UPDATE calendar_source SET syncToken = :token WHERE id = :id")
    suspend fun setSyncToken(id: String, token: String?)

    @Query("DELETE FROM calendar_source WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM calendar_source WHERE kind = 'GOOGLE'")
    suspend fun deleteAllGoogle()
}

@Dao
interface EventDao {
    /** Masters, single events and overrides whose envelope intersects [start, end). */
    @Query(
        """
        SELECT e.* FROM event e JOIN calendar_source c ON c.id = e.calendarId
        WHERE e.isDeleted = 0 AND c.isVisible = 1 AND (c.kind = 'LOCAL' OR c.isSyncEnabled = 1)
          AND e.rangeStartUtc < :end AND e.rangeEndUtc > :start
        """,
    )
    fun observeInRange(start: Long, end: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM event WHERE recurringParentId IN (:parentIds) AND isDeleted = 0")
    suspend fun overridesFor(parentIds: List<String>): List<EventEntity>

    @Query("SELECT * FROM event WHERE id = :id")
    suspend fun get(id: String): EventEntity?

    @Query("SELECT * FROM event WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<EventEntity>

    @Query("SELECT * FROM event WHERE id = :id")
    fun observe(id: String): Flow<EventEntity?>

    @Query("SELECT * FROM event WHERE calendarId = :calendarId AND remoteId = :remoteId")
    suspend fun byRemoteId(calendarId: String, remoteId: String): EventEntity?

    @Query("SELECT * FROM event WHERE recurringParentId = :parentId AND originalStartKey = :key")
    suspend fun override(parentId: String, key: String): EventEntity?

    @Query(
        """
        SELECT e.* FROM event e JOIN calendar_source c ON c.id = e.calendarId
        WHERE e.isDeleted = 0 AND c.isVisible = 1 AND (c.kind = 'LOCAL' OR c.isSyncEnabled = 1) AND e.isCancelled = 0
          AND (e.title LIKE '%' || :q || '%' ESCAPE '\' OR e.description LIKE '%' || :q || '%' ESCAPE '\'
               OR e.location LIKE '%' || :q || '%' ESCAPE '\')
        LIMIT 200
        """,
    )
    fun search(q: String): Flow<List<EventEntity>>

    /** Every series master and single event with an end after [from], for reminder scheduling. */
    @Query("SELECT * FROM event WHERE isDeleted = 0 AND isCancelled = 0 AND rangeEndUtc > :from")
    suspend fun activeAfter(from: Long): List<EventEntity>

    @Upsert suspend fun upsert(event: EventEntity)
    @Upsert suspend fun upsertAll(events: List<EventEntity>)

    @Query("DELETE FROM event WHERE id = :id OR recurringParentId = :id")
    suspend fun hardDelete(id: String)

    @Query("SELECT * FROM event WHERE calendarId = :calendarId")
    suspend fun allIn(calendarId: String): List<EventEntity>

    @Query("SELECT * FROM event WHERE calendarId = :calendarId AND syncState != 'SYNCED' AND syncState != 'LOCAL'")
    suspend fun pendingIn(calendarId: String): List<EventEntity>
}

@Dao
interface TaskListDao {
    @Query("SELECT * FROM task_list ORDER BY kind, sortOrder, title COLLATE NOCASE")
    fun observeAll(): Flow<List<TaskListEntity>>

    @Query("SELECT * FROM task_list")
    suspend fun all(): List<TaskListEntity>

    @Query("SELECT * FROM task_list WHERE id = :id")
    suspend fun get(id: String): TaskListEntity?

    @Query("SELECT * FROM task_list WHERE kind = 'GOOGLE' AND remoteId = :remoteId AND accountEmail = :account")
    suspend fun getGoogle(remoteId: String, account: String): TaskListEntity?

    @Upsert suspend fun upsert(list: TaskListEntity)

    @Query("UPDATE task_list SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE task_list SET isSyncEnabled = :enabled WHERE id = :id")
    suspend fun setSyncEnabled(id: String, enabled: Boolean)

    @Query("UPDATE task_list SET lastPulledAt = :at WHERE id = :id")
    suspend fun setLastPulled(id: String, at: Long?)

    @Query("DELETE FROM task_list WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM task_list WHERE kind = 'GOOGLE'")
    suspend fun deleteAllGoogle()
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM task WHERE isDeleted = 0")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM task WHERE id = :id")
    fun observe(id: String): Flow<TaskEntity?>

    /**
     * Local-only fields, written in place. They don't touch updatedAt, which is the edit time that
     * last-writer-wins compares with Google's: a priority change isn't a newer version of the task.
     */
    @Query("UPDATE task SET priority = :priority WHERE id = :id")
    suspend fun setPriority(id: String, priority: Int)

    @Query("UPDATE task SET countdownEnabled = :enabled WHERE id = :id")
    suspend fun setCountdown(id: String, enabled: Boolean)

    @Query("SELECT * FROM task WHERE parentId = :parentId AND isDeleted = 0 ORDER BY sortKey")
    fun observeSubtasks(parentId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM task WHERE parentId = :parentId")
    suspend fun subtasks(parentId: String): List<TaskEntity>

    @Query("SELECT * FROM task WHERE listId = :listId AND remoteId = :remoteId")
    suspend fun byRemoteId(listId: String, remoteId: String): TaskEntity?

    @Query("SELECT COALESCE(MAX(sortKey), 0) FROM task WHERE listId = :listId")
    suspend fun maxSortKey(listId: String): Long

    @Query("SELECT * FROM task WHERE isDeleted = 0 AND isCompleted = 0")
    suspend fun openTasks(): List<TaskEntity>

    @Upsert suspend fun upsert(task: TaskEntity)
    @Upsert suspend fun upsertAll(tasks: List<TaskEntity>)

    @Query("DELETE FROM task WHERE id = :id OR parentId = :id")
    suspend fun hardDelete(id: String)

    /** Local-only tombstones whose undo window has passed. */
    @Query("DELETE FROM task WHERE isDeleted = 1 AND syncState = 'LOCAL' AND deletedAt < :before")
    suspend fun purgeLocalTombstones(before: Long)

    @Query("SELECT * FROM task WHERE listId = :listId")
    suspend fun allIn(listId: String): List<TaskEntity>

    @Query("SELECT * FROM task WHERE listId = :listId AND syncState != 'SYNCED' AND syncState != 'LOCAL'")
    suspend fun pendingIn(listId: String): List<TaskEntity>

    /** The row for one series date, deleted or not (deleted ones block regenerating that date). */
    @Query("SELECT * FROM task WHERE seriesId = :seriesId AND occurrenceDate = :date")
    suspend fun bySeriesDate(seriesId: String, date: Long): TaskEntity?

    @Query("SELECT * FROM task WHERE seriesId = :seriesId")
    suspend fun inSeries(seriesId: String): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskEntity)
}

@Dao
interface TaskSeriesDao {
    @Query("SELECT * FROM task_series")
    fun observeAll(): Flow<List<TaskSeriesEntity>>

    @Query("SELECT * FROM task_series WHERE id = :id")
    suspend fun get(id: String): TaskSeriesEntity?

    @Query("SELECT * FROM task_series WHERE id = :id")
    fun observe(id: String): Flow<TaskSeriesEntity?>

    /** Every stored occurrence (task row) of series [seriesId], deleted ones included. */
    @Query("SELECT * FROM task WHERE seriesId = :seriesId")
    fun observeOccurrences(seriesId: String): Flow<List<TaskEntity>>

    @Upsert suspend fun upsert(series: TaskSeriesEntity)

    @Query("DELETE FROM task_series WHERE id = :id")
    suspend fun delete(id: String)

    /** Ended series none of whose occurrences are left (all purged or hard-deleted). */
    @Query("DELETE FROM task_series WHERE endedAt IS NOT NULL AND endedAt < :before AND id NOT IN (SELECT seriesId FROM task WHERE seriesId IS NOT NULL)")
    suspend fun purgeEnded(before: Long)
}

@Dao
interface TaskOccurrenceSkipDao {
    @Query("SELECT * FROM task_occurrence_skip")
    fun observeAll(): Flow<List<TaskOccurrenceSkipEntity>>

    @Query("SELECT * FROM task_occurrence_skip WHERE seriesId = :seriesId")
    suspend fun forSeries(seriesId: String): List<TaskOccurrenceSkipEntity>

    @Query("SELECT * FROM task_occurrence_skip WHERE seriesId = :seriesId")
    fun observeForSeries(seriesId: String): Flow<List<TaskOccurrenceSkipEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(skip: TaskOccurrenceSkipEntity)

    @Query("DELETE FROM task_occurrence_skip WHERE seriesId = :seriesId AND occurrenceDate = :date")
    suspend fun delete(seriesId: String, date: Long)
}

@Dao
interface SleepDao {
    /** Newest first, by the later of the two times. */
    @Query("SELECT * FROM sleep_session ORDER BY COALESCE(endUtc, startUtc) DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SleepSessionEntity>>

    @Query("SELECT * FROM sleep_session ORDER BY COALESCE(endUtc, startUtc) DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SleepSessionEntity>

    @Query("SELECT * FROM sleep_session WHERE id = :id")
    suspend fun get(id: String): SleepSessionEntity?

    /** Complete records that overlap [start, end), at any age (for the overlap check of an edit). */
    @Query("SELECT * FROM sleep_session WHERE startUtc IS NOT NULL AND endUtc IS NOT NULL AND startUtc < :end AND endUtc > :start")
    suspend fun overlapping(start: Long, end: Long): List<SleepSessionEntity>

    @Upsert suspend fun upsert(session: SleepSessionEntity)

    @Query("DELETE FROM sleep_session WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder WHERE ownerType = :type AND ownerId = :ownerId ORDER BY minutesBefore")
    suspend fun forOwner(type: OwnerType, ownerId: String): List<ReminderEntity>

    @Query("SELECT * FROM reminder WHERE ownerType = :type AND ownerId = :ownerId ORDER BY minutesBefore")
    fun observeForOwner(type: OwnerType, ownerId: String): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminder")
    suspend fun all(): List<ReminderEntity>

    @Query("DELETE FROM reminder WHERE ownerType = :type AND ownerId = :ownerId")
    suspend fun deleteForOwner(type: OwnerType, ownerId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(reminders: List<ReminderEntity>)

    @Transaction
    suspend fun replace(type: OwnerType, ownerId: String, reminders: List<ReminderEntity>) {
        deleteForOwner(type, ownerId)
        insertAll(reminders)
    }
}

@Dao
interface AlarmDao {
    @Query("SELECT * FROM scheduled_alarm WHERE ownerId = :ownerId")
    suspend fun forOwner(ownerId: String): List<ScheduledAlarmEntity>

    @Query("SELECT * FROM scheduled_alarm")
    suspend fun all(): List<ScheduledAlarmEntity>

    @Query("SELECT * FROM scheduled_alarm WHERE requestCode = :code")
    suspend fun get(code: Int): ScheduledAlarmEntity?

    @Query("SELECT * FROM scheduled_alarm WHERE ownerId = :ownerId")
    fun observeForOwner(ownerId: String): Flow<List<ScheduledAlarmEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alarm: ScheduledAlarmEntity): Long

    @Query("DELETE FROM scheduled_alarm WHERE requestCode = :code")
    suspend fun delete(code: Int)

    @Query("DELETE FROM scheduled_alarm WHERE ownerId = :ownerId")
    suspend fun deleteForOwner(ownerId: String)

    @Query("SELECT COUNT(*) FROM delivered_reminder WHERE `key` = :key")
    suspend fun wasDelivered(key: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markDelivered(entity: DeliveredReminderEntity): Long

    @Query("DELETE FROM delivered_reminder WHERE deliveredAt < :before")
    suspend fun pruneDelivered(before: Long)
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM outbox ORDER BY id")
    suspend fun outbox(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE entityType = :type ORDER BY id")
    suspend fun outbox(type: OwnerType): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeOutboxCount(): Flow<Int>

    @Insert suspend fun enqueue(op: OutboxEntity): Long

    /** Clears pending ops for an entity. Move snapshots (delete-old-remote-copy) are independent and kept. */
    @Query("DELETE FROM outbox WHERE entityType = :type AND entityId = :entityId AND snapshotRemoteId IS NULL")
    suspend fun clearFor(type: OwnerType, entityId: String)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun remove(id: Long)

    /** Records a transient error (retried later; doesn't count toward parking). */
    @Query("UPDATE outbox SET lastError = :error, lastAttemptAt = :at WHERE id = :id")
    suspend fun markFailed(id: Long, error: String, at: Long)

    /** Records a permanent rejection (e.g. 400/403 permission); parked after MAX_ATTEMPTS. */
    @Query("UPDATE outbox SET lastError = :error, lastAttemptAt = :at, permanentFailures = permanentFailures + 1 WHERE id = :id")
    suspend fun markRejected(id: Long, error: String, at: Long)

    @Query("SELECT COUNT(*) FROM outbox WHERE permanentFailures >= :max")
    fun observeParkedCount(max: Int): Flow<Int>

    /** "Retry parked changes": gives every rejected change a fresh set of attempts. */
    @Query("UPDATE outbox SET permanentFailures = 0, lastError = NULL")
    suspend fun resetFailures()

    @Query("UPDATE outbox SET attempts = attempts + 1, lastAttemptAt = :at WHERE id = :id")
    suspend fun markAttempt(id: Long, at: Long)

    @Query("DELETE FROM outbox")
    suspend fun clearOutbox()

    @Query("SELECT * FROM sync_state")
    fun observeStates(): Flow<List<SyncStateEntity>>

    @Query("SELECT * FROM sync_state WHERE service = :service")
    suspend fun state(service: SyncService): SyncStateEntity?

    @Upsert suspend fun upsertState(state: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun clearStates()

    @Query("DELETE FROM sync_state WHERE service = :service")
    suspend fun clearState(service: SyncService)

    @Insert suspend fun logConflict(entry: ConflictLogEntity)

    @Query("SELECT * FROM conflict_log ORDER BY at DESC LIMIT 100")
    fun observeConflicts(): Flow<List<ConflictLogEntity>>

    @Query("DELETE FROM conflict_log")
    suspend fun clearConflicts()
}
