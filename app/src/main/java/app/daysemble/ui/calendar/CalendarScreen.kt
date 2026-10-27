package app.daysemble.ui.calendar

import app.daysemble.ui.components.itemMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.R
import app.daysemble.domain.model.EventOccurrence
import app.daysemble.ui.components.DsChip
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.components.DsSegmented
import app.daysemble.ui.components.DashedDivider
import app.daysemble.ui.components.EventRow
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.components.StateMessage
import app.daysemble.ui.components.TaskRow
import app.daysemble.ui.components.eventColor
import app.daysemble.ui.daysembleViewModel
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.dayLabel
import app.daysemble.ui.format.rememberMinuteTicker
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape

private const val PAGES = 20_000
private const val CENTER = PAGES / 2

@Composable
fun CalendarRoute(
    contentPadding: PaddingValues,
    onOpenEvent: (String, String) -> Unit,
    onOpenTask: (String) -> Unit,
    onCreateAt: (LocalDate, Int?) -> Unit,
    onFocusedDate: (LocalDate) -> Unit = {},
) {
    val prefs = LocalTimePrefs.current
    val vm = daysembleViewModel { c, h -> CalendarViewModel(c.events, c.tasks, c.clock, c.timeContext, h) }
    val state by vm.state.collectAsStateWithLifecycle()
    val now = rememberMinuteTicker()
    LaunchedEffect(state.selected) { onFocusedDate(state.selected) }
    LaunchedEffect(now) { vm.onTick(now) }
    val c = DsTheme.colors
    val m = DsTheme.motion

    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.xs, top = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The title changes only when the month (or, in Day view, the day) changes, and fades
            // through in the direction of travel instead of cross-fading two titles over each other.
            val mode = state.mode
            AnimatedContent(
                state.selected,
                contentKey = { if (mode == CalendarMode.DAY) it else YearMonth.from(it) },
                transitionSpec = { m.fadeThrough(targetState.compareTo(initialState).coerceIn(-1, 1), rtl) },
                label = "calTitle", modifier = Modifier.weight(1f),
            ) { d ->
                val t = titleFor(mode, d)
                Text(
                    t, style = if (t.length > 12) DsTheme.type.titleLarge else DsTheme.type.headline, color = c.textPrimary,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (state.selected != state.today) DsChip(stringResource(R.string.day_today), false, vm::today, role = Role.Button)
            DsIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.cd_previous), { vm.step(-1) })
            DsIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.cd_next), { vm.step(1) })
        }
        DsSegmented(
            options = CalendarMode.entries, selected = state.mode,
            label = { stringResource(modeLabel(it)) }, onSelect = vm::setMode,
            modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.m),
        )
        // Changing the view swaps the area below the switcher only; header and switcher stay put.
        AnimatedContent(
            state.mode,
            transitionSpec = { m.fadeThrough(Integer.signum(targetState.ordinal - initialState.ordinal), rtl) },
            label = "calMode",
            modifier = Modifier.weight(1f),
        ) { mode ->
            when (mode) {
                CalendarMode.MONTH -> MonthMode(state, now, vm, contentPadding, onOpenEvent, onOpenTask, onCreateAt)
                CalendarMode.WEEK, CalendarMode.DAY -> GridMode(state, now, vm, mode, contentPadding, onOpenEvent, onCreateAt)
                CalendarMode.AGENDA -> AgendaMode(state, now, vm, contentPadding, onOpenEvent, onOpenTask)
            }
        }
    }
}

@Composable
private fun titleFor(mode: CalendarMode, d: LocalDate): String {
    val p = LocalTimePrefs.current
    return when (mode) {
        CalendarMode.DAY -> Fmt.mediumDate(d, p)
        else -> Fmt.monthYear(d, p)
    }
}

