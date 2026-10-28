package app.daysemble.ui.components

import androidx.compose.foundation.background
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

/**
 * A week of dates, swiped a week at a time. The selected date is a filled circle; today is
 * orange (number, weekday and the date dot), so the two never look alike.
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
) {
    val p = LocalTimePrefs.current
    val m = DsTheme.motion
    val base = remember(today, p.firstDayOfWeek) { Fmt.weekStart(today, p) }
    fun pageOf(d: LocalDate) = CENTER + ChronoUnit.WEEKS.between(base, Fmt.weekStart(d, p)).toInt()
    val pager = key(base) { rememberPagerState(initialPage = pageOf(selected)) { WEEKS } }
    val target = pageOf(selected)
    LaunchedEffect(pager, target) {
        if (pager.currentPage != target) {
            if (m.reduced) pager.scrollToPage(target) else pager.animateScrollToPage(target, animationSpec = m.enter(m.long))
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
    HorizontalPager(pager, modifier = modifier.fillMaxWidth(), beyondViewportPageCount = 1) { page ->
        val start = base.plusWeeks((page - CENTER).toLong())
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.s).selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            repeat(7) { i ->
                val d = start.plusDays(i.toLong())
                DateCell(d, d == selected, d == today, { select(d) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DateCell(date: LocalDate, selected: Boolean, isToday: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val p = LocalTimePrefs.current
    val interaction = remember { MutableInteractionSource() }
    val fill = app.daysemble.ui.components.animateStateColor(if (selected) c.inverse else Color.Transparent, m.indicator(), label = "dateFill")
    val number = app.daysemble.ui.components.animateStateColor(
        when {
            selected -> c.onInverse
            isToday -> c.accent
            else -> c.textPrimary
        },
        m.indicator(), label = "dateNumber",
    )
    val desc = Fmt.fullDate(date, p) + if (isToday) ", " + stringResource(R.string.today_suffix) else ""
    Column(
        modifier
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = desc }
            .padding(vertical = Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DateDisc(interaction, fill, size = 38.dp) {
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
        TodayDot(visible = isToday)
    }
}
