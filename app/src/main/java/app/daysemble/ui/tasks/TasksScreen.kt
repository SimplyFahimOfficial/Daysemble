package app.daysemble.ui.tasks

import app.daysemble.ui.components.itemMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.emptyActionEntrance
import app.daysemble.ui.components.staggeredEntrance
import app.daysemble.ui.theme.Radii
import kotlinx.coroutines.delay
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
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import app.daysemble.ui.theme.Elevation
import app.daysemble.ui.theme.Sizes
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalDensity
import app.daysemble.ui.components.rememberHaptics
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
import app.daysemble.ui.components.DsChip
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.components.HairlineDivider
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.components.SkeletonRows
import app.daysemble.ui.components.StateMessage
import app.daysemble.ui.components.TaskRow
import app.daysemble.ui.daysembleViewModel
import app.daysemble.ui.format.rememberMinuteTicker
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun TasksRoute(contentPadding: PaddingValues, onOpenTask: (String) -> Unit, onAdd: () -> Unit = {}) {
    val vm = daysembleViewModel { c, h -> TasksViewModel(c.tasks, c.settings, c.clock, c.timeContext, h) }
    val state by vm.state.collectAsStateWithLifecycle()
    val now = rememberMinuteTicker()
    LaunchedEffect(now) { vm.onTick(now) }
    val onCompleted = app.daysemble.ui.LocalTaskCompleted.current
    val onDone = app.daysemble.ui.LocalTaskDone.current
    TasksScreen(
        state = state, now = now, contentPadding = contentPadding,
        onFilter = vm::setFilter, onList = vm::setList, onSort = vm::setSort,
        // A repeating task says where its series went; a one-off one leaves the list with an Undo.
        onToggle = { id, done -> vm.setCompleted(id, done) { r -> if (done) { if (r.repeating) onCompleted(id, r) else onDone(id) } } },
        onOpen = onOpenTask,
        onReorder = vm::reorder,
        onAdd = onAdd,
    )
}

