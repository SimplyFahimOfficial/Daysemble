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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.daysemble.R
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
        // More than 3 all-day events: "+N" expands the lane, so every event can be opened.
        var allDayExpanded by remember(days) { mutableStateOf(false) }
        if (allDayByDay.any { it.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
                Box(Modifier.width(GutterWidth))
                days.forEachIndexed { i, _ ->
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                        val all = allDayByDay[i]
                        all.take(if (allDayExpanded) all.size else 3).forEach { e -> AllDayChip(e, compact = days.size > 1) { onEvent(e) } }
                        if (all.size > 3 && !allDayExpanded) {
                            val more = all.size - 3
                            val label = pluralStringResource(R.plurals.all_day_show_more, more, more)
                            Text(
                                stringResource(R.string.all_day_more, more),
                                style = DsTheme.type.caps, color = c.textSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button, onClickLabel = label) { allDayExpanded = true }
                                    .padding(horizontal = 6.dp, vertical = Space.xs)
                                    .semantics { contentDescription = label },
                            )
                        }
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
    // Positions use local wall-clock minutes, the same basis as the hour labels and slot taps, so a
    // 09:00 event sits on the 09:00 row on DST days too (the skipped/repeated hour is simply absent/shared).
    val untitled = stringResource(R.string.untitled_event)
    val newEvent = stringResource(R.string.new_event)
    val placed = remember(events, day, p.zone) {
        DayLayout.layout(
            events.map { e ->
                val s = wallMinutes(maxOf(e.start, dayStart), day, p.zone)
                val en = wallMinutes(minOf(e.end, dayEnd), day, p.zone)
                DayLayout.Item(e, s, maxOf(en, s))
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
            }
            // Tapping a slot has no TalkBack equivalent; offer "New event" at the next half hour today, else 09:00.
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(newEvent) {
                        val minute = if (isToday) (wallMinutes(now, day, p.zone) / 30 + 1) * 30 else 9 * 60
                        onEmptySlot(day, minute.coerceAtMost(24 * 60 - 30)); true
                    },
                )
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
            val height = (HourHeight * ((pl.endMin - pl.startMin) / 60f)).coerceAtLeast(22.dp).coerceAtMost(HourHeight * 24 - top)
            val color = eventColor(e.colorArgb)
            val a11y = e.title.ifBlank { untitled } + ", " + stringResource(R.string.time_range, Fmt.time(e.start, p), Fmt.time(e.end, p))
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
            val minute = wallMinutes(now, day, p.zone)
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

/** Minute of the local day for [t] on [day]; times on later days (incl. the next midnight) map to 24:00. */
internal fun wallMinutes(t: Instant, day: LocalDate, zone: java.time.ZoneId): Int {
    val z = t.atZone(zone)
    if (z.toLocalDate().isAfter(day)) return 24 * 60
    if (z.toLocalDate().isBefore(day)) return 0
    return z.hour * 60 + z.minute
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
