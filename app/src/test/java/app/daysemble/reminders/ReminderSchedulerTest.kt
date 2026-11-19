package app.daysemble.reminders

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.DeliveredReminderEntity
import app.daysemble.data.db.OwnerType
import app.daysemble.data.prefs.SettingsRepository
import app.daysemble.core.recurrence.RRule
import app.daysemble.data.repo.EventRepository
import app.daysemble.data.repo.TaskRepository
import app.daysemble.domain.model.EventDraft
import app.daysemble.data.sync.ChangeListener
import app.daysemble.domain.model.TaskDraft
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.time.LocalTime
import java.time.ZoneId

private class FakeAlarms(var exact: Boolean = true, var refuseExact: Boolean = false) : AlarmGateway {
    val active = mutableMapOf<Int, Pair<Instant, Boolean>>()
    override fun canScheduleExact() = exact
    override fun set(requestCode: Int, triggerAt: Instant, exact: Boolean): Boolean {
        if (exact && refuseExact) return false
        active[requestCode] = triggerAt to exact; return true
    }
    override fun cancel(requestCode: Int) { active.remove(requestCode) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderSchedulerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Europe/Berlin")
    private val clock = FixedAppClock(Instant.parse("2026-10-22T06:00:00Z"), zone) // 08:00 local
    private lateinit var db: DaysembleDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var tasks: TaskRepository
    private lateinit var scheduler: ReminderScheduler
    private val alarms = FakeAlarms()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notificationsOn = true
    private lateinit var list: String
    private val today = LocalDate.parse("2026-10-22")

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DaysembleDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") })
        tasks = TaskRepository(db, clock, ChangeListener.None)
        scheduler = ReminderScheduler(db, settings, alarms, clock) { notificationsOn }
        list = tasks.ensureDefaultList("My tasks")
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private suspend fun task(time: LocalTime? = LocalTime.of(14, 0), reminders: List<Int> = listOf(30)) =
        tasks.create(TaskDraft(title = "Call", dueDate = today, dueTime = time, reminderMinutesBefore = reminders), list)

    @Test fun `schedules exact alarms and records them`() = runTest {
        val id = task()
        scheduler.rescheduleFor(OwnerType.TASK, id)
        val rows = db.alarms().forOwner(id)
        assertThat(rows).hasSize(1)
        assertThat(alarms.active.values.single()).isEqualTo(Instant.parse("2026-10-22T11:30:00Z") to true)
    }

    @Test fun `editing reschedules and never leaves the old alarm`() = runTest {
        val id = task()
        scheduler.rescheduleFor(OwnerType.TASK, id)
        tasks.update(id, TaskDraft(title = "Call", dueDate = today, dueTime = LocalTime.of(16, 0), reminderMinutesBefore = listOf(30)))
        scheduler.rescheduleFor(OwnerType.TASK, id)
        assertThat(alarms.active.values.map { it.first }).containsExactly(Instant.parse("2026-10-22T13:30:00Z"))
        assertThat(db.alarms().forOwner(id)).hasSize(1)
    }

    @Test fun `completing or deleting cancels`() = runTest {
        val a = task(); val b = task()
        scheduler.rescheduleAll()
        assertThat(alarms.active).hasSize(2)
        tasks.setCompleted(a, true); scheduler.rescheduleFor(OwnerType.TASK, a)
        tasks.delete(b); scheduler.rescheduleFor(OwnerType.TASK, b)
        assertThat(alarms.active).isEmpty()
        assertThat(db.alarms().all()).isEmpty()
    }

    @Test fun `delivered reminders are not scheduled again`() = runTest {
        val id = task()
        scheduler.rescheduleFor(OwnerType.TASK, id)
        val row = db.alarms().forOwner(id).single()
        db.alarms().markDelivered(DeliveredReminderEntity(ReminderScheduler.deliveredKey(row.reminderId, row.occurrenceKey, row.triggerAt), clock.now().toEpochMilli()))
        scheduler.rescheduleAll()
        assertThat(alarms.active).isEmpty()
    }

    @Test fun `falls back to inexact when exact is refused`() = runTest {
        alarms.refuseExact = true
        val id = task()
        scheduler.rescheduleFor(OwnerType.TASK, id)
        assertThat(alarms.active.values.single().second).isFalse()
        assertThat(db.alarms().forOwner(id)).hasSize(1)
    }

    @Test fun `disabling reminders cancels everything`() = runTest {
        task(); scheduler.rescheduleAll()
        settings.setRemindersEnabled(false)
        scheduler.rescheduleAll()
        assertThat(alarms.active).isEmpty()
        assertThat(scheduler.health(false)).isEqualTo(ReminderHealth.DISABLED_IN_APP)
    }

    @Test fun `health reports blocked notifications and inexact alarms`() {
        assertThat(scheduler.health(true)).isEqualTo(ReminderHealth.OK)
        alarms.exact = false
        assertThat(scheduler.health(true)).isEqualTo(ReminderHealth.INEXACT)
        notificationsOn = false
        assertThat(scheduler.health(true)).isEqualTo(ReminderHealth.NOTIFICATIONS_BLOCKED)
    }

    @Test fun `time zone change re-plans wall-clock reminders`() = runTest {
        val id = task()
        scheduler.rescheduleFor(OwnerType.TASK, id)
        clock.zoneId = ZoneId.of("America/New_York")
        scheduler.rescheduleAll()
        // Due 14:00 local wall time now means New York.
        assertThat(alarms.active.values.single().first).isEqualTo(Instant.parse("2026-10-22T17:30:00Z"))
        assertThat(db.alarms().forOwner(id)).hasSize(1)
    }

    @Test fun `snooze schedules a separate one-off alarm`() = runTest {
        val id = task()
        scheduler.snooze(OwnerType.TASK, id, "r", "2026-10-22", 10)
        assertThat(alarms.active.values.single().first).isEqualTo(clock.now().plusSeconds(600))
    }

    /** A daily 10:00 series (08:00 now), reminding 30 minutes before. */
    private suspend fun dailySeries(): Pair<EventRepository, String> {
        val events = EventRepository(db, clock, ChangeListener.None)
        val id = events.create(
            EventDraft(
                title = "Sync", startDate = today, startTime = LocalTime.of(10, 0), endDate = today, endTime = LocalTime.of(10, 30), zone = zone,
                rrule = RRule.parse("FREQ=DAILY"), reminderMinutesBefore = listOf(30),
            ),
            events.ensureDefaultCalendar("Personal"),
        )
        return events to id
    }

    @Test fun `a snooze of a deleted occurrence is not delivered, and the next occurrence still is`() = runTest {
        val (events, id) = dailySeries()
        scheduler.rescheduleFor(OwnerType.EVENT, id)
        val key = "2026-10-22T10:00"
        scheduler.snooze(OwnerType.EVENT, id, db.reminders().forOwner(OwnerType.EVENT, id).single().id, key, 10)
        val snooze = db.alarms().forOwner(id).single { '#' in it.occurrenceKey }
        assertThat(scheduler.isStillPlanned(snooze)).isTrue()

        events.deleteOccurrence(id, key)
        assertThat(scheduler.isStillPlanned(snooze)).isFalse() // checked at delivery, before any re-plan

        scheduler.rescheduleFor(OwnerType.EVENT, id)
        val keys = db.alarms().forOwner(id).map { it.occurrenceKey }
        assertThat(keys.none { '#' in it || it == key }).isTrue() // snooze and the deleted one's own alarm are gone
        assertThat(keys).contains("2026-10-23T10:00")
    }

    @Test fun `a snooze of an occurrence that was only moved is still delivered`() = runTest {
        val (events, id) = dailySeries()
        val key = "2026-10-22T10:00"
        scheduler.rescheduleFor(OwnerType.EVENT, id)
        scheduler.snooze(OwnerType.EVENT, id, db.reminders().forOwner(OwnerType.EVENT, id).single().id, key, 10)
        val snooze = db.alarms().forOwner(id).single { '#' in it.occurrenceKey }

        val moved = events.draftForOccurrence(id, key, zone)!!
        events.updateOccurrence(id, key, moved.copy(startDate = today.plusDays(2), endDate = today.plusDays(2)))
        assertThat(scheduler.isStillPlanned(snooze)).isTrue()
        scheduler.rescheduleFor(OwnerType.EVENT, id)
        assertThat(db.alarms().forOwner(id).any { '#' in it.occurrenceKey }).isTrue()
    }
}
