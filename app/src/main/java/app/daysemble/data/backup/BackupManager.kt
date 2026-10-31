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

/** What an import added, and how many items were skipped (already here, or unusable). */
data class ImportResult(val added: BackupCounts, val skipped: Int)

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
        val text = withContext(Dispatchers.IO) { readLimited(input) }
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupFormatException(BackupFormatException.Reason.NOT_A_BACKUP, e)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException(BackupFormatException.Reason.NOT_A_BACKUP, e)
        }
        if (file.format != BackupFile.FORMAT) throw BackupFormatException(BackupFormatException.Reason.NOT_A_BACKUP)
        if (file.version > BackupFile.VERSION) throw BackupFormatException(BackupFormatException.Reason.NEWER_VERSION)
        val result = merge(file)
        if (result.added != BackupCounts(0, 0, 0)) onImported()
        return result
    }

    /**
     * Adds what isn't here yet; never changes or removes anything that is. Every row is stored as
     * on-device data, whatever the file says, and rows that point at something missing are skipped.
     */
    suspend fun merge(file: BackupFile): ImportResult = db.withTransaction {
        var skipped = 0
        fun added(rowId: Long): Boolean = (rowId != -1L).also { if (!it) skipped++ }

        for (c in file.calendars.filter { validId(it.id) }) {
            added(dao.insertCalendar(c.copy(kind = SourceKind.LOCAL, remoteId = null, accountEmail = null, isPrimary = false, accessRole = "owner", syncToken = null, isSyncEnabled = true)))
        }
        for (l in file.lists.filter { validId(it.id) }) {
            added(dao.insertList(l.copy(kind = SourceKind.LOCAL, remoteId = null, accountEmail = null, isSyncEnabled = true, lastPulledAt = null)))
        }
        val calendars = dao.calendarIds().toSet()
        val lists = dao.listIds().toSet()

        for (s in file.series.filter { validId(it.id) }) {
            if (s.listId !in lists) { skipped++; continue }
            added(dao.insertSeries(s))
        }
        val series = dao.seriesIds().toSet()

        var events = 0
        // Series and single events before the occurrences changed from them.
        val existingEvents = dao.eventIds().toSet()
        val (overrides, masters) = file.events.filter { validId(it.id) }.partition { it.recurringParentId != null }
        val keptMasters = HashSet<String>()
        for (e in masters + overrides) {
            val usable = e.calendarId in calendars && validZone(e.timeZone) &&
                (e.recurringParentId == null || e.recurringParentId in keptMasters || e.recurringParentId in existingEvents)
            if (!usable) { skipped++; continue }
            val row = e.copy(remoteId = null, etag = null, remoteUpdatedAt = null, syncState = SyncState.LOCAL, isDeleted = false, pendingClientId = null, instancesUntil = null)
            if (added(dao.insertEvent(row))) { events++; if (e.recurringParentId == null) keptMasters += e.id }
        }

        var tasks = 0
        for (t in file.tasks.filter { validId(it.id) }) {
            if (t.listId !in lists || (t.seriesId != null && t.seriesId !in series)) { skipped++; continue }
            val row = t.copy(remoteId = null, etag = null, remotePosition = null, remoteUpdatedAt = null, syncState = SyncState.LOCAL, isDeleted = false, deletedAt = null)
            if (added(dao.insertTask(row))) tasks++
        }
        dao.detachOrphanSubtasks()

        for (s in file.skips) if (s.seriesId in series) dao.insertSkip(s)

        // Reminders only for items that are here now, with the deterministic id rebuilt from its parts.
        val eventIds = dao.eventIds().toSet()
        val taskIds = dao.taskIds().toSet()
        for (r in file.reminders) {
            val owned = if (r.ownerType == OwnerType.EVENT) r.ownerId in eventIds else r.ownerId in taskIds
            if (owned && r.minutesBefore in 0..MAX_REMINDER_MINUTES) dao.insertReminder(ReminderEntity.of(r.ownerType, r.ownerId, r.minutesBefore))
        }

        var sleep = 0
        for (s in file.sleep.filter { validId(it.id) }) {
            if (s.startUtc == null && s.endUtc == null) { skipped++; continue }
            if (added(dao.insertSleep(s))) sleep++
        }
        ImportResult(BackupCounts(events, tasks, sleep), skipped)
    }

    private fun validId(id: String) = id.isNotBlank() && id.length <= MAX_ID_LENGTH

    private fun validZone(id: String) = runCatching { ZoneId.of(id) }.isSuccess

    private fun readLimited(input: InputStream): String {
        val bytes = input.use { s ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > MAX_FILE_BYTES) throw BackupFormatException(BackupFormatException.Reason.TOO_LARGE)
            }
            buf.toByteArray()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        /** Far above any real backup (about 1 KB per item), small enough to read into memory. */
        const val MAX_FILE_BYTES = 50L * 1024 * 1024
        private const val MAX_ID_LENGTH = 200
        /** Four weeks: Google Calendar's longest reminder lead (the editors offer up to a day). */
        private const val MAX_REMINDER_MINUTES = 4 * 7 * 24 * 60
    }
}
