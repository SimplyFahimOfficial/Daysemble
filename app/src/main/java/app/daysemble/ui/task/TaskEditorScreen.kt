package app.daysemble.ui.task

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.HourglassTop
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
import app.daysemble.ui.components.DsSwitchRow
import app.daysemble.ui.components.LiveCountdown
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
    initialDueMinute: Int? = null,
    initialTitle: String? = null,
    /** Called when the screen closes itself (after saving, deleting, or when its item is gone). Defaults to [onClose]. */
    onDone: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val finish = onDone ?: onClose
    val vm = daysembleViewModel(key = "task:$taskId:$parentId") { c, h ->
        TaskEditorViewModel(c.tasks, { c.newTaskListId() }, taskId, parentId, initialDue, h, initialDueMinute, initialTitle)
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
    var pickUntil by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var askSaveScope by rememberSaveable { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    val onCompleted = app.daysemble.ui.LocalTaskCompleted.current
    val m = DsTheme.motion
    val save: () -> Unit = { if (vm.needsScope()) askSaveScope = true else vm.save() }
    // Choosing a repeat without a due date dates the task today: a repeat counts from its due date.
    val setRepeat: (TaskRepeatForm) -> Unit = { r ->
        vm.update { old -> (if (old.dueEpochDay == null && r.repeats) old.withDue(today.toEpochDay()) else old).copy(repeat = r) }
    }

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
                stringResource(R.string.action_save), save,
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
                        selected = state.task.isCompleted, onClick = { vm.toggleCompleted(onCompleted) },
                        role = androidx.compose.ui.semantics.Role.Checkbox,
                    )
                }
            }
            // The live countdown: this task's detail view. It follows the due date as it's edited and
            // only this block ticks (once a second, while the screen is visible).
            val deadline = f.deadline(p.zone)
            AnimatedVisibility(f.countdown && deadline != null, enter = m.expandIn(), exit = m.collapseOut()) {
                LiveCountdown(
                    deadline, completed = state.task?.isCompleted == true,
                    modifier = Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.m),
                )
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
                    DsIconButton(Icons.Rounded.Close, stringResource(R.string.cd_clear_due), { vm.update { it.withDue(null) } }, tint = c.textSecondary)
                }
            }
            if (f.dueDate == null) {
                Row(Modifier.padding(horizontal = Space.gutter - Space.xs, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    DsChip(stringResource(R.string.day_today), false, { vm.update { it.withDue(today.toEpochDay()) } })
                    DsChip(stringResource(R.string.day_tomorrow), false, { vm.update { it.withDue(today.plusDays(1).toEpochDay()) } })
                }
            }
            if (state.isGoogle && (f.dueTime != null)) {
                Note(stringResource(R.string.google_task_time_local))
            }
            if (state.isRecurring && f.dueDate == null) {
                Note(stringResource(R.string.repeat_needs_due))
            }

            if (state.isPastOccurrence) {
                SectionHeader(stringResource(R.string.field_repeat), Modifier.padding(top = Space.l))
                f.repeat.toRRule(f.dueDate)?.let { Note(repeatLabel(it)) }
                Note(stringResource(R.string.repeat_past_occurrence))
            } else if (!state.isSubtask) {
                SectionHeader(stringResource(R.string.field_repeat), Modifier.padding(top = Space.l))
                TaskRepeatSection(
                    form = f.repeat, due = f.dueDate, onChange = setRepeat, onPickUntil = { pickUntil = true },
                    disabledReason = if (state.inGoogleListAfterSave) stringResource(R.string.repeat_google_unavailable) else null,
                )
            }

            SectionHeader(stringResource(R.string.field_duration), Modifier.padding(top = Space.l))
            DurationPicker(f.durationMinutes, onChange = { v -> vm.update { it.copy(durationMinutes = v) } })
            Note(
                when {
                    f.durationMinutes == null -> stringResource(R.string.duration_none_note)
                    f.dueDate != null && f.dueTime != null -> {
                        val start = f.dueTime!!
                        stringResource(R.string.duration_block_note, Fmt.time(start, p), Fmt.time(start.plusMinutes(f.durationMinutes.toLong()), p))
                    }
                    else -> stringResource(R.string.duration_estimate_note)
                },
            )

            SectionHeader(stringResource(R.string.field_countdown), Modifier.padding(top = Space.l))
            DsSwitchRow(
                title = stringResource(R.string.countdown_show),
                subtitle = stringResource(if (f.dueDate == null) R.string.cd_needs_due else R.string.countdown_show_sub),
                checked = f.countdown && f.dueDate != null, enabled = f.dueDate != null,
                onCheckedChange = { on -> vm.update { it.copy(countdown = on) } },
                icon = Icons.Rounded.HourglassTop,
            )
            if (state.isGoogle && f.countdown) Note(stringResource(R.string.google_task_countdown_local))

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

            // A repeating task can only be in a list on this device (Google Tasks can't store the rule).
            val listChoices = if (f.repeat.repeats) state.lists.filter { !it.isGoogle || it.id == f.listId } else state.lists
            if (listChoices.size > 1 && !state.isSubtask) {
                SectionHeader(stringResource(R.string.field_list), Modifier.padding(top = Space.l))
                FlowRow(Modifier.padding(horizontal = Space.gutter - Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    listChoices.forEach { l -> DsChip(l.title, f.listId == l.id, { vm.update { it.copy(listId = l.id) } }) }
                }
                if (f.repeat.repeats && listChoices.size < state.lists.size) Note(stringResource(R.string.repeat_local_lists_only))
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
                val loadedIds = remember(state.task?.id) { state.subtasks.map { it.id }.toSet() }
                Column(if (m.reduced) Modifier else Modifier.animateContentSize(m.standard(m.medium))) {
                    state.subtasks.forEach { s ->
                        androidx.compose.runtime.key(s.id) {
                            val shown = remember { androidx.compose.animation.core.MutableTransitionState(s.id in loadedIds) }
                            androidx.compose.runtime.LaunchedEffect(Unit) { shown.targetState = true }
                            androidx.compose.animation.AnimatedVisibility(shown, enter = m.expandIn()) {
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
            onPick = { d -> vm.update { it.withDue(d.toEpochDay()) } },
            onDismiss = { pickDate = false },
            onClear = if (f.dueDate != null) ({ vm.update { it.withDue(null) } }) else null,
        )
    }
    if (pickUntil) {
        val from = f.dueDate ?: today
        DsDatePickerDialog(
            initial = f.repeat.untilEpochDay?.let(LocalDate::ofEpochDay) ?: from.plusMonths(1),
            // The last date can't be before the first occurrence.
            onPick = { d ->
                val last = maxOf(d, from)
                setRepeat(f.repeat.copy(end = app.daysemble.domain.model.RepeatEnd.UNTIL.name, untilEpochDay = last.toEpochDay()))
            },
            onDismiss = { pickUntil = false },
        )
    }
    if (askSaveScope) {
        SeriesScopeDialog(
            forDelete = false, allowThis = !state.repeatChanged,
            onPick = { scope -> vm.save(scope) }, onDismiss = { askSaveScope = false },
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
    if (confirmDelete && state.isRecurring) {
        SeriesScopeDialog(
            forDelete = true, allowThis = true, pastOccurrence = state.isPastOccurrence,
            onPick = { scope -> vm.delete(scope) }, onDismiss = { confirmDelete = false },
        )
    } else if (confirmDelete) {
        DsConfirmDialog(
            title = stringResource(R.string.delete_task_title),
            body = stringResource(if (state.task?.isGoogle == true) R.string.delete_task_google_body else R.string.delete_task_body),
            confirm = stringResource(R.string.action_delete), onConfirm = vm::delete,
            onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
}

/** "Time needed": common lengths as chips, then 15-minute steps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationPicker(minutes: Int?, onChange: (Int?) -> Unit) {
    val c = DsTheme.colors
    FlowRow(
        Modifier.padding(horizontal = Space.gutter - Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        DsChip(stringResource(R.string.duration_none), minutes == null, { onChange(null) })
        listOf(15, 30, 60, 90, 120, 180).forEach { m ->
            DsChip(app.daysemble.ui.format.durationText(m.toLong()), minutes == m, { onChange(m) })
        }
    }
    if (minutes != null) {
        Row(Modifier.padding(horizontal = Space.gutter - Space.s), verticalAlignment = Alignment.CenterVertically) {
            DsIconButton(androidx.compose.material.icons.Icons.Rounded.Remove, stringResource(R.string.cd_decrease), { onChange((minutes - 15).coerceAtLeast(5)) }, enabled = minutes > 5)
            Text(app.daysemble.ui.format.durationText(minutes.toLong()), style = DsTheme.type.bodyStrong, color = c.textPrimary)
            DsIconButton(Icons.Rounded.Add, stringResource(R.string.cd_increase), { onChange((minutes + 15).coerceAtMost(24 * 60)) })
        }
    }
}

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
