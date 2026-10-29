package app.daysemble.ui.task

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.daysemble.core.recurrence.OccurrenceHistory
import app.daysemble.core.recurrence.OccurrenceHistoryCalc
import app.daysemble.core.time.AppClock
import app.daysemble.core.time.TimeContext
import app.daysemble.data.repo.TaskRepository
import app.daysemble.domain.model.SeriesScope
import app.daysemble.domain.model.Task
import app.daysemble.domain.model.TaskList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

@Immutable
data class TaskDetailUiState(
    val task: Task? = null,
    val subtasks: List<Task> = emptyList(),
    val list: TaskList? = null,
    val reminders: List<Int> = emptyList(),
    /** The series' stored history; null for one-off tasks. */
    val history: OccurrenceHistory? = null,
    /** The series' rule, also after it stopped repeating is false (null then). */
    val series: TaskRepository.SeriesData? = null,
    val today: LocalDate,
    val isLoading: Boolean = true,
    /** The task was deleted (or never existed): the screen closes. */
    val isMissing: Boolean = false,
) {
    val isSeries: Boolean get() = task?.seriesId != null && series != null
}

/**
 * A task's detail view. It follows its series: after the shown occurrence of a repeating task is
 * completed here, the screen moves on to the occurrence that completion created (the countdown then
 * counts to that one). If that next occurrence goes away again (Undo), it returns to the original.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModel(
    private val repo: TaskRepository,
    private val clock: AppClock,
    private val time: StateFlow<TimeContext>,
    private val initialId: String,
    private val handle: SavedStateHandle,
    compute: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) : ViewModel() {
    private val focus = MutableStateFlow(handle.get<String>(KEY_FOCUS) ?: initialId)
    private var previous: String? = handle.get<String>(KEY_PREVIOUS)
    private val tick = MutableStateFlow(clock.now())
    /** Moving to another occurrence: the old one being gone doesn't close the screen meanwhile. */
    private val following = MutableStateFlow(false)

    val focusedId: String get() = focus.value

    val state: StateFlow<TaskDetailUiState> = focus.flatMapLatest { id ->
        repo.observeTask(id).flatMapLatest { task ->
            val series: Flow<TaskRepository.SeriesData?> = task?.seriesId?.let(repo::observeSeries) ?: flowOf(null)
            combine(
                combine(repo.observeSubtasks(id), repo.observeReminders(id)) { a, b -> a to b },
                repo.taskLists, series, tick, time,
            ) { (subs, reminders), lists, s, now, t -> build(task, subs, reminders, lists, s, now, t) }
        }
    }.flowOn(compute).let { inner ->
        combine(inner, following) { st, f -> if (f && st.isMissing) st.copy(isMissing = false, isLoading = true) else st }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState(today = clock.today()))

    init {
        // The focused occurrence disappeared (e.g. "Undo" removed the one completing created): go back.
        viewModelScope.launch {
            state.map { it.isMissing }.collect { missing ->
                val back = previous
                if (missing && back != null) { previous = null; handle[KEY_PREVIOUS] = null; setFocus(back) }
            }
        }
    }

    fun onTick(now: Instant) { tick.value = now }

    private fun build(
        task: Task?, subs: List<Task>, reminders: List<Int>, lists: List<TaskList>,
        s: TaskRepository.SeriesData?, now: Instant, t: TimeContext,
    ): TaskDetailUiState {
        val today = now.atZone(t.zone).toLocalDate()
        if (task == null) return TaskDetailUiState(today = today, isLoading = false, isMissing = true)
        val history = s?.let {
            OccurrenceHistoryCalc.build(it.rule, it.anchor, it.rows, it.skips, now, t.zone, horizonEnd = today.plusWeeks(4))
        }
        return TaskDetailUiState(
            task = task, subtasks = subs, list = lists.firstOrNull { it.id == task.listId }, reminders = reminders,
            history = history, series = s, today = today, isLoading = false,
        )
    }

    /** Completes or reopens the shown task; completing a repeating one moves the screen to the next occurrence. */
    fun toggleCompleted(onResult: (String, TaskRepository.Completion) -> Unit = { _, _ -> }) = viewModelScope.launch {
        val t = state.value.task ?: return@launch
        val done = !t.isCompleted
        val r = repo.setCompleted(t.id, done) ?: return@launch
        if (done) {
            onResult(t.id, r)
            r.nextId?.takeIf { r.repeating && it != t.id }?.let { next -> previous = t.id; handle[KEY_PREVIOUS] = t.id; setFocus(next) }
        }
    }

    /** Skips the shown (open) occurrence of a repeating task: it is deleted, the next one comes. */
    fun skip(onDeleted: (String) -> Unit) = viewModelScope.launch {
        val t = state.value.task ?: return@launch
        val seriesId = t.seriesId
        following.value = seriesId != null
        repo.delete(t.id, SeriesScope.THIS)
        onDeleted(t.id)
        // Follow the series to its new current occurrence, if there is one.
        val next = seriesId?.let { sid -> repo.observeSeries(sid).first() }
            ?.rows?.filter { !it.isDeleted && !it.isCompleted }?.maxByOrNull { it.date ?: LocalDate.MIN }?.id
        if (next != null) { previous = null; setFocus(next) }
        following.value = false
    }

    fun delete(scope: SeriesScope, onDeleted: (String) -> Unit) = viewModelScope.launch {
        val t = state.value.task ?: return@launch
        previous = null
        repo.delete(t.id, scope)
        onDeleted(t.id)
    }

    fun setCountdown(on: Boolean) = viewModelScope.launch { state.value.task?.let { repo.setCountdown(it.id, on) } }

    fun toggleSubtask(id: String, done: Boolean) = viewModelScope.launch { repo.setCompleted(id, done) }

    private fun setFocus(id: String) {
        focus.value = id
        handle[KEY_FOCUS] = id
    }

    private companion object {
        const val KEY_FOCUS = "detail_focus"
        const val KEY_PREVIOUS = "detail_previous"
    }
}
