package app.daysemble.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.recurrence.OccurrenceHistoryCalc
import app.daysemble.core.recurrence.OccurrenceStatus
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.sync.ChangeListener
import app.daysemble.data.sync.Outbox
import app.daysemble.domain.model.SeriesScope
import app.daysemble.domain.model.TaskDraft
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.time.LocalTime
import java.time.ZoneId

/**
 * Occurrence tracking against Room: completion reports the next occurrence, durations and the
 * countdown carry over, skipped dates are kept as skipped after the deleted row is purged (and
 * removed again by undo), and the history built from the stored rows has the right totals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OccurrenceTrackingTest {
    private lateinit var db: DaysembleDatabase
    private lateinit var repo: TaskRepository
    private val zone = ZoneId.of("Europe/Berlin")
    private val clock = FixedAppClock(Instant.parse("2026-10-27T10:00:00Z"), zone) // Tue 27 Oct 2026
    private lateinit var list: String
    private fun d(s: String) = LocalDate.parse(s)

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DaysembleDatabase::class.java).allowMainThreadQueries().build()
        repo = TaskRepository(db, Outbox(db.sync()) { clock.now().toEpochMilli() }, clock, object : ChangeListener {
            override fun onTaskChanged(taskId: String) {}
            override fun onPendingSync() {}
        })
        list = repo.ensureDefaultList("My tasks")
    }

    @After fun tearDown() = db.close()

    private suspend fun history(seriesId: String) = TaskSeriesReader(db).observeSeries(seriesId).first()!!.let {
        OccurrenceHistoryCalc.build(OccurrenceHistoryCalc.SeriesInput(it.rule, it.anchor, it.rows, it.skips), clock.now(), zone, clock.today().plusWeeks(1))
    }

    @Test fun completion_reportsTheNextOccurrence_andCarriesDurationAndCountdown() = runTest {
        val id = repo.create(
            TaskDraft(title = "Run", dueDate = d("2026-10-27"), dueTime = LocalTime.of(7, 0), durationMinutes = 45, countdown = true, repeat = RRule.parse("FREQ=DAILY")),
            list,
        )
        val r = repo.setCompleted(id, true)!!
        assertThat(r.nextDue).isEqualTo(d("2026-10-28"))
        val next = repo.get(r.nextId!!)!!
        assertThat(next.dueDate).isEqualTo(d("2026-10-28"))
        assertThat(next.durationMinutes).isEqualTo(45)
        assertThat(next.countdownEnabled).isTrue()
        assertThat(next.scheduledBlock(zone)!!.first).isEqualTo(d("2026-10-28").atTime(7, 0).atZone(zone).toInstant())
        // Repeated completion of the same occurrence creates nothing more.
        assertThat(repo.setCompleted(id, true)).isNull()
        assertThat(repo.allTasks.first().count { it.title == "Run" }).isEqualTo(2)
    }

    @Test fun concurrentCompletions_createOneNextOccurrence() = runTest {
        val id = repo.create(TaskDraft(title = "Water", dueDate = d("2026-10-27"), repeat = RRule.parse("FREQ=DAILY")), list)
        listOf(async { repo.setCompleted(id, true) }, async { repo.setCompleted(id, true) }).awaitAll()
        assertThat(repo.allTasks.first().filter { it.title == "Water" && !it.isCompleted }).hasSize(1)
    }

    @Test fun skip_isRemembered_afterThePurge_andUndoRemovesIt() = runTest {
        val id = repo.create(TaskDraft(title = "Read", dueDate = d("2026-10-27"), repeat = RRule.parse("FREQ=DAILY")), list)
        val sid = repo.get(id)!!.seriesId!!
        repo.delete(id, SeriesScope.THIS) // skip today's
        clock.instant = clock.instant.plusSeconds(60)
        repo.purgeExpiredTombstones()
        val h = history(sid)
        assertThat(h.cells.first { it.date == d("2026-10-27") }.status).isEqualTo(OccurrenceStatus.SKIPPED)
        assertThat(h.active!!.date).isEqualTo(d("2026-10-28"))
        assertThat(h.missed).isEqualTo(0)

        // Undo (within the window) brings it back and forgets the skip.
        val id2 = repo.allTasks.first().single { it.title == "Read" && !it.isCompleted }.id
        repo.delete(id2, SeriesScope.THIS)
        repo.restore(id2)
        assertThat(db.skips().forSeries(sid).map { LocalDate.ofEpochDay(it.occurrenceDate) }).containsExactly(d("2026-10-27"))
    }

    @Test fun lateCompletion_marksThePassedDatesMissed_fromStoredRowsOnly() = runTest {
        // Created on the 24th, left until the 27th, then done.
        clock.instant = Instant.parse("2026-10-24T08:00:00Z")
        val id = repo.create(TaskDraft(title = "Log", dueDate = d("2026-10-24"), repeat = RRule.parse("FREQ=DAILY")), list)
        val sid = repo.get(id)!!.seriesId!!
        clock.instant = Instant.parse("2026-10-27T10:00:00Z")
        val before = history(sid)
        assertThat(before.active!!.status).isEqualTo(OccurrenceStatus.OVERDUE)
        repo.setCompleted(id, true)
        val h = history(sid)
        assertThat(h.completed).isEqualTo(1)
        assertThat(h.cells.filter { it.status == OccurrenceStatus.MISSED }.map { it.date }).containsExactly(d("2026-10-25"), d("2026-10-26"))
        assertThat(h.active!!.date).isEqualTo(d("2026-10-27"))
        // Undo of the completion restores the earlier picture.
        repo.setCompleted(id, false)
        assertThat(history(sid).completed).isEqualTo(0)
    }

    @Test fun countdownToggle_followsTheSeriesForFutureOccurrences() = runTest {
        val id = repo.create(TaskDraft(title = "Pay", dueDate = d("2026-10-27"), repeat = RRule.parse("FREQ=WEEKLY")), list)
        repo.setCountdown(id, true)
        assertThat(repo.get(id)!!.countdownEnabled).isTrue()
        val next = repo.setCompleted(id, true)!!.nextId!!
        assertThat(repo.get(next)!!.countdownEnabled).isTrue()
        // No due date: no countdown.
        val plain = repo.create(TaskDraft(title = "Someday"), list)
        repo.setCountdown(plain, true)
        assertThat(repo.get(plain)!!.countdownEnabled).isFalse()
    }

    @Test fun editThisOccurrenceOnly_leavesPastOccurrencesAlone() = runTest {
        val id = repo.create(TaskDraft(title = "Plan", dueDate = d("2026-10-27"), repeat = RRule.parse("FREQ=DAILY")), list)
        val next = repo.setCompleted(id, true)!!.nextId!!
        repo.update(next, TaskDraft(title = "Plan (short)", dueDate = d("2026-10-28"), repeat = RRule.parse("FREQ=DAILY"), durationMinutes = 15), SeriesScope.THIS)
        assertThat(repo.get(id)!!.title).isEqualTo("Plan")
        assertThat(repo.get(next)!!.durationMinutes).isEqualTo(15)
        // FUTURE changes the template the next ones copy.
        repo.update(next, TaskDraft(title = "Plan", dueDate = d("2026-10-28"), repeat = RRule.parse("FREQ=DAILY"), durationMinutes = 20), SeriesScope.FUTURE)
        val after = repo.setCompleted(next, true)!!.nextId!!
        assertThat(repo.get(after)!!.durationMinutes).isEqualTo(20)
        assertThat(repo.get(id)!!.durationMinutes).isNull()
    }

    /** Deleting a completed occurrence removes it from the history as skipped, never as missed. */
    @Test fun deletingACompletedOccurrence_showsSkipped_beforeAndAfterThePurge() = runTest {
        val id = repo.create(TaskDraft(title = "Floss", dueDate = d("2026-10-26"), repeat = RRule.parse("FREQ=DAILY")), list)
        val sid = repo.get(id)!!.seriesId!!
        repo.setCompleted(id, true) // next one: the 27th (today)
        repo.delete(id, SeriesScope.THIS)
        fun status() = kotlinx.coroutines.runBlocking { history(sid) }.cells.first { it.date == d("2026-10-26") }.status
        assertThat(status()).isEqualTo(OccurrenceStatus.SKIPPED) // within the undo window
        clock.instant = clock.instant.plusSeconds(60)
        repo.purgeExpiredTombstones()
        assertThat(status()).isEqualTo(OccurrenceStatus.SKIPPED) // after the purge, from the skip record
        val h = history(sid)
        assertThat(h.missed).isEqualTo(0)
        assertThat(h.completed).isEqualTo(0)
        // The current occurrence is untouched and no extra one was created.
        assertThat(repo.allTasks.first().filter { it.title == "Floss" && !it.isCompleted }.map { it.dueDate }).containsExactly(d("2026-10-27"))
        // Undo before a purge brings the completed one back.
        val id2 = repo.create(TaskDraft(title = "Walk", dueDate = d("2026-10-26"), repeat = RRule.parse("FREQ=DAILY")), list)
        val sid2 = repo.get(id2)!!.seriesId!!
        repo.setCompleted(id2, true); repo.delete(id2, SeriesScope.THIS); repo.restore(id2)
        assertThat(history(sid2).completed).isEqualTo(1)
        assertThat(history(sid2).skipped).isEqualTo(0)
    }
}
