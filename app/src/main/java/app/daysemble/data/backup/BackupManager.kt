package app.daysemble.data.backup

import androidx.room.withTransaction
import app.daysemble.core.time.AppClock
import app.daysemble.data.db.CalendarSourceEntity
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.EventEntity
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.ReminderEntity
import app.daysemble.data.db.SleepSessionEntity
import app.daysemble.data.db.SourceKind
import app.daysemble.data.db.SyncState
import app.daysemble.data.db.TaskEntity
import app.daysemble.data.db.TaskListEntity
import app.daysemble.data.db.TaskOccurrenceSkipEntity
import app.daysemble.data.db.TaskSeriesEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneId

/**
 * One JSON file with everything stored on this device: on-device calendars and events, task lists,
 * tasks (with repeats and skipped dates), reminders and sleep records. Google calendars and task
 * lists aren't included; they come back from Google when the account is connected again. Nothing
 * that identifies an account or a sync state is written.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = 0,
    val calendars: List<CalendarSourceEntity> = emptyList(),
    val events: List<EventEntity> = emptyList(),
    @SerialName("taskLists") val lists: List<TaskListEntity> = emptyList(),
    val tasks: List<TaskEntity> = emptyList(),
    @SerialName("taskSeries") val series: List<TaskSeriesEntity> = emptyList(),
    @SerialName("skippedDates") val skips: List<TaskOccurrenceSkipEntity> = emptyList(),
    val reminders: List<ReminderEntity> = emptyList(),
    val sleep: List<SleepSessionEntity> = emptyList(),
) {
    companion object {
        const val FORMAT = "daysemble-backup"
        const val VERSION = 1
    }
}

/** The file isn't a Daysemble backup, is damaged, or comes from a newer app version. */
class BackupFormatException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { NOT_A_BACKUP, NEWER_VERSION, TOO_LARGE }
}

data class BackupCounts(val events: Int, val tasks: Int, val sleep: Int)

/**
 * What an import added, and how many items were skipped (already here, or unusable). [changed] is
 * true when anything at all was written, also reminders or skipped dates for items already here.
 */
data class ImportResult(val added: BackupCounts, val skipped: Int, val changed: Boolean = added != BackupCounts(0, 0, 0))

