package app.daysemble.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds a real version-1 database from the exported schema (app/schemas/.../1.json: every table,
 * index and Room's identity hash) and fills it with rows, then opens it with the current
 * DaysembleDatabase and its migrations. Room runs the migration on open and validates the result
 * against the v2 schema (every table, column, type, default, foreign key and index), failing with
 * "Migration didn't properly handle" otherwise. The rows are then read back through the app's DAOs.
 *
 * (Room's MigrationTestHelper reads schemas from Android assets, which local unit tests don't
 * get without shipping the schemas in the app; this does the same from the schema file.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    private val name = "migration-test.db"
    private val app get() = ApplicationProvider.getApplicationContext<Context>()

    @Before @After fun clean() { app.deleteDatabase(name) }

    private fun createV1(fill: (SQLiteDatabase) -> Unit) = createAt(1, fill)

    /** A real database at schema [version] (from its exported schema), filled by [fill]. */
    private fun createAt(version: Int, fill: (SQLiteDatabase) -> Unit) {
        val schema = Json.parseToJsonElement(File("schemas/app.daysemble.data.db.DaysembleDatabase/$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        val file = app.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (e in schema.getValue("entities").jsonArray) {
                val o = e.jsonObject
                val table = o.getValue("tableName").jsonPrimitive.content
                db.execSQL(o.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                o["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '${schema.getValue("identityHash").jsonPrimitive.content}')")
            fill(db)
            db.version = version
        }
    }

    private fun openCurrent() = Room.databaseBuilder(app, DaysembleDatabase::class.java, name)
        .addMigrations(*Migrations.ALL).allowMainThreadQueries().build()

    @Test fun v1_to_v2_keepsData_andMatchesTheV2Schema() {
        createV1 { db ->
            db.execSQL("INSERT INTO task_list (id, kind, title, sortOrder, isSyncEnabled) VALUES ('l1', 'LOCAL', 'My tasks', 0, 1)")
            db.execSQL(
                "INSERT INTO task (id, listId, title, notes, dueDate, dueTimeMinutes, priority, isCompleted, completedAt, sortKey, " +
                    "createdAt, updatedAt, syncState, isDeleted) VALUES " +
                    "('t1', 'l1', 'Open task', 'n', 20755, 540, 3, 0, NULL, 1, 100, 100, 'LOCAL', 0)," +
                    "('t2', 'l1', 'Done task', '', NULL, NULL, 0, 1, 12345, 2, 100, 200, 'LOCAL', 0)",
            )
            db.execSQL("INSERT INTO reminder (id, ownerType, ownerId, minutesBefore) VALUES ('TASK:t1:10', 'TASK', 't1', 10)")
        }
        val room = openCurrent()
        try {
            runBlocking {
                val t1 = room.tasks().get("t1")!! // opening ran and validated the migration
                assertThat(t1.title).isEqualTo("Open task")
                assertThat(t1.notes).isEqualTo("n")
                assertThat(t1.dueDate).isEqualTo(20755)
                assertThat(t1.dueTimeMinutes).isEqualTo(540)
                assertThat(t1.priority).isEqualTo(3)
                assertThat(t1.isCompleted).isFalse()
                // Existing tasks become one-off tasks without a countdown.
                assertThat(t1.countdownEnabled).isFalse()
                assertThat(t1.seriesId).isNull()
                assertThat(t1.occurrenceDate).isNull()
                val t2 = room.tasks().get("t2")!!
                assertThat(t2.isCompleted).isTrue()
                assertThat(t2.completedAt).isEqualTo(12345)
                assertThat(room.reminders().forOwner(OwnerType.TASK, "t1").map { it.minutesBefore }).containsExactly(10)
                assertThat(room.taskLists().all().map { it.title }).containsExactly("My tasks")
                // The new table works and its defaults apply.
                room.taskSeries().upsert(TaskSeriesEntity("s1", "l1", "RRULE:FREQ=DAILY", 20755, "Water plants", createdAt = 1, updatedAt = 1))
                assertThat(room.taskSeries().get("s1")!!.reminders).isEmpty()
            }
        } finally {
            room.close()
        }
    }

    /** Two rows for the same series date are refused by the unique index (no duplicate occurrences). */
    @Test fun v2_refusesASecondRowForTheSameSeriesDate() {
        createV1 { db -> db.execSQL("INSERT INTO task_list (id, kind, title, sortOrder, isSyncEnabled) VALUES ('l1', 'LOCAL', 'My tasks', 0, 1)") }
        val room = openCurrent()
        try {
            runBlocking {
                val row = TaskEntity(
                    id = "a", listId = "l1", title = "Water plants", dueDate = 20755, sortKey = 1, createdAt = 1, updatedAt = 1,
                    syncState = SyncState.LOCAL, seriesId = "s1", occurrenceDate = 20755,
                )
                room.tasks().insert(row)
                val second = runCatching { room.tasks().insert(row.copy(id = "b")) }
                assertThat(second.exceptionOrNull()).isInstanceOf(SQLiteConstraintException::class.java)
                // One-off tasks (no series) are unaffected: NULLs never collide.
                room.tasks().insert(row.copy(id = "c", seriesId = null, occurrenceDate = null))
                room.tasks().insert(row.copy(id = "d", seriesId = null, occurrenceDate = null))
            }
        } finally {
            room.close()
        }
    }

    /**
     * v2 → v3 keeps repeating tasks, their completed history and countdowns exactly, adds no task
     * duration to any existing task (so none takes free time it didn't before), and the new
     * tables work: skipped dates (cascading with their series) and sleep records.
     */
    @Test fun v2_to_v3_keepsSeriesAndHistory_andAddsDurationsSkipsAndSleep() {
        createAt(2) { db ->
            db.execSQL("INSERT INTO task_list (id, kind, title, sortOrder, isSyncEnabled) VALUES ('l1', 'LOCAL', 'My tasks', 0, 1)")
            db.execSQL(
                "INSERT INTO task_series (id, listId, rrule, anchorDate, title, notes, dueTimeMinutes, priority, countdownEnabled, reminders, endedAt, createdAt, updatedAt) " +
                    "VALUES ('s1', 'l1', 'RRULE:FREQ=DAILY', 20750, 'Stretch', '', 450, 0, 1, '10', NULL, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO task (id, listId, title, notes, dueDate, dueTimeMinutes, priority, isCompleted, completedAt, sortKey, " +
                    "createdAt, updatedAt, syncState, isDeleted, countdownEnabled, seriesId, occurrenceDate) VALUES " +
                    "('s1:a', 'l1', 'Stretch', '', 20750, 450, 0, 1, 999, 1, 1, 2, 'LOCAL', 0, 1, 's1', 20750)," +
                    "('s1:b', 'l1', 'Stretch', '', 20751, 450, 0, 0, NULL, 2, 1, 1, 'LOCAL', 0, 1, 's1', 20751)," +
                    "('one', 'l1', 'Exam', '', 20800, 600, 2, 0, NULL, 3, 1, 1, 'LOCAL', 0, 1, NULL, NULL)",
            )
        }
        val room = openCurrent()
        try {
            runBlocking {
                val done = room.tasks().get("s1:a")!!
                assertThat(done.isCompleted).isTrue()
                assertThat(done.completedAt).isEqualTo(999)
                assertThat(done.occurrenceDate).isEqualTo(20750)
                val current = room.tasks().get("s1:b")!!
                assertThat(current.seriesId).isEqualTo("s1")
                assertThat(current.countdownEnabled).isTrue()
                assertThat(listOf(done, current, room.tasks().get("one")!!).map { it.durationMinutes }).containsExactly(null, null, null)
                val series = room.taskSeries().get("s1")!!
                assertThat(series.rrule).isEqualTo("RRULE:FREQ=DAILY")
                assertThat(series.reminders).isEqualTo("10")
                assertThat(series.durationMinutes).isNull()
                // New tables.
                room.skips().insert(TaskOccurrenceSkipEntity("s1", 20752, 5))
                assertThat(room.skips().forSeries("s1").map { it.occurrenceDate }).containsExactly(20752L)
                room.sleep().upsert(SleepSessionEntity("z", startUtc = 1, endUtc = 2, createdAt = 1, updatedAt = 1))
                assertThat(room.sleep().get("z")!!.endUtc).isEqualTo(2)
                // Skips go with their series.
                room.taskSeries().delete("s1")
                assertThat(room.skips().forSeries("s1")).isEmpty()
            }
        } finally {
            room.close()
        }
    }

    @Test fun v3_to_v4_removesGoogleSync_keepsEveryTaskAndLocalEvent() {
        createAt(3) { db ->
            val event = "(id, calendarId, remoteId, etag, title, description, location, isAllDay, startUtc, endUtc, startDate, endDateExclusive, timeZone, " +
                "exdates, isCancelled, rangeStartUtc, rangeEndUtc, createdAt, updatedAt, syncState, isDeleted, isReadOnlyRecurrence, pendingClientId, instancesUntil)"
            val task = "(id, listId, remoteId, etag, title, notes, priority, isCompleted, sortKey, remotePosition, createdAt, updatedAt, syncState, isDeleted, countdownEnabled)"
            db.execSQL("INSERT INTO calendar_source (id, kind, remoteId, accountEmail, name, colorArgb, isVisible, isSyncEnabled, isPrimary, accessRole, syncToken, defaultReminders) VALUES " +
                "('local', 'LOCAL', NULL, NULL, 'Personal', 1, 1, 1, 1, 'owner', NULL, '')," +
                "('g', 'GOOGLE', 'primary', 'me@example.com', 'Google', 2, 1, 1, 0, 'writer', 'tok', '10')")
            db.execSQL("INSERT INTO event $event VALUES " +
                "('mine', 'local', NULL, NULL, 'My event', '', '', 0, 100, 200, 0, 0, 'UTC', '', 0, 100, 200, 1, 1, 'LOCAL', 0, 0, NULL, NULL)," +
                "('theirs', 'g', 'r1', 'e', 'Synced event', '', '', 0, 100, 200, 0, 0, 'UTC', '', 0, 100, 200, 1, 1, 'SYNCED', 0, 0, NULL, NULL)")
            db.execSQL("INSERT INTO reminder (id, ownerType, ownerId, minutesBefore) VALUES ('EVENT:mine:10', 'EVENT', 'mine', 10), ('EVENT:theirs:10', 'EVENT', 'theirs', 10)")
            db.execSQL("INSERT INTO task_list (id, kind, remoteId, accountEmail, title, sortOrder, isSyncEnabled, lastPulledAt) VALUES " +
                "('l1', 'LOCAL', NULL, NULL, 'My tasks', 0, 1, NULL), ('gl', 'GOOGLE', 'L1', 'me@example.com', 'Google list', 1, 1, 77)")
            db.execSQL("INSERT INTO task $task VALUES " +
                "('t-local', 'l1', NULL, NULL, 'Local task', '', 0, 0, 1, NULL, 1, 1, 'LOCAL', 0, 0)," +
                "('t-synced', 'gl', 'z1', 'e', 'Synced task', 'n', 2, 0, 1, '0001', 1, 1, 'SYNCED', 0, 0)," +
                "('t-pending', 'gl', NULL, NULL, 'Not pushed yet', '', 0, 0, 2, NULL, 1, 1, 'PENDING_CREATE', 0, 0)," +
                "('t-conflict', 'gl', 'z2', 'e', 'Conflicted', '', 0, 1, 3, NULL, 1, 1, 'CONFLICT', 0, 0)," +
                "('t-gone', 'gl', 'z3', 'e', 'Deleted by the user', '', 0, 0, 4, NULL, 1, 1, 'PENDING_DELETE', 1, 0)")
            db.execSQL("INSERT INTO outbox (entityType, entityId, op, createdAt, attempts, permanentFailures) VALUES ('TASK', 't-pending', 'CREATE', 1, 0, 0)")
            db.execSQL("INSERT INTO conflict_log (entityType, entityId, title, losingVersion, resolvedWith, at) VALUES ('TASK', 't-conflict', 'x', 'y', 'z', 1)")
            db.execSQL("INSERT INTO sync_state (service, lastSuccessAt, needsReauth) VALUES ('CALENDAR', 1, 0)")
        }
        val room = openCurrent() // Room validates the migrated database against the v4 schema on open
        try {
            runBlocking {
                // Events of the Google calendar are gone (the phone's calendar supplies them now); the app's own stay.
                assertThat(room.calendars().all().map { it.id }).containsExactly("local")
                assertThat(room.events().get("mine")!!.title).isEqualTo("My event")
                assertThat(room.events().get("theirs")).isNull()
                assertThat(room.reminders().all().map { it.ownerId }).containsExactly("mine")
                // Every task survives, in an ordinary list, with no sync identity. Only the one already deleted is dropped.
                assertThat(room.taskLists().all().map { it.id to it.kind }).containsExactly("l1" to SourceKind.LOCAL, "gl" to SourceKind.LOCAL)
                assertThat(room.tasks().observeAll().first().map { it.id }).containsExactly("t-local", "t-synced", "t-pending", "t-conflict")
                val synced = room.tasks().get("t-synced")!!
                assertThat(synced.listId).isEqualTo("gl")
                assertThat(synced.notes).isEqualTo("n")
                assertThat(synced.priority).isEqualTo(2)
                assertThat(synced.remoteId).isNull()
                assertThat(synced.etag).isNull()
                assertThat(synced.syncState).isEqualTo(SyncState.LOCAL)
                assertThat(room.tasks().get("t-conflict")!!.isCompleted).isTrue()
                assertThat(room.tasks().get("t-gone")).isNull()
                assertThat(room.taskLists().get("gl")!!.accountEmail).isNull()
                // Nothing of the sync machinery is left, and new work can be saved.
                val tables = room.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
                    generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
                }
                assertThat(tables).containsNoneOf("outbox", "conflict_log", "sync_state")
                assertThat(room.calendars().get("local")!!.isPrimary).isTrue()
            }
        } finally {
            room.close()
        }
    }
}
