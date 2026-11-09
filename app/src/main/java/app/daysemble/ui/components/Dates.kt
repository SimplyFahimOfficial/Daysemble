package app.daysemble.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.daysemble.R
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The round date marker shared by every date picker in the app (Today's week strip, the month
 * grid, the week-view header).
 *
 * The surrounding cell is the touch target and has no indication of its own (`selectable(...,
 * indication = null)` with this [interaction]); press and focus feedback is drawn here instead,
 * clipped to the same circle as the selection [fill]. So tapping a date never shows a rectangle
 * or square, only the circle. The marker stays a circle at any font size: it grows to the larger
 * side of its content, never into an oval.
 */
@Composable
fun DateDisc(
    interaction: MutableInteractionSource,
    fill: Color,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    ring: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(CircleShape)
            .background(fill)
            .then(if (ring != Color.Transparent) Modifier.border(1.5.dp, ring, CircleShape) else Modifier)
            .indication(interaction, ripple(bounded = false))
            // Innermost, so the clip, fill, ring and ripple above all get the square's size.
            .squareAtLeast(size),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Square layout of at least [min], centring the content: keeps [DateDisc] circular. */
private fun Modifier.squareAtLeast(min: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val side = maxOf(min.roundToPx(), p.width, p.height)
        .let { if (constraints.maxWidth != Constraints.Infinity) it.coerceAtMost(maxOf(constraints.maxWidth, p.width)) else it }
    layout(side, side) { p.place((side - p.width) / 2, (side - p.height) / 2) }
}

/** The Daysemble date dot: a perfect circle in the brand orange, marking today. */
@Composable
fun TodayDot(modifier: Modifier = Modifier, size: Dp = 4.dp, visible: Boolean = true) {
    Box(modifier.size(size).clip(CircleShape).background(if (visible) DsTheme.colors.accentFill else Color.Transparent))
}

private const val WEEKS = 2_000
private const val CENTER = WEEKS / 2

/** Stand-in colour in a day's marks for "has an open task" (drawn neutral, events use their own colour). */
const val TASK_MARK = 0

/** Up to three small dots in the colours of what is on a day, fading in [delayMs] after they first appear. */
@Composable
fun DayMarks(colors: List<Int>, delayMs: Int, modifier: Modifier = Modifier, dim: Boolean = false, overflow: Boolean = false) {
    val c = DsTheme.colors
    Row(
        modifier.height(MARK_SIZE).then(if (colors.isEmpty()) Modifier else Modifier.staggeredEntrance(0, baseDelay = delayMs, shift = 0.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.take(3).forEach { argb ->
            val color = if (argb == TASK_MARK) c.outlineStrong else eventColor(argb)
            Box(Modifier.size(MARK_SIZE).clip(CircleShape).background(color.copy(alpha = if (dim) 0.4f else 1f)))
        }
        // More than three: a small plus after the dots.
        if (overflow) Box(Modifier.size(MARK_SIZE)) {
            Box(Modifier.align(Alignment.Center).size(width = MARK_SIZE, height = 1.dp).background(c.textTertiary))
            Box(Modifier.align(Alignment.Center).size(width = 1.dp, height = MARK_SIZE).background(c.textTertiary))
        }
    }
}

private val MARK_SIZE = 5.dp

/**
 * A week of dates, swiped a week at a time. The selected date is a filled circle that slides to
 * the new date on a spring (and fades between weeks); today is orange (number, weekday and the
 * date dot, pinned to today whatever is selected), so the two never look alike. Days with items
 * get small dots in their colours ([marks]). A long press on a day peeks at it ([DayPeek]).
 *
 * Moving to another week by swiping selects a day in it: today if it is this week, otherwise the
 * same weekday. Selecting a date elsewhere (for example "Back to today") brings its week into view.
 */
@Composable
fun WeekStrip(
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    marks: Map<LocalDate, List<Int>> = emptyMap(),
    onAddEvent: (LocalDate) -> Unit = {},
) {
    val p = LocalTimePrefs.current
    val m = DsTheme.motion
    val haptics = rememberHaptics()
    val base = remember(today, p.firstDayOfWeek) { Fmt.weekStart(today, p) }
    fun pageOf(d: LocalDate) = CENTER + ChronoUnit.WEEKS.between(base, Fmt.weekStart(d, p)).toInt()
    val pager = key(base) { rememberPagerState(initialPage = pageOf(selected)) { WEEKS } }
    val target = pageOf(selected)
    LaunchedEffect(pager, target) {
        if (pager.currentPage != target) {
            if (!m.enabled) pager.scrollToPage(target) else pager.animateScrollToPage(target, animationSpec = m.spring())
        }
    }
    // Read through rememberUpdatedState, not captured: a midnight in the middle of the week changes
    // today without changing the week (so neither pager nor base restarts this collector), and
    // paging back to this week must then select the new today.
    val currentSelected by rememberUpdatedState(selected)
    val currentToday by rememberUpdatedState(today)
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(pager, base) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            val start = base.plusWeeks((page - CENTER).toLong())
            val cur = currentSelected
            val curStart = Fmt.weekStart(cur, p)
            if (curStart != start) {
                select(if (start == base) currentToday else start.plusDays(ChronoUnit.DAYS.between(curStart, cur)))
            }
        }
    }
    var peek by remember { mutableStateOf<Pair<LocalDate, Rect?>?>(null) }
    HorizontalPager(pager, modifier = modifier.fillMaxWidth(), beyondViewportPageCount = 1) { page ->
        val start = base.plusWeeks((page - CENTER).toLong())
        val index = ChronoUnit.DAYS.between(start, selected).toInt().takeIf { it in 0..6 }
        val disc = rememberSlidingDisc(index?.let { it to 0 })
        val map = remember { DiscMap(7, 1) }
        val ink = DsTheme.colors.inverse
        Row(
            Modifier
                .fillMaxWidth()
                .discContainer(map)
                // The selected circle, under the numbers: it slides between the seven cells.
                .drawBehind { disc.draw(this, map, ink) }
                .padding(horizontal = Space.s)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            repeat(7) { i ->
                val d = start.plusDays(i.toLong())
                DateCell(
                    d, d == selected, d == today, marks[d].orEmpty(), i, map,
                    onClick = {
                        if (d != selected) haptics.tick()
                        select(d)
                    },
                    onLongClick = { r -> haptics.tick(); peek = d to r },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    peek?.let { (d, r) -> DayPeek(d, r, onDismiss = { peek = null }, onAddEvent = onAddEvent) }
}

/** Diameter (dp) of a date disc at normal font size. */
private const val DATE_DISC = 38f

/**
 * Where the date discs of a page are, measured: the container and every disc ([columns] x [rows]).
 * A circle can then be placed at any fractional (column, row) by interpolating between the real
 * centres, so it stays on its cell while rows collapse, move or grow at large font sizes.
 * Coordinates are read when drawing.
 */
@Stable
class DiscMap(private val columns: Int = 7, private val rows: Int = 1) {
    var container: LayoutCoordinates? = null
    private val cells = arrayOfNulls<LayoutCoordinates>(columns * rows)

    fun report(index: Int, coords: LayoutCoordinates) { if (index in cells.indices) cells[index] = coords }

    private fun center(index: Int): Offset? {
        val root = container?.takeIf { it.isAttached } ?: return null
        val c = cells.getOrNull(index)?.takeIf { it.isAttached } ?: return null
        return root.localBoundingBoxOf(c).center
    }

    /** The centre at ([col], [row]) in the container, and the disc's radius; null until measured. */
    fun place(col: Float, row: Float): Pair<Offset, Float>? {
        val c0 = col.toInt().coerceIn(0, columns - 1); val c1 = (c0 + 1).coerceAtMost(columns - 1)
        val r0 = row.toInt().coerceIn(0, rows - 1); val r1 = (r0 + 1).coerceAtMost(rows - 1)
        val a = center(r0 * columns + c0) ?: return null
        val b = center(r0 * columns + c1) ?: a
        val c = center(r1 * columns + c0) ?: a
        val d = center(r1 * columns + c1) ?: b
        val fc = col - c0; val fr = row - r0
        val top = androidx.compose.ui.geometry.lerp(a, b, fc); val bottom = androidx.compose.ui.geometry.lerp(c, d, fc)
        val radius = cells[r0 * columns + c0]?.size?.width?.div(2f) ?: return null
        return androidx.compose.ui.geometry.lerp(top, bottom, fr) to radius
    }
}

/** The container modifier that lets a [DiscMap] measure; use it on the page that holds the cells. */
fun Modifier.discContainer(map: DiscMap): Modifier = onPlaced { map.container = it }

/** The disc's own modifier: reports its position to [map] as cell [index]. */
fun Modifier.discCell(map: DiscMap, index: Int): Modifier = onPlaced { map.report(index, it) }

/**
 * The selected-date circle of one page. Moving to another cell of the same page slides it there on
 * the snappy spring with a small pop (1 to [pop] and back); entering the page fades it in already in
 * place, leaving fades it out. Everything is read in the draw phase ([draw]), so a moving circle
 * recomposes nothing.
 */
@Stable
class SlidingDisc internal constructor(
    private val cell: Animatable<Offset, androidx.compose.animation.core.AnimationVector2D>,
    private val alpha: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    private val scale: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
) {
    fun draw(scope: DrawScope, map: DiscMap, color: Color) = with(scope) {
        val a = alpha.value
        if (a <= 0f) return@with
        val (center, radius) = map.place(cell.value.x, cell.value.y) ?: return@with
        drawCircle(color.copy(alpha = a), radius * scale.value, center)
    }
}

@Composable
fun rememberSlidingDisc(cell: Pair<Int, Int>?, pop: Boolean = false): SlidingDisc {
    val m = DsTheme.motion
    fun Pair<Int, Int>.offset() = Offset(first.toFloat(), second.toFloat())
    val position = remember { Animatable((cell ?: (0 to 0)).offset(), Offset.VectorConverter) }
    val alpha = remember { Animatable(if (cell != null) 1f else 0f) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(cell) {
        if (cell == null) {
            alpha.animateTo(0f, if (m.enabled) tween(m.short) else snap())
        } else if (alpha.value == 0f || !m.enabled) {
            position.snapTo(cell.offset())
            alpha.animateTo(1f, if (m.enabled) tween(m.short) else snap())
        } else {
            alpha.animateTo(1f, m.snappy())
            coroutineScope {
                if (pop) launch { with(m) { scale.pop() } }
                position.animateTo(cell.offset(), m.snappy())
            }
        }
    }
    return remember { SlidingDisc(position, alpha, scale) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DateCell(
    date: LocalDate, selected: Boolean, isToday: Boolean, marks: List<Int>, column: Int, map: DiscMap,
    onClick: () -> Unit, onLongClick: (Rect?) -> Unit, modifier: Modifier,
) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val p = LocalTimePrefs.current
    val interaction = remember { MutableInteractionSource() }
    val number = app.daysemble.ui.components.animateStateColor(
        when {
            selected -> c.onInverse
            isToday -> c.accent
            else -> c.textPrimary
        },
        m.indicator(), label = "dateNumber",
    )
    val desc = Fmt.fullDate(date, p) + if (isToday) ", " + stringResource(R.string.today_suffix) else ""
    var bounds by remember { mutableStateOf<Rect?>(null) }
    Column(
        modifier
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .combinedClickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick, onLongClick = { onLongClick(bounds) })
            .semantics(mergeDescendants = true) { this.selected = selected; contentDescription = desc }
            .padding(vertical = Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DateDisc(interaction, Color.Transparent, Modifier.discCell(map, column), size = DATE_DISC.dp) {
            Text(date.dayOfMonth.toString(), style = DsTheme.type.title, color = number, maxLines = 1)
        }
        Spacer(Modifier.height(Space.xs))
        // Seven three-letter labels no longer fit a phone's width at large text sizes: use one letter
        // there (the full date is in the cell's description).
        val narrow = LocalDensity.current.fontScale >= 1.5f
        Text(
            if (narrow) Fmt.weekdayNarrow(date.dayOfWeek, p) else Fmt.weekdayShort(date.dayOfWeek, p).uppercase().take(3),
            style = DsTheme.type.caps,
            color = if (isToday) c.accent else c.textTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        // Pinned to today, whatever day is selected.
        TodayDot(visible = isToday)
        Spacer(Modifier.height(3.dp))
        DayMarks(marks, delayMs = column * m.markStagger)
    }
}
