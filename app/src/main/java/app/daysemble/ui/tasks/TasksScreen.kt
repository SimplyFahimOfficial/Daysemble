package app.daysemble.ui.tasks

import app.daysemble.ui.components.itemMotion
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import app.daysemble.ui.theme.Sizes
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.R
import app.daysemble.data.prefs.TaskSort
import app.daysemble.domain.TaskBucket
import app.daysemble.domain.TaskFilter
import app.daysemble.domain.model.Task
import app.daysemble.ui.LocalSnackbar
import app.daysemble.ui.components.DsChip
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.components.DsSearchField
import app.daysemble.ui.components.HairlineDivider
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.components.SkeletonRows
import app.daysemble.ui.components.StateMessage
import app.daysemble.ui.components.TaskRow
import app.daysemble.ui.daysembleViewModel
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.rememberMinuteTicker
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun TasksRoute(contentPadding: PaddingValues, onOpenTask: (String) -> Unit) {
    val prefs = LocalTimePrefs.current
    val vm = daysembleViewModel { c, h -> TasksViewModel(c.tasks, c.settings, c.clock, c.timeContext, h) }
    val state by vm.state.collectAsStateWithLifecycle()
    val now = rememberMinuteTicker()
    LaunchedEffect(now) { vm.onTick(now) }
    val onDeleted = app.daysemble.ui.LocalTaskDeleted.current
    TasksScreen(
        state = state, now = now, contentPadding = contentPadding,
        onQuery = vm::setQuery, onFilter = vm::setFilter, onList = vm::setList, onSort = vm::setSort,
        onToggle = vm::setCompleted, onOpen = onOpenTask,
        onDelete = { id -> vm.delete(id); onDeleted(id) },
        onReorder = vm::reorder,
    )
}

@Composable
fun TasksScreen(
    state: TasksUiState,
    now: Instant,
    contentPadding: PaddingValues,
    onQuery: (String) -> Unit,
    onFilter: (TaskFilter) -> Unit,
    onList: (String?) -> Unit,
    onSort: (TaskSort) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onReorder: (List<String>) -> Unit = {},
) {
    val c = DsTheme.colors
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Local copy of the manual order while dragging; resynced whenever the data changes.
    val manual = remember(state.sections) {
        androidx.compose.runtime.mutableStateListOf<Task>().apply { if (state.canReorder) addAll(state.sections.flatMap { it.tasks }) }
    }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(c.background),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + Space.xxl),
    ) {
        item(key = "header") {
            Row(
                Modifier
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(start = Space.gutter, end = Space.xs, top = Space.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.tab_tasks), style = DsTheme.type.headline, color = c.textPrimary, modifier = Modifier.weight(1f).semantics { heading() })
                SortMenu(state.sort, onSort)
            }
        }
        item(key = "search") {
            DsSearchField(
                value = state.query, onValueChange = onQuery,
                placeholder = stringResource(R.string.search_tasks_hint),
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
            )
        }
        item(key = "filters") {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Space.gutter - Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                TaskFilter.entries.forEach { f ->
                    DsChip(stringResource(filterLabel(f)), state.filter == f, { onFilter(f) })
                }
            }
        }
        if (state.lists.size > 1) {
            item(key = "lists") {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Space.gutter - Space.xs),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    DsChip(stringResource(R.string.all_lists), state.listId == null, { onList(null) })
                    state.lists.forEach { l -> DsChip(l.title, state.listId == l.id, { onList(l.id) }) }
                }
            }
        }

        if (state.isLoading) {
            item(key = "loading") { SkeletonRows(Modifier.padding(top = Space.xl)) }
        } else if (state.sections.isEmpty()) {
            item(key = "empty") {
                if (state.query.isNotBlank()) {
                    StateMessage(Icons.Rounded.SearchOff, stringResource(R.string.search_no_results, state.query), Modifier.padding(top = Space.x3))
                } else {
                    StateMessage(
                        Icons.Rounded.TaskAlt,
                        stringResource(if (state.filter == TaskFilter.ALL) R.string.tasks_empty_title else R.string.tasks_empty_filter_title),
                        Modifier.padding(top = Space.x3),
                        body = stringResource(R.string.tasks_empty_body),
                    )
                }
            }
        }

        if (state.canReorder && manual.isNotEmpty()) {
            item(key = "reorderHint") {
                Text(
                    stringResource(R.string.reorder_hint), style = DsTheme.type.label, color = c.textSecondary,
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
                )
            }
            items(manual, key = { it.id }) { task ->
                val index = manual.indexOf(task)
                val isDragged = dragging == task.id
                val moveUp = stringResource(R.string.action_move_up)
                val moveDown = stringResource(R.string.action_move_down)
                Box(
                    Modifier
                        .then(if (isDragged) Modifier else Modifier.then(itemMotion()))
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffset else 0f; shadowElevation = if (isDragged) 8f else 0f }
                        .background(if (isDragged) c.surfaceRaised else c.background),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            TaskRow(
                                task, now, onToggle = { onToggle(task.id, it) }, onClick = { onOpen(task.id) },
                                actions = buildList {
                                    if (index > 0) add(CustomAccessibilityAction(moveUp) { manual.swap(index, index - 1); onReorder(manual.map { it.id }); true })
                                    if (index < manual.lastIndex) add(CustomAccessibilityAction(moveDown) { manual.swap(index, index + 1); onReorder(manual.map { it.id }); true })
                                },
                            )
                        }
                        Icon(
                            Icons.Rounded.DragIndicator, stringResource(R.string.cd_drag_handle), tint = c.textTertiary,
                            modifier = Modifier
                                .padding(end = Space.s)
                                .size(Sizes.touch)
                                .padding(12.dp)
                                .pointerInput(task.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { dragging = task.id; dragOffset = 0f },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.y
                                            val infos = listState.layoutInfo.visibleItemsInfo
                                            val cur = infos.firstOrNull { it.key == task.id } ?: return@detectDragGesturesAfterLongPress
                                            val center = cur.offset + cur.size / 2 + dragOffset
                                            val target = infos.firstOrNull { info ->
                                                info.key != task.id && manual.any { it.id == info.key } &&
                                                    center.toInt() in info.offset..(info.offset + info.size)
                                            } ?: return@detectDragGesturesAfterLongPress
                                            val from = manual.indexOfFirst { it.id == task.id }
                                            val to = manual.indexOfFirst { it.id == target.key }
                                            manual.swap(from, to)
                                            dragOffset += (cur.offset - target.offset).toFloat()
                                        },
                                        onDragEnd = { dragging = null; dragOffset = 0f; onReorder(manual.map { it.id }) },
                                        onDragCancel = { dragging = null; dragOffset = 0f },
                                    )
                                },
                        )
                    }
                    HairlineDivider(Modifier.align(Alignment.BottomCenter), inset = true)
                }
            }
        }

        if (!state.canReorder) state.sections.forEach { section ->
            if (section.bucket != null) {
                item(key = "h:" + section.bucket.name) {
                    SectionHeader(stringResource(bucketLabel(section.bucket)), Modifier.padding(top = Space.l).then(itemMotion()))
                }
            } else {
                item(key = "h:flat") { Spacer(Modifier.size(Space.s)) }
            }
            items(section.tasks, key = { it.id }) { task ->
                SwipeableTaskRow(
                    task, now,
                    onToggle = { onToggle(task.id, it) }, onOpen = { onOpen(task.id) }, onDelete = { onDelete(task.id) },
                    modifier = Modifier.then(itemMotion()),
                )
            }
        }
    }
}

