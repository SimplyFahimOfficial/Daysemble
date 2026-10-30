package app.daysemble.ui.task

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.daysemble.R
import app.daysemble.core.recurrence.Freq
import app.daysemble.core.recurrence.OccurrenceCell
import app.daysemble.core.recurrence.OccurrenceHistory
import app.daysemble.core.recurrence.OccurrenceStatus
import app.daysemble.core.recurrence.RRule
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.TimePrefs
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

object HistoryTags {
    const val GRID = "history_grid"
    const val STATS = "history_stats"
    const val EMPTY = "history_empty"
    const val EARLIER = "history_earlier"
    const val SELECTED = "history_selected"
    fun cell(date: LocalDate) = "history_cell_$date"
}

private val CellShape = RoundedCornerShape(8.dp)

/**
 * The completion history of a repeating task, from stored occurrences only ([OccurrenceHistory]):
 * totals, the streak, then a grid. Daily and weekly repeats get a calendar of recent weeks (days
 * the task isn't scheduled on are a small dot and don't count); monthly and yearly repeats get one
 * cell per occurrence. Tapping a cell shows its date and state underneath.
 */
@Composable
fun OccurrenceTracker(history: OccurrenceHistory, rule: RRule?, today: LocalDate, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    Column(modifier) {
        Stats(history)
        Spacer(Modifier.height(Space.m))
        if (history.isEmpty && history.active == null && history.upcoming == 0) {
            Text(
                stringResource(R.string.history_empty), style = DsTheme.type.bodySmall, color = c.textSecondary,
                modifier = Modifier.testTag(HistoryTags.EMPTY),
            )
        } else {
            if (history.isEmpty) {
                Text(
                    stringResource(R.string.history_empty), style = DsTheme.type.bodySmall, color = c.textSecondary,
                    modifier = Modifier.padding(bottom = Space.s).testTag(HistoryTags.EMPTY),
                )
            }
            val byDate = remember(history) { history.cells.associateBy { it.date } }
            val onSelect: (LocalDate) -> Unit = { d -> selected = if (selected == d.toEpochDay()) null else d.toEpochDay() }
            if (rule == null || rule.freq == Freq.DAILY || rule.freq == Freq.WEEKLY) {
                WeekGrid(byDate, history, today, selected?.let(LocalDate::ofEpochDay), onSelect)
            } else {
                OccurrenceRowGrid(history, rule.freq == Freq.YEARLY, today, selected?.let(LocalDate::ofEpochDay), onSelect)
            }
            selected?.let(LocalDate::ofEpochDay)?.let { d ->
                Spacer(Modifier.height(Space.s))
                Text(
                    selectionText(d, byDate[d]), style = DsTheme.type.bodySmall, color = c.textPrimary,
                    modifier = Modifier.testTag(HistoryTags.SELECTED),
                )
            }
            Spacer(Modifier.height(Space.m))
            Legend()
        }
    }
}

@Composable
private fun Stats(h: OccurrenceHistory) {
    val c = DsTheme.colors
    // One row of equal tiles; labels wrap inside their tile rather than pushing a tile to a new row.
    Row(Modifier.fillMaxWidth().testTag(HistoryTags.STATS), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        val m = Modifier.weight(1f)
        Stat(h.completed.toString(), stringResource(R.string.history_completed), c.accent, m)
        Stat(h.missed.toString(), stringResource(R.string.history_missed), c.textSecondary, m)
        if (h.skipped > 0) Stat(h.skipped.toString(), stringResource(R.string.history_skipped), c.textSecondary, m)
        Stat(h.upcoming.toString(), stringResource(if (h.endsWithinHorizon) R.string.history_left else R.string.history_upcoming), c.textSecondary, m)
        Stat(h.streak.toString(), pluralStringResource(R.plurals.history_streak, h.streak), c.textPrimary, m)
    }
}

@Composable
private fun Stat(value: String, label: String, tint: Color, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.surfaceVariant)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Space.s, vertical = Space.s),
    ) {
        Text(value, style = DsTheme.type.titleLarge.copy(fontFeatureSettings = "tnum"), color = tint)
        Text(label, style = DsTheme.type.label, color = c.textSecondary)
    }
}

