package app.daysemble.ui.tasks

import app.daysemble.ui.components.itemMotion
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
import app.daysemble.ui.theme.Elevation
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.daysemble.domain.model.SeriesScope
import app.daysemble.ui.task.SeriesScopeDialog
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
    val vm = daysembleViewModel { c, h -> TasksViewModel(c.tasks, c.settings, c.clock, c.timeContext, h) }
    val state by vm.state.collectAsStateWithLifecycle()
    val now = rememberMinuteTicker()
    LaunchedEffect(now) { vm.onTick(now) }
    val onDeleted = app.daysemble.ui.LocalTaskDeleted.current
    val onCompleted = app.daysemble.ui.LocalTaskCompleted.current
    TasksScreen(
        state = state, now = now, contentPadding = contentPadding,
        query = vm.searchText, onQuery = vm::setQuery, onFilter = vm::setFilter, onList = vm::setList, onSort = vm::setSort,
        onToggle = { id, done -> vm.setCompleted(id, done) { r -> if (done) onCompleted(id, r) } }, onOpen = onOpenTask,
        onDelete = { id, scope -> vm.delete(id, scope); onDeleted(id) },
        onReorder = vm::reorder,
    )
}

@Composable
fun TasksScreen(
    state: TasksUiState,
    /** The search field's own text (synchronous; see TasksViewModel.searchText). */
    query: String = state.query,
    now: Instant,
    contentPadding: PaddingValues,
    onQuery: (String) -> Unit,
    onFilter: (TaskFilter) -> Unit,
    onList: (String?) -> Unit,
    onSort: (TaskSort) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onOpen: (String) -> Unit,
    /** A repeating task's occurrence asks first which occurrences (see SeriesScopeDialog). */
    onDelete: (String, SeriesScope) -> Unit,
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
    // After release the row glides from where the finger left it into its slot ([DsMotion.settle]),
    // instead of jumping there. Grabbing it again mid-glide continues from its live position.
    var settling by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val dragScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val liftPx = with(density) { Elevation.lifted.toPx() }
    val m = DsTheme.motion
    // Moves the dragged row into the slot of the row under its centre, if that's another one. Past
    // the first or last visible row (under the bottom bar, or while the list auto-scrolls), it takes
    // that row's slot, so its own slot always stays on screen and the gesture is never dropped.
    fun swapUnderFinger(id: String) {
        val infos = listState.layoutInfo.visibleItemsInfo
        val cur = infos.firstOrNull { it.key == id } ?: return
        val center = (cur.offset + cur.size / 2 + dragOffset).toInt()
        val rows = infos.filter { info -> manual.any { it.id == info.key } }
        val target = rows.firstOrNull { it.key != id && center in it.offset..(it.offset + it.size) }
            ?: rows.lastOrNull()?.takeIf { it.key != id && center > it.offset + it.size }
            ?: rows.firstOrNull()?.takeIf { it.key != id && center < it.offset }
            ?: return
        manual.swap(manual.indexOfFirst { it.id == id }, manual.indexOfFirst { it.id == target.key })
        dragOffset += (cur.offset - target.offset).toFloat()
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    // Held near the top or bottom of the list, the list scrolls under the row, faster the closer
    // it is to the edge, and keeps going while the finger is still. The row stays under the finger
    // (the scroll is added to its offset) and keeps taking the places it passes.
    val edgePx = with(density) { AutoScrollEdge.toPx() }
    val maxSpeedPx = with(density) { AutoScrollMaxSpeed.toPx() }
    LaunchedEffect(dragging, settling) {
        val id = dragging ?: return@LaunchedEffect
        if (settling) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f
            last = now
            val info = listState.layoutInfo
            val cur = info.visibleItemsInfo.firstOrNull { it.key == id }
            val top = cur?.let { it.offset + dragOffset } ?: 0f
            val end = info.viewportEndOffset - info.afterContentPadding
            // -1..1: how far into the top (negative) or bottom edge zone the row is; 0 outside both.
            val pull = when {
                cur == null -> 0f
                top < edgePx -> -((edgePx - top) / edgePx).coerceAtMost(1f)
                top + cur.size > end - edgePx -> ((top + cur.size - (end - edgePx)) / edgePx).coerceAtMost(1f)
                else -> 0f
            }
            if (pull != 0f) {
                dragOffset += listState.scrollBy(pull * kotlin.math.abs(pull) * maxSpeedPx * dt)
                swapUnderFinger(id)
            }
        }
    }
    fun settleDrop(id: String) {
        settling = true
        settleJob = dragScope.launch {
            try {
                animate(dragOffset, 0f, animationSpec = m.settle()) { v, _ -> dragOffset = v }
            } finally {
                // Not if the row was grabbed again (or another one was) in the meantime.
                if (dragging == id && settling) { dragging = null; dragOffset = 0f; settling = false }
            }
        }
    }
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
                    .fillMaxWidth()
                    .padding(start = Space.gutter, end = Space.xs, top = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.tab_tasks), style = DsTheme.type.headline, color = c.textPrimary, modifier = Modifier.weight(1f).semantics { heading() })
                SortMenu(state.sort, onSort)
            }
        }
        item(key = "search") {
            DsSearchField(
                value = query, onValueChange = onQuery,
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
                // Lifted while held; set down (shadow and scale back) as it settles.
                val lift by animateFloatAsState(if (isDragged && !settling) 1f else 0f, m.indicator(), label = "lift")
                val moveUp = stringResource(R.string.action_move_up)
                val moveDown = stringResource(R.string.action_move_down)
                Box(
                    Modifier
                        .then(if (isDragged) Modifier else Modifier.then(itemMotion()))
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragged) dragOffset else 0f
                            shadowElevation = lift * liftPx
                            val scale = 1f + (1f - m.pressScale(large = true)) * lift // 1.5 % up, none when reduced
                            scaleX = scale; scaleY = scale
                        }
                        .background(if (isDragged) c.surfaceRaised else c.background),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            TaskRow(
                                task, now, onToggle = { onToggle(task.id, it) }, onClick = { onOpen(task.id) },
                                onLongClick = app.daysemble.ui.actions.LocalItemActions.current?.let { a -> { a.onTaskLongPress(task) } },
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
                                        onDragStart = {
                                            settleJob?.cancel(); settling = false
                                            // Caught again mid-glide: keep its live offset, no jump.
                                            if (dragging != task.id) dragOffset = 0f
                                            dragging = task.id
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.y
                                            swapUnderFinger(task.id)
                                        },
                                        onDragEnd = {
                                            // The order is saved now; the glide only follows it.
                                            onReorder(manual.map { it.id })
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            settleDrop(task.id)
                                        },
                                        onDragCancel = {
                                            // Cancelled by the system, not the user: keep the order they see.
                                            onReorder(manual.map { it.id })
                                            settleDrop(task.id)
                                        },
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
                    onToggle = { onToggle(task.id, it) }, onOpen = { onOpen(task.id) }, onDelete = { scope -> onDelete(task.id, scope) },
                    modifier = Modifier.then(itemMotion()),
                )
            }
        }
    }
}

