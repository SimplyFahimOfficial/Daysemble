package app.daysemble.ui.calendar

import app.daysemble.ui.components.itemMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.offset
import app.daysemble.ui.components.ContentSwap
import app.daysemble.ui.components.DayMarks
import app.daysemble.ui.components.DayPeek
import app.daysemble.ui.components.DiscMap
import app.daysemble.ui.components.TASK_MARK
import app.daysemble.ui.components.animateStateColor
import app.daysemble.ui.components.discCell
import app.daysemble.ui.components.discContainer
import app.daysemble.ui.components.rememberHaptics
import app.daysemble.ui.components.rememberSlidingDisc
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.R
import app.daysemble.domain.model.EventOccurrence
import app.daysemble.ui.components.DsChip
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.components.DsSegmented
import app.daysemble.ui.components.DateDisc
import app.daysemble.ui.components.AgendaEventCard
import app.daysemble.ui.components.AgendaTaskCard
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.components.StateMessage
import app.daysemble.ui.daysembleViewModel
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.dayLabel
import app.daysemble.ui.format.rememberMinuteTicker
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import androidx.compose.foundation.interaction.MutableInteractionSource

private const val PAGES = 20_000
private const val CENTER = PAGES / 2
private const val COLLAPSE_FADE_FACTOR = 1.5f

/** Day and Agenda are views of their own; Month and Week are one view (see MonthWeekMode). */
private fun CalendarMode.isOwnView() = this == CalendarMode.DAY || this == CalendarMode.AGENDA

@Composable
fun CalendarRoute(
    contentPadding: PaddingValues,
    onOpenEvent: (String, String) -> Unit,
    onOpenTask: (String) -> Unit,
    onCreateAt: (LocalDate, Int?) -> Unit,
    onFocusedDate: (LocalDate) -> Unit = {},
) {
    val vm = daysembleViewModel { c, h -> CalendarViewModel(c.events, c.tasks, c.clock, c.timeContext, h) }
    val state by vm.state.collectAsStateWithLifecycle()
    val now = rememberMinuteTicker()
    LaunchedEffect(state.selected) { onFocusedDate(state.selected) }
    LaunchedEffect(now) { vm.onTick(now) }
    val c = DsTheme.colors
    val m = DsTheme.motion
    val haptics = rememberHaptics()

    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    // 0 = Month, 1 = Week: one value for the control and for the vertical drag on the grid. The
    // grid's rows collapse to the selected week as it grows (see MonthWeekMode).
    val morph = remember {
        MorphState(if (state.mode == CalendarMode.WEEK) 1f else 0f).also { it.offscreen = state.mode.isOwnView() }
    }
    // Read now, before the Month/Week content below is composed (it marks the morph as on screen).
    val enteringFromOwnView = morph.offscreen
    LaunchedEffect(state.mode) {
        val to = when (state.mode) { CalendarMode.WEEK -> 1f; CalendarMode.MONTH -> 0f; else -> return@LaunchedEffect }
        // Coming back from Day or Agenda the Month/Week content fades in already in its final layout;
        // animating there would show the layout the morph was last left in, then fold into the right one.
        if (enteringFromOwnView) morph.snapTo(to) else morph.settle(to, 0f, m.spring())
    }
    // One vertical scroll for the time grids (Week and Day, every page): the hour you were looking at
    // stays when switching between them and when paging, instead of each page jumping to 08:00.
    val timeScroll = rememberTimeScroll(now, nowVisible = vm.weekStart(state.selected) == vm.weekStart(state.today))
    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.xs, top = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The title changes only when the month (or, in Day view, the day) changes. It slides a
            // little in the direction of travel while the grid below slides a full page: parallax.
            val mode = state.mode
            AnimatedContent(
                state.selected,
                contentKey = { if (mode == CalendarMode.DAY) it else YearMonth.from(it) },
                transitionSpec = { m.titleSlide(targetState.compareTo(initialState).coerceIn(-1, 1), rtl) },
                label = "calTitle", modifier = Modifier.weight(1f),
            ) { d ->
                val t = titleFor(mode, d)
                Text(
                    t, style = if (t.length > 12) DsTheme.type.titleLarge else DsTheme.type.headline, color = c.textPrimary,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
            // Appears (scale and fade) when another month or date is showing; tapping it goes back to
            // today in the right direction. The title is not a button.
            AnimatedVisibility(
                visible = state.selected != state.today,
                enter = scaleIn(m.snappy(), initialScale = 0.6f) + fadeIn(m.snappy()),
                exit = scaleOut(m.snappy(), targetScale = 0.6f) + fadeOut(m.snappy()),
            ) {
                DsChip(stringResource(R.string.day_today), false, { haptics.tick(); vm.today() }, role = Role.Button)
            }
            DsIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.cd_previous), { vm.step(-1) })
            DsIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.cd_next), { vm.step(1) })
        }
        DsSegmented(
            options = CalendarMode.entries, selected = state.mode,
            label = { stringResource(modeLabel(it)) }, onSelect = vm::setMode,
            modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.m),
        )
        // Month and Week are one view that morphs; Day and Agenda cross-fade. Header and switcher stay put.
        val group = when (state.mode) { CalendarMode.MONTH, CalendarMode.WEEK -> CalendarMode.MONTH; else -> state.mode }
        AnimatedContent(
            group,
            // The new view fades in (as before, 250 ms) over the old one, which stays drawn until it is
            // covered. Before, the old one faded out in 150 ms while the new one was still translucent:
            // two layouts on top of each other with the page showing through. The old view fades to 99.9 %,
            // not 100 %: AnimatedContent drops an exiting child when its own exit animation ends, and a
            // fade from 1 to 1 ends at once.
            transitionSpec = { fadeIn(tween(m.medium)) togetherWith fadeOut(tween(m.medium), targetAlpha = 0.999f) },
            label = "calMode",
            modifier = Modifier.weight(1f),
        ) { g ->
            // Opaque, so the view being covered never shows through the one fading in.
            Box(Modifier.fillMaxSize().background(c.background)) {
                when (g) {
                    CalendarMode.MONTH, CalendarMode.WEEK ->
                        MonthWeekMode(state, now, vm, morph, timeScroll, contentPadding, onOpenEvent, onOpenTask, onCreateAt)
                    CalendarMode.DAY -> GridMode(state, now, vm, CalendarMode.DAY, timeScroll, contentPadding, onOpenEvent, onCreateAt)
                    CalendarMode.AGENDA -> AgendaMode(state, now, vm, contentPadding, onOpenEvent, onOpenTask)
                }
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
            if (!m.enabled) pager.scrollToPage(targetPage) else pager.animateScrollToPage(targetPage, animationSpec = m.spring())
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { settled(it) }
    }
}