@Composable
fun TasksScreen(
    state: TasksUiState,
    now: Instant,
    contentPadding: PaddingValues,
    onFilter: (TaskFilter) -> Unit,
    onList: (String?) -> Unit,
    onSort: (TaskSort) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onReorder: (List<String>) -> Unit = {},
    onAdd: () -> Unit = {},
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
    val haptics = rememberHaptics()
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
        haptics.tick()
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
    // Completing: the row shows the strike sweeping across first, then the task is really completed
    // and the row leaves the list (with an Undo from the app root). Ids in [completing] show as done.
    val completing = remember { androidx.compose.runtime.mutableStateMapOf<String, Job>() }
    val complete: (String, Boolean) -> Unit = { id, done ->
        val pending = completing[id]
        when {
            // Tapped again before the sweep finished: nothing was completed yet, so just cancel.
            pending != null -> { pending.cancel(); completing.remove(id) }
            done && state.filter != TaskFilter.COMPLETED && m.enabled -> {
                completing[id] = dragScope.launch {
                    delay((m.medium + m.short).toLong())
                    onToggle(id, true)
                    completing.remove(id)
                }
            }
            else -> onToggle(id, done)
        }
    }
    // The first rows enter one after another, once, when the tab is first on screen with its data.
    val visible = app.daysemble.ui.components.LocalTabVisible.current
    var entrance by remember { mutableStateOf(true) }
    LaunchedEffect(visible, state.isLoading) {
        if (visible && !state.isLoading && entrance) {
            delay((m.stagger * m.staggerMax + m.medium).toLong())
            entrance = false
        }
    }
    val settleScroll = rememberUpdatedState(listState)
    LaunchedEffect(state.filter) { settleScroll.value.scrollToItem(0) }
    var showSort by rememberSaveable { mutableStateOf(false) }
    if (showSort) SortSheet(state.sort, onSort = { onSort(it); showSort = false }, onDismiss = { showSort = false })
    val dimmed = state.filter == TaskFilter.COMPLETED

    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = Space.gutter, end = Space.xs, top = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.tab_tasks), style = DsTheme.type.headline, color = c.textPrimary, modifier = Modifier.weight(1f).semantics { heading() })
            DsIconButton(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.cd_sort), { showSort = true })
        }
        FilterChips(state, onFilter)
        if (state.lists.size > 1) {
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
        // Another filter replaces the list as a whole: it fades in while rising a little.
        Box(Modifier.weight(1f).fillMaxWidth()) {
        app.daysemble.ui.components.ContentSwap(state.filter, shift = m.listShift) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("tasks_list"),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + Space.xxl),
    ) {
        if (state.isLoading) {
            item(key = "loading") { SkeletonRows(Modifier.padding(top = Space.xl)) }
        } else if (state.sections.isEmpty()) {
            item(key = "empty") {
                // The copy says what this filter is for, not just that it is empty.
                val (title, body) = when (state.filter) {
                    TaskFilter.ALL -> R.string.tasks_empty_title to R.string.tasks_empty_body
                    TaskFilter.TODAY -> R.string.tasks_empty_today to R.string.tasks_empty_today_body
                    TaskFilter.UPCOMING -> R.string.tasks_empty_upcoming to R.string.tasks_empty_upcoming_body
                    TaskFilter.OVERDUE -> R.string.tasks_empty_overdue to R.string.tasks_empty_overdue_body
                    TaskFilter.COMPLETED -> R.string.tasks_empty_completed to R.string.tasks_empty_completed_body
                }
                StateMessage(
                    Icons.Rounded.TaskAlt, stringResource(title), Modifier.padding(top = Space.x3), body = stringResource(body),
                    action = if (state.filter == TaskFilter.OVERDUE || state.filter == TaskFilter.COMPLETED) null else ({
                        DsButton(stringResource(R.string.add_task), onAdd, style = DsButtonStyle.Tint, modifier = Modifier.emptyActionEntrance(0))
                    }),
                )
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
                val lift by animateFloatAsState(if (isDragged && !settling) 1f else 0f, m.snappy(), label = "lift")
                val moveUp = stringResource(R.string.action_move_up)
                val moveDown = stringResource(R.string.action_move_down)
                Box(
                    Modifier
                        .then(if (isDragged) Modifier else Modifier.then(itemMotion()))
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragged) dragOffset else 0f
                            shadowElevation = lift * liftPx
                            val scale = 1f + (m.liftScale - 1f) * lift // 3 % up while held, none with motion off
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
                                            haptics.tick()
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.y
                                            swapUnderFinger(task.id)
                                        },
                                        onDragEnd = {
                                            // The order is saved now; the glide only follows it.
                                            onReorder(manual.map { it.id })
                                            haptics.confirm()
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

        if (!state.canReorder) {
            val firstIndex = state.sections.runningFold(0) { acc, sec -> acc + sec.tasks.size }
            state.sections.forEachIndexed { si, section ->
                if (section.bucket != null) {
                    item(key = "h:" + section.bucket.name) {
                        SectionHeader(stringResource(bucketLabel(section.bucket)), Modifier.padding(top = Space.l).then(itemMotion()))
                    }
                } else {
                    item(key = "h:flat") { Spacer(Modifier.size(Space.s)) }
                }
                itemsIndexed(section.tasks, key = { _, t -> t.id }) { i, task ->
                    SwipeableTaskRow(
                        task, now,
                        completing = completing.containsKey(task.id), dimmed = dimmed,
                        onToggle = { complete(task.id, it) }, onOpen = { onOpen(task.id) },
                        modifier = Modifier
                            .staggeredEntrance(firstIndex[si] + i, play = entrance, gate = visible)
                            .then(itemMotion()),
                    )
                }
            }
        }
    }
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
    completing: Boolean,
    dimmed: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = DsTheme.colors
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val itemActions = app.daysemble.ui.actions.LocalItemActions.current
    // "Armed": past the threshold, so letting go now acts. The colour and icon have been growing with
    // the drag; at that moment there is one tick, so the user knows before lifting the finger.
    val armed by remember { derivedStateOf { state.targetValue != SwipeToDismissBoxValue.Settled } }
    val haptics = rememberHaptics()
    LaunchedEffect(state) {
        snapshotFlow { armed }.distinctUntilChanged().filter { it }.collect { haptics.tick() }
    }
    val completeLabel = stringResource(if (task.isCompleted) R.string.action_mark_incomplete else R.string.action_complete)
    val rescheduleLabel = stringResource(R.string.menu_reschedule)
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        // Right completes; left opens the quick reschedule options (Tomorrow, Next week, Pick date).
        // Either way the row comes back: completing moves it out of the list, rescheduling re-sorts it.
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onToggle(!task.isCompleted); scope.launch { state.reset() } }
                SwipeToDismissBoxValue.EndToStart -> { itemActions?.onTaskReschedule(task); scope.launch { state.reset() } }
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
        backgroundContent = { SwipeBackground(state.dismissDirection, { state.progress }, armed) },
    ) {
        Box(Modifier.background(c.background)) {
            TaskRow(
                task, now, onToggle = onToggle, onClick = onOpen,
                forceCompleted = completing,
                modifier = if (dimmed) Modifier.graphicsLayer { alpha = COMPLETED_ALPHA } else Modifier,
                onLongClick = itemActions?.let { a -> { a.onTaskLongPress(task) } },
                actions = listOf(
                    CustomAccessibilityAction(completeLabel) { onToggle(!task.isCompleted); true },
                    CustomAccessibilityAction(rescheduleLabel) { itemActions?.onTaskReschedule(task); itemActions != null },
                ),
            )
            HairlineDivider(Modifier.align(Alignment.BottomCenter), inset = true)
        }
    }
}

