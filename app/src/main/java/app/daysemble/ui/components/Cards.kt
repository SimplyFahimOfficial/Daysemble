package app.daysemble.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SubdirectoryArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
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
import app.daysemble.ui.format.durationText
import app.daysemble.ui.format.relativeText
import app.daysemble.ui.task.repeatLabel
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space
import java.time.Duration
import java.time.Instant

/*
 * Agenda cards (Today and the calendar's day lists). From the references: rounded tiles on the
 * panel, a time line on top, the title large, then small pills; the focus event (on now, or next)
 * is the one orange card. Unlike the references they show only what the item really has: no
 * invented people, avatars or agendas.
 */

private val CardShape = RoundedCornerShape(20.dp)

/** Pill fill on the orange card: a dark tint keeps white text above 4.5:1 (a light one wouldn't). */
internal val OnAccentPillTint = Color.Black.copy(alpha = 0.18f)

/** Outer spacing of a card in a list; cards are separated by space, not dividers. */
private val CardOuter = Modifier.padding(horizontal = Space.gutter - Space.xs, vertical = Space.xs)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgendaEventCard(
    event: EventOccurrence,
    now: Instant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The event on now or next: the orange card. */
    focused: Boolean = false,
    /** Show "in 25 min" / "ends in 10 min" (kept current by the caller's minute [now]). */
    showCountdown: Boolean = focused,
    /** Long-press: the item menu (edit, delete). */
    onLongClick: (() -> Unit)? = null,
) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val p = LocalTimePrefs.current
    val ended = !event.isAllDay && now >= event.end && event.end > event.start
    val bg = app.daysemble.ui.components.animateStateColor(if (focused) c.accentStrong else c.surfaceVariant, m.indicator(), label = "eventCardBg")
    val primary = if (focused) c.onAccent else if (ended) c.textSecondary else c.textPrimary
    val secondary = if (focused) c.onAccent else c.textSecondary
    val pillBg = if (focused) OnAccentPillTint else c.surface
    val untitled = stringResource(R.string.untitled_event)
    val time = if (event.isAllDay) stringResource(R.string.all_day) else stringResource(R.string.time_range, Fmt.time(event.start, p), Fmt.time(event.end, p))
    val rel = RelativeTime.of(event.start, event.end, event.isAllDay, now, p.zone)
    val relText = if (showCountdown && rel !is Relative.Ended && rel !is Relative.AllDayToday) relativeText(rel) else null
    val minutes = Duration.between(event.start, event.end).toMinutes()
    val sameDay = event.start.atZone(p.zone).toLocalDate() == event.end.atZone(p.zone).toLocalDate()
    val duration = if (!event.isAllDay && minutes > 0 && sameDay) durationText(minutes) else null
    val repeats = if (event.isRecurring) stringResource(R.string.card_repeats) else null
    val a11y = listOfNotNull(event.title.ifBlank { untitled }, time, event.location.takeIf { it.isNotBlank() }, relText, repeats).joinToString(", ")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Column(
        modifier
            .then(CardOuter)
            .fillMaxWidth()
            .clip(CardShape)
            .background(bg)
            .itemClick(onClick, onLongClick)
            .semantics(mergeDescendants = true) { contentDescription = a11y }
            .padding(start = Space.l, end = Space.m, top = Space.m, bottom = Space.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            EventMarker(if (focused) c.onAccent else eventColor(event.colorArgb), event.isAllDay)
            Text(time, style = DsTheme.type.numeric, color = secondary, maxLines = 1, modifier = Modifier.weight(1f))
            // The "opens" affordance from the references: a small round arrow.
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (focused) OnAccentPillTint else c.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.ArrowOutward, null, tint = if (focused) c.onAccent else c.textSecondary,
                    modifier = Modifier.size(16.dp).graphicsLayer { if (rtl) scaleX = -1f },
                )
            }
        }
        Spacer(Modifier.height(Space.xs))
        Text(
            event.title.ifBlank { untitled }, style = DsTheme.type.title, color = primary,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        val sub = event.location.takeIf { it.isNotBlank() } ?: event.description.lineSequence().firstOrNull { it.isNotBlank() }
        if (sub != null) {
            Row(Modifier.padding(top = Space.xxs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                if (event.location.isNotBlank()) Icon(Icons.Rounded.Place, null, tint = secondary, modifier = Modifier.size(14.dp))
                Text(sub, style = DsTheme.type.bodySmall, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (duration != null || repeats != null || relText != null) {
            FlowRow(
                Modifier.padding(top = Space.s),
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                relText?.let { Pill(it, fg = if (focused) c.onAccent else c.accent, bg = if (focused) OnAccentPillTint else c.accentContainer) }
                duration?.let { Pill(it, icon = Icons.Rounded.Schedule, fg = secondary, bg = pillBg) }
                repeats?.let { Pill(it, icon = Icons.Rounded.Repeat, fg = secondary, bg = pillBg) }
            }
        }
    }
}

/**
 * A task as a card: the completion circle, the title (struck through as it completes), the due
 * line and a notes line, then pills for subtasks, the repeat and the countdown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgendaTaskCard(
    task: Task,
    now: Instant,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Show the due date too (overdue lists); otherwise only the time. */
    showDate: Boolean = false,
    actions: List<CustomAccessibilityAction> = emptyList(),
    /** Long-press: the item menu (edit, complete, delete). */
    onLongClick: (() -> Unit)? = null,
) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val p = LocalTimePrefs.current
    val today = now.atZone(p.zone).toLocalDate()
    val overdue = TaskRules.isOverdue(task, now, p.zone)
    val textColor = app.daysemble.ui.components.animateStateColor(if (task.isCompleted) c.textTertiary else c.textPrimary, m.standard(m.medium), label = "taskCardText")
    val strike by animateFloatAsState(if (task.isCompleted) 1f else 0f, if (m.reduced) snap() else m.standard(m.medium), label = "taskCardStrike")
    Row(
        modifier
            .then(CardOuter)
            .fillMaxWidth()
            .clip(CardShape)
            .background(c.surfaceVariant)
            .itemClick(onClick, onLongClick)
            .semantics { if (actions.isNotEmpty()) customActions = actions }
            .padding(start = Space.xxs, end = Space.l, top = Space.xs, bottom = Space.xs),
        verticalAlignment = Alignment.Top,
    ) {
        DsCheck(
            checked = task.isCompleted,
            onCheckedChange = onToggle,
            contentDescription = stringResource(if (task.isCompleted) R.string.cd_mark_incomplete else R.string.cd_mark_complete, task.title),
            tint = if (task.priority == Priority.HIGH && !task.isCompleted) c.accent else c.textPrimary,
        )
        Column(
            Modifier
                .weight(1f)
                .padding(top = Space.m, bottom = Space.s),
        ) {
            StrikeTitle(task.title, strike, textColor, DsTheme.type.bodyStrong)
            val due = task.dueDate
            val dueText = when {
                due == null -> null
                showDate || due != today -> dayLabel(due, today, p) + (task.dueTime?.let { " · " + Fmt.time(it, p) } ?: "")
                else -> task.dueTime?.let { Fmt.time(it, p) }
            }
            if (dueText != null) {
                Text(
                    if (overdue) stringResource(R.string.overdue_since, dueText) else dueText,
                    style = DsTheme.type.label, color = if (overdue) c.error else c.textSecondary, maxLines = 1,
                )
            }
            task.notes.lineSequence().firstOrNull { it.isNotBlank() }?.let {
                Text(it, style = DsTheme.type.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val countdown = task.countdownEnabled && !task.isCompleted && due != null
            if (task.subtaskCount > 0 || task.repeat != null || countdown) {
                FlowRow(
                    Modifier.padding(top = Space.s),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    if (task.subtaskCount > 0) {
                        Pill(
                            pluralStringResource(R.plurals.subtasks_progress, task.subtaskCount, task.subtasksDone, task.subtaskCount),
                            icon = Icons.Rounded.SubdirectoryArrowRight, bg = c.surface,
                        )
                    }
                    task.repeat?.let { Pill(repeatLabel(it), icon = Icons.Rounded.Repeat, bg = c.surface) }
                    if (countdown) CountdownPill(task.dueMoment(p.zone), task.isCompleted, now, p.zone)
                }
            }
        }
        PriorityFlag(task.priority, Modifier.padding(top = Space.l))
    }
}
