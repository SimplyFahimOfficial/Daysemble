package app.daysemble.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.SubdirectoryArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daysemble.R
import app.daysemble.core.time.Relative
import app.daysemble.core.time.RelativeTime
import app.daysemble.domain.TaskRules
import app.daysemble.domain.model.EventOccurrence
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.Task
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.dayLabel
import app.daysemble.ui.format.relativeText
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.EventSwatch
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space
import java.time.Instant

/** Theme-aware tone for a stored calendar/event colour. */
@Composable
fun eventColor(argb: Int): Color = EventSwatch.nearest(argb).color(DsTheme.colors.isDark)

/** Brand marker: the rounded square from the logo, in the calendar colour. */
@Composable
fun EventMarker(color: Color, allDay: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = if (allDay) 18.dp else 12.dp, height = 12.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .background(color),
    )
}

@Composable
fun CountdownChip(rel: Relative, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val live = rel is Relative.Now || rel is Relative.EndsIn
    Text(
        relativeText(rel),
        style = DsTheme.type.label,
        color = if (live) c.onAccent else c.accent,
        maxLines = 1,
        modifier = modifier
            .clip(Radii.pill)
            .background(if (live) c.accentStrong else c.accentContainer)
            .padding(horizontal = Space.s + Space.xxs, vertical = Space.xxs),
    )
}

/**
 * Timeline/agenda event row. [showCountdown] adds the relative-time chip (kept current by
 * the caller passing a fresh [now] each minute).
 */