/**
 * Month and Week as one view. [morph] (0 = Month, 1 = Week) is driven both by the mode control and by
 * a vertical drag on the grid: the rows other than the selected week collapse and fade, the
 * selected week stays, and the weekday header and the week's cells slide into the columns of the
 * time grid. The day list fades out as the time grid fades in.
 */
@Composable
private fun MonthWeekMode(
    state: CalendarUiState,
    now: Instant,
    vm: CalendarViewModel,
    morph: MorphState,
    timeScroll: ScrollState,
    contentPadding: PaddingValues,
    onOpenEvent: (String, String) -> Unit,
    onOpenTask: (String) -> Unit,
    onCreateAt: (LocalDate, Int?) -> Unit,
) {
    val p = LocalTimePrefs.current
    val m = DsTheme.motion
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val base = YearMonth.from(state.today)
    val page = CENTER + ChronoUnit.MONTHS.between(base, YearMonth.from(state.selected)).toInt()
    val pager = androidx.compose.runtime.key(base) { rememberPagerState(initialPage = page) { PAGES } }
    SyncPager(pager, page) { settled ->
        val ym = base.plusMonths((settled - CENTER).toLong())
        if (YearMonth.from(vm.state.value.selected) != ym) vm.select(if (ym == base) state.today else ym.atDay(1))
    }
    // The day list and the week's time grid are composed as soon as the mode (or a drag) heads toward
    // them, not in the first animated frame, so the composition of the time grid doesn't land inside
    // the morph and skip its first frames. They are still invisible until the morph reaches them.
    val dragging = morph.dragging
    val showList by remember { derivedStateOf { morph.value < 0.999f } }
    val showWeek by remember { derivedStateOf { morph.value > 0.001f } }
    val listComposed = showList || dragging || state.mode == CalendarMode.MONTH
    val weekComposed = showWeek || dragging || state.mode == CalendarMode.WEEK
    DisposableEffect(morph) {
        morph.offscreen = false
        onDispose { morph.offscreen = true }
    }
    // Only the crossing of the middle matters here, so the pager is not recomposed on every frame of the morph.
    val monthSwipes by remember { derivedStateOf { morph.value < 0.5f } }
    var peek by remember { mutableStateOf<Pair<LocalDate, Rect?>?>(null) }
    // The drag covers the five rows that collapse.
    val travel = with(density) { (MONTH_ROW_HEIGHT * (MONTH_ROWS - 1)).toPx() }
    morph.travel = travel
    // The finger and the settle share one owner (MorphState): grabbing the grid mid-settle stops it
    // and continues from the live value.
    val past by remember { derivedStateOf { morph.value > 0.5f } }
    LaunchedEffect(past) { if (morph.dragging) haptics.tick() }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.draggable(
                morph, Orientation.Vertical,
                onDragStopped = { velocity ->
                    // Past halfway, or flung toward the other state: settle there, carrying the release velocity.
                    val toWeek = if (kotlin.math.abs(velocity) > 600f) velocity < 0f else morph.value > 0.5f
                    // Started now, before the mode change below can ask for the same settle without the velocity.
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        morph.settle(if (toWeek) 1f else 0f, -velocity / travel, m.spring())
                    }
                    vm.setMode(if (toWeek) CalendarMode.WEEK else CalendarMode.MONTH)
                },
            ),
        ) {
            Row(Modifier.morphInset({ morph.value })) {
                (0 until 7).forEach { i ->
                    Text(
                        Fmt.weekdayNarrow(p.firstDayOfWeek.plus(i.toLong()), p),
                        style = DsTheme.type.caps, color = DsTheme.colors.textTertiary,
                        modifier = Modifier.weight(1f).padding(vertical = Space.xs),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            HorizontalPager(pager, beyondViewportPageCount = 1, userScrollEnabled = monthSwipes) { pg ->
                val ym = base.plusMonths((pg - CENTER).toLong())
                MonthGrid(
                    ym, vm.gridStart(ym), state, collapse = { morph.value },
                    onSelect = vm::select, onPeek = { d, r -> peek = d to r },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (listComposed) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - morph.value * 2f).coerceIn(0f, 1f) }) {
                    DayList(state, now, vm, contentPadding, onOpenEvent, onOpenTask, onCreateAt)
                }
            }
            if (weekComposed) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = (morph.value * 2f - 1f).coerceIn(0f, 1f) }) {
                    GridMode(state, now, vm, CalendarMode.WEEK, timeScroll, contentPadding, onOpenEvent, onCreateAt)
                }
            }
        }
    }
    peek?.let { (d, r) -> DayPeek(d, r, onDismiss = { peek = null }, onAddEvent = { onCreateAt(it, null) }) }
}

