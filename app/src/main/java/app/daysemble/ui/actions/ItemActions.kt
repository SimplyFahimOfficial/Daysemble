package app.daysemble.ui.actions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daysemble.AppContainer
import app.daysemble.R
import app.daysemble.domain.model.EventOccurrence
import app.daysemble.domain.model.SeriesScope
import app.daysemble.domain.model.Task
import app.daysemble.ui.LocalTaskCompleted
import app.daysemble.ui.LocalTaskDeleted
import app.daysemble.ui.components.DsConfirmDialog
import app.daysemble.ui.task.SeriesScopeDialog
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Long-press on a task or agenda item anywhere in the app opens one shared menu (Open, Edit,
 * Complete, Skip, Delete). A normal tap opens the detail screen instead. Screens get this from
 * [LocalItemActions]; outside the app root (screen tests) it is null and long-press does nothing.
 * The same actions are reachable without the gesture: the detail screens' overflow menu, and
 * the items' accessibility actions.
 */
interface ItemActions {
    fun onTaskLongPress(task: Task)
    fun onEventLongPress(event: EventOccurrence)
}

val LocalItemActions = staticCompositionLocalOf<ItemActions?> { null }

object ItemMenuTags {
    const val SHEET = "item_menu"
    const val OPEN = "item_menu_open"
    const val EDIT = "item_menu_edit"
    const val COMPLETE = "item_menu_complete"
    const val SKIP = "item_menu_skip"
    const val DELETE = "item_menu_delete"
}

/**
 * The menu's target, by id. Actions read the item again by id when they run, so a list that
 * re-sorted or changed under the menu can't make an action hit a different item.
 */
@Immutable
@Serializable
internal data class MenuTarget(
    val isTask: Boolean,
    val id: String,
    val title: String,
    val occurrenceKey: String? = null,
    val recurring: Boolean = false,
    val completed: Boolean = false,
    val readOnly: Boolean = false,
)

@Composable
fun ItemActionsHost(
    container: AppContainer,
    onOpenTask: (String) -> Unit,
    onEditTask: (String) -> Unit,
    onOpenEvent: (String, String) -> Unit,
    onEditEvent: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    var target by rememberSaveable(stateSaver = MenuTargetSaver) { mutableStateOf<MenuTarget?>(null) }
    var confirmDelete by rememberSaveable(stateSaver = MenuTargetSaver) { mutableStateOf<MenuTarget?>(null) }
    val actions = remember {
        object : ItemActions {
            override fun onTaskLongPress(task: Task) {
                target = MenuTarget(true, task.id, task.title, recurring = task.isRecurring, completed = task.isCompleted)
            }
            override fun onEventLongPress(event: EventOccurrence) {
                target = MenuTarget(false, event.eventId, event.title, event.occurrenceKey, event.isRecurring, readOnly = event.isReadOnly)
            }
        }
    }
    CompositionLocalProvider(LocalItemActions provides actions) { content() }

    val scope = rememberCoroutineScope()
    val onDeleted = LocalTaskDeleted.current
    val onCompleted = LocalTaskCompleted.current
    target?.let { t ->
        ItemMenu(
            t,
            onDismiss = { target = null },
            onOpen = { target = null; if (t.isTask) onOpenTask(t.id) else onOpenEvent(t.id, t.occurrenceKey.orEmpty()) },
            onEdit = { target = null; if (t.isTask) onEditTask(t.id) else onEditEvent(t.id) },
            onToggle = {
                target = null
                scope.launch {
                    val done = !(container.tasks.get(t.id)?.isCompleted ?: return@launch)
                    container.tasks.setCompleted(t.id, done)?.let { r -> if (done) onCompleted(t.id, r) }
                }
            },
            onSkip = {
                target = null
                scope.launch { container.tasks.delete(t.id, SeriesScope.THIS); onDeleted(t.id) }
            },
            onDelete = { target = null; confirmDelete = t },
        )
    }
    confirmDelete?.let { t ->
        val c = DsTheme.colors
        when {
            t.isTask && t.recurring -> SeriesScopeDialog(
                forDelete = true, allowThis = true, pastOccurrence = t.completed,
                onPick = { scope2 -> scope.launch { container.tasks.delete(t.id, scope2); onDeleted(t.id) } },
                onDismiss = { confirmDelete = null },
            )
            t.isTask -> DsConfirmDialog(
                stringResource(R.string.delete_task_title), stringResource(R.string.delete_task_body), stringResource(R.string.action_delete),
                onConfirm = { scope.launch { container.tasks.delete(t.id); onDeleted(t.id) } },
                onDismiss = { confirmDelete = null }, destructive = true,
            )
            t.recurring -> AlertDialog(
                onDismissRequest = { confirmDelete = null }, shape = Radii.dialog, containerColor = c.surfaceRaised,
                title = { Text(stringResource(R.string.delete_recurring_title), style = DsTheme.type.titleLarge, color = c.textPrimary) },
                text = { Text(stringResource(R.string.delete_recurring_body), style = DsTheme.type.body, color = c.textSecondary) },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = {
                            confirmDelete = null
                            scope.launch { container.events.deleteOccurrence(t.id, t.occurrenceKey.orEmpty()) }
                        }) { Text(stringResource(R.string.delete_this_event), color = c.error) }
                        TextButton(onClick = { confirmDelete = null; scope.launch { container.events.delete(t.id) } }) {
                            Text(stringResource(R.string.delete_all_events), color = c.error)
                        }
                        TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel), color = c.textSecondary) }
                    }
                },
            )
            else -> DsConfirmDialog(
                stringResource(R.string.delete_event_title), stringResource(R.string.delete_event_body), stringResource(R.string.action_delete),
                onConfirm = { scope.launch { container.events.delete(t.id) } },
                onDismiss = { confirmDelete = null }, destructive = true,
            )
        }
    }
}

