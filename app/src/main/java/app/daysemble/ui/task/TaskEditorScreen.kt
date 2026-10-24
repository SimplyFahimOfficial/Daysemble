package app.daysemble.ui.task

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.R
import app.daysemble.domain.model.Priority
import app.daysemble.ui.LocalSnackbar
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsChip
import app.daysemble.ui.components.DsConfirmDialog
import app.daysemble.ui.components.DsDatePickerDialog
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.components.DsSegmented
import app.daysemble.ui.components.DsTextField
import app.daysemble.ui.components.DsTimePickerDialog
import app.daysemble.ui.components.DsTopBar
import app.daysemble.ui.components.DsValueChip
import app.daysemble.ui.components.HairlineDivider
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.components.TaskRow
import app.daysemble.ui.components.priorityLabel
import app.daysemble.ui.components.ReminderPicker
import app.daysemble.ui.components.TaskReminderOptions
import app.daysemble.ui.daysembleViewModel
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.dayLabel
import app.daysemble.ui.format.rememberMinuteTicker
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun TaskEditorRoute(
    taskId: String?, parentId: String?, initialDue: LocalDate?,
    /** Called when the screen closes itself (after saving, deleting, or when its item is gone). Defaults to [onClose]. */
    onDone: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val finish = onDone ?: onClose
    val vm = daysembleViewModel(key = "task:$taskId:$parentId") { c, h ->
        TaskEditorViewModel(c.tasks, { c.newTaskListId() }, taskId, parentId, initialDue, h)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val event by vm.events.collectAsStateWithLifecycle()
    val onDeleted = app.daysemble.ui.LocalTaskDeleted.current
    LaunchedEffect(event) {
        when (event) {
            EditorEvent.Saved -> { vm.consumeEvent(); finish() }
            EditorEvent.Deleted -> {
                vm.consumeEvent()
                taskId?.let(onDeleted)
                finish()
            }
            null -> Unit
        }
    }
    LaunchedEffect(state.isMissing) { if (state.isMissing) finish() }
    TaskEditorScreen(state, vm, onClose)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskEditorScreen(state: TaskEditorUiState, vm: TaskEditorViewModel, onClose: () -> Unit) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    val now = rememberMinuteTicker()
    val today = now.atZone(p.zone).toLocalDate()
    val f = state.form
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }

    val attemptClose = { if (state.isDirty) confirmDiscard = true else onClose() }
    BackHandler(enabled = state.isDirty) { confirmDiscard = true }
    LaunchedEffect(state.isNew) { if (state.isNew) runCatching { titleFocus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .imePadding(),
    ) {
        DsTopBar(
            title = stringResource(if (state.isNew) R.string.new_task else R.string.task),
            onBack = attemptClose,
        ) {
            if (!state.isNew) DsIconButton(Icons.Rounded.DeleteOutline, stringResource(R.string.action_delete), { confirmDelete = true })
            DsButton(
                stringResource(R.string.action_save), vm::save,
                modifier = Modifier.padding(end = Space.s),
                enabled = state.isNew || state.isDirty,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = Space.x3),
        ) {
            DsTextField(
                value = f.title, onValueChange = { v -> vm.update { it.copy(title = v) } },
                placeholder = stringResource(R.string.task_title_hint),
                error = if (state.showTitleError) stringResource(R.string.error_title_required) else null,
                large = true, imeAction = ImeAction.Done, focusRequester = titleFocus,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
            )
            if (state.task != null) {
                Row(Modifier.padding(horizontal = Space.gutter - Space.xs)) {
                    DsChip(
                        stringResource(if (state.task.isCompleted) R.string.action_mark_incomplete else R.string.action_complete),
                        selected = state.task.isCompleted, onClick = vm::toggleCompleted,
                        role = androidx.compose.ui.semantics.Role.Checkbox,
                    )
                }
            }

            SectionHeader(stringResource(R.string.field_due), Modifier.padding(top = Space.l))
            FlowRow(
                Modifier.padding(horizontal = Space.gutter),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                DsValueChip(
                    f.dueDate?.let { dayLabel(it, today, p) } ?: stringResource(R.string.no_date),
                    { pickDate = true }, icon = Icons.Rounded.CalendarToday, emphasized = f.dueDate != null,
                    contentDescription = stringResource(R.string.cd_pick_due_date),
                )
                if (f.dueDate != null) {
                    DsValueChip(
                        f.dueTime?.let { Fmt.time(it, p) } ?: stringResource(R.string.add_time),
                        { pickTime = true }, icon = Icons.Rounded.Schedule, emphasized = f.dueTime != null,
                        contentDescription = stringResource(R.string.cd_pick_due_time),
                    )
                    DsIconButton(Icons.Rounded.Close, stringResource(R.string.cd_clear_due), { vm.update { it.copy(dueEpochDay = null, dueMinutes = null) } }, tint = c.textSecondary)
                }
            }
            if (f.dueDate == null) {
                Row(Modifier.padding(horizontal = Space.gutter - Space.xs, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    DsChip(stringResource(R.string.day_today), false, { vm.update { it.copy(dueEpochDay = today.toEpochDay()) } })
                    DsChip(stringResource(R.string.day_tomorrow), false, { vm.update { it.copy(dueEpochDay = today.plusDays(1).toEpochDay()) } })
                }
            }
            if (state.isGoogle && (f.dueTime != null)) {
                Note(stringResource(R.string.google_task_time_local))
            }

            SectionHeader(stringResource(R.string.field_reminders), Modifier.padding(top = Space.l))
            ReminderPicker(
                selected = f.reminders, options = TaskReminderOptions, forTask = true,
                onChange = { r -> vm.update { it.copy(reminders = r) } },
                enabledReason = if (f.dueDate == null) stringResource(R.string.reminder_needs_due) else null,
            )
            if (f.dueDate != null && f.dueTime == null && f.reminders.isNotEmpty()) {
                Note(stringResource(R.string.reminder_task_default_time))
            }

            SectionHeader(stringResource(R.string.field_priority), Modifier.padding(top = Space.l))
            DsSegmented(
                options = Priority.entries, selected = Priority.of(f.priority),
                label = { stringResource(priorityLabel(it)) },
                onSelect = { pr -> vm.update { it.copy(priority = pr.level) } },
                modifier = Modifier.padding(horizontal = Space.gutter),
            )
            if (state.isGoogle && f.priority > 0) Note(stringResource(R.string.google_task_priority_local))

            if (state.lists.size > 1 && state.task?.parentId == null && parentIdOf(state) == null) {
                SectionHeader(stringResource(R.string.field_list), Modifier.padding(top = Space.l))
                FlowRow(Modifier.padding(horizontal = Space.gutter - Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    state.lists.forEach { l -> DsChip(l.title, f.listId == l.id, { vm.update { it.copy(listId = l.id) } }) }
                }
            }

            SectionHeader(stringResource(R.string.field_notes), Modifier.padding(top = Space.l))
            DsTextField(
                value = f.notes, onValueChange = { v -> vm.update { it.copy(notes = v) } },
                placeholder = stringResource(R.string.notes_hint), singleLine = false,
                modifier = Modifier.padding(horizontal = Space.gutter),
            )

            if (!state.isNew && state.task?.parentId == null) {
                SectionHeader(stringResource(R.string.field_subtasks), Modifier.padding(top = Space.l))
                // New subtasks fade in and the list grows/shrinks smoothly instead of jumping.
                // Subtasks already there when the task loads (same emission as the task) appear without motion.
                val m = DsTheme.motion
                val loadedIds = remember(state.task?.id) { state.subtasks.map { it.id }.toSet() }
                Column(if (m.reduced) Modifier else Modifier.animateContentSize(m.standard(m.medium))) {
                    state.subtasks.forEach { s ->
                        androidx.compose.runtime.key(s.id) {
                            val shown = remember { androidx.compose.animation.core.MutableTransitionState(s.id in loadedIds) }
                            androidx.compose.runtime.LaunchedEffect(Unit) { shown.targetState = true }
                            androidx.compose.animation.AnimatedVisibility(shown, enter = if (m.reduced) fadeIn(m.enter()) else fadeIn(m.enter()) + expandVertically(m.enter())) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TaskRow(s, now, onToggle = { vm.toggleSubtask(s.id, it) }, onClick = { vm.toggleSubtask(s.id, !s.isCompleted) }, showDue = false, modifier = Modifier.weight(1f))
                                        DsIconButton(Icons.Rounded.Close, stringResource(R.string.cd_delete_subtask, s.title), { vm.deleteSubtask(s.id) }, tint = c.textTertiary)
                                    }
                                    HairlineDivider(inset = true)
                                }
                            }
                        }
                    }
                }
                AddSubtaskField(onAdd = vm::addSubtask)
            }

            if (state.task?.isGoogle == true) {
                Row(
                    Modifier.padding(horizontal = Space.gutter, vertical = Space.xl),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Icon(Icons.Rounded.CloudQueue, null, tint = c.textTertiary, modifier = Modifier.padding(top = 1.dp))
                    Text(stringResource(R.string.google_task_badge), style = DsTheme.type.label, color = c.textSecondary)
                }
            }
        }
    }

    if (pickDate) {
        DsDatePickerDialog(
            initial = f.dueDate ?: today,
            onPick = { d -> vm.update { it.copy(dueEpochDay = d.toEpochDay()) } },
            onDismiss = { pickDate = false },
            onClear = if (f.dueDate != null) ({ vm.update { it.copy(dueEpochDay = null, dueMinutes = null) } }) else null,
        )
    }
    if (pickTime) {
        DsTimePickerDialog(
            initial = f.dueTime ?: LocalTime.of(9, 0),
            onPick = { t -> vm.update { it.copy(dueMinutes = t.hour * 60 + t.minute) } },
            onDismiss = { pickTime = false },
            onClear = if (f.dueTime != null) ({ vm.update { it.copy(dueMinutes = null) } }) else null,
        )
    }
    if (confirmDiscard) {
        DsConfirmDialog(
            title = stringResource(R.string.discard_changes_title), body = null,
            confirm = stringResource(R.string.action_discard), onConfirm = onClose,
            onDismiss = { confirmDiscard = false }, destructive = true,
        )
    }
    if (confirmDelete) {
        DsConfirmDialog(
            title = stringResource(R.string.delete_task_title),
            body = stringResource(if (state.task?.isGoogle == true) R.string.delete_task_google_body else R.string.delete_task_body),
            confirm = stringResource(R.string.action_delete), onConfirm = vm::delete,
            onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
}

/** Subtasks stay in their parent's list, so they get no list chooser. */
private fun parentIdOf(state: TaskEditorUiState): String? = state.task?.parentId

@Composable
private fun Note(text: String) {
    Text(
        text, style = DsTheme.type.label, color = DsTheme.colors.textSecondary,
        modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xs),
    )
}

@Composable
private fun AddSubtaskField(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.gutter, end = Space.s, top = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DsTextField(
            value = text, onValueChange = { text = it },
            placeholder = stringResource(R.string.add_subtask_hint),
            imeAction = ImeAction.Done,
            onImeAction = { onAdd(text); text = "" },
            modifier = Modifier.weight(1f),
        )
        DsIconButton(Icons.Rounded.Add, stringResource(R.string.cd_add_subtask), { onAdd(text); text = "" }, enabled = text.isNotBlank())
    }
}
