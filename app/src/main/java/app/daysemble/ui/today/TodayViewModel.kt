package app.daysemble.ui.today

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.daysemble.core.recurrence.TaskRecurrence
import app.daysemble.core.time.AppClock
import app.daysemble.core.time.Availability
import app.daysemble.core.time.AwakeWindow
import app.daysemble.core.time.DayAvailability
import app.daysemble.core.time.DaySchedulePrefs
import app.daysemble.core.time.SleepLog
import app.daysemble.core.time.SleepRecord
import app.daysemble.core.time.SleepSchedule
import app.daysemble.data.prefs.SettingsRepository
import app.daysemble.data.repo.EventRepository
import app.daysemble.data.repo.SleepRepository
import app.daysemble.data.repo.TaskRepository
import app.daysemble.domain.TaskRules
import app.daysemble.domain.model.EventOccurrence
import app.daysemble.domain.model.Task
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Immutable
sealed interface TimelineItem {
    val key: String

    data class EventItem(val event: EventOccurrence) : TimelineItem {
        override val key get() = "e:" + event.stableKey
    }

    data class TaskItem(val task: Task) : TimelineItem {
        override val key get() = "t:" + task.id
    }
}

/** One line of the free-time breakdown: what took (or didn't take) time, and why. */
@Immutable
sealed interface FreeTimeEntry {
    val title: String

    /** A timed event in the waking window. */
    data class Event(override val title: String, val start: Instant, val end: Instant) : FreeTimeEntry
    /** A task with a due time and a duration; [projected] = a later occurrence of a repeating task (no row yet). */
    data class TaskBlock(override val title: String, val start: Instant, val end: Instant, val projected: Boolean) : FreeTimeEntry
    /** A task with a duration but no time slot: estimated work, not placed on the clock. */
    data class Estimate(override val title: String, val minutes: Int) : FreeTimeEntry
}

/** The manual sleep records that matter for the selected day. */
@Immutable
data class SleepSummary(
    /** The newest complete record whose wake-up is on the selected date (the night before it). */
    val lastNight: SleepRecord?,
    /** A bedtime recorded without a wake-up yet (only for today). */
    val open: SleepRecord?,
    /** A wake-up on the selected date that has no bedtime (no duration can be shown). */
    val wakeOnly: SleepRecord?,
)