private val MenuTargetSaver = androidx.compose.runtime.saveable.Saver<MenuTarget?, String>(
    save = { it?.let { t -> kotlinx.serialization.json.Json.encodeToString(MenuTarget.serializer(), t) } ?: "" },
    restore = { s -> if (s.isEmpty()) null else runCatching { kotlinx.serialization.json.Json.decodeFromString(MenuTarget.serializer(), s) }.getOrNull() },
)

@Composable
private fun ItemMenu(
    t: MenuTarget,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onSkip: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = DsTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surfaceRaised, shape = Radii.sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Space.l).testTag(ItemMenuTags.SHEET)) {
            Text(
                t.title.ifBlank { stringResource(if (t.isTask) R.string.task else R.string.untitled_event) },
                style = DsTheme.type.title, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Space.gutter).semantics { heading() },
            )
            Spacer(Modifier.height(Space.s))
            MenuRow(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.menu_open_details), onOpen, Modifier.testTag(ItemMenuTags.OPEN))
            if (!t.readOnly) MenuRow(Icons.Rounded.Edit, stringResource(R.string.action_edit), onEdit, Modifier.testTag(ItemMenuTags.EDIT))
            if (t.isTask) {
                MenuRow(
                    if (t.completed) Icons.Rounded.RadioButtonUnchecked else Icons.Rounded.CheckCircleOutline,
                    stringResource(if (t.completed) R.string.action_mark_incomplete else R.string.action_complete), onToggle,
                    Modifier.testTag(ItemMenuTags.COMPLETE),
                )
                if (t.recurring && !t.completed) MenuRow(Icons.Rounded.SkipNext, stringResource(R.string.menu_skip_occurrence), onSkip, Modifier.testTag(ItemMenuTags.SKIP))
            }
            if (!t.readOnly) MenuRow(Icons.Rounded.DeleteOutline, stringResource(R.string.action_delete), onDelete, Modifier.testTag(ItemMenuTags.DELETE), tint = c.error)
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = DsTheme.colors.textPrimary) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.touch + 4.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(Sizes.icon))
        Text(label, style = DsTheme.type.body, color = tint)
    }
}