/** Completed tasks rest at about half opacity. */
private const val COMPLETED_ALPHA = 0.5f

/**
 * Behind a swiped row. The colour and the icon follow the drag ([progress], 0 to 1 at the
 * threshold): the fill fades from neutral to the action's colour and the icon grows and darkens.
 * Right is complete (green, a check), left is reschedule (accent, a calendar).
 */
@Composable
private fun SwipeBackground(dir: SwipeToDismissBoxValue, progress: () -> Float, armed: Boolean) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val complete = dir == SwipeToDismissBoxValue.StartToEnd
    val idle = dir == SwipeToDismissBoxValue.Settled
    val fill = if (complete) c.success.copy(alpha = 0.16f) else c.accent.copy(alpha = 0.14f)
    val tint = if (complete) c.success else c.accent
    // A little extra when armed, so the last stretch of the drag is felt as well as seen.
    val pop by animateFloatAsState(if (armed) 1.15f else 1f, m.snappy(), label = "swipePop")
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val p = progress().coerceIn(0f, 1f)
                if (!idle) {
                    drawRect(c.surfaceVariant.copy(alpha = 1f - p))
                    drawRect(fill.copy(alpha = fill.alpha * p))
                }
            }
            .padding(horizontal = Space.gutter),
        contentAlignment = if (complete) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        if (!idle) {
            Icon(
                if (complete) Icons.Rounded.Check else Icons.Rounded.EditCalendar, null, tint = tint,
                modifier = Modifier.graphicsLayer {
                    val p = progress().coerceIn(0f, 1f)
                    val k = (if (m.enabled) 0.6f + 0.4f * p else 1f) * pop
                    scaleX = k; scaleY = k
                    alpha = if (m.enabled) 0.4f + 0.6f * p else 1f
                },
            )
        }
    }
}

/** The filter chips: they animate their fill and text, scroll the picked one into view, and fade out at the right edge. */
@Composable
private fun FilterChips(state: TasksUiState, onFilter: (TaskFilter) -> Unit) {
    val c = DsTheme.colors
    val scroll = rememberScrollState()
    val requesters = remember { TaskFilter.entries.associateWith { BringIntoViewRequester() } }
    LaunchedEffect(state.filter) { requesters.getValue(state.filter).bringIntoView() }
    val fadeWidth = 24.dp
    Row(
        Modifier
            .fillMaxWidth()
            // Fade the content out at the right edge while there is more to scroll to.
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (scroll.canScrollForward) {
                    val w = fadeWidth.toPx()
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
                        topLeft = Offset(size.width - w, 0f), size = Size(w, size.height), blendMode = BlendMode.DstIn,
                    )
                }
            }
            .horizontalScroll(scroll)
            .padding(horizontal = Space.l - Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        TaskFilter.entries.forEach { f ->
            val count = state.counts[f] ?: 0
            DsChip(
                stringResource(filterLabel(f)), state.filter == f, { onFilter(f) },
                Modifier.bringIntoViewRequester(requesters.getValue(f)),
                count = count, alert = f == TaskFilter.OVERDUE,
            )
        }
    }
}

/** The sort options in a bottom sheet; the list reorders itself (rows glide to their new places) once one is picked. */
@Composable
private fun SortSheet(sort: TaskSort, onSort: (TaskSort) -> Unit, onDismiss: () -> Unit) {
    val c = DsTheme.colors
    val haptics = rememberHaptics()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surfaceRaised, shape = Radii.sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Space.l)) {
            Text(
                stringResource(R.string.cd_sort), style = DsTheme.type.title, color = c.textPrimary,
                modifier = Modifier.padding(horizontal = Space.gutter).semantics { heading() },
            )
            Spacer(Modifier.height(Space.s))
            TaskSort.entries.forEach { s ->
                val selectedText = stringResource(R.string.state_selected)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.touch + 4.dp)
                        .selectable(
                            selected = s == sort,
                            role = Role.RadioButton,
                            onClick = {
                                if (s != sort) haptics.tick()
                                onSort(s)
                            },
                        )
                        .semantics { if (s == sort) stateDescription = selectedText }
                        .padding(horizontal = Space.gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(sortLabel(s)), style = DsTheme.type.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                    if (s == sort) Icon(Icons.Rounded.Check, null, tint = c.textPrimary, modifier = Modifier.size(Sizes.icon))
                }
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
