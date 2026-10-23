package app.daysemble.ui.today

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.daysemble.core.time.AppClock
import app.daysemble.data.prefs.SettingsRepository
import app.daysemble.data.repo.EventRepository
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
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

@Immutable
data class TodayUiState(
    val date: LocalDate,
    val today: LocalDate,
    val allDay: List<TimelineItem.EventItem> = emptyList(),
    /** Timed events and timed tasks, by time; then date-only tasks. */
    val timeline: List<TimelineItem> = emptyList(),
    val overdue: List<Task> = emptyList(),
    val completed: List<Task> = emptyList(),
    val eventCount: Int = 0,
    val openTaskCount: Int = 0,
    /** End of the last timed event, when the rest of the day is free after it. */
    val freeAfter: Instant? = null,
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
) : ViewModel() {
    private fun zone(): ZoneId = time.value.zone

    private val selected = MutableStateFlow(handle.get<Long>(KEY_DATE)?.let(LocalDate::ofEpochDay) ?: clock.today())

    /** Bumped by the screen once a minute so "today", overdue and focus stay current. */
    private val tick = MutableStateFlow(clock.now())

    val state: StateFlow<TodayUiState> = combine(selected, time) { d, t -> d to t.zone }.flatMapLatest { (date, z) ->
        combine(
            events.occurrences(date, date, z),
            tasks.allTasks,
            settings.settings,
            tick,
        ) { occ, allTasks, s, now -> build(date, occ, allTasks, s.showCompletedOnToday, now) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState(selected.value, clock.today()))

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

    fun setCompleted(id: String, done: Boolean) = viewModelScope.launch { tasks.setCompleted(id, done) }

    fun delete(id: String) = viewModelScope.launch { tasks.delete(id) }
    fun restore(id: String) = viewModelScope.launch { tasks.restore(id) }

    private fun build(date: LocalDate, occ: List<EventOccurrence>, all: List<Task>, showCompleted: Boolean, now: Instant): TodayUiState {
        val z = zone()
        val today = now.atZone(z).toLocalDate()
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
        val lastEnd = timed.maxOfOrNull { it.end }
        val endOfDay = date.plusDays(1).atStartOfDay(z).toInstant()
        val freeAfter = lastEnd?.takeIf { date == today && it > now && it < endOfDay.minusSeconds(3 * 3600) }

        return TodayUiState(
            date = date, today = today,
            allDay = allDay,
            timeline = merged + dateOnly.map { TimelineItem.TaskItem(it) },
            overdue = overdue,
            completed = completed,
            eventCount = occ.size,
            openTaskCount = dayTasks.size + overdue.size,
            freeAfter = freeAfter,
            focusEventKey = focus,
            isLoading = false,
        )
    }

    private companion object {
        const val KEY_DATE = "today_selected"
    }
}
