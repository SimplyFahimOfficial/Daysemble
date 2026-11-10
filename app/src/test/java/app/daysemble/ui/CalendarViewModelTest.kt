package app.daysemble.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.time.FixedAppClock
import app.daysemble.core.time.TimeContext
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.repo.EventRepository
import app.daysemble.data.repo.TaskRepository
import app.daysemble.data.sync.ChangeListener
import app.daysemble.data.sync.Outbox
import app.daysemble.domain.model.EventDraft
import app.daysemble.domain.model.TaskDraft
import app.daysemble.ui.calendar.CalendarUiState
import app.daysemble.ui.calendar.CalendarViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarViewModelTest {
    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 10, 22)
    private val clock = FixedAppClock(Instant.parse("2026-10-22T23:00:00Z"), zone)
    private val time = MutableStateFlow(TimeContext(zone))
    private lateinit var db: DaysembleDatabase
    private lateinit var tasks: TaskRepository
    private lateinit var events: EventRepository
    private lateinit var vm: CalendarViewModel
    private lateinit var list: String

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DaysembleDatabase::class.java)
            .allowMainThreadQueries().build()
        val listener = object : ChangeListener {
            override fun onTaskChanged(taskId: String) {}
            override fun onPendingSync() {}
        }
        val outbox = Outbox(db.sync()) { clock.now().toEpochMilli() }
        tasks = TaskRepository(db, outbox, clock, listener)
        events = EventRepository(db, outbox, clock, listener)
        list = tasks.ensureDefaultList("Tasks")
        tasks.create(TaskDraft(title = "Task", dueDate = day), list)
        val calendar = events.ensureDefaultCalendar("Calendar")
        events.create(
            EventDraft(title = "Late event", startDate = day, startTime = LocalTime.of(23, 30),
                endTime = LocalTime.of(23, 45), zone = zone),
            calendar,
        )
        vm = CalendarViewModel(events, tasks, clock, time, SavedStateHandle(), Dispatchers.Unconfined)
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun awaitState(predicate: (CalendarUiState) -> Boolean = { true }): CalendarUiState =
        withTimeout(10_000) { vm.state.first { !it.isLoading && predicate(it) } }

    @Test fun selectionAndMidnightReuseGroupedData() = runBlocking {
        val collector = launch(Dispatchers.Unconfined) { vm.state.collect() }
        try {
            val initial = awaitState()
            assertThat(initial.byDate[day]).hasSize(1)
            assertThat(initial.tasksByDate[day]).hasSize(1)
            vm.select(day.plusDays(1))
            val selected = awaitState { it.selected == day.plusDays(1) }
            assertThat(selected.byDate).isSameInstanceAs(initial.byDate)
            assertThat(selected.tasksByDate).isSameInstanceAs(initial.tasksByDate)

            vm.onTick(Instant.parse("2026-10-23T00:01:00Z"))
            val midnight = awaitState { it.today == day.plusDays(1) }
            assertThat(midnight.byDate).isSameInstanceAs(initial.byDate)
            assertThat(midnight.tasksByDate).isSameInstanceAs(initial.tasksByDate)
            // A backwards clock adjustment must also update the highlighted local date.
            vm.onTick(clock.now())
            assertThat(awaitState { it.today == day }.selected).isEqualTo(day.plusDays(1))
        } finally {
            collector.cancel()
        }
    }

    @Test fun zoneChangeRegroupsEventsAndUpdatesTodayWithoutAnotherTick() = runBlocking {
        val collector = launch(Dispatchers.Unconfined) { vm.state.collect() }
        try {
            awaitState()
            time.value = time.value.copy(zone = ZoneId.of("Asia/Kolkata"))
            val shifted = awaitState { it.today == day.plusDays(1) && it.byDate[day.plusDays(1)]?.size == 1 }
            assertThat(shifted.byDate[day]).isNull()
            assertThat(shifted.tasksByDate[day]).hasSize(1) // task due dates are local dates
        } finally {
            collector.cancel()
        }
    }

    @Test fun databaseAndWindowChangesStillRefreshGroupedData() = runBlocking {
        val collector = launch(Dispatchers.Unconfined) { vm.state.collect() }
        try {
            val initial = awaitState()
            val id = tasks.create(TaskDraft(title = "Another", dueDate = day), list)
            awaitState { it.tasksByDate[day]?.size == 2 }
            tasks.setCompleted(id, true)
            awaitState { it.tasksByDate[day]?.size == 1 }
            val distant = day.plusMonths(6)
            tasks.create(TaskDraft(title = "Future", dueDate = distant), list)
            vm.select(distant)
            val moved = awaitState { it.selected == distant && it.tasksByDate[distant]?.size == 1 }
            assertThat(moved.loadedFrom).isGreaterThan(initial.loadedTo)
            assertThat(moved.tasksByDate[day]).isNull()
            assertThat(moved.byDate).isEmpty()
        } finally {
            collector.cancel()
        }
    }
}
