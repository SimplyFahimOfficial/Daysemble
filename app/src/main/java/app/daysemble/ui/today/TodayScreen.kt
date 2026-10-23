package app.daysemble.ui.today

import app.daysemble.ui.components.itemMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.R
import app.daysemble.ui.brand.BrandLogo
import app.daysemble.ui.components.DashedDivider
import app.daysemble.ui.components.DateStrip
import app.daysemble.ui.components.DsChip
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.components.EventRow
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.components.StateMessage
import app.daysemble.ui.components.TaskRow
import app.daysemble.ui.daysembleViewModel
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.TimePrefs
import app.daysemble.ui.format.rememberMinuteTicker
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Space
import java.time.Instant

@Composable
fun TodayRoute(
    contentPadding: PaddingValues,
    onOpenEvent: (eventId: String, key: String) -> Unit,
    onOpenTask: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onFocusedDate: (java.time.LocalDate) -> Unit = {},
    onOpenSync: () -> Unit = {},
) {
    val prefs = LocalTimePrefs.current
    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as app.daysemble.DaysembleApp).container
    val rootSync = app.daysemble.ui.LocalSyncStatus.current
    val sync = if (rootSync != null) rootSync.value else {
        val syncFlow = androidx.compose.runtime.remember(container) { container.sync.status }
        syncFlow.collectAsStateWithLifecycle(initialValue = null).value
    }
    val vm = daysembleViewModel { c, h -> TodayViewModel(c.events, c.tasks, c.settings, c.clock, c.timeContext, h) }
    val state by vm.state.collectAsStateWithLifecycle()
    val now = rememberMinuteTicker()
    LaunchedEffect(now) { vm.onTick(now) }
    LaunchedEffect(state.date) { onFocusedDate(state.date) }
    TodayScreen(
        state = state, now = now, contentPadding = contentPadding,
        onSelectDate = vm::select, onToday = vm::goToToday,
        onToggleTask = vm::setCompleted, onOpenEvent = onOpenEvent, onOpenTask = onOpenTask,
        onSearch = onSearch, onSettings = onSettings,
        syncStatus = sync?.takeIf { it.account.isConnected }, onOpenSync = onOpenSync,
    )
}

