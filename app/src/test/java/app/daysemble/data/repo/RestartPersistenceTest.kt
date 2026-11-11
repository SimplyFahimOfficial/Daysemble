package app.daysemble.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.Migrations
import app.daysemble.data.sync.ChangeListener
import app.daysemble.domain.model.TaskDraft
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * An app restart, for real: the database file is closed and opened again by a new database and
 * repository instance (the in-memory tests elsewhere share one connection). The repeat rule, the
 * countdown option and the completion history survive, and completing again after the restart
 * creates no second next occurrence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestartPersistenceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val name = "restart-test.db"
    private val zone = ZoneId.of("Europe/Berlin")
    private val clock = FixedAppClock(Instant.parse("2027-01-31T10:00:00Z"), zone)
    private val open = mutableListOf<DaysembleDatabase>()

    private fun db() = Room.databaseBuilder(ctx, DaysembleDatabase::class.java, name)
        .addMigrations(*Migrations.ALL).allowMainThreadQueries().build().also { open += it }

    private fun repo(db: DaysembleDatabase) = TaskRepository(db, clock, object : ChangeListener {
        override fun onTaskChanged(taskId: String) {}
    })

    @Before fun clean() { ctx.deleteDatabase(name) }
    @After fun close() { open.forEach { it.close() }; ctx.deleteDatabase(name) }

    @Test fun repeatCountdownAndHistory_surviveARestart_withoutDuplicates() = runTest {
        val first = db()
        val r1 = repo(first)
        val list = r1.ensureDefaultList("My tasks")
        val id = r1.create(
            TaskDraft(title = "Pay rent", dueDate = LocalDate.parse("2027-01-31"), repeat = RRule.parse("FREQ=MONTHLY"), countdown = true),
            list,
        )
        val other = r1.create(TaskDraft(title = "Exam", dueDate = LocalDate.parse("2027-06-01"), countdown = true), list)
        r1.setCompleted(id, true)
        r1.update(other, TaskDraft(title = "Exam", dueDate = LocalDate.parse("2027-06-01"), countdown = false))
        first.close()

        // "Restart": a new database connection and repository on the same file.
        val second = db()
        val r2 = repo(second)
        assertThat(r2.get(id)!!.isCompleted).isTrue()
        assertThat(r2.get(other)!!.countdownEnabled).isFalse() // turned off before the restart
        r2.setCompleted(id, true) // e.g. the notification's Complete, delivered after the restart
        val series = r2.allTasks.first().filter { it.title == "Pay rent" }.sortedBy { it.dueDate }
        assertThat(series.map { it.dueDate.toString() to it.isCompleted }).containsExactly("2027-01-31" to true, "2027-02-28" to false).inOrder()
        val next = series[1]
        assertThat(next.countdownEnabled).isTrue()
        assertThat(next.repeat!!.format()).isEqualTo("RRULE:FREQ=MONTHLY")
        // It continues from the 31st, not from the clamped 28th.
        clock.instant = Instant.parse("2027-02-28T10:00:00Z")
        r2.setCompleted(next.id, true)
        assertThat(r2.allTasks.first().single { it.title == "Pay rent" && !it.isCompleted }.dueDate).isEqualTo(LocalDate.parse("2027-03-31"))
    }
}