/**
 * The Month (0) to Week (1) morph, with one owner. The finger drives it as a [DraggableState] and
 * [settle] animates it; both go through one mutex, so grabbing the grid stops a running settle and
 * a new settle replaces the last, always from the live value. (Before, every drag delta launched its
 * own coroutine that read a value its predecessor had not written yet, and the release started two
 * animations of the same value, neither with the finger's velocity.)
 */
@Stable
internal class MorphState(initial: Float) : DraggableState {
    var value by mutableFloatStateOf(initial)
        private set
    /** Pixels of vertical drag for the whole morph; set by the screen. */
    var travel = 1f
    /** True while a finger holds the grid. */
    var dragging by mutableStateOf(false)
        private set
    /** True while Day or Agenda is showing instead of the Month/Week content (set by that content). */
    var offscreen = false
    private var settlingTo: Float? = null
    private val mutex = MutatorMutex()
    private val scope = object : DragScope {
        override fun dragBy(pixels: Float) = dispatchRawDelta(pixels)
    }

    override fun dispatchRawDelta(delta: Float) { value = (value - delta / travel).coerceIn(0f, 1f) }

    override suspend fun drag(dragPriority: MutatePriority, block: suspend DragScope.() -> Unit) {
        mutex.mutateWith(scope, dragPriority) {
            dragging = true
            settlingTo = null
            try { block() } finally { dragging = false }
        }
    }

    /** Jumps to [to] (0 or 1); stops any settle and any drag in progress. */
    suspend fun snapTo(to: Float) {
        mutex.mutate {
            settlingTo = null
            value = to
        }
    }

    /** Animates to [to] (0 or 1) from the live value; a request for the target already under way is ignored. */
    suspend fun settle(to: Float, velocity: Float, spec: AnimationSpec<Float>) {
        if (settlingTo == to) return
        mutex.mutate {
            settlingTo = to
            try {
                animate(value, to, velocity, spec) { v, _ -> value = v.coerceIn(0f, 1f) }
            } finally {
                if (settlingTo == to) settlingTo = null
            }
        }
    }
}

/** Height of one month row at normal font size; also how far a vertical drag travels per collapsed row. */
private val MONTH_ROW_HEIGHT = 52.dp