private fun modeLabel(m: CalendarMode) = when (m) {
    CalendarMode.MONTH -> R.string.mode_month
    CalendarMode.WEEK -> R.string.mode_week
    CalendarMode.DAY -> R.string.mode_day
    CalendarMode.AGENDA -> R.string.mode_agenda
}

/** Keeps a pager and the VM selection in sync in both directions. */
@Composable
private fun SyncPager(pager: PagerState, targetPage: Int, onPageSettled: (Int) -> Unit) {
    val m = DsTheme.motion
    val settled by androidx.compose.runtime.rememberUpdatedState(onPageSettled)
    LaunchedEffect(targetPage) {
        if (pager.currentPage != targetPage) {
            // Arrow/"Today" jumps use the app's motion curve instead of the pager's default spring.
            if (m.reduced) pager.scrollToPage(targetPage) else pager.animateScrollToPage(targetPage, animationSpec = m.enter(m.long))
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { settled(it) }
    }
}

@Composable
private fun MonthMode(
    state: CalendarUiState,
    now: Instant,
    vm: CalendarViewModel,
    contentPadding: PaddingValues,
    onOpenEvent: (String, String) -> Unit,
    onOpenTask: (String) -> Unit,
    onCreateAt: (LocalDate, Int?) -> Unit,
) {
    val p = LocalTimePrefs.current
    val base = YearMonth.from(state.today)
    val page = CENTER + ChronoUnit.MONTHS.between(base, YearMonth.from(state.selected)).toInt()
    val pager = androidx.compose.runtime.key(base) { rememberPagerState(initialPage = page) { PAGES } }
    SyncPager(pager, page) { settled ->
        val ym = base.plusMonths((settled - CENTER).toLong())
        if (YearMonth.from(vm.state.value.selected) != ym) vm.select(if (ym == base) state.today else ym.atDay(1))
    }
    LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + Space.xxl)) {
        item(key = "weekdays") {
            Row(Modifier.padding(horizontal = Space.m)) {
                (0 until 7).forEach { i ->
                    Text(
                        Fmt.weekdayNarrow(p.firstDayOfWeek.plus(i.toLong()), p),
                        style = DsTheme.type.caps, color = DsTheme.colors.textTertiary,
                        modifier = Modifier.weight(1f).padding(vertical = Space.xs),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        item(key = "grid") {
            HorizontalPager(pager, beyondViewportPageCount = 1) { pg ->
                val ym = base.plusMonths((pg - CENTER).toLong())
                MonthGrid(ym, vm.gridStart(ym), state, onSelect = vm::select)
            }
        }
        item(key = "dayHeader") {
            SectionHeader(dayLabel(state.selected, state.today, p), Modifier.padding(top = Space.l)) {
                DsChip(stringResource(R.string.add_event), false, { onCreateAt(state.selected, null) }, role = Role.Button)
            }
        }
        val evs = state.byDate[state.selected].orEmpty()
        val tasks = state.tasksByDate[state.selected].orEmpty()
        if (evs.isEmpty() && tasks.isEmpty() && !state.isLoading) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.day_empty), style = DsTheme.type.bodySmall, color = DsTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.m),
                )
            }
        }
        items(evs, key = { "e" + it.stableKey }) { e ->
            Column(Modifier.then(itemMotion())) {
                EventRow(e, now, onClick = { onOpenEvent(e.eventId, e.occurrenceKey) }, showCountdown = state.selected == state.today)
                DashedDivider(Modifier.padding(horizontal = Space.gutter))
            }
        }
        items(tasks, key = { "t" + it.id }) { t ->
            Column(Modifier.then(itemMotion())) {
                TaskRow(t, now, onToggle = { vm.setTaskCompleted(t.id, it) }, onClick = { onOpenTask(t.id) }, showDue = false)
                DashedDivider(Modifier.padding(horizontal = Space.gutter))
            }
        }
    }
}

