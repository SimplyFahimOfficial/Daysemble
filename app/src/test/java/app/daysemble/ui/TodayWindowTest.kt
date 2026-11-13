package app.daysemble.ui

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.DaysembleApp
import app.daysemble.core.time.FixedAppClock
import app.daysemble.core.time.TimeContext
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.Migrations
import app.daysemble.data.repo.EventRepository
import app.daysemble.data.repo.SleepRepository
import app.daysemble.data.repo.TaskRepository
import app.daysemble.data.repo.TaskSeriesReader
import app.daysemble.data.sync.ChangeListener
import app.daysemble.domain.model.EventDraft
import app.daysemble.domain.model.TaskDraft
import app.daysemble.ui.today.TodayUiState
import app.daysemble.ui.today.TodayViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Choosing another day in the month Today has loaded needs no new database query: the day is built
 * from the data in memory. (Each selection used to restart the event, task, series, settings and
 * sleep queries, and the new day only reached the screen, and the day swipe, after all of them.)
 * Another month loads a window of its own, and the previous day stays until it has arrived.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayWindowTest {
    private val app get() = ApplicationProvider.getApplicationContext<DaysembleApp>()
    private val zone = ZoneId.of("UTC")
    private val clock = FixedAppClock(Instant.parse("2026-10-15T08:00:00Z"), zone)
    private val day = LocalDate.of(2026, 10, 15)
    private val dbName = "today-window-test.db"
    private val queries = AtomicInteger()
    private lateinit var db: DaysembleDatabase
    private lateinit var vm: TodayViewModel

    @Before fun setUp() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app.deleteDatabase(dbName)
        db = Room.databaseBuilder(app, DaysembleDatabase::class.java, dbName)
            .addMigrations(*Migrations.ALL).allowMainThreadQueries()
            .setQueryCallback({ sql, _ -> if (sql.trimStart().startsWith("SELECT")) queries.incrementAndGet() }, { it.run() })
            .build()
        val listener = object : ChangeListener { override fun onTaskChanged(taskId: String) = Unit }
        val tasks = TaskRepository(db, clock, listener)
        val events = EventRepository(db, clock, listener)
        val cal = events.ensureDefaultCalendar("Calendar")
        val list = tasks.ensureDefaultList("Tasks")
        for (d in listOf(day, day.plusDays(1), day.plusDays(9))) {
            events.create(EventDraft(title = "Event $d", startDate = d, startTime = LocalTime.of(10, 0), endTime = LocalTime.of(11, 0), zone = zone), cal)
            tasks.create(TaskDraft(title = "Task $d", dueDate = d), list)
        }
        vm = TodayViewModel(events, tasks, app.container.settings, clock, MutableStateFlow(TimeContext(zone)), SavedStateHandle(mapOf("today_selected" to day.toEpochDay())), Dispatchers.Unconfined, SleepRepository(db, clock), TaskSeriesReader(db))
    }

    @After fun tearDown() {
        db.close(); app.deleteDatabase(dbName); Dispatchers.resetMain()
    }


    private suspend fun TodayViewModel.await(p: (TodayUiState) -> Boolean): TodayUiState = withTimeout(10_000) { state.first { !it.isLoading && p(it) } }

    @Test fun anotherDayInTheSameMonth_isBuiltWithoutANewQuery() = runBlocking {
        val collectors = listOf(launch(Dispatchers.Unconfined) { vm.state.collect() }, launch(Dispatchers.Unconfined) { vm.marks.collect() })
        try {
            vm.await { it.date == day && it.timeline.size == 2 }
            val before = queries.get()
            for (d in listOf(day.plusDays(1), day.plusDays(9), day.minusDays(10), day)) {
                vm.select(d)
                val s = vm.await { it.date == d }
                // Days with an event and a task show both, the others nothing.
                assertThat(s.timeline.size).isEqualTo(if (d == day || d == day.plusDays(1) || d == day.plusDays(9)) 2 else 0)
            }
            assertThat(queries.get()).isEqualTo(before)
            // The week strip's dots come from the same data.
            vm.select(day.plusDays(9))
            vm.await { it.date == day.plusDays(9) }
            assertThat(vm.marks.value.keys).contains(day.plusDays(9))
            assertThat(queries.get()).isEqualTo(before)
        } finally {
            collectors.forEach { it.cancel() }
        }
    }

    /**
     * Another month has no data in memory yet: every state shown for the new day already has its event
     * (the previous day stays until the window has arrived), never an empty agenda that fills in later.
     */
    @Test fun anotherMonth_loadsItsOwnWindow_andTheNewDayIsNeverShownEmpty() = runBlocking {
        val next = LocalDate.of(2026, 12, 3)
        val events = EventRepository(db, clock, object : ChangeListener { override fun onTaskChanged(taskId: String) = Unit })
        events.create(EventDraft(title = "December", startDate = next, startTime = LocalTime.of(9, 0), endTime = LocalTime.of(10, 0), zone = zone), events.ensureDefaultCalendar("Calendar"))
        val seen = mutableListOf<TodayUiState>()
        val collector = launch(Dispatchers.Unconfined) { vm.state.collect { seen += it } }
        try {
            vm.await { it.date == day && it.timeline.size == 2 }
            vm.select(next)
            vm.await { it.date == next }
            val forNext = seen.filter { it.date == next }
            assertThat(forNext).isNotEmpty()
            assertThat(forNext.all { it.timeline.size == 1 }).isTrue()
            // Back inside the loaded window is immediate again.
            val before = queries.get()
            vm.select(next.plusDays(2))
            vm.await { it.date == next.plusDays(2) }
            assertThat(queries.get()).isEqualTo(before)
        } finally {
            collector.cancel()
        }
    }
}
