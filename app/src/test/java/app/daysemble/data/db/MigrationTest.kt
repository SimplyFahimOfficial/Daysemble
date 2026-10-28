package app.daysemble.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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

    private fun createV1(fill: (SQLiteDatabase) -> Unit) {
        val schema = Json.parseToJsonElement(File("schemas/app.daysemble.data.db.DaysembleDatabase/1.json").readText())
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
            db.version = 1
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
}
