package app.daysemble.ui.task

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.R
import app.daysemble.domain.TaskRules
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.SeriesScope
import app.daysemble.domain.model.Task
import app.daysemble.ui.components.CountdownHero
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsChip
import app.daysemble.ui.components.DsConfirmDialog
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.components.DsTopBar
import app.daysemble.ui.components.HairlineDivider
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.components.StateMessage
import app.daysemble.ui.components.TaskRow
import app.daysemble.ui.components.priorityLabel
import app.daysemble.ui.components.reminderLabel
import app.daysemble.ui.daysembleViewModel
import app.daysemble.ui.event.repeatSummary
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.dayLabel
import app.daysemble.ui.format.durationText
import app.daysemble.ui.format.rememberMinuteTicker
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Space
import java.time.Instant

object TaskDetailTags {
    const val SCREEN = "task_detail"
    const val TITLE = "task_detail_title"
    const val COMPLETE = "task_detail_complete"
    const val SKIP = "task_detail_skip"
    const val EDIT = "task_detail_edit"
    const val MORE = "task_detail_more"
    const val COUNTDOWN = "task_detail_countdown"
    const val COUNTDOWN_TOGGLE = "task_detail_countdown_toggle"
    const val HISTORY = "task_detail_history"
}

/**
 * The detail view a tap on a task opens: what the task is, how long until it is due (live, when its
 * countdown is on), its repeat with the next date and the completion history, and its actions.
 * Editing is one step further (the pencil, the overflow menu, or a long-press in any list).
 */