class BackupManager(
    private val db: DaysembleDatabase,
    private val clock: AppClock,
    /** Called after an import added something: re-plans reminders. */
    private val onImported: suspend () -> Unit,
) {
    private val dao = db.backup()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    suspend fun snapshot(): BackupFile = db.withTransaction {
        val calendars = dao.localCalendars()
        val lists = dao.localLists()
        val events = dao.localEvents()
        val tasks = dao.localTasks()
        val owners = events.map { OwnerType.EVENT to it.id }.toSet() + tasks.map { OwnerType.TASK to it.id }
        BackupFile(
            exportedAt = clock.now().toEpochMilli(),
            calendars = calendars.map { it.copy(syncToken = null) },
            events = events,
            lists = lists.map { it.copy(lastPulledAt = null) },
            tasks = tasks,
            series = dao.localSeries(),
            skips = dao.localSkips(),
            reminders = dao.reminders().filter { (it.ownerType to it.ownerId) in owners },
            sleep = dao.sleep(),
        )
    }

    suspend fun export(out: OutputStream): BackupCounts {
        val file = snapshot()
        withContext(Dispatchers.IO) { out.bufferedWriter().use { it.write(json.encodeToString(BackupFile.serializer(), file)) } }
        return BackupCounts(file.events.size, file.tasks.size, file.sleep.size)
    }

    /** Reads a backup; throws [BackupFormatException] for a file it can't use. Nothing is written then. */
    suspend fun import(input: InputStream): ImportResult {
        // Reading and parsing (up to MAX_FILE_BYTES) stay off the caller's thread, which is the main one.
        val file = withContext(Dispatchers.IO) { parse(readLimited(input)) }
        if (file.format != BackupFile.FORMAT) throw BackupFormatException(BackupFormatException.Reason.NOT_A_BACKUP)
        if (file.version > BackupFile.VERSION) throw BackupFormatException(BackupFormatException.Reason.NEWER_VERSION)
        val result = merge(file)
        if (result.changed) replanReminders()
        return result
    }

    /**
     * The data is in: a failure while re-planning mustn't report the import as failed. Reminders are
     * planned again at the next app start and by the periodic refresh.
     */
    private suspend fun replanReminders() {
        runCatching { onImported() }.onFailure { if (it is CancellationException) throw it }
    }

    private fun parse(text: String): BackupFile = try {
        json.decodeFromString(BackupFile.serializer(), text)
    } catch (e: SerializationException) {
        throw BackupFormatException(BackupFormatException.Reason.NOT_A_BACKUP, e)
    } catch (e: IllegalArgumentException) {
        throw BackupFormatException(BackupFormatException.Reason.NOT_A_BACKUP, e)
    }

    /**
     * Adds what isn't here yet; never changes or removes anything that is. Every row is stored as
     * on-device data, whatever the file says, and rows that point at something missing are skipped.
     * Only on-device rows count as "here" for references: an id that matches a Google calendar, list
     * or item must not pull imported rows into synced data, so rows pointing at one are skipped too.
     */
    suspend fun merge(file: BackupFile): ImportResult = db.withTransaction {
        val tally = Tally()
        mergeContainers(file, tally)
        val calendars = dao.localCalendars().mapTo(HashSet()) { it.id }
        val lists = dao.localLists().mapTo(HashSet()) { it.id }
        file.series.filter { validId(it.id) }.forEach { s ->
            if (s.listId in lists && validDay(s.anchorDate) && validMinute(s.dueTimeMinutes)) tally.added(dao.insertSeries(s)) else tally.skipped++
        }
        val series = dao.localSeries().mapTo(HashSet()) { it.id }
        mergeEvents(file.events, calendars, tally)
        mergeTasks(file.tasks, lists, series, tally)
        // Skipped dates and reminders belong to an item: one whose item was skipped isn't counted again.
        file.skips.forEach { s -> if (s.seriesId in series) tally.addedOther(dao.insertSkip(s)) }
        mergeReminders(file.reminders, tally)
        file.sleep.filter { validId(it.id) }.forEach { s ->
            if (s.startUtc == null && s.endUtc == null) tally.skipped++ else if (tally.added(dao.insertSleep(s))) tally.sleep++
        }
        ImportResult(BackupCounts(tally.events, tally.tasks, tally.sleep), tally.skipped, tally.changed)
    }

    /** Counts of one merge. [added] and [addedOther] take an insert's row id (-1: already here). */
    private class Tally {
        var skipped = 0
        var events = 0
        var tasks = 0
        var sleep = 0
        var changed = false
        fun added(rowId: Long): Boolean = (rowId != -1L).also { if (it) changed = true else skipped++ }
        fun addedOther(rowId: Long) { if (rowId != -1L) changed = true }
    }

    private suspend fun mergeContainers(file: BackupFile, tally: Tally) {
        for (c in file.calendars.filter { validId(it.id) }) {
            val row = c.copy(
                kind = SourceKind.LOCAL, remoteId = null, accountEmail = null, isPrimary = false,
                accessRole = "owner", syncToken = null, isSyncEnabled = true,
            )
            tally.added(dao.insertCalendar(row))
        }
        for (l in file.lists.filter { validId(it.id) }) {
            tally.added(dao.insertList(l.copy(kind = SourceKind.LOCAL, remoteId = null, accountEmail = null, isSyncEnabled = true, lastPulledAt = null)))
        }
    }

    private suspend fun mergeEvents(events: List<EventEntity>, calendars: Set<String>, tally: Tally) {
        // Series before the occurrences changed from them. A changed occurrence needs an on-device
        // series to belong to: one of a single event would never be shown.
        val seriesIds = dao.localEvents().filter { it.rrule != null && it.recurringParentId == null }.mapTo(HashSet()) { it.id }
        val (overrides, masters) = events.filter { validId(it.id) }.partition { it.recurringParentId != null }
        for (e in masters + overrides) {
            val usable = e.calendarId in calendars && validZone(e.timeZone) && validEventDays(e) &&
                (e.recurringParentId == null || e.recurringParentId in seriesIds)
            if (!usable) { tally.skipped++; continue }
            val row = e.copy(
                remoteId = null, etag = null, remoteUpdatedAt = null, syncState = SyncState.LOCAL,
                isDeleted = false, pendingClientId = null, instancesUntil = null,
            )
            if (tally.added(dao.insertEvent(row))) {
                tally.events++
                if (e.recurringParentId == null && e.rrule != null) seriesIds += e.id
            }
        }
    }

    private suspend fun mergeTasks(tasks: List<TaskEntity>, lists: Set<String>, series: Set<String>, tally: Tally) {
        // A parent that is here but not on-device (a Google task) doesn't adopt an imported subtask.
        val foreign = dao.taskIds().toHashSet().apply { removeAll(dao.localTasks().mapTo(HashSet()) { it.id }) }
        for (t in tasks.filter { validId(it.id) }) {
            val usable = t.listId in lists && (t.seriesId == null || t.seriesId in series) && validTaskTimes(t)
            if (!usable) { tally.skipped++; continue }
            val row = t.copy(
                parentId = t.parentId?.takeIf { it !in foreign },
                remoteId = null, etag = null, remotePosition = null, remoteUpdatedAt = null,
                syncState = SyncState.LOCAL, isDeleted = false, deletedAt = null,
            )
            if (tally.added(dao.insertTask(row))) tally.tasks++
        }
        dao.detachOrphanSubtasks()
    }

    /** Reminders only for on-device items that are here now, with the deterministic id rebuilt from its parts. */
    private suspend fun mergeReminders(reminders: List<ReminderEntity>, tally: Tally) {
        val eventIds = dao.localEvents().mapTo(HashSet()) { it.id }
        val taskIds = dao.localTasks().mapTo(HashSet()) { it.id }
        for (r in reminders) {
            val owned = if (r.ownerType == OwnerType.EVENT) r.ownerId in eventIds else r.ownerId in taskIds
            if (owned && r.minutesBefore in 0..MAX_REMINDER_MINUTES) {
                tally.addedOther(dao.insertReminder(ReminderEntity.of(r.ownerType, r.ownerId, r.minutesBefore)))
            }
        }
    }

    /** Dates and times the app reads back with LocalDate/LocalTime: out of range, they would throw. */
    private fun validTaskTimes(t: TaskEntity) =
        (t.dueDate == null || validDay(t.dueDate)) && (t.occurrenceDate == null || validDay(t.occurrenceDate)) && validMinute(t.dueTimeMinutes)

    private fun validEventDays(e: EventEntity) = !e.isAllDay || (validDay(e.startDate) && validDay(e.endDateExclusive))

    private fun validDay(epochDay: Long) = epochDay in MIN_EPOCH_DAY..MAX_EPOCH_DAY

    private fun validMinute(m: Int?) = m == null || m in 0 until MINUTES_PER_DAY

    private fun validId(id: String) = id.isNotBlank() && id.length <= MAX_ID_LENGTH

    private fun validZone(id: String) = runCatching { ZoneId.of(id) }.isSuccess

    private fun readLimited(input: InputStream): String =
        input.use { s ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(CHUNK_BYTES)
            while (true) {
                val n = s.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > MAX_FILE_BYTES) throw BackupFormatException(BackupFormatException.Reason.TOO_LARGE)
            }
            // Decoded straight from the buffer: no second copy of up to MAX_FILE_BYTES.
            buf.toString(Charsets.UTF_8.name())
        }

    companion object {
        /** Far above any real backup (about 1 KB per item), small enough to read into memory. */
        const val MAX_FILE_BYTES = 50L * 1024 * 1024
        private const val MAX_ID_LENGTH = 200
        /** Four weeks: Google Calendar's longest reminder lead (the editors offer up to a day). */
        private const val MAX_REMINDER_MINUTES = 4 * 7 * 24 * 60
        private const val CHUNK_BYTES = 64 * 1024
        private const val MINUTES_PER_DAY = 24 * 60
        private val MIN_EPOCH_DAY = java.time.LocalDate.MIN.toEpochDay()
        private val MAX_EPOCH_DAY = java.time.LocalDate.MAX.toEpochDay()
    }
}