/** Recent weeks as a calendar, newest at the bottom; "Show earlier" adds weeks back to the series start. */
@Composable
private fun WeekGrid(byDate: Map<LocalDate, OccurrenceCell>, h: OccurrenceHistory, today: LocalDate, selected: LocalDate?, onSelect: (LocalDate) -> Unit) {
    val p = LocalTimePrefs.current
    val c = DsTheme.colors
    var weeks by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(6) }
    val thisWeek = Fmt.weekStart(today, p)
    val first = h.cells.firstOrNull()?.date?.let { Fmt.weekStart(it, p) } ?: thisWeek
    val lastCell = h.cells.lastOrNull()?.date?.let { Fmt.weekStart(it, p) } ?: thisWeek
    // Up to the week after this one (upcoming), never beyond the last known cell.
    val lastWeek = minOf(maxOf(thisWeek, lastCell), thisWeek.plusWeeks(1))
    val available = (ChronoUnit.WEEKS.between(first, lastWeek) + 1).toInt().coerceAtLeast(1)
    val shown = weeks.coerceAtMost(available)
    val start = lastWeek.minusWeeks((shown - 1).toLong())
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(HistoryTags.GRID)) {
        val cell: Dp = min((maxWidth - Space.xs * 6) / 7, 48.dp)
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(
                rangeLabel(start, lastWeek.plusDays(6), p), style = DsTheme.type.label, color = c.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.clearAndSetSemantics { }) {
                for (day in app.daysemble.ui.settings.weekOrder(p.firstDayOfWeek)) {
                    Text(
                        Fmt.weekdayNarrow(day, p), style = DsTheme.type.caps, color = c.textTertiary,
                        textAlign = TextAlign.Center, modifier = Modifier.size(width = cell, height = 16.dp),
                    )
                }
            }
            for (w in 0 until shown) {
                val ws = start.plusWeeks(w.toLong())
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    for (i in 0L until 7L) {
                        val d = ws.plusDays(i)
                        DayCell(d, byDate[d], d == today, d == selected, cell, p) { onSelect(d) }
                    }
                }
            }
        }
    }
    if (shown < available) {
        DsButton(
            stringResource(R.string.history_show_earlier), { weeks += 8 }, style = DsButtonStyle.Ghost,
            modifier = Modifier.padding(top = Space.xs).testTag(HistoryTags.EARLIER),
        )
    }
}

@Composable
private fun DayCell(date: LocalDate, cell: OccurrenceCell?, isToday: Boolean, selected: Boolean, size: Dp, p: TimePrefs, onClick: () -> Unit) {
    val c = DsTheme.colors
    val style = cellStyle(cell?.status)
    val desc = Fmt.fullDate(date, p) + ": " + statusLabel(cell?.status)
    Box(
        Modifier
            .size(size)
            .clip(CellShape)
            .then(if (cell != null) Modifier.background(style.fill) else Modifier)
            .then(if (style.ring != Color.Transparent && cell != null) Modifier.border(1.5.dp, style.ring, CellShape) else Modifier)
            .then(if (selected) Modifier.border(2.dp, c.textPrimary, CellShape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = desc; this.selected = selected }
            .testTag(HistoryTags.cell(date)),
        contentAlignment = Alignment.Center,
    ) {
        if (cell == null) {
            // Not a scheduled day: a small neutral dot, not counted anywhere.
            Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(c.outline))
        } else {
            Text(
                date.dayOfMonth.toString(), style = DsTheme.type.caps, color = if (isToday && cell.status != OccurrenceStatus.COMPLETED) c.accent else style.text,
                maxLines = 1,
            )
        }
    }
}