@Composable
private fun MonthGrid(ym: YearMonth, gridStart: LocalDate, state: CalendarUiState, onSelect: (LocalDate) -> Unit) {
    val weeks = run {
        val last = ym.atEndOfMonth()
        ((ChronoUnit.DAYS.between(gridStart, last) / 7) + 1).toInt()
    }
    Column(Modifier.padding(horizontal = Space.m)) {
        repeat(weeks) { w ->
            Row {
                repeat(7) { i ->
                    val d = gridStart.plusDays((w * 7 + i).toLong())
                    MonthCell(
                        d, inMonth = YearMonth.from(d) == ym, selected = d == state.selected, isToday = d == state.today,
                        events = state.byDate[d].orEmpty(), taskCount = state.tasksByDate[d]?.size ?: 0,
                        onClick = { onSelect(d) }, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    d: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    isToday: Boolean,
    events: List<EventOccurrence>,
    taskCount: Int,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val p = LocalTimePrefs.current
    val bg by animateColorAsState(if (selected) c.inverse else Color.Transparent, m.indicator(), label = "cellBg")
    val count = events.size + taskCount
    val a11y = Fmt.fullDate(d, p) + (if (isToday) ", " + stringResource(R.string.today_suffix) else "") + ", " + pluralStringResource(R.plurals.n_items, count, count)
    // The whole cell stays the touch target, but its press/focus feedback is drawn only on the date
    // circle (see DateCircle). The cell used the default bounded ripple, which filled its rectangle
    // and showed as a grey square around the circle on every tap.
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .heightIn(min = 52.dp)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = a11y }
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .dateCircle(interaction, bg)
                .then(if (isToday && !selected) Modifier.border(1.5.dp, c.accentFill, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                d.dayOfMonth.toString(),
                style = DsTheme.type.bodyStrong,
                color = when {
                    selected -> c.onInverse
                    isToday -> c.accent
                    inMonth -> c.textPrimary
                    else -> c.textTertiary
                },
            )
        }
        Row(Modifier.height(8.dp).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            events.take(3).forEach { e ->
                Box(Modifier.size(5.dp).clip(Radii.pill).background(eventColor(e.colorArgb).copy(alpha = if (inMonth) 1f else 0.4f)))
            }
            if (events.size < 3 && taskCount > 0) Box(Modifier.size(5.dp).clip(Radii.pill).background(c.outlineStrong))
        }
    }
}

/**
 * The round date marker shared by month cells and the week header: selection fill, plus the press
 * and keyboard-focus feedback of the surrounding cell ([interaction]), all clipped to the same circle.
 */
private fun Modifier.dateCircle(interaction: MutableInteractionSource, fill: Color, size: Dp = 38.dp): Modifier = this
    .defaultMinSize(size, size)
    .clip(CircleShape)
    .background(fill)
    .indication(interaction, ripple(bounded = false, radius = size / 2))

@Composable
private fun GridMode(
    state: CalendarUiState,
    now: Instant,
    vm: CalendarViewModel,
    mode: CalendarMode,
    contentPadding: PaddingValues,
    onOpenEvent: (String, String) -> Unit,
    onCreateAt: (LocalDate, Int?) -> Unit,
) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    val isWeek = mode == CalendarMode.WEEK
    val baseStart = if (isWeek) vm.weekStart(state.today) else state.today
    val selStart = if (isWeek) vm.weekStart(state.selected) else state.selected
    val page = CENTER + (if (isWeek) ChronoUnit.WEEKS.between(baseStart, selStart) else ChronoUnit.DAYS.between(baseStart, selStart)).toInt()
    val pager = androidx.compose.runtime.key(baseStart, isWeek) { rememberPagerState(initialPage = page) { PAGES } }
    SyncPager(pager, page) { settled ->
        val start = if (isWeek) baseStart.plusWeeks((settled - CENTER).toLong()) else baseStart.plusDays((settled - CENTER).toLong())
        val cur = vm.state.value.selected
        if (isWeek) { if (vm.weekStart(cur) != start) vm.select(start.plusDays(ChronoUnit.DAYS.between(vm.weekStart(cur), cur))) }
        else if (cur != start) vm.select(start)
    }
    Column(Modifier.padding(bottom = contentPadding.calculateBottomPadding())) {
        HorizontalPager(pager, beyondViewportPageCount = 1, modifier = Modifier.weight(1f)) { pg ->
            val start = if (isWeek) baseStart.plusWeeks((pg - CENTER).toLong()) else baseStart.plusDays((pg - CENTER).toLong())
            val days = (0 until if (isWeek) 7 else 1).map { start.plusDays(it.toLong()) }
            Column {
                if (isWeek) {
                    Row(Modifier.fillMaxWidth().padding(start = 52.dp)) {
                        days.forEach { d ->
                            val sel = d == state.selected
                            val selBg by androidx.compose.animation.animateColorAsState(if (sel) c.inverse else Color.Transparent, DsTheme.motion.indicator(), label = "weekSel")
                            val selFg by androidx.compose.animation.animateColorAsState(
                                if (sel) c.onInverse else if (d == state.today) c.accent else c.textPrimary, DsTheme.motion.indicator(), label = "weekSelText",
                            )
                            val interaction = remember { MutableInteractionSource() }
                            Column(
                                Modifier
                                    .weight(1f)
                                    .selectable(sel, interactionSource = interaction, indication = null, role = Role.Tab) { vm.select(d) }
                                    .padding(vertical = Space.xs),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(Fmt.weekdayNarrow(d.dayOfWeek, p), style = DsTheme.type.caps, color = if (d == state.today) c.accent else c.textTertiary)
                                Box(Modifier.padding(top = 2.dp).dateCircle(interaction, selBg, size = 32.dp), contentAlignment = Alignment.Center) {
                                    Text(d.dayOfMonth.toString(), style = DsTheme.type.bodyStrong, color = selFg)
                                }
                            }
                        }
                    }
                }
                TimeGrid(
                    days = days, byDate = state.byDate, today = state.today, now = now,
                    onEvent = { onOpenEvent(it.eventId, it.occurrenceKey) },
                    onEmptySlot = { d, minute -> onCreateAt(d, minute) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun AgendaMode(
    state: CalendarUiState,
    now: Instant,
    vm: CalendarViewModel,
    contentPadding: PaddingValues,
    onOpenEvent: (String, String) -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val p = LocalTimePrefs.current
    val days = (0 until CalendarViewModel.AGENDA_DAYS).map { state.selected.plusDays(it.toLong()) }
        .filter { state.byDate[it].orEmpty().isNotEmpty() || state.tasksByDate[it].orEmpty().isNotEmpty() }
    if (days.isEmpty() && !state.isLoading) {
        StateMessage(
            Icons.Rounded.EventAvailable, stringResource(R.string.agenda_empty_title),
            Modifier.padding(top = Space.x3), body = stringResource(R.string.agenda_empty_body),
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + Space.xxl)) {
        days.forEach { d ->
            item(key = "h$d") {
                SectionHeader(dayLabel(d, state.today, p) + if (d == state.today || d == state.today.plusDays(1)) " · " + Fmt.mediumDate(d, p) else "", Modifier.padding(top = Space.m))
            }
            items(state.byDate[d].orEmpty(), key = { "e$d" + it.stableKey }) { e ->
                EventRow(e, now, onClick = { onOpenEvent(e.eventId, e.occurrenceKey) }, showCountdown = d == state.today, modifier = Modifier.then(itemMotion()))
            }
            items(state.tasksByDate[d].orEmpty(), key = { "t$d" + it.id }) { t ->
                TaskRow(t, now, onToggle = { vm.setTaskCompleted(t.id, it) }, onClick = { onOpenTask(t.id) }, showDue = false, modifier = Modifier.then(itemMotion()))
            }
        }
        item(key = "spacer") { Spacer(Modifier.height(Space.l)) }
    }
}

