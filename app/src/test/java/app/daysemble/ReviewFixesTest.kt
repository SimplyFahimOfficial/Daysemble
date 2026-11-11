package app.daysemble

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.recurrence.RecurrenceExpander
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.repo.EventRepository
import app.daysemble.data.repo.TaskRepository
import app.daysemble.data.sync.ChangeListener
import app.daysemble.data.db.EventEntity
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.ReminderEntity
import app.daysemble.data.prefs.SettingsRepository
import app.daysemble.domain.model.EventDraft
import app.daysemble.domain.model.TaskDraft
import app.daysemble.reminders.AlarmGateway
import app.daysemble.reminders.ReminderPlanner
import app.daysemble.reminders.ReminderScheduler
import app.daysemble.ui.calendar.wallMinutes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Regression tests for the Greptile review findings on PR #1. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewFixesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val clock = FixedAppClock(Instant.parse("2026-10-22T08:00:00Z"), zone)
    private lateinit var db: DaysembleDatabase
    private lateinit var events: EventRepository
    private lateinit var tasks: TaskRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DaysembleDatabase::class.java).allowMainThreadQueries().build()
        events = EventRepository(db, clock, ChangeListener.None)
        tasks = TaskRepository(db, clock, ChangeListener.None)
    }

    @After fun tearDown() { db.close() }

    private class Alarms : AlarmGateway {
        val set = mutableMapOf<Int, Instant>()
        override fun canScheduleExact() = true
        override fun set(requestCode: Int, triggerAt: Instant, exact: Boolean): Boolean { set[requestCode] = triggerAt; return true }
        override fun cancel(requestCode: Int) { set.remove(requestCode) }
    }
    private fun scheduler(alarms: Alarms) = ReminderScheduler(
        db, SettingsRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { File(tmp.root, "s${System.nanoTime()}.preferences_pb") }),
        alarms, clock,
    ) { true }

    @Test fun `a due alarm row survives rescheduleAll so the receiver can deliver it`() = runTest {
        val list = tasks.ensureDefaultList("My tasks")
        val due = LocalDateTime.ofInstant(clock.now(), zone).plusMinutes(40)
        val id = tasks.create(TaskDraft(title = "Call", dueDate = due.toLocalDate(), dueTime = due.toLocalTime(), reminderMinutesBefore = listOf(30)), list)
        val alarms = Alarms(); val s = scheduler(alarms)
        s.rescheduleFor(OwnerType.TASK, id)
        clock.instant = clock.instant.plusSeconds(11 * 60) // trigger (10 min from start) has passed
        s.rescheduleAll()
        assertThat(db.alarms().forOwner(id)).hasSize(1)
    }

    @Test fun `a due alarm invalidated by an edit is cancelled and not delivered`() = runTest {
        val list = tasks.ensureDefaultList("My tasks")
        val due = LocalDateTime.ofInstant(clock.now(), zone).plusMinutes(40)
        val id = tasks.create(TaskDraft(title = "Call", dueDate = due.toLocalDate(), dueTime = due.toLocalTime(), reminderMinutesBefore = listOf(30)), list)
        val alarms = Alarms(); val s = scheduler(alarms)
        s.rescheduleFor(OwnerType.TASK, id)
        clock.instant = clock.instant.plusSeconds(11 * 60) // reminder is due but (inexact) not yet delivered
        val row = db.alarms().forOwner(id).single()
        assertThat(s.isStillPlanned(row)).isTrue()
        // The user removes the reminder: the due alarm must go too.
        tasks.update(id, TaskDraft(title = "Call", dueDate = due.toLocalDate(), dueTime = due.toLocalTime(), reminderMinutesBefore = emptyList()))
        assertThat(s.isStillPlanned(row)).isFalse()
        s.rescheduleFor(OwnerType.TASK, id)
        assertThat(db.alarms().forOwner(id)).isEmpty()
        assertThat(alarms.set).isEmpty()
    }

    @Test fun `a due alarm survives an unrelated edit that rewrites reminders`() = runTest {
        val list = tasks.ensureDefaultList("My tasks")
        val due = LocalDateTime.ofInstant(clock.now(), zone).plusMinutes(40)
        val id = tasks.create(TaskDraft(title = "Call", dueDate = due.toLocalDate(), dueTime = due.toLocalTime(), reminderMinutesBefore = listOf(30)), list)
        val alarms = Alarms(); val s = scheduler(alarms)
        s.rescheduleFor(OwnerType.TASK, id)
        val before = db.reminders().forOwner(OwnerType.TASK, id).single().id
        clock.instant = clock.instant.plusSeconds(11 * 60) // due, not yet delivered
        // Unrelated edit: title changes, same reminder offset and time -> same reminder id.
        tasks.update(id, TaskDraft(title = "Call the bank", dueDate = due.toLocalDate(), dueTime = due.toLocalTime(), reminderMinutesBefore = listOf(30)))
        assertThat(db.reminders().forOwner(OwnerType.TASK, id).single().id).isEqualTo(before)
        s.rescheduleFor(OwnerType.TASK, id)
        val row = db.alarms().forOwner(id).single()
        assertThat(s.isStillPlanned(row)).isTrue()
        // Even if a rewrite did replace ids (legacy rows), validity is judged by time, not id.
        db.reminders().replace(OwnerType.TASK, id, listOf(ReminderEntity("legacy-random-id", OwnerType.TASK, id, 30)))
        assertThat(s.isStillPlanned(row)).isTrue()
        s.rescheduleAll()
        assertThat(db.alarms().forOwner(id)).hasSize(1)
    }

    @Test fun `after a reminder fired, moving the task later the same day schedules the new reminder`() = runTest {
        val list = tasks.ensureDefaultList("My tasks")
        val ten = LocalDate.parse("2026-10-22").atTime(12, 0) // clock is 08:00Z = 10:00 Berlin
        val id = tasks.create(TaskDraft(title = "Call", dueDate = ten.toLocalDate(), dueTime = ten.toLocalTime(), reminderMinutesBefore = listOf(30)), list)
        val alarms = Alarms(); val s = scheduler(alarms)
        s.rescheduleFor(OwnerType.TASK, id)
        val fired = db.alarms().forOwner(id).single()
        // The 11:30 reminder is delivered.
        db.alarms().markDelivered(app.daysemble.data.db.DeliveredReminderEntity(
            ReminderScheduler.deliveredKey(fired.reminderId, fired.occurrenceKey, fired.triggerAt), clock.now().toEpochMilli()))
        db.alarms().delete(fired.requestCode)
        // Unchanged re-plan: no duplicate.
        s.rescheduleFor(OwnerType.TASK, id)
        assertThat(db.alarms().forOwner(id)).isEmpty()
        // Same day, same offset, later time: same reminder id and occurrence key, but a new trigger.
        tasks.update(id, TaskDraft(title = "Call", dueDate = ten.toLocalDate(), dueTime = LocalTime.of(15, 0), reminderMinutesBefore = listOf(30)))
        assertThat(db.reminders().forOwner(OwnerType.TASK, id).single().id).isEqualTo(fired.reminderId)
        s.rescheduleFor(OwnerType.TASK, id)
        val next = db.alarms().forOwner(id).single()
        assertThat(next.occurrenceKey).isEqualTo(fired.occurrenceKey) // same key; previously suppressed
        assertThat(Instant.ofEpochMilli(next.triggerAt)).isEqualTo(LocalDate.parse("2026-10-22").atTime(14, 30).atZone(zone).toInstant())
    }

    @Test fun `a due alarm whose task moved later is replaced, not kept`() = runTest {
        val list = tasks.ensureDefaultList("My tasks")
        val due = LocalDateTime.ofInstant(clock.now(), zone).plusMinutes(40)
        val id = tasks.create(TaskDraft(title = "Call", dueDate = due.toLocalDate(), dueTime = due.toLocalTime(), reminderMinutesBefore = listOf(30)), list)
        val alarms = Alarms(); val s = scheduler(alarms)
        s.rescheduleFor(OwnerType.TASK, id)
        clock.instant = clock.instant.plusSeconds(11 * 60)
        val later = due.plusHours(3)
        tasks.update(id, TaskDraft(title = "Call", dueDate = later.toLocalDate(), dueTime = later.toLocalTime(), reminderMinutesBefore = listOf(30)))
        s.rescheduleFor(OwnerType.TASK, id)
        val rows = db.alarms().forOwner(id)
        assertThat(rows).hasSize(1)
        assertThat(Instant.ofEpochMilli(rows.single().triggerAt)).isEqualTo(later.minusMinutes(30).atZone(zone).toInstant())
    }

    @Test fun `snooze survives refresh while pending, and is cancelled when the task is completed`() = runTest {
        val list = tasks.ensureDefaultList("My tasks")
        val id = tasks.create(TaskDraft(title = "Call", dueDate = LocalDate.parse("2026-10-22")), list)
        val alarms = Alarms(); val s = scheduler(alarms)
        s.snooze(OwnerType.TASK, id, "r", "2026-10-22", 10)
        s.rescheduleAll()
        assertThat(db.alarms().forOwner(id).single().occurrenceKey).contains("#snooze")
        tasks.setCompleted(id, true)
        s.rescheduleFor(OwnerType.TASK, id)
        assertThat(db.alarms().forOwner(id)).isEmpty()
        assertThat(alarms.set).isEmpty()
    }

    @Test fun `stored instances of a read-only series remind even though the RRULE parses`() {
        val now = Instant.parse("2026-10-22T06:00:00Z")
        val master = EventEntity(id = "m", calendarId = "c", title = "T", isAllDay = false,
            startUtc = Instant.parse("2026-10-01T07:00:00Z").toEpochMilli(), endUtc = Instant.parse("2026-10-01T08:00:00Z").toEpochMilli(),
            timeZone = zone.id, rrule = "RRULE:FREQ=MONTHLY;COUNT=3", isReadOnlyRecurrence = true, // had an RDATE
            rangeStartUtc = 0, rangeEndUtc = Long.MAX_VALUE, createdAt = 0, updatedAt = 0)
        val extra = master.copy(id = "rd", rrule = null, isReadOnlyRecurrence = false, recurringParentId = "m", originalStartKey = "2026-10-24T09:00",
            startUtc = Instant.parse("2026-10-24T07:00:00Z").toEpochMilli(), endUtc = Instant.parse("2026-10-24T08:00:00Z").toEpochMilli())
        val plan = ReminderPlanner.forEvent(master, listOf(ReminderEntity("r", OwnerType.EVENT, "m", 10)), listOf(extra), now, zone, 540)
        assertThat(plan.map { it.triggerAt }).containsExactly(Instant.parse("2026-10-24T06:50:00Z"))
    }

    @Test fun `an occurrence moved beyond a bounded series still shows`() = runTest {
        val cal = events.ensureDefaultCalendar("Personal")
        val id = events.create(EventDraft(title = "Course", startDate = LocalDate.parse("2026-10-01"), startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(10, 0), zone = zone, rrule = RRule.parse("FREQ=WEEKLY;COUNT=3")), cal)
        val master = db.events().get(id)!!
        val moved = master.copy(id = "ov", rrule = null, recurringParentId = id, originalStartKey = "2026-10-15T09:00",
            startUtc = Instant.parse("2026-11-20T08:00:00Z").toEpochMilli(), endUtc = Instant.parse("2026-11-20T09:00:00Z").toEpochMilli())
        db.events().upsert(app.daysemble.data.repo.EventRepository.withEnvelope(moved))
        val occ = events.occurrences(LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-20"), zone).first()
        assertThat(occ.map { it.occurrenceKey }).containsExactly("2026-10-15T09:00")
    }

    @Test fun `planner reminds for an occurrence moved into the horizon from far away`() {
        val now = Instant.parse("2026-10-22T06:00:00Z")
        val master = EventEntity(id = "m", calendarId = "c", title = "T", isAllDay = false,
            startUtc = Instant.parse("2026-09-01T07:00:00Z").toEpochMilli(), endUtc = Instant.parse("2026-09-01T08:00:00Z").toEpochMilli(),
            timeZone = zone.id, rrule = "RRULE:FREQ=MONTHLY;COUNT=2", rangeStartUtc = 0, rangeEndUtc = Long.MAX_VALUE,
            createdAt = 0, updatedAt = 0)
        val ov = master.copy(id = "o", rrule = null, recurringParentId = "m", originalStartKey = "2026-09-01T09:00",
            startUtc = Instant.parse("2026-10-23T07:00:00Z").toEpochMilli(), endUtc = Instant.parse("2026-10-23T08:00:00Z").toEpochMilli())
        val plan = ReminderPlanner.forEvent(master, listOf(ReminderEntity("r", OwnerType.EVENT, "m", 10)), listOf(ov), now, zone, 540)
        assertThat(plan.map { it.triggerAt }).containsExactly(Instant.parse("2026-10-23T06:50:00Z"))
    }

    @Test fun `time grid uses wall-clock minutes on DST days`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val springDay = LocalDate.parse("2026-03-29")
        val nine = springDay.atTime(9, 0).atZone(berlin).toInstant()
        assertThat(wallMinutes(nine, springDay, berlin)).isEqualTo(9 * 60)
        val autumn = LocalDate.parse("2026-10-25")
        assertThat(wallMinutes(autumn.atTime(9, 0).atZone(berlin).toInstant(), autumn, berlin)).isEqualTo(9 * 60)
        assertThat(wallMinutes(autumn.plusDays(1).atStartOfDay(berlin).toInstant(), autumn, berlin)).isEqualTo(24 * 60)
        // An event crossing midnight that ends 30 s into the displayed day occupies minute 0, not the whole day.
        val next = autumn.plusDays(1)
        assertThat(wallMinutes(next.atTime(0, 0, 30).atZone(berlin).toInstant(), next, berlin)).isEqualTo(0)
    }

    @Test fun `recurrence with only empty periods terminates`() {
        val r = RRule.parse("FREQ=MONTHLY;INTERVAL=12;BYMONTHDAY=31")!!
        val got = RecurrenceExpander.dates(r, LocalDate.parse("2026-02-01"), LocalDate.parse("2026-01-01"), LocalDate.parse("2099-12-31"))
        assertThat(got).isEmpty()
    }
}
