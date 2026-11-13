package app.daysemble.ui.tasks

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.daysemble.core.time.AppClock
import app.daysemble.data.prefs.SettingsRepository
import app.daysemble.data.prefs.TaskSort
import app.daysemble.data.repo.TaskRepository
import app.daysemble.domain.TaskBucket
import app.daysemble.domain.TaskFilter
import app.daysemble.domain.TaskRules
import app.daysemble.domain.model.Task
import app.daysemble.domain.model.TaskList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@Immutable
data class TaskSection(val bucket: TaskBucket?, val tasks: List<Task>)

@Immutable
data class TasksUiState(
    val filter: TaskFilter = TaskFilter.ALL,
    val sort: TaskSort = TaskSort.DUE,
    val listId: String? = null,
    val lists: List<TaskList> = emptyList(),
    val sections: List<TaskSection> = emptyList(),
    val totalOpen: Int = 0,
    /** How many tasks each filter shows (the list chosen is respected); drives the counts on the chips. */
    val counts: Map<TaskFilter, Int> = emptyMap(),
    val isLoading: Boolean = true,
) {
    /** Drag-to-reorder is offered in "List order" when only open tasks are shown. */
    val canReorder: Boolean get() = sort == TaskSort.MANUAL && filter == TaskFilter.ALL
}

private data class TaskQuery(val filter: TaskFilter, val listId: String?)

class TasksViewModel(
    private val repo: TaskRepository,
    private val settings: SettingsRepository,
    private val clock: AppClock,
    private val time: kotlinx.coroutines.flow.StateFlow<app.daysemble.core.time.TimeContext>,
    private val handle: SavedStateHandle,
    private val compute: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) : ViewModel() {
    private fun zone(): ZoneId = time.value.zone

    private val query = MutableStateFlow(
        TaskQuery(
            filter = handle.get<String>("f")?.let { runCatching { TaskFilter.valueOf(it) }.getOrNull() } ?: TaskFilter.ALL,
            listId = handle["l"],
        ),
    )
    private val tick = MutableStateFlow(clock.now())

    // Grouping, sorting and recurrence expansion run off the main thread, so a month change or a tab
    // switch never waits for them; the result is collected on the main thread as before.
    val state: StateFlow<TasksUiState> = combine(
        combine(repo.allTasks, repo.taskLists) { a, b -> a to b },
        settings.settings.map { it.taskSort }.distinctUntilChanged(), query, tick, time,
    ) { (tasks, lists), sort, q, now, _ -> build(tasks, lists, sort, q, now) }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState())

    fun setFilter(f: TaskFilter) = update { it.copy(filter = f) }.also { handle["f"] = f.name }
    fun setList(id: String?) = update { it.copy(listId = id) }.also { handle["l"] = id }
    fun setSort(s: TaskSort) = viewModelScope.launch { settings.setTaskSort(s) }
    fun onTick(now: Instant) { tick.value = now }

    fun setCompleted(id: String, done: Boolean, onResult: (app.daysemble.data.repo.TaskRepository.Completion) -> Unit = {}) = viewModelScope.launch { repo.setCompleted(id, done)?.let(onResult) }
    fun restore(id: String) = viewModelScope.launch { repo.restore(id) }
    fun reorder(ids: List<String>) = viewModelScope.launch { repo.reorder(ids) }

    private fun update(f: (TaskQuery) -> TaskQuery) { query.value = f(query.value) }

    private fun build(all: List<Task>, lists: List<TaskList>, sort: TaskSort, q: TaskQuery, now: Instant): TasksUiState {
        val z = zone()
        val listId = q.listId?.takeIf { id -> lists.any { it.id == id } }
        val candidates = all.filter { t ->
            (listId == null || t.listId == listId) &&
                // Subtasks show inside their parent.
                t.parentId == null &&
                TaskRules.matches(t, q.filter, now, z)
        }.sortedWith(TaskRules.comparator(sort, z))

        val sections = if (q.filter == TaskFilter.ALL && sort == TaskSort.DUE) {
            val order = listOf(TaskBucket.OVERDUE, TaskBucket.TODAY, TaskBucket.UPCOMING, TaskBucket.NO_DATE)
            val grouped = candidates.groupBy { TaskRules.bucket(it, now, z) }
            order.mapNotNull { b -> grouped[b]?.let { TaskSection(b, it) } }
        } else listOf(TaskSection(null, candidates)).filter { it.tasks.isNotEmpty() }

        return TasksUiState(
            filter = q.filter, sort = sort, listId = listId, lists = lists,
            sections = sections,
            totalOpen = all.count { !it.isCompleted && it.parentId == null },
            counts = listOf(TaskFilter.TODAY, TaskFilter.UPCOMING, TaskFilter.OVERDUE).associateWith { f ->
                all.count { t -> t.parentId == null && (listId == null || t.listId == listId) && TaskRules.matches(t, f, now, z) }
            },
            isLoading = false,
        )
    }
}
