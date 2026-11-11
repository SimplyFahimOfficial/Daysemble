package app.daysemble.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Reads for the backup file ([app.daysemble.data.backup.BackupManager]). On-device data only. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM calendar_source WHERE kind = 'LOCAL'")
    suspend fun localCalendars(): List<CalendarSourceEntity>

    @Query("SELECT * FROM task_list WHERE kind = 'LOCAL'")
    suspend fun localLists(): List<TaskListEntity>

    @Query("SELECT * FROM event WHERE calendarId IN (SELECT id FROM calendar_source WHERE kind = 'LOCAL') AND isDeleted = 0")
    suspend fun localEvents(): List<EventEntity>

    @Query("SELECT * FROM task WHERE listId IN (SELECT id FROM task_list WHERE kind = 'LOCAL') AND isDeleted = 0")
    suspend fun localTasks(): List<TaskEntity>

    @Query("SELECT * FROM task_series WHERE listId IN (SELECT id FROM task_list WHERE kind = 'LOCAL')")
    suspend fun localSeries(): List<TaskSeriesEntity>

    @Query("SELECT * FROM task_occurrence_skip WHERE seriesId IN (SELECT s.id FROM task_series s JOIN task_list l ON s.listId = l.id WHERE l.kind = 'LOCAL')")
    suspend fun localSkips(): List<TaskOccurrenceSkipEntity>

    @Query("SELECT * FROM sleep_session")
    suspend fun sleep(): List<SleepSessionEntity>

    @Query("SELECT * FROM reminder")
    suspend fun reminders(): List<ReminderEntity>

    @Query("SELECT id FROM event")
    suspend fun eventIds(): List<String>
}

/**
 * Inserts for a backup import ([app.daysemble.data.backup.BackupManager]). They never overwrite: a
 * row whose id (or unique key) is already here is skipped, which the return value shows as -1.
 */
@Dao
interface BackupImportDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCalendar(c: CalendarSourceEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertList(l: TaskListEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvent(e: EventEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTask(t: TaskEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSeries(s: TaskSeriesEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSkip(s: TaskOccurrenceSkipEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSleep(s: SleepSessionEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertReminder(r: ReminderEntity): Long

    /** A subtask whose parent isn't here (it wasn't in the file) becomes a top-level task. */
    @Query("UPDATE task SET parentId = NULL WHERE parentId IS NOT NULL AND parentId NOT IN (SELECT id FROM task)")
    suspend fun detachOrphanSubtasks()
}