@Composable
private fun SwipeableTaskRow(
    task: Task,
    now: Instant,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = DsTheme.colors
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val completeLabel = stringResource(if (task.isCompleted) R.string.action_mark_incomplete else R.string.action_complete)
    val deleteLabel = stringResource(R.string.action_delete)
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onToggle(!task.isCompleted); scope.launch { state.reset() } }
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
        backgroundContent = {
            val dir = state.dismissDirection
            val bg by animateColorAsState(
                when (dir) {
                    SwipeToDismissBoxValue.StartToEnd -> c.success.copy(alpha = 0.16f)
                    SwipeToDismissBoxValue.EndToStart -> c.errorContainer
                    else -> Color.Transparent
                },
                label = "swipeBg",
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(bg)
                    .padding(horizontal = Space.gutter),
                contentAlignment = if (dir == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                when (dir) {
                    SwipeToDismissBoxValue.StartToEnd -> Icon(Icons.Rounded.Check, null, tint = c.success)
                    SwipeToDismissBoxValue.EndToStart -> Icon(Icons.Rounded.DeleteOutline, null, tint = c.error)
                    else -> Unit
                }
            }
        },
    ) {
        Box(Modifier.background(c.background)) {
            TaskRow(
                task, now, onToggle = onToggle, onClick = onOpen,
                actions = listOf(
                    CustomAccessibilityAction(completeLabel) { onToggle(!task.isCompleted); true },
                    CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                ),
            )
            HairlineDivider(Modifier.align(Alignment.BottomCenter), inset = true)
        }
    }
}

@Composable
private fun SortMenu(sort: TaskSort, onSort: (TaskSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        DsIconButton(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.cd_sort), { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = DsTheme.colors.surfaceRaised) {
            TaskSort.entries.forEach { s ->
                val selectedText = stringResource(R.string.state_selected)
                DropdownMenuItem(
                    modifier = Modifier.semantics { if (s == sort) stateDescription = selectedText },
                    text = { Text(stringResource(sortLabel(s)), style = DsTheme.type.body) },
                    trailingIcon = { if (s == sort) Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp)) },
                    onClick = { onSort(s); open = false },
                )
            }
        }
    }
}

private fun <T> MutableList<T>.swap(a: Int, b: Int) {
    if (a !in indices || b !in indices || a == b) return
    val x = this[a]; this[a] = this[b]; this[b] = x
}

fun filterLabel(f: TaskFilter) = when (f) {
    TaskFilter.ALL -> R.string.filter_all
    TaskFilter.TODAY -> R.string.filter_today
    TaskFilter.UPCOMING -> R.string.filter_upcoming
    TaskFilter.OVERDUE -> R.string.filter_overdue
    TaskFilter.COMPLETED -> R.string.filter_completed
}

fun bucketLabel(b: TaskBucket) = when (b) {
    TaskBucket.OVERDUE -> R.string.section_overdue
    TaskBucket.TODAY -> R.string.section_today
    TaskBucket.UPCOMING -> R.string.section_upcoming
    TaskBucket.NO_DATE -> R.string.section_no_date
    TaskBucket.COMPLETED -> R.string.section_completed
}

fun sortLabel(s: TaskSort) = when (s) {
    TaskSort.DUE -> R.string.sort_due
    TaskSort.PRIORITY -> R.string.sort_priority
    TaskSort.CREATED -> R.string.sort_created
    TaskSort.MANUAL -> R.string.sort_manual
}
