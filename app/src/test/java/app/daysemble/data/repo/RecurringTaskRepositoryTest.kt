package app.daysemble.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.SourceKind
import app.daysemble.data.db.TaskListEntity
import app.daysemble.data.sync.ChangeListener
import app.daysemble.data.sync.Outbox
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.SeriesScope
import app.daysemble.domain.model.Task
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
 * Repeating tasks against a real Room database: creation, completion and the next occurrence,
 * duplicate prevention (repeated taps, concurrent calls, a "restart" with a new repository),
 * missed dates, COUNT/UNTIL, month ends and leap years, edit and delete scopes, undo, and the
 * Google list restriction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecurringTaskRepositoryTest {
    private lateinit var db: DaysembleDatabase
    private lateinit var repo: TaskRepository
    private val zone = ZoneId.of("Europe/Berlin")
    private val clock = FixedAppClock(Instant.parse("2026-10-27T10:00:00Z"), zone) // Tue 27 Oct 2026
    private val changed = mutableListOf<String>()
    private lateinit var list: String

    private fun newRepo() = TaskRepository(db, Outbox(db.sync()) { clock.now().toEpochMilli() }, clock, object : ChangeListener {
        override fun onTaskChanged(taskId: String) { changed += taskId }
        override fun onPendingSync() {}
    })

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DaysembleDatabase::class.java).allowMainThreadQueries().build()
        repo = newRepo()
        list = repo.ensureDefaultList("My tasks")
    }

    @After fun tearDown() = db.close()

    private fun d(s: String) = LocalDate.parse(s)
    private fun today(s: String) { clock.instant = d(s).atTime(12, 0).atZone(zone).toInstant() }
    private suspend fun all(): List<Task> = repo.allTasks.first()
    private suspend fun series(id: String) = all().filter { it.seriesId == repo.get(id)!!.seriesId }.sortedBy { it.occurrenceDate }
    private suspend fun open(seriesOf: String) = all().filter { it.seriesId == seriesOf && !it.isCompleted }

    private suspend fun repeating(rule: String, due: String, title: String = "Water plants", time: LocalTime? = null) = repo.create(
        TaskDraft(title = title, dueDate = d(due), dueTime = time, repeat = RRule.parse(rule), reminderMinutesBefore = listOf(30), priority = Priority.HIGH),
        list,
    )

    @Test fun create_repeatingTask_isASeriesWithOneOccurrence() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        val t = repo.get(id)!!
        assertThat(t.isRecurring).isTrue()
        assertThat(t.repeat!!.format()).isEqualTo("RRULE:FREQ=DAILY")
        assertThat(t.occurrenceDate).isEqualTo(d("2026-10-27"))
        assertThat(all()).hasSize(1) // no future rows are created up front
    }

    @Test fun complete_createsTheNextOccurrence_withTheTemplate() = runTest {
        val id = repeating("FREQ=WEEKLY;BYDAY=TU,TH", "2026-10-27", time = LocalTime.of(9, 0))
        repo.setCompleted(id, true)
        val rows = series(id)
        assertThat(rows.map { it.occurrenceDate.toString() to it.isCompleted }).containsExactly("2026-10-27" to true, "2026-10-29" to false).inOrder()
        val next = rows[1]
        assertThat(next.title).isEqualTo("Water plants")
        assertThat(next.dueTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(next.priority).isEqualTo(Priority.HIGH)
        assertThat(next.id).isEqualTo("${next.seriesId}:2026-10-29") // stable id
        assertThat(repo.reminders(next.id)).containsExactly(30)
        assertThat(changed).contains(next.id) // its reminders get scheduled
    }

    @Test fun completingAgain_orConcurrently_neverDuplicates() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        listOf(async { repo.setCompleted(id, true) }, async { repo.setCompleted(id, true) }, async { repo.setCompleted(id, true) }).awaitAll()
        repo.setCompleted(id, true)
        // A fresh repository (as after an app restart) completing the same row again does nothing either.
        newRepo().setCompleted(id, true)
        assertThat(series(id).map { it.occurrenceDate.toString() }).containsExactly("2026-10-27", "2026-10-28").inOrder()
    }

    @Test fun missedDates_areSkipped_notBackfilled() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-20") // a week overdue
        repo.setCompleted(id, true)
        assertThat(series(id).map { it.occurrenceDate.toString() }).containsExactly("2026-10-20", "2026-10-27").inOrder()
    }

    @Test fun completingEarly_givesTheOneAfterIt() = runTest {
        val id = repeating("FREQ=WEEKLY;BYDAY=FR", "2026-10-30")
        repo.setCompleted(id, true)
        assertThat(open(repo.get(id)!!.seriesId!!).single().dueDate).isEqualTo(d("2026-11-06"))
    }

    @Test fun count_endsTheSeries() = runTest {
        var id = repeating("FREQ=DAILY;COUNT=2", "2026-10-27")
        repo.setCompleted(id, true)
        id = open(repo.get(id)!!.seriesId!!).single().id
        repo.setCompleted(id, true)
        assertThat(series(id).map { it.isCompleted }).containsExactly(true, true)
    }

    @Test fun monthEnd_andLeapDay() = runTest {
        today("2027-01-31")
        var id = repeating("FREQ=MONTHLY", "2027-01-31", title = "Rent")
        val dates = mutableListOf<String>()
        repeat(3) {
            val sid = repo.get(id)!!.seriesId!!
            repo.setCompleted(id, true)
            val next = open(sid).single()
            dates += next.dueDate.toString()
            today(next.dueDate.toString())
            id = next.id
        }
        assertThat(dates).containsExactly("2027-02-28", "2027-03-31", "2027-04-30").inOrder()

        today("2028-02-29")
        val leap = repeating("FREQ=YEARLY", "2028-02-29", title = "Leap")
        repo.setCompleted(leap, true)
        assertThat(open(repo.get(leap)!!.seriesId!!).single().dueDate).isEqualTo(d("2029-02-28"))
    }

    @Test fun reopening_removesTheUntouchedNextOccurrence() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        repo.setCompleted(id, true)
        repo.setCompleted(id, false)
        assertThat(series(id).map { it.occurrenceDate.toString() to it.isCompleted }).containsExactly("2026-10-27" to false)
    }

    @Test fun reopening_keepsANextOccurrenceYouChanged() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        repo.setCompleted(id, true)
        val next = open(repo.get(id)!!.seriesId!!).single()
        clock.instant = clock.instant.plusSeconds(60)
        repo.update(next.id, TaskDraft(title = "Water plants (balcony)", dueDate = next.dueDate, repeat = next.repeat))
        repo.setCompleted(id, false)
        assertThat(series(id).map { it.title }).containsExactly("Water plants", "Water plants (balcony)").inOrder()
    }

    @Test fun editThis_changesOnlyThisOccurrence() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        val t = repo.get(id)!!
        repo.update(id, TaskDraft(title = "Water plants twice", dueDate = d("2026-10-28"), repeat = t.repeat), SeriesScope.THIS)
        repo.setCompleted(id, true)
        val next = open(t.seriesId!!).single()
        // Moved to the 28th for this time only; the series keeps its dates and old title, and the
        // next one is the first after the day it was actually due (no second task on the 28th).
        assertThat(next.dueDate).isEqualTo(d("2026-10-29"))
        assertThat(next.title).isEqualTo("Water plants")
        assertThat(repo.get(id)!!.title).isEqualTo("Water plants twice")
    }

    @Test fun postponingOntoTheNextDate_doesNotCreateATwin() = runTest {
        val id = repeating("FREQ=WEEKLY;BYDAY=TU", "2026-10-27")
        val t = repo.get(id)!!
        repo.update(id, TaskDraft(title = t.title, dueDate = d("2026-11-03"), repeat = t.repeat), SeriesScope.THIS)
        today("2026-11-03")
        repo.setCompleted(id, true)
        assertThat(open(t.seriesId!!).map { it.dueDate.toString() }).containsExactly("2026-11-10")
    }

    @Test fun undoingTheDeleteOfACompletedOccurrence_keepsTheCurrentOne() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        repo.setCompleted(id, true)
        val sid = repo.get(id)!!.seriesId!!
        val current = open(sid).single()
        repo.delete(id, SeriesScope.THIS) // remove yesterday's done one from the history…
        assertThat(open(sid).map { it.id }).containsExactly(current.id) // …nothing new is created
        repo.restore(id) // …and undo it
        assertThat(series(id).map { it.id to it.isCompleted }).containsExactly(id to true, current.id to false).inOrder()
    }

    @Test fun aTakenNextDate_isPassedOver_theSeriesNeverStalls() = runTest {
        // The reviewer's case: completed early, then moved back with "this and future", so the next
        // computed date belongs to the first, completed row.
        val first = repeating("FREQ=DAILY", "2026-10-30")
        repo.setCompleted(first, true) // done early on the 27th: next is the 31st
        val sid = repo.get(first)!!.seriesId!!
        val n31 = open(sid).single()
        repo.update(n31.id, TaskDraft(title = n31.title, dueDate = d("2026-10-28"), repeat = n31.repeat), SeriesScope.FUTURE)
        var cur = open(sid).single().id
        repeat(2) { repo.setCompleted(cur, true); cur = open(sid).single().id } // 28th, 29th
        // The 30th is the completed first row: the series goes on with the 31st.
        assertThat(open(sid).single().dueDate).isEqualTo(d("2026-10-31"))
        assertThat(all().filter { it.seriesId == sid }.map { it.occurrenceDate }.toSet()).hasSize(all().count { it.seriesId == sid })
    }

    @Test fun reopeningAnOlderOccurrence_andCompletingIt_doesNotAddASecondOpenOne() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        repo.setCompleted(id, true)
        val sid = repo.get(id)!!.seriesId!!
        val next = open(sid).single()
        clock.instant = clock.instant.plusSeconds(60)
        repo.update(next.id, TaskDraft(title = "Edited", dueDate = next.dueDate, repeat = next.repeat)) // touched: kept on reopen
        repo.setCompleted(id, false)
        repo.setCompleted(id, true)
        assertThat(open(sid).map { it.title }).containsExactly("Edited")
    }

    @Test fun editingACompletedOccurrence_neverChangesTheSeries() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        repo.setCompleted(id, true)
        val sid = repo.get(id)!!.seriesId!!
        repo.update(id, TaskDraft(title = "Old one", dueDate = d("2026-10-27"), repeat = RRule.parse("FREQ=WEEKLY")), SeriesScope.FUTURE)
        assertThat(repo.get(id)!!.title).isEqualTo("Old one")
        val current = open(sid).single()
        assertThat(current.title).isEqualTo("Water plants")
        assertThat(current.repeat!!.format()).isEqualTo("RRULE:FREQ=DAILY")
        // Moving a completed one to another list leaves the series (and its next ones) where they are.
        val other = repo.createList("Archive")
        repo.moveToList(id, other)
        repo.setCompleted(current.id, true)
        assertThat(open(sid).single().listId).isEqualTo(list)
    }

    @Test fun changingTheRule_onAClampedMonthEnd_keepsTheDayOfMonth() = runTest {
        today("2027-01-31")
        val id = repeating("FREQ=MONTHLY", "2027-01-31", title = "Rent")
        repo.setCompleted(id, true)
        val sid = repo.get(id)!!.seriesId!!
        val feb = open(sid).single()
        assertThat(feb.dueDate).isEqualTo(d("2027-02-28"))
        today("2027-02-28")
        // Every 2 months from this (clamped) occurrence: Apr 30, Jun 30, Aug 31, not the 28th.
        repo.update(feb.id, TaskDraft(title = "Rent", dueDate = feb.dueDate, repeat = RRule.parse("FREQ=MONTHLY;INTERVAL=2")), SeriesScope.FUTURE)
        val dates = mutableListOf<String>()
        var cur = feb.id
        repeat(3) { repo.setCompleted(cur, true); val n = open(sid).single(); dates += n.dueDate.toString(); today(n.dueDate.toString()); cur = n.id }
        assertThat(dates).containsExactly("2027-04-30", "2027-06-30", "2027-08-31").inOrder()
    }

    @Test fun changingOnlyTheEnd_keepsTheSeriesDates() = runTest {
        today("2027-01-31")
        val id = repeating("FREQ=MONTHLY", "2027-01-31", title = "Rent")
        repo.setCompleted(id, true)
        val sid = repo.get(id)!!.seriesId!!
        val feb = open(sid).single()
        today("2027-02-28")
        // "3 times in all" (the editor shows the count from the series start): Jan, Feb, Mar 31.
        repo.update(feb.id, TaskDraft(title = "Rent", dueDate = feb.dueDate, repeat = RRule.parse("FREQ=MONTHLY;COUNT=3")), SeriesScope.FUTURE)
        repo.setCompleted(feb.id, true)
        val mar = open(sid).single()
        assertThat(mar.dueDate).isEqualTo(d("2027-03-31"))
        today("2027-03-31")
        repo.setCompleted(mar.id, true)
        assertThat(open(sid)).isEmpty() // 3 done: the series is over
    }

    @Test fun makingACompletedTaskRepeat_createsItsNextOccurrence() = runTest {
        val id = repo.create(TaskDraft(title = "Backup", dueDate = d("2026-10-27")), list)
        repo.setCompleted(id, true)
        repo.update(id, TaskDraft(title = "Backup", dueDate = d("2026-10-27"), repeat = RRule.parse("FREQ=WEEKLY")))
        assertThat(open(repo.get(id)!!.seriesId!!).single().dueDate).isEqualTo(d("2026-11-03"))
    }

    @Test fun editFuture_changesTheTemplate_andRestartsOnANewRule() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        val t = repo.get(id)!!
        repo.update(id, TaskDraft(title = "Water plants", priority = Priority.LOW, dueDate = d("2026-10-27"), repeat = RRule.parse("FREQ=WEEKLY;BYDAY=TU")), SeriesScope.FUTURE)
        repo.setCompleted(id, true)
        val next = open(t.seriesId!!).single()
        assertThat(next.dueDate).isEqualTo(d("2026-11-03"))
        assertThat(next.priority).isEqualTo(Priority.LOW)
        assertThat(next.repeat!!.format()).isEqualTo("RRULE:FREQ=WEEKLY;BYDAY=TU")
    }

    @Test fun editFuture_keepsTheRemainingCount() = runTest {
        var id = repeating("FREQ=DAILY;COUNT=5", "2026-10-27")
        repo.setCompleted(id, true) // 1 of 5 done
        id = open(repo.get(id)!!.seriesId!!).single().id // 28 Oct, occurrence 2 of 5
        val t = repo.get(id)!!
        // Move this and future to the 30th: 4 occurrences are left (30, 31, 1, 2), not 5 more.
        repo.update(id, TaskDraft(title = t.title, dueDate = d("2026-10-30"), repeat = t.repeat), SeriesScope.FUTURE)
        assertThat(repo.get(id)!!.repeat!!.count).isEqualTo(4)
        var last = id
        today("2026-10-30")
        repeat(4) { repo.setCompleted(last, true); last = open(t.seriesId!!).singleOrNull()?.id ?: last }
        assertThat(series(id).map { it.dueDate.toString() }).containsExactly("2026-10-27", "2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02").inOrder()
    }

    @Test fun editAll_alsoRenamesCompletedOccurrences() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        repo.setCompleted(id, true)
        val next = open(repo.get(id)!!.seriesId!!).single()
        repo.update(next.id, TaskDraft(title = "Feed the cat", dueDate = next.dueDate, repeat = next.repeat), SeriesScope.ALL)
        assertThat(series(id).map { it.title }).containsExactly("Feed the cat", "Feed the cat")
        assertThat(series(id).map { it.isCompleted }).containsExactly(true, false).inOrder() // history stays done
    }

    @Test fun turningRepeatOff_endsTheSeries_keepsHistory() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        repo.setCompleted(id, true)
        val next = open(repo.get(id)!!.seriesId!!).single()
        repo.update(next.id, TaskDraft(title = next.title, dueDate = next.dueDate, repeat = null), SeriesScope.FUTURE)
        assertThat(repo.get(next.id)!!.isRecurring).isFalse()
        repo.setCompleted(next.id, true)
        assertThat(all().count { it.seriesId == next.seriesId }).isEqualTo(2) // nothing new created
    }

    @Test fun deleteThis_skipsToTheNext_andUndoRestores() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        val sid = repo.get(id)!!.seriesId!!
        repo.delete(id, SeriesScope.THIS)
        assertThat(open(sid).map { it.dueDate.toString() }).containsExactly("2026-10-28")
        repo.restore(id)
        assertThat(open(sid).map { it.dueDate.toString() }).containsExactly("2026-10-27")
    }

    @Test fun deleteFuture_endsTheSeries_keepsCompleted_andUndoResumes() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        repo.setCompleted(id, true)
        val sid = repo.get(id)!!.seriesId!!
        val current = open(sid).single()
        repo.delete(current.id, SeriesScope.FUTURE)
        assertThat(all().filter { it.seriesId == sid }.map { it.isCompleted }).containsExactly(true)
        assertThat(repo.get(id)!!.isRecurring).isFalse() // ended
        repo.restore(current.id)
        assertThat(open(sid).single().isRecurring).isTrue()
    }

    @Test fun deleteAll_removesEveryOccurrence_andUndoBringsThemBack() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        repo.setCompleted(id, true)
        val sid = repo.get(id)!!.seriesId!!
        val current = open(sid).single()
        repo.delete(current.id, SeriesScope.ALL)
        assertThat(all().filter { it.seriesId == sid }).isEmpty()
        repo.restore(current.id)
        assertThat(all().filter { it.seriesId == sid }.map { it.isCompleted }).containsExactly(true, false)
        // After the undo window, a deleted series is purged entirely.
        repo.delete(current.id, SeriesScope.ALL)
        clock.instant = clock.instant.plusSeconds(60)
        repo.purgeExpiredTombstones()
        assertThat(db.tasks().inSeries(sid)).isEmpty()
        assertThat(db.taskSeries().get(sid)).isNull()
    }

    @Test fun oneOffTask_canStartRepeating() = runTest {
        val id = repo.create(TaskDraft(title = "Stretch", dueDate = d("2026-10-27")), list)
        repo.update(id, TaskDraft(title = "Stretch", dueDate = d("2026-10-27"), repeat = RRule.parse("FREQ=DAILY")))
        repo.setCompleted(id, true)
        assertThat(open(repo.get(id)!!.seriesId!!).single().dueDate).isEqualTo(d("2026-10-28"))
    }

    @Test fun repeatNeedsADueDate_andAnOnDeviceList() = runTest {
        val noDate = repo.create(TaskDraft(title = "Someday", repeat = RRule.parse("FREQ=DAILY")), list)
        assertThat(repo.get(noDate)!!.isRecurring).isFalse()
        db.taskLists().upsert(TaskListEntity(id = "g", kind = SourceKind.GOOGLE, remoteId = "r", accountEmail = "a@example.com", title = "Google"))
        val google = repo.create(TaskDraft(title = "Synced", listId = "g", dueDate = d("2026-10-27"), repeat = RRule.parse("FREQ=DAILY")), list)
        assertThat(repo.get(google)!!.isRecurring).isFalse() // Google Tasks can't store the rule
        // A repeating task can't be moved into a Google list either.
        val local = repeating("FREQ=DAILY", "2026-10-27")
        repo.moveToList(local, "g")
        assertThat(repo.get(local)!!.listId).isEqualTo(list)
    }

    @Test fun subtasks_carryForward_unticked_once() = runTest {
        val id = repeating("FREQ=WEEKLY;BYDAY=TU", "2026-10-27", title = "Weekly review")
        val a = repo.create(TaskDraft(title = "Inbox zero", parentId = id), list)
        repo.create(TaskDraft(title = "Plan next week", parentId = id), list)
        repo.setCompleted(a, true)
        repo.setCompleted(id, true)
        repo.setCompleted(id, true) // again: nothing is copied twice
        val next = open(repo.get(id)!!.seriesId!!).single()
        val subs = repo.observeSubtasks(next.id).first()
        assertThat(subs.map { it.title to it.isCompleted }).containsExactly("Inbox zero" to false, "Plan next week" to false).inOrder()
        assertThat(subs.map { it.id }).containsExactly("${next.id}/0", "${next.id}/1").inOrder()
        assertThat(repo.observeSubtasks(id).first().map { it.isCompleted }).containsExactly(true, false).inOrder() // history unchanged
        // Reopening removes the untouched next occurrence with its copied subtasks.
        repo.setCompleted(id, false)
        assertThat(db.tasks().subtasks(next.id)).isEmpty()
        assertThat(repo.get(next.id)).isNull()
    }

    @Test fun reopening_keepsTheNextOccurrence_whenOneOfItsSubtasksWasTicked() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        repo.create(TaskDraft(title = "Step", parentId = id), list)
        repo.setCompleted(id, true)
        val next = open(repo.get(id)!!.seriesId!!).single()
        clock.instant = clock.instant.plusSeconds(60)
        repo.setCompleted("${next.id}/0", true)
        repo.setCompleted(id, false)
        assertThat(repo.get(next.id)).isNotNull()
        assertThat(repo.get("${next.id}/0")!!.isCompleted).isTrue()
    }

    @Test fun deleteAll_takesSubtasksOfEveryOccurrence_andUndoRestoresThem() = runTest {
        val id = repeating("FREQ=DAILY", "2026-10-27")
        repo.create(TaskDraft(title = "Step", parentId = id), list)
        repo.setCompleted(id, true)
        val next = open(repo.get(id)!!.seriesId!!).single()
        repo.delete(next.id, SeriesScope.ALL)
        assertThat(all()).isEmpty() // no orphaned subtasks left visible
        repo.restore(next.id)
        assertThat(repo.observeSubtasks(id).first().map { it.title }).containsExactly("Step")
        assertThat(repo.observeSubtasks(next.id).first().map { it.title }).containsExactly("Step")
    }

    @Test fun countdown_isStoredPerTask_andNeedsADueDate() = runTest {
        val a = repo.create(TaskDraft(title = "Exam", dueDate = d("2027-06-01"), countdown = true), list)
        val b = repo.create(TaskDraft(title = "Later", countdown = true), list)
        assertThat(repo.get(a)!!.countdownEnabled).isTrue()
        assertThat(repo.get(b)!!.countdownEnabled).isFalse()
        // Occurrences inherit it from the series.
        val r = repo.create(TaskDraft(title = "Report", dueDate = d("2026-10-27"), repeat = RRule.parse("FREQ=WEEKLY;BYDAY=TU"), countdown = true), list)
        repo.setCompleted(r, true)
        assertThat(open(repo.get(r)!!.seriesId!!).single().countdownEnabled).isTrue()
    }
}
