package app.daysemble.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.daysemble.DaysembleApp
import app.daysemble.R
import app.daysemble.domain.model.EventOccurrence
import app.daysemble.domain.model.Task
import app.daysemble.ui.calendar.CalendarViewModel
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Elevation
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/** A day's items for the peek: events first, then the tasks due that day. */
private class PeekItems(val events: List<EventOccurrence>, val tasks: List<Task>)

/**
 * A small card over the screen showing what is on [date], opened by a long press on a day (the week
 * strip, the month grid). It sits next to [anchor] (the pressed day, in root coordinates) and has an
 * "Add event" button for that date. Tapping anywhere else closes it.
 */
@Composable
fun DayPeek(date: LocalDate, anchor: Rect?, onDismiss: () -> Unit, onAddEvent: (LocalDate) -> Unit) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val p = LocalTimePrefs.current
    val today = LocalAppClock.current.today()
    val container = (LocalContext.current.applicationContext as DaysembleApp).container
    val items by produceState<PeekItems?>(null, date, p.zone) {
        combine(container.events.occurrences(date, date, p.zone), container.tasks.allTasks) { occ, all ->
            PeekItems(
                CalendarViewModel.groupByDate(occ, date, date)[date].orEmpty(),
                all.filter { !it.isCompleted && it.parentId == null && it.dueDate == date },
            )
        }.collect { value = it }
    }
    val enter = remember { Animatable(if (m.enabled) 0f else 1f) }
    LaunchedEffect(Unit) { if (enter.value < 1f) enter.animateTo(1f, m.snappy()) }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { Space.s.roundToPx() }
    val position = remember(anchor, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val a = anchor
                val cx = a?.center?.x?.toInt() ?: (windowSize.width / 2)
                val below = (a?.bottom?.toInt() ?: (windowSize.height / 3)) + gap
                val x = (cx - popupContentSize.width / 2).coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap))
                val y = below.coerceAtMost((windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap))
                return IntOffset(x, y)
            }
        }
    }
    Popup(position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .graphicsLayer {
                    val k = 0.92f + 0.08f * enter.value
                    scaleX = k; scaleY = k
                    alpha = enter.value.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .widthIn(min = 240.dp, max = 320.dp)
                .shadow(Elevation.floating, Radii.tile)
                .clip(Radii.tile)
                .background(c.surfaceRaised)
                .padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(Fmt.smartDate(date, today, p), style = DsTheme.type.title, color = c.textPrimary)
            val it = items
            val rows = it?.let { x -> x.events.map { e -> PeekRow(e.title, if (e.isAllDay) stringResource(R.string.all_day) else Fmt.time(e.start, p), eventColor(e.colorArgb)) } +
                x.tasks.map { t -> PeekRow(t.title, t.dueTime?.let { tm -> Fmt.time(tm, p) }.orEmpty(), c.outlineStrong) } }
            when {
                rows == null -> Spacer(Modifier.height(Space.l))
                rows.isEmpty() -> Text(stringResource(R.string.day_empty), style = DsTheme.type.bodySmall, color = c.textSecondary)
                else -> {
                    rows.take(PEEK_ROWS).forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(r.color))
                            Text(r.title.ifBlank { stringResource(R.string.untitled_event) }, style = DsTheme.type.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (r.time.isNotEmpty()) Text(r.time, style = DsTheme.type.label, color = c.textSecondary, maxLines = 1)
                        }
                    }
                    if (rows.size > PEEK_ROWS) Text(stringResource(R.string.peek_more, rows.size - PEEK_ROWS), style = DsTheme.type.label, color = c.textSecondary)
                }
            }
            DsButton(
                stringResource(R.string.add_event), { onDismiss(); onAddEvent(date) },
                Modifier.fillMaxWidth(), style = DsButtonStyle.Primary,
            )
        }
    }
}

private class PeekRow(val title: String, val time: String, val color: androidx.compose.ui.graphics.Color)

private const val PEEK_ROWS = 4
