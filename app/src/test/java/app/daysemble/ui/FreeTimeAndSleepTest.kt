package app.daysemble.ui

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.edit
import app.daysemble.DaysembleApp
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.time.FixedAppClock
import app.daysemble.core.time.SleepSchedule
import app.daysemble.core.time.TimeContext
import app.daysemble.data.db.Migrations
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.data.repo.EventRepository
import app.daysemble.data.repo.SleepRepository
import app.daysemble.data.repo.TaskRepository
import app.daysemble.data.sync.ChangeListener
import app.daysemble.data.sync.Outbox
import app.daysemble.domain.model.EventDraft
import app.daysemble.domain.model.TaskDraft
import app.daysemble.ui.today.FreeTimeEntry
import app.daysemble.ui.today.TodayUiState
import app.daysemble.ui.today.TodayViewModel
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Free time, scheduled sleep and the sleep records end to end: Room, the settings store and the
 * Today ViewModel together. The waking hours come from the settings, so changing them recalculates
 * the day at once; events and timed tasks are counted as a union; tasks without a time slot never
 * take time; sleep records survive a restart and never change the schedule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FreeTimeAndSleepTest {
    private val app get() = ApplicationProvider.getApplicationContext<DaysembleApp>()
    private val c get() = app.container
    private val zone = ZoneId.of("Europe/Berlin")
    private val clock = FixedAppClock(Instant.parse("2026-10-27T05:00:00Z"), zone) // Tue 06:00 in Berlin
    private val day = LocalDate.parse("2026-10-28") // a whole future day
    private val dbName = "free-time-test.db"
    private val open = mutableListOf<DaysembleDatabase>()

    private fun db() = Room.databaseBuilder(app, DaysembleDatabase::class.java, dbName)
        .addMigrations(*Migrations.ALL).allowMainThreadQueries().build().also { open += it }
    private val listener = object : ChangeListener {
        override fun onTaskChanged(taskId: String) {}
        override fun onPendingSync() {}
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Before fun setUp() = runBlocking<Unit> {
        // viewModelScope runs on Main; here that is a dispatcher that doesn't need the looper.
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        app.deleteDatabase(dbName)
        app.settingsDataStore.edit { it.clear() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @After fun tearDown() {
        open.forEach { it.close() }; app.deleteDatabase(dbName)
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun vm(d: DaysembleDatabase): Triple<TodayViewModel, TaskRepository, EventRepository> {
        val outbox = Outbox(d.sync()) { clock.now().toEpochMilli() }
        val tasks = TaskRepository(d, outbox, clock, listener)
        val events = EventRepository(d, outbox, clock, listener)
        val handle = SavedStateHandle(mapOf("today_selected" to day.toEpochDay()))
        val vm = TodayViewModel(events, tasks, c.settings, clock, MutableStateFlow(TimeContext(zone)), handle, Dispatchers.Unconfined, SleepRepository(d, clock))
        return Triple(vm, tasks, events)
    }

    private suspend fun TodayViewModel.awaitState(p: (TodayUiState) -> Boolean): TodayUiState =
        withTimeout(10_000) { state.first { !it.isLoading && p(it) } }

    private fun at(h: Int, m: Int = 0, d: LocalDate = day) = d.atTime(h, m)

    @Test fun freeTime_usesTheConfiguredWindow_unionOfBusy_andNoInventedTaskTime() = runBlocking {
        val d = db()
        val (vm, tasks, events) = vm(d)
        val cal = events.ensureDefaultCalendar("Calendar")
        val list = tasks.ensureDefaultList("My tasks")
        c.settings.setDefaultSchedule(7 * 60, 23 * 60)
        // Events 09–11 and 10–12 overlap: 3 h busy, not 4.
        events.create(EventDraft(title = "A", startDate = day, startTime = LocalTime.of(9, 0), endTime = LocalTime.of(11, 0), zone = zone), cal)
        events.create(EventDraft(title = "B", startDate = day, startTime = LocalTime.of(10, 0), endTime = LocalTime.of(12, 0), zone = zone), cal)
        // A timed task with a duration is a block: 14:00–15:00.
        tasks.create(TaskDraft(title = "Write report", dueDate = day, dueTime = LocalTime.of(14, 0), durationMinutes = 60), list)
        // A timed task overlapping an event counts once: 11:30–12:30 adds 30 min.
        tasks.create(TaskDraft(title = "Prep", dueDate = day, dueTime = LocalTime.of(11, 30), durationMinutes = 60), list)
        // Due date only, with an estimate: estimated work, not subtracted.
        tasks.create(TaskDraft(title = "Read", dueDate = day, durationMinutes = 90), list)
        // Due date only, no estimate; and a timed task without duration: neither takes time.
        tasks.create(TaskDraft(title = "Call mum", dueDate = day), list)
        tasks.create(TaskDraft(title = "Pick up parcel", dueDate = day, dueTime = LocalTime.of(17, 0)), list)

        val s = vm.awaitState { it.availability?.eventBusy == Duration.ofHours(3) && it.timeline.size >= 5 && it.availability?.estimatedWork == Duration.ofMinutes(90) }
        val a = s.availability!!
        assertThat(a.window.duration).isEqualTo(Duration.ofHours(16))
        assertThat(a.taskBusy).isEqualTo(Duration.ofHours(2))
        assertThat(a.busy).isEqualTo(Duration.ofMinutes(4 * 60 + 30)) // 9–12:30 and 14–15
        assertThat(a.free).isEqualTo(Duration.ofMinutes(16 * 60 - 270))
        assertThat(a.estimatedWork).isEqualTo(Duration.ofMinutes(90))
        assertThat(a.unestimatedTasks).isEqualTo(2)
        assertThat(s.freeTimeEntries.filterIsInstance<FreeTimeEntry.Estimate>().map { it.title }).containsExactly("Read")
        assertThat(s.scheduledSleep).isEqualTo(Duration.ofHours(8))

        // Changing the waking hours recalculates the same day straight away.
        c.settings.setDefaultSchedule(8 * 60, 22 * 60)
        val s2 = vm.awaitState { it.availability?.window?.duration == Duration.ofHours(14) }
        assertThat(s2.availability!!.free).isEqualTo(Duration.ofMinutes(14 * 60 - 270))
        assertThat(s2.scheduledSleep).isEqualTo(Duration.ofHours(10))
    }

    @Test fun completedTasksAndCancelledTime_dontCount_andANoActivityDayIsAllFree() = runBlocking {
        val d = db()
        val (vm, tasks, _) = vm(d)
        val list = tasks.ensureDefaultList("My tasks")
        val s0 = vm.awaitState { it.availability != null }
        assertThat(s0.availability!!.isFree).isTrue()
        assertThat(s0.availability!!.free).isEqualTo(Duration.ofHours(16)) // defaults 07:00–23:00
        val id = tasks.create(TaskDraft(title = "Gym", dueDate = day, dueTime = LocalTime.of(18, 0), durationMinutes = 60), list)
        vm.awaitState { it.availability?.taskBusy == Duration.ofHours(1) }
        tasks.setCompleted(id, true)
        val s = vm.awaitState { it.availability?.taskBusy == Duration.ZERO }
        assertThat(s.availability!!.free).isEqualTo(Duration.ofHours(16))
    }

    @Test fun repeatingTaskWithTimeAndDuration_blocksItsNextOccurrenceDay() = runBlocking {
        val d = db()
        val (vm, tasks, _) = vm(d)
        val list = tasks.ensureDefaultList("My tasks")
        // Current occurrence is today (27th); the 28th has no row yet but is a scheduled date.
        tasks.create(
            TaskDraft(title = "Run", dueDate = day.minusDays(1), dueTime = LocalTime.of(7, 30), durationMinutes = 45, repeat = RRule.parse("FREQ=DAILY")),
            list,
        )
        val s = vm.awaitState { it.availability?.taskBusy == Duration.ofMinutes(45) }
        val block = s.freeTimeEntries.filterIsInstance<FreeTimeEntry.TaskBlock>().single()
        assertThat(block.projected).isTrue()
        assertThat(block.start).isEqualTo(at(7, 30).atZone(zone).toInstant())
    }

    @Test fun eventCrossingMidnight_countsOnlyItsPartInTheWindow() = runBlocking {
        val d = db()
        val (vm, _, events) = vm(d)
        val cal = events.ensureDefaultCalendar("Calendar")
        c.settings.setDefaultSchedule(7 * 60, 60) // bedtime 01:00, after midnight
        events.create(
            EventDraft(title = "Night out", startDate = day, startTime = LocalTime.of(22, 0), endDate = day.plusDays(1), endTime = LocalTime.of(3, 0), zone = zone),
            cal,
        )
        val s = vm.awaitState { it.availability?.window?.duration == Duration.ofHours(18) && it.availability?.eventBusy != Duration.ZERO }
        assertThat(s.availability!!.eventBusy).isEqualTo(Duration.ofHours(3)) // 22:00–01:00
    }

    // ---- Sleep records ------------------------------------------------------------------------

    @Test fun sleepAcrossMidnight_duplicateTaps_restart_andTheScheduleIsUntouched() = runBlocking {
        c.settings.setDefaultSchedule(7 * 60, 23 * 60)
        val first = db()
        val sleep = SleepRepository(first, clock)
        clock.instant = at(23, 10, day.minusDays(1)).atZone(zone).toInstant()
        val saved = sleep.goToSleep(zone)
        assertThat(saved).isInstanceOf(SleepRepository.Result.Saved::class.java)
        // A second tap: no second record, the UI is asked instead.
        assertThat(sleep.goToSleep(zone)).isInstanceOf(SleepRepository.Result.AlreadyAsleep::class.java)
        first.close()

        // Restart, then wake up the next morning.
        val second = db()
        val sleep2 = SleepRepository(second, clock)
        clock.instant = at(6, 55).atZone(zone).toInstant()
        val woke = sleep2.wakeUp(zone) as SleepRepository.Result.Saved
        assertThat(woke.record.duration).isEqualTo(Duration.ofMinutes(7 * 60 + 45))
        assertThat(sleep2.wakeUp(zone)).isInstanceOf(SleepRepository.Result.AlreadyAwake::class.java)
        val records = sleep2.recent.first()
        assertThat(records).hasSize(1)
        assertThat(records.single().nightOf(zone)).isEqualTo(day)
        // The configured schedule is unchanged by recording actual times.
        assertThat(c.settings.current().schedule.default).isEqualTo(SleepSchedule(7 * 60, 23 * 60))

        // Today shows it as last night's sleep, next to the planned 8 h.
        val outbox = Outbox(second.sync()) { clock.now().toEpochMilli() }
        val vm = TodayViewModel(
            EventRepository(second, outbox, clock, listener), TaskRepository(second, outbox, clock, listener), c.settings, clock,
            MutableStateFlow(TimeContext(zone)), SavedStateHandle(mapOf("today_selected" to day.toEpochDay())), Dispatchers.Unconfined, sleep2,
        )
        val s = vm.awaitState { it.sleep.lastNight != null }
        assertThat(s.sleep.lastNight!!.duration).isEqualTo(Duration.ofMinutes(465))
        assertThat(s.scheduledSleep).isEqualTo(Duration.ofHours(8))
    }

    @Test fun incompleteRecords_areKept_andCanBeCorrected() = runBlocking {
        val d = db()
        val sleep = SleepRepository(d, clock)
        clock.instant = at(7, 0).atZone(zone).toInstant()
        val w = (sleep.wakeUp(zone) as SleepRepository.Result.Saved).record
        assertThat(w.bedtime).isNull()
        assertThat(w.duration).isNull()
        // The user adds the forgotten bedtime afterwards.
        val bed = at(23, 30, day.minusDays(1)).atZone(zone).toInstant()
        assertThat(sleep.update(w.id, bed, w.wake, zone)).isNull()
        assertThat(sleep.recent.first().single().duration).isEqualTo(Duration.ofMinutes(450))
        // A wake-up before the bedtime is refused and nothing changes.
        assertThat(sleep.update(w.id, w.wake, bed, zone)).isNotNull()
        assertThat(sleep.recent.first().single().duration).isEqualTo(Duration.ofMinutes(450))
    }

    @Test fun scheduleSettings_persistAcrossARestartOfTheStore_andRestoreDefaults() = runBlocking {
        assertThat(c.settings.setDefaultSchedule(6 * 60 + 30, 22 * 60 + 30)).isTrue()
        assertThat(c.settings.setDefaultSchedule(8 * 60, 8 * 60)).isFalse() // same minute refused
        c.settings.setPerWeekdaySchedule(true)
        c.settings.setWeekdaySchedule(java.time.DayOfWeek.SATURDAY, SleepSchedule(9 * 60, 60))
        val s = c.settings.current().schedule
        assertThat(s.default).isEqualTo(SleepSchedule(390, 1350))
        assertThat(s.forDate(LocalDate.parse("2026-10-31"))).isEqualTo(SleepSchedule(540, 60))
        c.settings.resetSchedule()
        assertThat(c.settings.current().schedule.default).isEqualTo(SleepSchedule.DEFAULT)
        assertThat(c.settings.current().schedule.perWeekday).isFalse()
    }
}
