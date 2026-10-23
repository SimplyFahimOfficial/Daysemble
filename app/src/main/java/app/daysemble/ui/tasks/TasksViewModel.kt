package app.daysemble.ui.tasks

import androidx.compose.runtime.Immutable
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@Immutable
data class TaskSection(val bucket: TaskBucket?, val tasks: List<Task>)

@Immutable
data class TasksUiState(
    val query: String = "",
    val filter: TaskFilter = TaskFilter.ALL,
    val sort: TaskSort = TaskSort.DUE,
    val listId: String? = null,
    val lists: List<TaskList> = emptyList(),
    val sections: List<TaskSection> = emptyList(),
    val totalOpen: Int = 0,
    val isLoading: Boolean = true,
) {
    /** Drag-to-reorder is offered in "List order" when not searching and only open tasks are shown. */
    val canReorder: Boolean get() = sort == TaskSort.MANUAL && query.isBlank() && filter == TaskFilter.ALL
}

private data class TaskQuery(val query: String, val filter: TaskFilter, val listId: String?)

class TasksViewModel(
    private val repo: TaskRepository,
    private val settings: SettingsRepository,
    private val clock: AppClock,
    private val time: kotlinx.coroutines.flow.StateFlow<app.daysemble.core.time.TimeContext>,
    private val handle: SavedStateHandle,
) : ViewModel() {
    private fun zone(): ZoneId = time.value.zone

    private val query = MutableStateFlow(
        TaskQuery(
            query = handle["q"] ?: "",
            filter = handle.get<String>("f")?.let { runCatching { TaskFilter.valueOf(it) }.getOrNull() } ?: TaskFilter.ALL,
            listId = handle["l"],
        ),
    )
    private val tick = MutableStateFlow(clock.now())

    val state: StateFlow<TasksUiState> = combine(
        combine(repo.allTasks, repo.taskLists) { a, b -> a to b }, settings.settings, query, tick, time,
    ) { (tasks, lists), s, q, now, _ -> build(tasks, lists, s.taskSort, q, now) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState())

    fun setQuery(q: String) = update { it.copy(query = q) }.also { handle["q"] = q }
    fun setFilter(f: TaskFilter) = update { it.copy(filter = f) }.also { handle["f"] = f.name }
    fun setList(id: String?) = update { it.copy(listId = id) }.also { handle["l"] = id }
    fun setSort(s: TaskSort) = viewModelScope.launch { settings.setTaskSort(s) }
    fun onTick(now: Instant) { tick.value = now }

    fun setCompleted(id: String, done: Boolean) = viewModelScope.launch { repo.setCompleted(id, done) }
    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
    fun restore(id: String) = viewModelScope.launch { repo.restore(id) }
    fun purgeTombstones() = viewModelScope.launch { repo.purgeExpiredTombstones() }
    fun reorder(ids: List<String>) = viewModelScope.launch { repo.reorder(ids) }

    private fun update(f: (TaskQuery) -> TaskQuery) { query.value = f(query.value) }

    private fun build(all: List<Task>, lists: List<TaskList>, sort: TaskSort, q: TaskQuery, now: Instant): TasksUiState {
        val z = zone()
        val listId = q.listId?.takeIf { id -> lists.any { it.id == id } }
        val searching = q.query.isNotBlank()
        val candidates = all.filter { t ->
            (listId == null || t.listId == listId) &&
                // Subtasks show inside their parent, except when searching.
                (t.parentId == null || searching) &&
                TaskRules.matchesQuery(t, q.query) &&
                (if (searching && q.filter == TaskFilter.ALL) true else TaskRules.matches(t, q.filter, now, z))
        }.sortedWith(TaskRules.comparator(sort, z))

        val sections = if (q.filter == TaskFilter.ALL && sort == TaskSort.DUE && !searching) {
            val order = listOf(TaskBucket.OVERDUE, TaskBucket.TODAY, TaskBucket.UPCOMING, TaskBucket.NO_DATE)
            val grouped = candidates.groupBy { TaskRules.bucket(it, now, z) }
            order.mapNotNull { b -> grouped[b]?.let { TaskSection(b, it) } }
        } else listOf(TaskSection(null, candidates)).filter { it.tasks.isNotEmpty() }

        return TasksUiState(
            query = q.query, filter = q.filter, sort = sort, listId = listId, lists = lists,
            sections = sections,
            totalOpen = all.count { !it.isCompleted && it.parentId == null },
            isLoading = false,
        )
    }
}