/** Distance from the list's edge where a dragged row starts scrolling it, and the top speed. */
private val AutoScrollEdge = 64.dp
private val AutoScrollMaxSpeed = 900.dp // per second

@Composable
private fun SwipeableTaskRow(
    task: Task,
    now: Instant,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onDelete: (SeriesScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    // "Armed": past the threshold, so letting go now completes or deletes. The background fills
    // with the action's colour and the icon grows at that moment, with one tick, so the user knows
    // before lifting the finger. Only arming ticks; the reset after a completion doesn't.
    val armed by remember { derivedStateOf { state.targetValue != SwipeToDismissBoxValue.Settled } }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state) {
        snapshotFlow { armed }.distinctUntilChanged().filter { it }.collect {
            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        }
    }
    // Deleting an occurrence of a repeating task asks which ones, as in the editor; cancelling puts
    // the row back.
    var askScope by rememberSaveable { mutableStateOf(false) }
    val delete: () -> Unit = { if (task.isRecurring) askScope = true else onDelete(SeriesScope.THIS) }
    if (askScope) {
        SeriesScopeDialog(
            forDelete = true, allowThis = true, pastOccurrence = task.isCompleted,
            onPick = onDelete,
            onDismiss = { askScope = false; scope.launch { state.reset() } },
        )
    }
    val completeLabel = stringResource(if (task.isCompleted) R.string.action_mark_incomplete else R.string.action_complete)
    val deleteLabel = stringResource(R.string.action_delete)
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onToggle(!task.isCompleted); scope.launch { state.reset() } }
                SwipeToDismissBoxValue.EndToStart -> delete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
        backgroundContent = { SwipeBackground(state.dismissDirection, armed) },
    ) {
        Box(Modifier.background(c.background)) {
            TaskRow(
                task, now, onToggle = onToggle, onClick = onOpen,
                onLongClick = app.daysemble.ui.actions.LocalItemActions.current?.let { a -> { a.onTaskLongPress(task) } },
                actions = listOf(
                    CustomAccessibilityAction(completeLabel) { onToggle(!task.isCompleted); true },
                    CustomAccessibilityAction(deleteLabel) { delete(); true },
                ),
            )
            HairlineDivider(Modifier.align(Alignment.BottomCenter), inset = true)
        }
    }
}

/**
 * Behind a swiped row: neutral until the swipe passes the threshold ([armed]), then the action's
 * colour (complete: green, delete: red) with its icon at full size.
 */
@Composable
private fun SwipeBackground(dir: SwipeToDismissBoxValue, armed: Boolean) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val complete = dir == SwipeToDismissBoxValue.StartToEnd
    val action = if (complete) SwipeAction(c.success.copy(alpha = 0.16f), c.success, Icons.Rounded.Check)
    else SwipeAction(c.errorContainer, c.error, Icons.Rounded.DeleteOutline)
    val idle = dir == SwipeToDismissBoxValue.Settled
    val bg = app.daysemble.ui.components.animateStateColor(
        action.fill(idle, armed, c.surfaceVariant),
        m.standard(m.short),
        label = "swipeBg",
    )
    val iconScale by animateFloatAsState(if (armed || m.reduced) 1f else 0.8f, m.indicator(), label = "swipeIcon")
    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
            .padding(horizontal = Space.gutter),
        contentAlignment = if (complete) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        if (!idle) {
            Icon(
                action.icon, null, tint = action.tint(armed, c.textSecondary),
                modifier = Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale },
            )
        }
    }
}

private data class SwipeAction(val armedFill: Color, val armedTint: Color, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    fun fill(idle: Boolean, armed: Boolean, neutral: Color) = when {
        idle -> Color.Transparent
        armed -> armedFill
        else -> neutral
    }
    fun tint(armed: Boolean, neutral: Color) = if (armed) armedTint else neutral
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
