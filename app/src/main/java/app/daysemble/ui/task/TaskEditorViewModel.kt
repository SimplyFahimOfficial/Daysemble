package app.daysemble.ui.task

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.daysemble.data.repo.TaskRepository
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.Task
import app.daysemble.domain.model.TaskDraft
import app.daysemble.domain.model.TaskList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime

/** Editable form; serialisable so drafts survive process death via SavedStateHandle. */
@Serializable
data class TaskForm(
    val title: String = "",
    val notes: String = "",
    val listId: String? = null,
    val dueEpochDay: Long? = null,
    val dueMinutes: Int? = null,
    val priority: Int = 0,
    val reminders: List<Int> = emptyList(),
) {
    val dueDate: LocalDate? get() = dueEpochDay?.let(LocalDate::ofEpochDay)
    val dueTime: LocalTime? get() = dueMinutes?.let { LocalTime.of(it / 60, it % 60) }

    fun toDraft(parentId: String?) = TaskDraft(
        title = title, notes = notes, listId = listId, parentId = parentId,
        dueDate = dueDate, dueTime = if (dueDate == null) null else dueTime,
        priority = Priority.of(priority), reminderMinutesBefore = reminders,
    )

    companion object {
        fun from(t: Task, reminders: List<Int>) = TaskForm(
            title = t.title, notes = t.notes, listId = t.listId, dueEpochDay = t.dueDate?.toEpochDay(),
            dueMinutes = t.dueTime?.let { it.hour * 60 + it.minute }, priority = t.priority.level, reminders = reminders,
        )
    }
}

@Immutable
data class TaskEditorUiState(
    val isNew: Boolean,
    val form: TaskForm,
    val original: TaskForm?,
    val task: Task?,
    val subtasks: List<Task> = emptyList(),
    val lists: List<TaskList> = emptyList(),
    val showTitleError: Boolean = false,
    val isMissing: Boolean = false,
    val isLoading: Boolean = true,
) {
    val isDirty: Boolean get() = if (isNew) form.copy(listId = null) != TaskForm(dueEpochDay = form.dueEpochDay) || form.title.isNotBlank()
        else original != null && form != original
    val isGoogle: Boolean get() = task?.isGoogle ?: lists.firstOrNull { it.id == form.listId }?.isGoogle ?: false
}

sealed interface EditorEvent {
    data object Saved : EditorEvent
    data object Deleted : EditorEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class TaskEditorViewModel(
    private val repo: TaskRepository,
    private val defaultListId: suspend () -> String,
    private val taskId: String?,
    private val parentId: String?,
    initialDue: LocalDate?,
    private val handle: SavedStateHandle,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val form = MutableStateFlow(
        handle.get<String>(KEY_FORM)?.let { runCatching { json.decodeFromString<TaskForm>(it) }.getOrNull() }
            ?: TaskForm(dueEpochDay = initialDue?.toEpochDay()),
    )
    private val original = MutableStateFlow<TaskForm?>(handle.get<String>(KEY_ORIGINAL)?.let { runCatching { json.decodeFromString<TaskForm>(it) }.getOrNull() })
    private val titleError = MutableStateFlow(false)
    val events = MutableStateFlow<EditorEvent?>(null)

    init {
        viewModelScope.launch {
            if (taskId != null && original.value == null) {
                val t = repo.get(taskId)
                if (t != null) {
                    val f = TaskForm.from(t, repo.reminders(taskId))
                    original.value = f; form.value = f
                    persist()
                }
            } else if (taskId == null && form.value.listId == null) {
                val id = defaultListId()
                update { it.copy(listId = it.listId ?: id) } // read form after suspending: keeps typed text
            }
        }
    }

    val state: StateFlow<TaskEditorUiState> = combine(
        form, original, titleError,
        if (taskId != null) repo.observeTask(taskId) else flowOf(null),
        combine(if (taskId != null) repo.observeSubtasks(taskId) else flowOf(emptyList()), repo.taskLists) { a, b -> a to b },
    ) { f, o, err, task, (subs, lists) ->
        TaskEditorUiState(
            isNew = taskId == null, form = f, original = o, task = task, subtasks = subs, lists = lists,
            showTitleError = err, isMissing = taskId != null && task == null && o != null,
            isLoading = taskId != null && o == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskEditorUiState(taskId == null, form.value, original.value, null))

    fun update(f: (TaskForm) -> TaskForm) {
        setForm(f(form.value))
        if (form.value.title.isNotBlank()) titleError.value = false
    }

    fun save() = viewModelScope.launch {
        val f = form.value
        if (f.title.isBlank()) { titleError.value = true; return@launch }
        if (taskId == null) repo.create(f.toDraft(parentId), f.listId ?: defaultListId())
        else {
            repo.update(taskId, f.toDraft(null))
            val newList = f.listId
            if (newList != null && newList != original.value?.listId) repo.moveToList(taskId, newList)
        }
        original.value = form.value
        persist()
        events.value = EditorEvent.Saved
    }

    fun toggleCompleted() = viewModelScope.launch {
        val t = state.value.task ?: return@launch
        repo.setCompleted(t.id, !t.isCompleted)
    }

    fun delete() = viewModelScope.launch {
        taskId?.let { repo.delete(it) }
        events.value = EditorEvent.Deleted
    }

    fun restore() = viewModelScope.launch { taskId?.let { repo.restore(it) } }

    fun addSubtask(title: String) = viewModelScope.launch {
        val parent = taskId ?: return@launch
        if (title.isBlank()) return@launch
        repo.create(TaskDraft(title = title, parentId = parent), state.value.task?.listId ?: defaultListId())
    }

    fun toggleSubtask(id: String, done: Boolean) = viewModelScope.launch { repo.setCompleted(id, done) }
    fun deleteSubtask(id: String) = viewModelScope.launch { repo.delete(id) }

    fun consumeEvent() { events.value = null }

    private fun setForm(f: TaskForm) { form.value = f; persist() }

    private fun persist() {
        handle[KEY_FORM] = json.encodeToString(TaskForm.serializer(), form.value)
        original.value?.let { handle[KEY_ORIGINAL] = json.encodeToString(TaskForm.serializer(), it) }
    }

    private companion object {
        const val KEY_FORM = "task_form"
        const val KEY_ORIGINAL = "task_original"
    }

}