@Composable
fun TaskDetailRoute(
    taskId: String,
    onEdit: (String) -> Unit,
    onClose: () -> Unit,
    onDone: (() -> Unit)? = null,
) {
    val finish = onDone ?: onClose
    val vm = daysembleViewModel(key = "taskDetail:$taskId") { c, h -> TaskDetailViewModel(c.tasks, c.clock, c.timeContext, taskId, h) }
    val state by vm.state.collectAsStateWithLifecycle()
    val now = rememberMinuteTicker()
    LaunchedEffect(now) { vm.onTick(now) }
    LaunchedEffect(state.isMissing) { if (state.isMissing) finish() }
    val onCompleted = app.daysemble.ui.LocalTaskCompleted.current
    val onDeleted = app.daysemble.ui.LocalTaskDeleted.current
    TaskDetailScreen(
        state = state, now = now, onClose = onClose,
        onEdit = { state.task?.let { onEdit(it.id) } },
        onToggle = { vm.toggleCompleted(onCompleted) },
        onSkip = { vm.skip(onDeleted) },
        onDelete = { scope -> vm.delete(scope) { id -> onDeleted(id); finish() } },
        onCountdown = vm::setCountdown,
        onToggleSubtask = vm::toggleSubtask,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(
    state: TaskDetailUiState,
    now: Instant,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onSkip: () -> Unit,
    onDelete: (SeriesScope) -> Unit,
    onCountdown: (Boolean) -> Unit,
    onToggleSubtask: (String, Boolean) -> Unit,
) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val p = LocalTimePrefs.current
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val task = state.task

    Column(Modifier.fillMaxSize().background(c.background).testTag(TaskDetailTags.SCREEN)) {
        DsTopBar("", onClose, close = true) {
            if (task != null) {
                DsIconButton(Icons.Rounded.Edit, stringResource(R.string.action_edit), onEdit, modifier = Modifier.testTag(TaskDetailTags.EDIT))
                Box {
                    DsIconButton(Icons.Rounded.MoreVert, stringResource(R.string.menu_more_actions), { menu = true }, modifier = Modifier.testTag(TaskDetailTags.MORE))
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surfaceRaised) {
                        MenuItem(Icons.Rounded.Edit, stringResource(R.string.action_edit)) { menu = false; onEdit() }
                        MenuItem(
                            if (task.isCompleted) Icons.AutoMirrored.Rounded.Undo else Icons.Rounded.Check,
                            stringResource(if (task.isCompleted) R.string.action_mark_incomplete else R.string.action_complete),
                        ) { menu = false; onToggle() }
                        if (task.isRecurring && !task.isCompleted) MenuItem(Icons.Rounded.SkipNext, stringResource(R.string.menu_skip_occurrence)) { menu = false; onSkip() }
                        MenuItem(
                            Icons.Rounded.HourglassTop,
                            stringResource(if (task.countdownEnabled) R.string.detail_countdown_off else R.string.detail_countdown_on),
                            enabled = task.dueDate != null,
                        ) { menu = false; onCountdown(!task.countdownEnabled) }
                        MenuItem(Icons.Rounded.DeleteOutline, stringResource(R.string.action_delete), destructive = true) { menu = false; confirmDelete = true }
                    }
                }
            }
        }
        if (task == null) {
            if (!state.isLoading) StateMessage(Icons.Rounded.TaskAlt, stringResource(R.string.task_missing), Modifier.padding(top = Space.x3))
            return@Column
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = Space.x3),
        ) {
            Header(task, state, now)
            Spacer(Modifier.height(Space.xl))

            // The countdown, the centre of the screen when it's on.
            val deadline = task.dueMoment(p.zone)
            AnimatedVisibility(task.countdownEnabled && deadline != null, enter = m.expandIn(), exit = m.collapseOut()) {
                CountdownHero(
                    deadline, task.isCompleted,
                    Modifier
                        .padding(horizontal = Space.gutter - Space.xs)
                        .clip(Radii.card)
                        .background(c.surfaceVariant)
                        .padding(horizontal = Space.l, vertical = Space.l)
                        .testTag(TaskDetailTags.COUNTDOWN),
                )
            }
            if (!task.countdownEnabled && deadline != null && !task.isCompleted) {
                Row(Modifier.padding(horizontal = Space.gutter - Space.xs)) {
                    DsChip(
                        stringResource(R.string.detail_countdown_on), false, { onCountdown(true) }, icon = Icons.Rounded.HourglassTop,
                        role = androidx.compose.ui.semantics.Role.Button, modifier = Modifier.testTag(TaskDetailTags.COUNTDOWN_TOGGLE),
                    )
                }
            }

            // Primary actions.
            FlowRow(
                Modifier.padding(horizontal = Space.gutter, vertical = Space.l),
                horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                DsButton(
                    stringResource(if (task.isCompleted) R.string.action_mark_incomplete else R.string.detail_complete),
                    onToggle, icon = if (task.isCompleted) Icons.AutoMirrored.Rounded.Undo else Icons.Rounded.Check,
                    style = if (task.isCompleted) DsButtonStyle.Secondary else DsButtonStyle.Primary,
                    modifier = Modifier.testTag(TaskDetailTags.COMPLETE),
                )
                if (task.isRecurring && !task.isCompleted) {
                    DsButton(
                        stringResource(R.string.menu_skip_occurrence), onSkip, icon = Icons.Rounded.SkipNext, style = DsButtonStyle.Secondary,
                        modifier = Modifier.testTag(TaskDetailTags.SKIP),
                    )
                }
            }

            Details(task, state)

            if (task.notes.isNotBlank()) {
                SectionHeader(stringResource(R.string.field_notes), Modifier.padding(top = Space.l))
                Row(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    Icon(Icons.AutoMirrored.Rounded.Notes, null, tint = c.textTertiary, modifier = Modifier.size(20.dp))
                    Text(task.notes, style = DsTheme.type.body, color = c.textPrimary)
                }
            }

            if (state.subtasks.isNotEmpty()) {
                SectionHeader(
                    stringResource(R.string.field_subtasks) + " · " + pluralStringResource(
                        R.plurals.subtasks_progress, state.subtasks.size, state.subtasks.count { it.isCompleted }, state.subtasks.size,
                    ),
                    Modifier.padding(top = Space.l),
                )
                state.subtasks.forEach { s ->
                    androidx.compose.runtime.key(s.id) {
                        TaskRow(s, now, onToggle = { onToggleSubtask(s.id, it) }, onClick = { onToggleSubtask(s.id, !s.isCompleted) }, showDue = false, isSubtask = true)
                        HairlineDivider(inset = true)
                    }
                }
            }

            val history = state.history
            if (history != null) {
                SectionHeader(stringResource(R.string.history_title), Modifier.padding(top = Space.xl))
                OccurrenceTracker(
                    history, state.series?.rule, state.today,
                    Modifier.padding(horizontal = Space.gutter).testTag(TaskDetailTags.HISTORY),
                )
                Text(
                    stringResource(R.string.history_policy), style = DsTheme.type.label, color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.m),
                )
            }
        }
    }

    if (confirmDelete && task != null) {
        if (task.seriesId != null && (task.isRecurring || state.series?.rule != null)) {
            SeriesScopeDialog(forDelete = true, allowThis = true, pastOccurrence = task.isCompleted, onPick = onDelete, onDismiss = { confirmDelete = false })
        } else {
            DsConfirmDialog(
                stringResource(R.string.delete_task_title),
                stringResource(if (task.isGoogle) R.string.delete_task_google_body else R.string.delete_task_body),
                stringResource(R.string.action_delete), onConfirm = { onDelete(SeriesScope.THIS) }, onDismiss = { confirmDelete = false }, destructive = true,
            )
        }
    }
}