/** Monthly and yearly repeats: one cell per occurrence (the latest 12 and what's coming). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OccurrenceRowGrid(h: OccurrenceHistory, yearly: Boolean, today: LocalDate, selected: LocalDate?, onSelect: (LocalDate) -> Unit) {
    val p = LocalTimePrefs.current
    var more by rememberSaveable { mutableStateOf(false) }
    val past = h.cells.filter { !it.date.isAfter(today) }
    val future = h.cells.filter { it.date.isAfter(today) }.take(3)
    val shownPast = if (more) past else past.takeLast(12)
    FlowRow(
        Modifier.fillMaxWidth().testTag(HistoryTags.GRID),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        (shownPast + future).forEach { cell ->
            val style = cellStyle(cell.status)
            val label = if (yearly) cell.date.year.toString() else cell.date.month.getDisplayName(TextStyle.SHORT, p.locale) +
                if (cell.date.year != today.year) " ’" + (cell.date.year % 100).toString().padStart(2, '0') else ""
            val desc = Fmt.fullDate(cell.date, p) + ": " + statusLabel(cell.status)
            Box(
                Modifier
                    .size(width = 56.dp, height = 40.dp)
                    .clip(CellShape)
                    .background(style.fill)
                    .then(if (style.ring != Color.Transparent) Modifier.border(1.5.dp, style.ring, CellShape) else Modifier)
                    .then(if (cell.date == selected) Modifier.border(2.dp, DsTheme.colors.textPrimary, CellShape) else Modifier)
                    .clickable(role = Role.Button) { onSelect(cell.date) }
                    .semantics { contentDescription = desc }
                    .testTag(HistoryTags.cell(cell.date)),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = DsTheme.type.caps, color = style.text, maxLines = 1) }
        }
    }
    if (!more && past.size > 12) {
        DsButton(stringResource(R.string.history_show_earlier), { more = true }, style = DsButtonStyle.Ghost, modifier = Modifier.testTag(HistoryTags.EARLIER))
    }
}

private data class CellStyle(val fill: Color, val ring: Color, val text: Color)

/**
 * Completed: the one orange fill (with its text-safe pair). Missed: a quiet tinted fill, no alarm
 * colour. Skipped: neutral fill. Active (due or overdue): an orange ring (overdue in the error tone).
 * Upcoming: just an outline.
 */
@Composable
private fun cellStyle(s: OccurrenceStatus?): CellStyle {
    val c = DsTheme.colors
    return when (s) {
        OccurrenceStatus.COMPLETED -> CellStyle(c.accentStrong, Color.Transparent, c.onAccent)
        OccurrenceStatus.MISSED -> CellStyle(c.errorContainer, Color.Transparent, c.textSecondary)
        OccurrenceStatus.SKIPPED -> CellStyle(c.surfaceVariant, Color.Transparent, c.textTertiary)
        OccurrenceStatus.OVERDUE -> CellStyle(Color.Transparent, c.error, c.error)
        OccurrenceStatus.DUE -> CellStyle(Color.Transparent, c.accentFill, c.textPrimary)
        OccurrenceStatus.UPCOMING -> CellStyle(Color.Transparent, c.outlineStrong, c.textSecondary)
        null -> CellStyle(Color.Transparent, Color.Transparent, c.textTertiary)
    }
}

@Composable
private fun statusLabel(s: OccurrenceStatus?): String = stringResource(
    when (s) {
        OccurrenceStatus.COMPLETED -> R.string.history_status_completed
        OccurrenceStatus.MISSED -> R.string.history_status_missed
        OccurrenceStatus.SKIPPED -> R.string.history_status_skipped
        OccurrenceStatus.OVERDUE -> R.string.history_status_overdue
        OccurrenceStatus.DUE -> R.string.history_status_due
        OccurrenceStatus.UPCOMING -> R.string.history_status_upcoming
        null -> R.string.history_status_not_scheduled
    },
)

@Composable
private fun selectionText(d: LocalDate, cell: OccurrenceCell?): String {
    val p = LocalTimePrefs.current
    val base = Fmt.fullDate(d, p) + " · " + statusLabel(cell?.status)
    val at = cell?.completedAt?.let { " · " + stringResource(R.string.history_completed_at, Fmt.mediumDate(it.atZone(p.zone).toLocalDate(), p) + " " + Fmt.time(it, p)) } ?: ""
    return base + at
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    val c = DsTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        listOf(
            OccurrenceStatus.COMPLETED, OccurrenceStatus.MISSED, OccurrenceStatus.SKIPPED,
            OccurrenceStatus.DUE, OccurrenceStatus.OVERDUE, OccurrenceStatus.UPCOMING,
        ).forEach { s ->
            val st = cellStyle(s)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(st.fill)
                        .then(if (st.ring != Color.Transparent) Modifier.border(1.5.dp, st.ring, RoundedCornerShape(4.dp)) else Modifier),
                )
                Text(statusLabel(s), style = DsTheme.type.label, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun rangeLabel(from: LocalDate, to: LocalDate, p: TimePrefs): String =
    stringResource(R.string.date_range, Fmt.monthDayShort(from, p), Fmt.monthDayShort(to, p))
