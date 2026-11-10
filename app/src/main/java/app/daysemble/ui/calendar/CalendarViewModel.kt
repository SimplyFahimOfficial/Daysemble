package app.daysemble.ui.calendar

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.daysemble.core.time.AppClock
import app.daysemble.data.repo.EventRepository
import app.daysemble.data.repo.TaskRepository
import app.daysemble.domain.model.EventOccurrence
import app.daysemble.domain.model.Task
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class CalendarMode { MONTH, WEEK, DAY, AGENDA }

@Immutable
data class CalendarUiState(
    val mode: CalendarMode,
    val selected: LocalDate,
    val today: LocalDate,
    /** Occurrences per local date (multi-day events appear on each day they touch). */
    val byDate: Map<LocalDate, List<EventOccurrence>> = emptyMap(),
    /** Open tasks with a due date, per date (shown in month day list and agenda). */
    val tasksByDate: Map<LocalDate, List<Task>> = emptyMap(),
    /** Range currently loaded, inclusive. */
    val loadedFrom: LocalDate = selected,
    val loadedTo: LocalDate = selected,
    val isLoading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val events: EventRepository,
    private val tasks: TaskRepository,
    private val clock: AppClock,
    private val time: kotlinx.coroutines.flow.StateFlow<app.daysemble.core.time.TimeContext>,
    private val handle: SavedStateHandle,
    private val compute: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) : ViewModel() {
    private fun zone(): ZoneId = time.value.zone
    private fun firstDayOfWeek(): DayOfWeek = time.value.firstDayOfWeek

    /** Bumped once a minute by the screen so "today" follows midnight. */
    private val tick = MutableStateFlow(clock.now())
    fun onTick(now: java.time.Instant) { tick.value = now }

    private val mode = MutableStateFlow(handle.get<String>("mode")?.let { runCatching { CalendarMode.valueOf(it) }.getOrNull() } ?: CalendarMode.MONTH)
    private val selected = MutableStateFlow(handle.get<Long>("sel")?.let(LocalDate::ofEpochDay) ?: clock.today())

    /** Load window derived from mode + selection; changes only when the visible span does. */
    private val window = combine(mode, selected, time) { m, s, t -> Triple(windowFor(m, s), t.zone, t.firstDayOfWeek) }.distinctUntilChanged()

    // Grouping, sorting and recurrence expansion run off the main thread, so a month change or a tab
    // switch never waits for them; the result is collected on the main thread as before.
    val state: StateFlow<CalendarUiState> = window.flatMapLatest { (range, z, _) ->
        val (from, to) = range
        val eventsByDate = events.occurrences(from, to, z).map { groupByDate(it, from, to) }
        val tasksByDate = tasks.openTasksInRange(from, to).map { all -> all.groupBy { it.dueDate!! } }
        // Selection and clock updates reuse the grouped data. Only the local date matters here;
        // countdowns have their own ticker, so minute ticks need not rebuild calendar state.
        val today = tick.map { it.atZone(z).toLocalDate() }.distinctUntilChanged()
        combine(eventsByDate, tasksByDate, mode, selected, today) { byEvents, byTasks, m, s, currentDate ->
            CalendarUiState(
                mode = m, selected = s, today = currentDate,
                byDate = byEvents,
                tasksByDate = byTasks,
                loadedFrom = from, loadedTo = to, isLoading = false,
            )
        }
    }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState(mode.value, selected.value, clock.today()))

    fun setMode(m: CalendarMode) { mode.value = m; handle["mode"] = m.name }

    fun select(d: LocalDate) { selected.value = d; handle["sel"] = d.toEpochDay() }

    fun today() = select(clock.today())

    fun step(direction: Int) {
        val s = selected.value
        select(
            when (mode.value) {
                CalendarMode.MONTH -> s.plusMonths(direction.toLong()).withDayOfMonth(1).let { if (YearMonth.from(it) == YearMonth.from(clock.today())) clock.today() else it }
                CalendarMode.WEEK -> s.plusWeeks(direction.toLong())
                CalendarMode.DAY -> s.plusDays(direction.toLong())
                CalendarMode.AGENDA -> s.plusWeeks(direction.toLong())
            },
        )
    }

    fun setTaskCompleted(id: String, done: Boolean, onResult: (app.daysemble.data.repo.TaskRepository.Completion) -> Unit = {}) = viewModelScope.launch { tasks.setCompleted(id, done)?.let(onResult) }

    private fun windowFor(m: CalendarMode, s: LocalDate): Pair<LocalDate, LocalDate> = when (m) {
        // Previous..next month grids so swiping never shows empty cells.
        CalendarMode.MONTH -> {
            val ym = YearMonth.from(s)
            gridStart(ym.minusMonths(1)) to gridStart(ym.plusMonths(1)).plusDays(41)
        }
        CalendarMode.WEEK -> weekStart(s).minusWeeks(1) to weekStart(s).plusWeeks(2).minusDays(1)
        CalendarMode.DAY -> s.minusDays(1) to s.plusDays(1)
        CalendarMode.AGENDA -> s to s.plusDays(AGENDA_DAYS - 1L)
    }

    fun weekStart(d: LocalDate): LocalDate = d.minusDays((((d.dayOfWeek.value - firstDayOfWeek().value) + 7) % 7).toLong())
    fun gridStart(ym: YearMonth): LocalDate = weekStart(ym.atDay(1))

    companion object {
        const val AGENDA_DAYS = 60

        fun groupByDate(occ: List<EventOccurrence>, from: LocalDate, to: LocalDate): Map<LocalDate, List<EventOccurrence>> {
            val out = HashMap<LocalDate, MutableList<EventOccurrence>>()
            for (o in occ) {
                var d = maxOf(o.startDate, from)
                val lastExclusive = minOf(o.endDate, to.plusDays(1))
                // Zero-length or same-day events still land on their start date.
                if (!d.isBefore(lastExclusive)) { if (o.startDate in from..to) out.getOrPut(o.startDate) { mutableListOf() } += o; continue }
                while (d.isBefore(lastExclusive)) { out.getOrPut(d) { mutableListOf() } += o; d = d.plusDays(1) }
            }
            return out
        }
    }
}