/** Insets the content from the start by the month grid's margin, easing to the time grid's gutter as [p] goes 0 to 1. */
private fun Modifier.morphInset(p: () -> Float): Modifier = layout { measurable, constraints ->
    val t = p().coerceIn(0f, 1f)
    val start = androidx.compose.ui.unit.lerp(Space.m, TIME_GUTTER, t).roundToPx()
    val end = androidx.compose.ui.unit.lerp(Space.m, 0.dp, t).roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = -(start + end)))
    layout(placeable.width + start + end, placeable.height) { placeable.place(start, 0) }
}

/** The width of the hour labels at the start of the time grid. */
private val TIME_GUTTER = 52.dp

/** The selected day's events and tasks under the month grid; another day replaces the list in one piece. */
@Composable
private fun DayList(
    state: CalendarUiState,
    now: Instant,
    vm: CalendarViewModel,
    contentPadding: PaddingValues,
    onOpenEvent: (String, String) -> Unit,
    onOpenTask: (String) -> Unit,
    onCreateAt: (LocalDate, Int?) -> Unit,
) {
    val onCompleted = app.daysemble.ui.LocalTaskCompleted.current
    val p = LocalTimePrefs.current
    val itemActions = app.daysemble.ui.actions.LocalItemActions.current
    // A list that has only just been composed (the morph back to Month, a tab shown) is faded in by
    // whatever shows it; only a later change of day fades the old list into the new one.
    val first = remember { booleanArrayOf(true) }
    val swapFade = !first[0]
    SideEffect { first[0] = false }
    ContentSwap(state.selected, fade = swapFade) {
        LazyColumn(Modifier.testTag(CalendarTags.DAY_LIST), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + Space.xxl)) {
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
            val day = state.selected
            items(evs, key = { e -> "e$day" + e.stableKey }) { e ->
                AgendaEventCard(
                    e, now, onClick = { onOpenEvent(e.eventId, e.occurrenceKey) },
                    onLongClick = itemActions?.let { a -> { a.onEventLongPress(e) } },
                    showCountdown = state.selected == state.today,
                    modifier = itemMotion(fadeOut = false),
                )
            }
            items(tasks, key = { t -> "t$day" + t.id }) { t ->
                AgendaTaskCard(
                    t, now, onToggle = { done -> vm.setTaskCompleted(t.id, done) { r -> if (done) onCompleted(t.id, r) } }, onClick = { onOpenTask(t.id) },
                    modifier = itemMotion(fadeOut = false),
                    onLongClick = itemActions?.let { a -> { a.onTaskLongPress(t) } },
                )
            }
        }
    }
}