@Composable
private fun Header(task: Task, state: TaskDetailUiState, now: Instant) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    val overdue = TaskRules.isOverdue(task, now, p.zone)
    Column(Modifier.padding(horizontal = Space.gutter)) {
        val kicker = listOfNotNull(
            state.list?.title,
            task.priority.takeIf { it != Priority.NONE }?.let { stringResource(priorityLabel(it)) },
        ).joinToString(" · ")
        if (kicker.isNotEmpty()) Text(kicker.uppercase(), style = DsTheme.type.caps, color = c.textSecondary)
        Spacer(Modifier.height(Space.xs))
        Text(
            task.title, style = DsTheme.type.headline,
            color = if (task.isCompleted) c.textSecondary else c.textPrimary,
            modifier = Modifier.semantics { heading() }.testTag(TaskDetailTags.TITLE),
        )
        Spacer(Modifier.height(Space.s))
        val status = when {
            task.isCompleted -> task.completedAt?.let {
                stringResource(R.string.detail_completed_at, Fmt.mediumDate(it.atZone(p.zone).toLocalDate(), p) + " · " + Fmt.time(it, p))
            } ?: stringResource(R.string.cd_completed)
            overdue -> stringResource(R.string.detail_overdue)
            task.dueDate != null -> stringResource(R.string.detail_open)
            else -> stringResource(R.string.detail_no_due)
        }
        val tint = when {
            task.isCompleted -> c.success
            overdue -> c.error
            else -> c.textSecondary
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Box(Modifier.size(8.dp).clip(Radii.pill).background(tint))
            Text(status, style = DsTheme.type.label, color = tint)
        }
    }
}

@Composable
private fun Details(task: Task, state: TaskDetailUiState) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    Column(
        Modifier
            .padding(horizontal = Space.gutter - Space.xs)
            .fillMaxWidth()
            .clip(Radii.card)
            .background(c.surfaceVariant)
            .padding(vertical = Space.xs),
    ) {
        val due = task.dueDate
        DetailLine(
            Icons.Rounded.CalendarToday,
            due?.let { dayLabel(it, state.today, p) + (task.dueTime?.let { t -> " · " + Fmt.time(t, p) } ?: "") } ?: stringResource(R.string.no_date),
            due?.let { Fmt.fullDate(it, p) },
        )
        task.durationMinutes?.let { mins ->
            val block = task.scheduledBlock(p.zone)
            DetailLine(
                Icons.Rounded.Timelapse, durationText(mins.toLong()),
                if (block != null) stringResource(R.string.detail_duration_block, Fmt.time(block.first, p), Fmt.time(block.second, p))
                else stringResource(R.string.detail_duration_estimate),
            )
        }
        val rule = task.repeat ?: state.series?.rule
        if (rule != null) {
            val next = state.history?.let { h -> if (task.isCompleted) h.active?.date else h.nextAfterActive }
            DetailLine(
                Icons.Rounded.Repeat, repeatSummary(rule),
                next?.let { stringResource(if (task.isCompleted) R.string.detail_current_occurrence else R.string.detail_next_after, Fmt.smartDate(it, state.today, p)) }
                    ?: stringResource(R.string.detail_last_occurrence).takeIf { state.history?.endsWithinHorizon == true && state.history.upcoming <= 1 },
            )
        } else if (task.seriesId != null) {
            DetailLine(Icons.Rounded.Repeat, stringResource(R.string.detail_repeat_ended), null)
        }
        if (state.reminders.isNotEmpty() && due != null) {
            DetailLine(
                Icons.Rounded.Notifications,
                state.reminders.map { reminderLabel(it, forTask = true) }.joinToString(", "),
                null,
            )
        }
        if (task.priority != Priority.NONE) DetailLine(Icons.Rounded.Flag, stringResource(priorityLabel(task.priority)), null)
    }
}

@Composable
private fun DetailLine(icon: ImageVector, text: String, sub: String?) {
    val c = DsTheme.colors
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = Space.l, vertical = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(22.dp))
        Column {
            Text(text, style = DsTheme.type.body, color = c.textPrimary)
            if (sub != null) Text(sub, style = DsTheme.type.bodySmall, color = c.textSecondary)
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val c = DsTheme.colors
    DropdownMenuItem(
        text = { Text(label, style = DsTheme.type.body, color = if (destructive) c.error else c.textPrimary) },
        leadingIcon = { Icon(icon, null, tint = if (destructive) c.error else c.textSecondary) },
        enabled = enabled,
        onClick = onClick,
    )
}