@Composable
fun EventRow(
    event: EventOccurrence,
    now: Instant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showCountdown: Boolean = false,
    showDate: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    val color = eventColor(event.colorArgb)
    val ended = !event.isAllDay && now >= event.end && event.end > event.start
    val timeText = if (event.isAllDay) stringResource(R.string.all_day) else Fmt.time(event.start, p)
    val endText = if (event.isAllDay) null else Fmt.time(event.end, p)
    val rel = RelativeTime.of(event.start, event.end, event.isAllDay, now, p.zone)
    val untitled = stringResource(R.string.untitled_event)
    val a11y = buildString {
        append(event.title.ifBlank { untitled }); append(", ")
        append(timeText); if (endText != null) append(" – ").append(endText)
        if (event.location.isNotBlank()) append(", ").append(event.location)
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.rowMin)
            .itemClick(onClick, onLongClick)
            .semantics(mergeDescendants = true) { contentDescription = a11y }
            .padding(horizontal = Space.gutter, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) { EventMarker(color, event.isAllDay) }
        Column(Modifier.weight(1f)) {
            Text(
                event.title.ifBlank { stringResource(R.string.untitled_event) },
                style = DsTheme.type.bodyStrong,
                color = if (ended) c.textSecondary else c.textPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                if (showDate) dayLabel(event.startDate, now.atZone(p.zone).toLocalDate(), p) else null,
                event.location.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (sub.isNotEmpty() || event.isRecurring) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    if (event.isRecurring) Icon(Icons.Rounded.Repeat, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                    if (sub.isNotEmpty()) Text(sub, style = DsTheme.type.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (showCountdown && rel !is Relative.Ended && rel !is Relative.AllDayToday) {
                Spacer(Modifier.height(Space.xs + Space.xxs))
                CountdownChip(rel)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(timeText, style = DsTheme.type.numeric, color = if (ended) c.textTertiary else c.textSecondary, maxLines = 1)
            if (endText != null) Text(endText, style = DsTheme.type.label, color = c.textTertiary, maxLines = 1)
        }
    }
}

/**
 * A task title with the completion strike-through drawn across it as [strike] goes 0 → 1 (and
 * retracting when the task is reopened) instead of switching on halfway through.
 */
@Composable
internal fun StrikeTitle(text: String, strike: Float, color: Color, style: androidx.compose.ui.text.TextStyle, maxLines: Int = 2) {
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    Text(
        text,
        style = style,
        color = color,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = Modifier.drawWithContent {
            drawContent()
            val l = layout ?: return@drawWithContent
            if (strike <= 0f) return@drawWithContent
            val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
            val stroke = 1.25.dp.toPx()
            for (i in 0 until l.lineCount) {
                val left = l.getLineLeft(i); val right = l.getLineRight(i)
                // Through the middle of the lowercase letters, where a regular strike-through sits.
                val y = l.getLineBaseline(i) - (l.getLineBottom(i) - l.getLineTop(i)) * 0.27f
                val len = (right - left) * strike
                val (x0, x1) = if (rtl) right to right - len else left to left + len
                drawLine(color, androidx.compose.ui.geometry.Offset(x0, y), androidx.compose.ui.geometry.Offset(x1, y), stroke)
            }
        },
    )
}

@Composable
fun PriorityFlag(priority: Priority, modifier: Modifier = Modifier) {
    if (priority == Priority.NONE) return
    val c = DsTheme.colors
    val tint = when (priority) {
        Priority.HIGH -> c.accent
        Priority.MEDIUM -> c.warning
        else -> c.textTertiary
    }
    Icon(Icons.Rounded.Flag, stringResource(priorityLabel(priority)), tint = tint, modifier = modifier.size(16.dp))
}

fun priorityLabel(p: Priority) = when (p) {
    Priority.NONE -> R.string.priority_none
    Priority.LOW -> R.string.priority_low
    Priority.MEDIUM -> R.string.priority_medium
    Priority.HIGH -> R.string.priority_high
}

/**
 * Task row with animated completion (check fill, strike-through, fade to secondary). Its meta line
 * shows the due date, subtask progress, the repeat and (when the task has one) a compact countdown
 * at minute precision from [now].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskRow(
    task: Task,
    now: Instant,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDue: Boolean = true,
    isSubtask: Boolean = false,
    actions: List<androidx.compose.ui.semantics.CustomAccessibilityAction> = emptyList(),
    onLongClick: (() -> Unit)? = null,
) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val p = LocalTimePrefs.current
    val today = now.atZone(p.zone).toLocalDate()
    val overdue = TaskRules.isOverdue(task, now, p.zone)
    val textColor = app.daysemble.ui.components.animateStateColor(if (task.isCompleted) c.textTertiary else c.textPrimary, m.standard(m.medium), label = "taskText")
    // Reduced motion: the line appears in full at once (only the text colour fades).
    val strike by animateFloatAsState(
        if (task.isCompleted) 1f else 0f,
        if (m.reduced) androidx.compose.animation.core.snap() else m.standard(m.medium), label = "strike",
    )
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.rowMin)
            .itemClick(onClick, onLongClick)
            .semantics { if (actions.isNotEmpty()) customActions = actions }
            .padding(start = if (isSubtask) Space.gutter + Space.xl else Space.gutter - 13.dp, end = Space.gutter, top = Space.xxs, bottom = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        DsCheck(
            checked = task.isCompleted,
            onCheckedChange = onToggle,
            contentDescription = stringResource(if (task.isCompleted) R.string.cd_mark_incomplete else R.string.cd_mark_complete, task.title),
            tint = if (task.priority == Priority.HIGH && !task.isCompleted) c.accent else c.textPrimary,
            dashed = isSubtask,
        )
        Column(Modifier.weight(1f).padding(vertical = Space.s)) {
            StrikeTitle(task.title, strike, textColor, DsTheme.type.bodyStrong)
            val due = task.dueDate
            val countdown = task.countdownEnabled && !task.isCompleted && due != null
            val hasMeta = (showDue && due != null) || task.subtaskCount > 0 || task.isRecurring || countdown
            if (hasMeta) {
                FlowRow(
                    itemVerticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.xxs),
                ) {
                    if (showDue && due != null) {
                        val label = dayLabel(due, today, p) + (task.dueTime?.let { " · " + Fmt.time(it, p) } ?: "")
                        Text(
                            if (overdue) stringResource(R.string.overdue_since, label) else label,
                            style = DsTheme.type.label,
                            color = if (overdue) c.error else c.textSecondary,
                            maxLines = 1,
                        )
                    }
                    if (task.subtaskCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.SubdirectoryArrowRight, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                            Text(
                                pluralStringResource(R.plurals.subtasks_progress, task.subtaskCount, task.subtasksDone, task.subtaskCount),
                                style = DsTheme.type.label, color = c.textSecondary,
                            )
                        }
                    }
                    task.repeat?.let { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xxs)) {
                            Icon(Icons.Rounded.Repeat, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                            Text(app.daysemble.ui.task.repeatLabel(r), style = DsTheme.type.label, color = c.textSecondary, maxLines = 1)
                        }
                    }
                    if (countdown) CountdownPill(task.dueMoment(p.zone), task.isCompleted, now, p.zone)
                }
            }
        }
        PriorityFlag(task.priority)
        if (task.dueTime != null && !showDue) {
            Text(Fmt.time(task.dueTime, p), style = DsTheme.type.numeric, color = c.textSecondary)
        }
    }
}
