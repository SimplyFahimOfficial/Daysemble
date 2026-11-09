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
import app.daysemble.data.repo.TaskSeriesReader
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
    private val seriesReader: TaskSeriesReader? = null,
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
            combine(tasks.allTasks, seriesReader?.timedSeries ?: flowOf(emptyList())) { a, b -> a to b },
            settings.settings,
            tick,
            sleepRepo?.recent ?: flowOf(emptyList()),
        ) { occ, (allTasks, series), s, now, sleep ->
            build(DayInputs(date, z, now, occ, allTasks, series, s.showCompletedOnToday, s.schedule, sleep))
        }
    }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState(selected.value, clock.today()))

    /**
     * Colours of what is on each day of the weeks around the selected one (event colours, then a
     * neutral mark for open tasks), for the dots in the week strip. Read-only.
     */
    val marks: StateFlow<Map<LocalDate, List<Int>>> = combine(selected, time) { d, t -> d to t }.flatMapLatest { (date, t) ->
        val start = date.minusDays(
            ((date.dayOfWeek.value - t.firstDayOfWeek.value + DAYS_IN_WEEK) % DAYS_IN_WEEK).toLong(),
        ).minusWeeks(WEEKS_BEFORE_SELECTED)
        val end = start.plusWeeks(WEEKS_IN_MARKS_RANGE).minusDays(LAST_DAY_OFFSET)
        combine(events.occurrences(start, end, t.zone), tasks.allTasks) { occ, all ->
            val byEvents = app.daysemble.ui.calendar.CalendarViewModel.groupByDate(occ, start, end)
            val byTasks = all.filter { !it.isCompleted && it.parentId == null && it.dueDate?.let { d -> d in start..end } == true }.groupBy { it.dueDate!! }
            (byEvents.keys + byTasks.keys).associateWith { d ->
                val colors = byEvents[d].orEmpty().map { it.colorArgb }.distinct().take(MAX_MARKS)
                if (colors.size < MAX_MARKS && byTasks[d].orEmpty().isNotEmpty()) colors + app.daysemble.ui.components.TASK_MARK else colors
            }
        }
    }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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

    /** Everything one day's state is built from (all loaded for the date and the next one). */
    private data class DayInputs(
        val date: LocalDate,
        val zone: ZoneId,
        val now: Instant,
        /** Event occurrences of the date and the day after (a waking window can pass midnight). */
        val occurrences: List<EventOccurrence>,
        val all: List<Task>,
        val series: List<TaskSeriesReader.SeriesTemplate>,
        val showCompleted: Boolean,
        val schedule: DaySchedulePrefs,
        val sleep: List<SleepRecord>,
    ) {
        val today: LocalDate = now.atZone(zone).toLocalDate()
        val topLevel: List<Task> = all.filter { it.parentId == null }
        /** Open top-level tasks due on the date. */
        val dayTasks: List<Task> = topLevel.filter { !it.isCompleted && it.dueDate == date }
    }

    private fun build(i: DayInputs): TodayUiState {
        val agenda = agenda(i)
        val free = freeTime(i)
        return TodayUiState(
            date = i.date, today = i.today,
            allDay = agenda.allDay,
            timeline = agenda.timeline,
            overdue = agenda.overdue,
            completed = agenda.completed,
            eventCount = agenda.eventCount,
            taskCount = i.dayTasks.size,
            overdueCount = agenda.overdue.size,
            availability = free.availability,
            window = free.window,
            schedule = i.schedule.forDate(i.date),
            scheduledSleep = i.schedule.scheduledSleepBefore(i.date, i.zone),
            freeTimeEntries = free.entries,
            sleep = sleepSummary(i),
            nextEvent = agenda.next,
            focusEventKey = agenda.focus,
            isLoading = false,
        )
    }

    private data class Agenda(
        val allDay: List<TimelineItem.EventItem>,
        val timeline: List<TimelineItem>,
        val overdue: List<Task>,
        val completed: List<Task>,
        val eventCount: Int,
        val next: EventOccurrence?,
        val focus: String?,
    )

    /** The date's agenda: all-day events, then timed events and tasks by time, then date-only tasks. */
    private fun agenda(i: DayInputs): Agenda {
        val date = i.date
        val z = i.zone
        val now = i.now
        val today = i.today
        val dayStart = date.atStartOfDay(z).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(z).toInstant()
        val occ = i.occurrences.filter { o -> if (o.isAllDay) o.coversDate(date) else o.overlaps(dayStart, dayEnd) }
        val overdue = if (date == today) {
            i.topLevel.filter { !it.isCompleted && it.dueDate?.isBefore(today) == true }
                .sortedWith(TaskRules.comparator(app.daysemble.data.prefs.TaskSort.DUE, z))
        } else emptyList()
        val completed = if (i.showCompleted) {
            i.topLevel.filter { it.isCompleted && it.completedOn(date, z) }.sortedByDescending { it.completedAt }
        } else emptyList()
        val timed = occ.filter { !it.isAllDay }
        val dateOnly = i.dayTasks.filter { it.dueTime == null }.sortedWith(compareByDescending<Task> { it.priority.level }.thenBy { it.sortKey })
        val merged = (timed.map { TimelineItem.EventItem(it) to it.start } +
            i.dayTasks.filter { it.dueTime != null }.map { TimelineItem.TaskItem(it) to (it.dueMoment(z) ?: Instant.MAX) })
            .sortedBy { it.second }.map { it.first }
        val isToday = date == today
        return Agenda(
            allDay = occ.filter { it.isAllDay }.map { TimelineItem.EventItem(it) },
            timeline = merged + dateOnly.map { TimelineItem.TaskItem(it) },
            overdue = overdue,
            completed = completed,
            eventCount = occ.size,
            next = if (isToday) timed.firstOrNull { it.start > now } else null,
            focus = if (isToday) timed.firstOrNull { it.end > now }?.stableKey else null,
        )
    }

    private class FreeTime(val window: AwakeWindow, val availability: DayAvailability?, val entries: List<FreeTimeEntry>)

    /**
     * The date's waking window and what takes time in it: timed events (any day, clipped), open tasks
     * with a due time and duration, later occurrences of such repeating tasks, and, not subtracted,
     * estimated work of tasks with a duration but no time.
     */
    private fun freeTime(i: DayInputs): FreeTime {
        val z = i.zone
        val dayTasks = i.dayTasks
        val window = i.schedule.awakeWindow(i.date, z)
        val windowEvents = i.occurrences.filter { !it.isAllDay && it.start.isBefore(window.end) && it.end.isAfter(window.start) }
        // Any due date: a block after midnight belongs to this window if bedtime is after midnight.
        val blocks = i.topLevel.filter { !it.isCompleted }.mapNotNull { t -> t.scheduledBlock(z)?.let { t to it } }
            .filter { (_, b) -> b.first.isBefore(window.end) && b.second.isAfter(window.start) }
        val projected = projectedBlocks(i.series, i.all, window, z)
        val estimates = dayTasks.filter { it.dueTime == null && (it.durationMinutes ?: 0) > 0 }
        val entries = windowEvents.map { FreeTimeEntry.Event(it.title, it.start, it.end) } +
            blocks.map { (t, b) -> FreeTimeEntry.TaskBlock(t.title, b.first, b.second, projected = false) } +
            projected + estimates.map { FreeTimeEntry.Estimate(it.title, it.durationMinutes!!) }
        val availability = Availability.of(
            window,
            windowEvents.map { Availability.Busy(it.start, it.end) },
            i.now,
            tasks = blocks.map { (_, b) -> Availability.Busy(b.first, b.second) } + projected.map { Availability.Busy(it.start, it.end) },
            estimatedWork = Duration.ofMinutes(estimates.sumOf { it.durationMinutes!!.toLong() }),
            unestimatedTasks = dayTasks.count { (it.durationMinutes ?: 0) <= 0 },
        )
        val sorted = entries.sortedBy {
            when (it) {
                is FreeTimeEntry.Event -> it.start
                is FreeTimeEntry.TaskBlock -> it.start
                is FreeTimeEntry.Estimate -> Instant.MAX
            }
        }
        return FreeTime(window, availability, sorted)
    }

    /** Last night's complete record, an open bedtime (today only), or a wake-up without bedtime. */
    private fun sleepSummary(i: DayInputs): SleepSummary {
        val open = if (i.date == i.today) SleepLog.openSession(i.sleep, i.now) else null
        val lastNight = SleepLog.lastNight(i.sleep, i.date, i.zone)
        val wakeOnly = i.sleep.filter { it.bedtime == null && it.wake != null && it.nightOf(i.zone) == i.date }.maxByOrNull { it.wake!! }
        return SleepSummary(lastNight = lastNight, open = open, wakeOnly = wakeOnly.takeIf { lastNight == null })
    }

    private fun projectedBlocks(series: List<TaskSeriesReader.SeriesTemplate>, all: List<Task>, window: AwakeWindow, z: ZoneId): List<FreeTimeEntry.TaskBlock> {
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
        const val MAX_MARKS = 3
        const val DAYS_IN_WEEK = 7
        const val WEEKS_BEFORE_SELECTED = 1L
        const val WEEKS_IN_MARKS_RANGE = 3L
        const val LAST_DAY_OFFSET = 1L
    }
}

/** An all-day occurrence that includes [date] (its end date is exclusive). */
private fun EventOccurrence.coversDate(date: LocalDate) = !date.isBefore(startDate) && date.isBefore(endDate)

/** A timed occurrence inside [from, until); a zero-length one counts if it starts in it. */
private fun EventOccurrence.overlaps(from: Instant, until: Instant): Boolean {
    if (!start.isBefore(until)) return false
    return end.isAfter(from) || (start == end && !start.isBefore(from))
}

/** Completed on [date]: due that day, or with no due date and ticked off that day. */
private fun Task.completedOn(date: LocalDate, z: ZoneId): Boolean =
    dueDate == date || (dueDate == null && completedAt?.atZone(z)?.toLocalDate() == date)