@Composable
fun TodayScreen(
    state: TodayUiState,
    now: Instant,
    contentPadding: PaddingValues,
    onSelectDate: (java.time.LocalDate) -> Unit,
    onToday: () -> Unit,
    onToggleTask: (String, Boolean) -> Unit,
    onOpenEvent: (String, String) -> Unit,
    onOpenTask: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    syncStatus: app.daysemble.data.google.sync.SyncStatus? = null,
    onOpenSync: () -> Unit = {},
) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val p = LocalTimePrefs.current
    var showCompleted by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(c.background),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + Space.xxl),
    ) {
        item(key = "top") {
            Column(Modifier.statusBarsPadding()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Space.gutter, end = Space.xs, top = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandLogo(size = 30.dp)
                    Spacer(Modifier.weight(1f))
                    if (syncStatus != null) {
                        app.daysemble.ui.components.SyncBadge(
                            app.daysemble.ui.settings.syncIndicator(syncStatus),
                            app.daysemble.ui.settings.syncLabel(syncStatus, p),
                            Modifier
                                .weight(1f, fill = false)
                                .minimumInteractiveComponentSize()
                                .clip(Radii.pill)
                                .clickable(role = Role.Button, onClick = onOpenSync),
                        )
                    }
                    DsIconButton(Icons.Rounded.Search, stringResource(R.string.cd_search), onSearch)
                    DsIconButton(Icons.Rounded.Settings, stringResource(R.string.cd_settings), onSettings)
                }
                Header(state, p)
            }
        }
        item(key = "strip") {
            DateStrip(selected = state.date, today = state.today, onSelect = onSelectDate, modifier = Modifier.padding(top = Space.l))
        }
        item(key = "summary") {
            AnimatedContent(
                targetState = state,
                contentKey = { it.date },
                transitionSpec = {
                    val dir = if (targetState.date > initialState.date) 1 else -1
                    if (m.reduced) fadeIn(m.enter(m.short)) togetherWith fadeOut(m.exit())
                    else (fadeIn(m.enter()) + slideInHorizontally(m.enter()) { dir * it / 8 }) togetherWith
                        (fadeOut(m.exit()) + slideOutHorizontally(m.exit()) { -dir * it / 8 })
                },
                label = "summary",
            ) { s -> Summary(s, p) }
        }
        if (state.date != state.today) {
            item(key = "backToToday") {
                Row(Modifier.padding(horizontal = Space.gutter - Space.xs)) {
                    DsChip(stringResource(R.string.back_to_today), selected = false, onClick = onToday, role = Role.Button)
                }
            }
        }

        if (!state.isLoading && state.allDay.isEmpty() && state.timeline.isEmpty() && state.overdue.isEmpty() && state.completed.isEmpty()) {
            item(key = "empty") {
                StateMessage(
                    icon = Icons.Rounded.WbTwilight,
                    title = stringResource(R.string.today_empty_title),
                    body = stringResource(R.string.today_empty_body),
                    modifier = Modifier.padding(top = Space.x3),
                )
            }
        }

        if (state.allDay.isNotEmpty() || state.timeline.isNotEmpty()) {
            item(key = "dividerTop") { DashedDivider(Modifier.padding(horizontal = Space.gutter, vertical = Space.xs)) }
        }
        items(state.allDay, key = { it.key }) { item ->
            Column(Modifier.then(itemMotion())) {
                EventRow(item.event, now, onClick = { onOpenEvent(item.event.eventId, item.event.occurrenceKey) })
                DashedDivider(Modifier.padding(horizontal = Space.gutter))
            }
        }
        items(state.timeline, key = { it.key }) { item ->
            Column(Modifier.then(itemMotion())) {
                when (item) {
                    is TimelineItem.EventItem -> EventRow(
                        item.event, now,
                        onClick = { onOpenEvent(item.event.eventId, item.event.occurrenceKey) },
                        showCountdown = item.event.stableKey == state.focusEventKey,
                    )
                    is TimelineItem.TaskItem -> TaskRow(
                        item.task, now,
                        onToggle = { onToggleTask(item.task.id, it) },
                        onClick = { onOpenTask(item.task.id) },
                        showDue = false,
                    )
                }
                DashedDivider(Modifier.padding(horizontal = Space.gutter))
            }
        }

        if (state.overdue.isNotEmpty()) {
            item(key = "overdueHeader") {
                SectionHeader(
                    stringResource(R.string.section_overdue),
                    Modifier
                        .padding(top = Space.xl)
                        .then(itemMotion()),
                )
            }
            items(state.overdue, key = { "o:" + it.id }) { t ->
                TaskRow(
                    t, now, onToggle = { onToggleTask(t.id, it) }, onClick = { onOpenTask(t.id) },
                    modifier = Modifier.then(itemMotion()),
                )
            }
        }

        if (state.completed.isNotEmpty()) {
            item(key = "completedHeader") {
                CompletedHeader(state.completed.size, showCompleted, { showCompleted = !showCompleted }, Modifier.then(itemMotion()))
            }
            item(key = "completedList") {
                AnimatedVisibility(
                    visible = showCompleted,
                    enter = fadeIn(m.enter()) + expandVertically(m.enter()),
                    exit = fadeOut(m.exit()) + shrinkVertically(m.exit()),
                ) {
                    Column {
                        state.completed.forEach { t ->
                            TaskRow(t, now, onToggle = { onToggleTask(t.id, it) }, onClick = { onOpenTask(t.id) }, showDue = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: TodayUiState, p: TimePrefs) {
    val c = DsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter)
            .padding(top = Space.m)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.weekdayShort(state.date.dayOfWeek, p), style = DsTheme.type.display, color = c.textPrimary, maxLines = 1)
            Spacer(Modifier.size(Space.s))
            // The brand dot (from the wordmark). Coral on today, quiet on other days.
            Box(
                Modifier
                    .padding(top = Space.xs)
                    .size(14.dp)
                    .clip(Radii.pill)
                    .background(if (state.date == state.today) c.accentFill else c.outlineStrong),
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = Space.xs)) {
            Text(Fmt.monthDay(state.date, p), style = DsTheme.type.title, color = c.textSecondary, maxLines = 1)
            Text(state.date.year.toString(), style = DsTheme.type.title, color = c.textTertiary, maxLines = 1)
        }
    }
}

@Composable
private fun Summary(state: TodayUiState, p: TimePrefs) {
    val c = DsTheme.colors
    val strong = SpanStyle(color = c.textPrimary)
    val events = pluralStringResource(R.plurals.n_events, state.eventCount, state.eventCount)
    val tasks = pluralStringResource(R.plurals.n_tasks, state.openTaskCount, state.openTaskCount)
    val isToday = state.date == state.today
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = c.textSecondary)) {
            when {
                state.isLoading -> append(" ")
                state.eventCount == 0 && state.openTaskCount == 0 ->
                    append(stringResource(if (isToday) R.string.summary_nothing_today else R.string.summary_nothing_day))
                else -> {
                    append(stringResource(if (isToday) R.string.summary_today_prefix else R.string.summary_day_prefix))
                    append(" ")
                    val parts = buildList {
                        if (state.eventCount > 0) add(events)
                        if (state.openTaskCount > 0) add(tasks)
                    }
                    parts.forEachIndexed { i, s ->
                        if (i > 0) { append(" "); append(stringResource(R.string.summary_and)); append(" ") }
                        withStyle(strong) { append(s) }
                    }
                    append(".")
                    state.freeAfter?.let {
                        append(" ")
                        append(stringResource(R.string.summary_free_after_prefix))
                        append(" ")
                        withStyle(strong) { append(Fmt.time(it, p)) }
                        append(".")
                    }
                }
            }
        }
    }
    Text(
        text,
        style = DsTheme.type.summary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.l),
    )
}

@Composable
private fun CompletedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, DsTheme.motion.standard(), label = "chev")
    Row(
        modifier
            .padding(top = Space.l)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = Space.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pluralStringResource(R.plurals.completed_count, count, count).uppercase(),
            style = DsTheme.type.caps, color = c.textSecondary, modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Rounded.ExpandMore,
            stringResource(if (expanded) R.string.cd_collapse else R.string.cd_expand),
            tint = c.textSecondary,
            modifier = Modifier.graphicsLayer { rotationZ = rotation },
        )
    }
}
