package app.daysemble.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.OwnerType
import app.daysemble.data.prefs.SettingsRepository
import app.daysemble.data.sync.ChangeListener
import app.daysemble.data.sync.Outbox
import app.daysemble.domain.model.EventDraft
import app.daysemble.domain.model.TaskDraft
import app.daysemble.reminders.AlarmGateway
import app.daysemble.reminders.ReminderScheduler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManagementTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Europe/Berlin")
    private val clock = FixedAppClock(Instant.parse("2026-10-22T06:00:00Z"), zone)
    private lateinit var db: DaysembleDatabase
    private lateinit var tasks: TaskRepository
    private lateinit var events: EventRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DaysembleDatabase::class.java).allowMainThreadQueries().build()
        val outbox = Outbox(db.sync()) { clock.now().toEpochMilli() }
        tasks = TaskRepository(db, outbox, clock, ChangeListener.None)
        events = EventRepository(db, outbox, clock, ChangeListener.None)
    }
    @After fun tearDown() { db.close(); scope.cancel() }

    @Test fun `lists can be created, renamed, moved between and deleted, but not the last one`() = runTest {
        val a = tasks.ensureDefaultList("My tasks")
        assertThat(tasks.deleteList(a)).isFalse()
        val b = tasks.createList("  Work ")
        tasks.renameList(b, "Office")
        assertThat(db.taskLists().get(b)!!.title).isEqualTo("Office")
        val t = tasks.create(TaskDraft(title = "Report"), a)
        val sub = tasks.create(TaskDraft(title = "Charts", parentId = t), a)
        tasks.moveToList(t, b)
        assertThat(db.tasks().get(t)!!.listId).isEqualTo(b)
        assertThat(db.tasks().get(sub)!!.listId).isEqualTo(b) // subtasks follow
        assertThat(db.sync().outbox()).isEmpty() // local to local: nothing to sync
        assertThat(tasks.deleteList(b)).isTrue()
        assertThat(db.tasks().get(t)).isNull()
    }

    @Test fun `hidden calendars disappear from views and stop reminders`() = runTest {
        val cal = events.ensureDefaultCalendar("Personal")
        val alarms = object : AlarmGateway {
            val set = mutableSetOf<Int>()
            override fun canScheduleExact() = true
            override fun set(requestCode: Int, triggerAt: Instant, exact: Boolean) = set.add(requestCode)
            override fun cancel(requestCode: Int) { set.remove(requestCode) }
        }
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") })
        val scheduler = ReminderScheduler(db, settings, alarms, clock) { true }
        val id = events.create(EventDraft(title = "Gym", startDate = LocalDate.parse("2026-10-22"), startTime = LocalTime.of(18, 0),
            endTime = LocalTime.of(19, 0), zone = zone, reminderMinutesBefore = listOf(15)), cal)
        scheduler.rescheduleFor(OwnerType.EVENT, id)
        assertThat(alarms.set).hasSize(1)
        events.setCalendarVisible(cal, false)
        scheduler.rescheduleAll()
        assertThat(alarms.set).isEmpty()
        assertThat(events.occurrences(LocalDate.parse("2026-10-22"), LocalDate.parse("2026-10-22"), zone).first()).isEmpty()
        events.setCalendarVisible(cal, true)
        scheduler.rescheduleAll()
        assertThat(alarms.set).hasSize(1)
    }

    @Test fun `calendars can be created, recoloured and deleted, but not the last on-device one`() = runTest {
        val a = events.ensureDefaultCalendar("Personal")
        val b = events.createCalendar("Family", 0xFF5B3FE0.toInt())
        events.setCalendarColor(b, 0xFF0F7A72.toInt())
        assertThat(db.calendars().get(b)!!.colorArgb).isEqualTo(0xFF0F7A72.toInt())
        assertThat(events.deleteCalendar(b)).isTrue()
        assertThat(events.deleteCalendar(a)).isFalse()
    }

    @Test fun `reorder assigns the given manual order`() = runTest {
        val l = tasks.ensureDefaultList("My tasks")
        val a = tasks.create(TaskDraft(title = "A"), l)
        val b = tasks.create(TaskDraft(title = "B"), l)
        val c = tasks.create(TaskDraft(title = "C"), l)
        tasks.reorder(listOf(c, a, b))
        val sorted = tasks.allTasks.first().sortedWith(app.daysemble.domain.TaskRules.comparator(app.daysemble.data.prefs.TaskSort.MANUAL, zone))
        assertThat(sorted.map { it.title }).containsExactly("C", "A", "B").inOrder()
    }
}