@Immutable
data class TodayUiState(
    val date: LocalDate,
    val today: LocalDate,
    val allDay: List<TimelineItem.EventItem> = emptyList(),
    /** Timed events and timed tasks, by time; then date-only tasks. */
    val timeline: List<TimelineItem> = emptyList(),
    val overdue: List<Task> = emptyList(),
    val completed: List<Task> = emptyList(),
    /** Events on the date, all-day ones included. */
    val eventCount: Int = 0,
    /** Open top-level tasks due on the date. */
    val taskCount: Int = 0,
    /** Open top-level tasks due before today (only when the date is today). */
    val overdueCount: Int = 0,
    /** Free and busy time in the date's waking window; null for past dates or after bedtime today. */
    val availability: DayAvailability? = null,
    /** The waking window of the date from the settings (shown even when [availability] is null). */
    val window: AwakeWindow? = null,
    /** The schedule that applies on the date. */
    val schedule: SleepSchedule = SleepSchedule.DEFAULT,
    /** Planned sleep in the night before the date (previous bedtime → this wake-up). */
    val scheduledSleep: Duration = Duration.ZERO,
    /** What went into [availability], for the breakdown. */
    val freeTimeEntries: List<FreeTimeEntry> = emptyList(),
    val sleep: SleepSummary = SleepSummary(null, null, null),
    /** The next timed event that hasn't started yet (today only). */
    val nextEvent: EventOccurrence? = null,
    /** The event whose countdown is highlighted (next upcoming or in progress). */
    val focusEventKey: String? = null,
    val isLoading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val events: EventRepository,
    private val tasks: TaskRepository,
    settings: SettingsRepository,
    private val clock: AppClock,
    private val time: kotlinx.coroutines.flow.StateFlow<app.daysemble.core.time.TimeContext>,
    private val handle: SavedStateHandle,
    private val compute: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    private val sleepRepo: SleepRepository? = null,
) : ViewModel() {
    private fun zone(): ZoneId = time.value.zone

    private val selected = MutableStateFlow(handle.get<Long>(KEY_DATE)?.let(LocalDate::ofEpochDay) ?: clock.today())

    /** Bumped by the screen once a minute so "today", overdue and focus stay current. */
    private val tick = MutableStateFlow(clock.now())

    // Grouping, sorting, recurrence expansion and the free-time sums run off the main thread, so a
    // date change or a tab switch never waits for them; the result is collected on the main thread.
    // Events are loaded for the date and the next one: a waking window can run past midnight.
    val state: StateFlow<TodayUiState> = combine(selected, time) { d, t -> d to t.zone }.flatMapLatest { (date, z) ->
        combine(
            events.occurrences(date, date.plusDays(1), z),
            combine(tasks.allTasks, tasks.timedSeries) { a, b -> a to b },
            settings.settings,
            tick,
            sleepRepo?.recent ?: flowOf(emptyList()),
        ) { occ, (allTasks, series), s, now, sleep ->
            build(date, occ, allTasks, series, s.showCompletedOnToday, s.schedule, sleep, now, z)
        }
    }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState(selected.value, clock.today()))

    fun select(date: LocalDate) {
        selected.value = date
        handle[KEY_DATE] = date.toEpochDay()
    }

    fun goToToday() = select(clock.today())

    fun onTick(now: Instant) {
        val prevToday = tick.value.atZone(zone()).toLocalDate()
        tick.value = now
        // If the day rolled over while the user was looking at "today", follow it.
        val newToday = now.atZone(zone()).toLocalDate()
        if (newToday != prevToday && selected.value == prevToday) select(newToday)
    }

    fun setCompleted(id: String, done: Boolean, onResult: (TaskRepository.Completion) -> Unit = {}) = viewModelScope.launch { tasks.setCompleted(id, done)?.let(onResult) }

    fun delete(id: String) = viewModelScope.launch { tasks.delete(id) }
    fun restore(id: String) = viewModelScope.launch { tasks.restore(id) }

    private fun build(
        date: LocalDate,
        allOcc: List<EventOccurrence>,
        all: List<Task>,
        series: List<TaskRepository.SeriesTemplate>,
        showCompleted: Boolean,
        schedule: DaySchedulePrefs,
        sleep: List<SleepRecord>,
        now: Instant,
        z: ZoneId,
    ): TodayUiState {
        val today = now.atZone(z).toLocalDate()
        val dayStart = date.atStartOfDay(z).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(z).toInstant()
        // The agenda shows the date itself; the extra day is only for a window past midnight.
        val occ = allOcc.filter { o ->
            if (o.isAllDay) !date.isBefore(o.startDate) && date.isBefore(o.endDate)
            else o.start.isBefore(dayEnd) && (o.end.isAfter(dayStart) || (o.start == o.end && !o.start.isBefore(dayStart)))
        }
        val topLevel = all.filter { it.parentId == null }
        val dayTasks = topLevel.filter { !it.isCompleted && it.dueDate == date }
        val overdue = if (date == today) {
            topLevel.filter { !it.isCompleted && it.dueDate != null && it.dueDate.isBefore(today) }
                .sortedWith(TaskRules.comparator(app.daysemble.data.prefs.TaskSort.DUE, z))
        } else emptyList()
        val completed = if (showCompleted) {
            topLevel.filter { it.isCompleted && (it.dueDate == date || (it.dueDate == null && it.completedAt?.atZone(z)?.toLocalDate() == date)) }
                .sortedByDescending { it.completedAt }
        } else emptyList()

        val allDay = occ.filter { it.isAllDay }.map { TimelineItem.EventItem(it) }
        val timed = occ.filter { !it.isAllDay }
        val timedTasks = dayTasks.filter { it.dueTime != null }
        val dateOnly = dayTasks.filter { it.dueTime == null }.sortedWith(compareByDescending<Task> { it.priority.level }.thenBy { it.sortKey })

        val merged = (timed.map { TimelineItem.EventItem(it) to it.start } +
            timedTasks.map { TimelineItem.TaskItem(it) to (it.dueMoment(z) ?: Instant.MAX) })
            .sortedBy { it.second }.map { it.first }

        val focus = if (date == today) timed.firstOrNull { it.end > now }?.stableKey else null
        val next = if (date == today) timed.firstOrNull { it.start > now } else null

        // ---- Free time -------------------------------------------------------------------------
        val window = schedule.awakeWindow(date, z)
        val windowEvents = allOcc.filter { !it.isAllDay && it.start.isBefore(window.end) && it.end.isAfter(window.start) }
        val entries = ArrayList<FreeTimeEntry>()
        windowEvents.forEach { entries += FreeTimeEntry.Event(it.title, it.start, it.end) }
        // Open tasks with a real time block (due time + duration) inside the window, any due date
        // (a block after midnight belongs to this window if bedtime is after midnight).
        val blocks = topLevel.filter { !it.isCompleted }.mapNotNull { t -> t.scheduledBlock(z)?.let { t to it } }
            .filter { (_, b) -> b.first.isBefore(window.end) && b.second.isAfter(window.start) }
        blocks.forEach { (t, b) -> entries += FreeTimeEntry.TaskBlock(t.title, b.first, b.second, projected = false) }
        // Later occurrences of repeating tasks with a time and duration, on dates that have no row
        // yet (only the current occurrence is stored); never on or before the current one.
        val projected = projectedBlocks(series, all, window, z)
        projected.forEach { entries += it }
        val estimates = dayTasks.filter { it.dueTime == null && (it.durationMinutes ?: 0) > 0 }
        estimates.forEach { entries += FreeTimeEntry.Estimate(it.title, it.durationMinutes!!) }
        val unestimated = dayTasks.count { (it.durationMinutes ?: 0) <= 0 }
        val availability = Availability.of(
            window,
            windowEvents.map { Availability.Busy(it.start, it.end) },
            now,
            tasks = blocks.map { (_, b) -> Availability.Busy(b.first, b.second) } + projected.map { Availability.Busy(it.start, it.end) },
            estimatedWork = Duration.ofMinutes(estimates.sumOf { it.durationMinutes!!.toLong() }),
            unestimatedTasks = unestimated,
        )

        // ---- Sleep -----------------------------------------------------------------------------
        val open = if (date == today) SleepLog.openSession(sleep, now) else null
        val lastNight = SleepLog.lastNight(sleep, date, z)
        val wakeOnly = sleep.filter { it.bedtime == null && it.wake != null && it.nightOf(z) == date }.maxByOrNull { it.wake!! }

        return TodayUiState(
            date = date, today = today,
            allDay = allDay,
            timeline = merged + dateOnly.map { TimelineItem.TaskItem(it) },
            overdue = overdue,
            completed = completed,
            eventCount = occ.size,
            taskCount = dayTasks.size,
            overdueCount = overdue.size,
            availability = availability,
            window = window,
            schedule = schedule.forDate(date),
            scheduledSleep = schedule.scheduledSleepBefore(date, z),
            freeTimeEntries = entries.sortedBy {
                when (it) {
                    is FreeTimeEntry.Event -> it.start
                    is FreeTimeEntry.TaskBlock -> it.start
                    is FreeTimeEntry.Estimate -> Instant.MAX
                }
            },
            sleep = SleepSummary(lastNight = lastNight, open = open, wakeOnly = wakeOnly.takeIf { lastNight == null }),
            nextEvent = next,
            focusEventKey = focus,
            isLoading = false,
        )
    }

    private fun projectedBlocks(series: List<TaskRepository.SeriesTemplate>, all: List<Task>, window: AwakeWindow, z: ZoneId): List<FreeTimeEntry.TaskBlock> {
        if (series.isEmpty()) return emptyList()
        val bySeries = all.filter { it.seriesId != null }.groupBy { it.seriesId }
        // The window can touch two dates (bedtime after midnight).
        val dates = listOf(window.date, window.date.plusDays(1))
        return series.flatMap { s ->
            val rows = bySeries[s.id].orEmpty()
            val current = rows.filter { !it.isCompleted }.mapNotNull { it.occurrenceDate }.maxOrNull() ?: return@flatMap emptyList()
            dates.mapNotNull { d ->
                if (!d.isAfter(current)) return@mapNotNull null
                if (rows.any { it.occurrenceDate == d || it.dueDate == d }) return@mapNotNull null
                TaskRecurrence.indexOf(s.rule, s.anchor, d) ?: return@mapNotNull null
                val start = LocalDateTime.of(d, s.dueTime).atZone(z)
                val end = start.plusMinutes(s.durationMinutes.toLong())
                if (!start.toInstant().isBefore(window.end) || !end.toInstant().isAfter(window.start)) return@mapNotNull null
                FreeTimeEntry.TaskBlock(s.title, start.toInstant(), end.toInstant(), projected = true)
            }
        }
    }

    private companion object {
        const val KEY_DATE = "today_selected"
    }
}