@Composable
private fun MonthGrid(
    ym: YearMonth,
    gridStart: LocalDate,
    state: CalendarUiState,
    collapse: () -> Float,
    onSelect: (LocalDate) -> Unit,
    onPeek: (LocalDate, Rect?) -> Unit,
) {
    // Always six rows (the most a month needs), so the grid, and everything under it, keeps its
    // height while paging between 5- and 6-week months instead of jumping when a swipe settles.
    // The days of the neighbouring months are shown dimmed. The view model loads 42 days from each
    // grid start, so they have their events.
    val haptics = rememberHaptics()
    val selectedIndex = ChronoUnit.DAYS.between(gridStart, state.selected).toInt().takeIf { it in 0 until MONTH_ROWS * 7 }
    val disc = rememberSlidingDisc(selectedIndex?.let { it % 7 to it / 7 })
    val map = remember { DiscMap(7, MONTH_ROWS) }
    val ink = DsTheme.colors.inverse
    // The selected week stays when the rest collapses; a page without the selection keeps the first row.
    val keepRow = selectedIndex?.div(7) ?: 0
    Column(
        Modifier
            .morphInset(collapse)
            .discContainer(map)
            .drawBehind { disc.draw(this, map, ink) },
    ) {
        repeat(MONTH_ROWS) { w ->
            Row(Modifier.collapsing(if (w == keepRow) null else collapse)) {
                repeat(7) { i ->
                    val d = gridStart.plusDays((w * 7 + i).toLong())
                    MonthCell(
                        d, inMonth = YearMonth.from(d) == ym, selected = d == state.selected, isToday = d == state.today,
                        events = state.byDate[d].orEmpty(), taskCount = state.tasksByDate[d]?.size ?: 0,
                        index = w * 7 + i, map = map,
                        onClick = {
                            if (d != state.selected) haptics.tick()
                            onSelect(d)
                        },
                        onLongClick = { r -> haptics.tick(); onPeek(d, r) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** A row that shrinks to nothing and fades as [collapse] goes 0 to 1 (null: it never collapses). */
private fun Modifier.collapsing(collapse: (() -> Float)?): Modifier =
    if (collapse == null) this
    else this
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val h = (placeable.height * (1f - collapse().coerceIn(0f, 1f))).roundToInt()
            layout(placeable.width, h) { placeable.place(0, 0) }
        }
        .graphicsLayer { alpha = (1f - collapse().coerceIn(0f, 1f) * COLLAPSE_FADE_FACTOR).coerceIn(0f, 1f); clip = true }
        // Once folded away the row is clipped to nothing; it must not be read out or focused either.
        .semantics { if (collapse() >= 1f) invisibleToUser() }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthCell(
    d: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    isToday: Boolean,
    events: List<EventOccurrence>,
    taskCount: Int,
    index: Int,
    map: DiscMap,
    onClick: () -> Unit,
    onLongClick: (Rect?) -> Unit,
    modifier: Modifier,
) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val p = LocalTimePrefs.current
    val count = events.size + taskCount
    val a11y = Fmt.fullDate(d, p) + (if (isToday) ", " + stringResource(R.string.today_suffix) else "") + ", " + pluralStringResource(R.plurals.n_items, count, count)
    val marks = remember(events, taskCount) {
        val colors = events.map { it.colorArgb }.take(3)
        if (colors.size < 3 && taskCount > 0) colors + TASK_MARK else colors
    }
    val coords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    // The whole cell stays the touch target, but its press/focus feedback is drawn only on the date
    // circle (see DateDisc), and the selected circle itself is drawn by the grid (it slides).
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .heightIn(min = MONTH_ROW_HEIGHT)
            .onPlaced { coords[0] = it }
            .combinedClickable(
                interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick,
                onLongClick = { onLongClick(coords[0]?.takeIf { it.isAttached }?.boundsInRoot()) },
            )
            .semantics(mergeDescendants = true) { this.selected = selected; contentDescription = a11y }
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val number = animateStateColor(
            when {
                selected -> c.onInverse
                isToday -> c.accent
                inMonth -> c.textPrimary
                else -> c.textTertiary
            },
            m.snappy(), label = "monthNumber",
        )
        DateDisc(interaction, Color.Transparent, Modifier.discCell(map, index), ring = if (isToday && !selected) c.accentFill else Color.Transparent) {
            Text(d.dayOfMonth.toString(), style = DsTheme.type.bodyStrong, color = number)
        }
        DayMarks(
            marks, modifier = Modifier.padding(top = 3.dp), dim = !inMonth,
            overflow = events.size + (if (taskCount > 0) 1 else 0) > 3,
        )
    }
}

@Composable
private fun GridMode(
    state: CalendarUiState,
    now: Instant,
    vm: CalendarViewModel,
    mode: CalendarMode,
    timeScroll: ScrollState,
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
                TimeGrid(
                    days = days, byDate = state.byDate, today = state.today, now = now, scroll = timeScroll,
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
    val onCompleted = app.daysemble.ui.LocalTaskCompleted.current
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
    LazyColumn(Modifier.testTag(CalendarTags.AGENDA), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + Space.xxl)) {
        days.forEach { d ->
            item(key = "h$d") {
                SectionHeader(dayLabel(d, state.today, p) + if (d == state.today || d == state.today.plusDays(1)) " · " + Fmt.mediumDate(d, p) else "", Modifier.padding(top = Space.m))
            }
            items(state.byDate[d].orEmpty(), key = { "e$d" + it.stableKey }) { e ->
                AgendaEventCard(e, now, onClick = { onOpenEvent(e.eventId, e.occurrenceKey) }, showCountdown = d == state.today, modifier = Modifier.then(itemMotion()), onLongClick = app.daysemble.ui.actions.LocalItemActions.current?.let { a -> { a.onEventLongPress(e) } })
            }
            items(state.tasksByDate[d].orEmpty(), key = { "t$d" + it.id }) { t ->
                AgendaTaskCard(t, now, onToggle = { done -> vm.setTaskCompleted(t.id, done) { r -> if (done) onCompleted(t.id, r) } }, onClick = { onOpenTask(t.id) }, modifier = Modifier.then(itemMotion()), onLongClick = app.daysemble.ui.actions.LocalItemActions.current?.let { a -> { a.onTaskLongPress(t) } })
            }
        }
        item(key = "spacer") { Spacer(Modifier.height(Space.l)) }
    }
}

private const val MONTH_ROWS = 6
