package app.daysemble.ui.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.daysemble.core.time.DayLayout
import app.daysemble.domain.model.EventOccurrence
import app.daysemble.ui.components.eventColor
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

private val HourHeight = 56.dp
private val GutterWidth = 52.dp

/**
 * Day/week time grid. Each column lays out its timed events with [DayLayout]; segments of
 * multi-day events are clipped to the column's day. All-day events sit in a lane above.
 */
@Composable
fun TimeGrid(
    days: List<LocalDate>,
    byDate: Map<LocalDate, List<EventOccurrence>>,
    today: LocalDate,
    now: Instant,
    onEvent: (EventOccurrence) -> Unit,
    onEmptySlot: (LocalDate, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val hourPx = with(density) { HourHeight.toPx() }
    LaunchedEffect(Unit) {
        // Start near "now" on today, otherwise at 08:00.
        val startHour = if (today in days) (now.atZone(p.zone).hour - 1).coerceIn(0, 18) else 8
        scroll.scrollTo((startHour * hourPx).toInt())
    }

    Column(modifier) {
        // All-day lane
        val allDayByDay = days.map { d -> byDate[d].orEmpty().filter { it.isAllDay } }
        if (allDayByDay.any { it.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
                Box(Modifier.width(GutterWidth))
                days.forEachIndexed { i, _ ->
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                        allDayByDay[i].take(3).forEach { e -> AllDayChip(e, compact = days.size > 1) { onEvent(e) } }
                        if (allDayByDay[i].size > 3) Text("+${allDayByDay[i].size - 3}", style = DsTheme.type.caps, color = c.textSecondary)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
        Box(Modifier.weight(1f).verticalScroll(scroll)) {
            Row(Modifier.height(HourHeight * 24)) {
                // Hour labels
                Box(Modifier.width(GutterWidth).fillMaxHeight()) {
                    for (h in 1 until 24) {
                        Text(
                            Fmt.hourShort(h, p),
                            style = DsTheme.type.caps, color = c.textTertiary, maxLines = 1,
                            modifier = Modifier
                                .offset(y = HourHeight * h - 7.dp)
                                .padding(start = Space.s),
                        )
                    }
                }
                days.forEach { day ->
                    DayColumn(day, byDate[day].orEmpty().filter { !it.isAllDay }, day == today, now, onEvent, onEmptySlot, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    day: LocalDate,
    events: List<EventOccurrence>,
    isToday: Boolean,
    now: Instant,
    onEvent: (EventOccurrence) -> Unit,
    onEmptySlot: (LocalDate, Int) -> Unit,
    modifier: Modifier,
) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    val density = LocalDensity.current
    val dayStart = day.atStartOfDay(p.zone).toInstant()
    val dayEnd = day.plusDays(1).atStartOfDay(p.zone).toInstant()
    // Minutes are measured in real elapsed time from local midnight, clamped to the 24 h grid;
    // on DST days the grid stays 24 rows and the extra/missing hour is absorbed at the edges.
    val untitled = androidx.compose.ui.res.stringResource(app.daysemble.R.string.untitled_event)
    val placed = remember(events, day, p.zone) {
        DayLayout.layout(
            events.map { e ->
                val s = ChronoUnit.MINUTES.between(dayStart, maxOf(e.start, dayStart)).toInt().coerceIn(0, 24 * 60)
                val en = ChronoUnit.MINUTES.between(dayStart, minOf(e.end, dayEnd)).toInt().coerceIn(0, 24 * 60)
                DayLayout.Item(e, s, en)
            },
        )
    }
    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .pointerInput(day) {
                detectTapGestures { pos: Offset ->
                    val minute = ((pos.y / HourHeight.toPx()) * 60).toInt().coerceIn(0, 24 * 60 - 30)
                    onEmptySlot(day, minute / 30 * 30)
                }
            },
    ) {
        val width = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val hp = HourHeight.toPx()
            for (h in 0..24) drawLine(c.outline, Offset(0f, h * hp), Offset(size.width, h * hp), 1f)
            drawLine(c.outline, Offset(0f, 0f), Offset(0f, size.height), 1f)
        }
        placed.forEach { pl ->
            val e = pl.key
            val top = HourHeight * (pl.startMin / 60f)
            val height = (HourHeight * ((pl.endMin - pl.startMin) / 60f)).coerceAtLeast(22.dp)
            val color = eventColor(e.colorArgb)
            val a11y = "${e.title.ifBlank { untitled }}, ${Fmt.time(e.start, p)} – ${Fmt.time(e.end, p)}"
            Box(
                Modifier
                    .offset { IntOffset(with(density) { (width * pl.leftFraction).roundToPx() } + 1, with(density) { top.roundToPx() } + 1) }
                    .width(width * pl.widthFraction - 2.dp)
                    .height(height - 2.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = if (c.isDark) 0.28f else 0.16f))
                    .clickable(role = Role.Button) { onEvent(e) }
                    .semantics(mergeDescendants = true) { contentDescription = a11y },
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(color))
                Column(Modifier.padding(start = 6.dp, end = 3.dp, top = 2.dp)) {
                    Text(e.title, style = DsTheme.type.label, color = c.textPrimary, maxLines = if (height > 40.dp) 2 else 1, overflow = TextOverflow.Ellipsis)
                    if (height > 44.dp) Text(Fmt.time(e.start, p), style = DsTheme.type.caps, color = c.textSecondary, maxLines = 1)
                }
            }
        }
        if (isToday && now >= dayStart && now < dayEnd) {
            val minute = ChronoUnit.MINUTES.between(dayStart, now)
            Box(
                Modifier
                    .offset(y = HourHeight * (minute / 60f) - 1.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(c.accentFill),
            )
            Box(
                Modifier
                    .offset(x = (-4).dp, y = HourHeight * (minute / 60f) - 5.dp)
                    .size(10.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(c.accentFill),
            )
        }
    }
}

@Composable
fun AllDayChip(e: EventOccurrence, compact: Boolean, onClick: () -> Unit) {
    val c = DsTheme.colors
    val color = eventColor(e.colorArgb)
    Text(
        e.title,
        style = DsTheme.type.label, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(vertical = 1.dp)
            .fillMaxWidth()
            .heightIn(min = if (compact) 22.dp else 28.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .background(color.copy(alpha = if (c.isDark) 0.28f else 0.16f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}
