package app.daysemble.ui.event

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import app.daysemble.ui.theme.EventSwatch
import app.daysemble.ui.theme.Radii
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.DaysembleApp
import app.daysemble.R
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.time.RelativeTime
import app.daysemble.domain.model.EventOccurrence
import app.daysemble.ui.components.CountdownChip
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsConfirmDialog
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.components.DsTopBar
import app.daysemble.ui.components.EventMarker
import app.daysemble.ui.components.StateMessage
import app.daysemble.ui.components.eventColor
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.TimePrefs
import app.daysemble.ui.format.durationText
import app.daysemble.ui.format.rememberMinuteTicker
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.Duration

@Composable
fun EventDetailRoute(
    eventId: String, occurrenceKey: String?, onEdit: (String) -> Unit,
    /** Called when the screen closes itself (after saving, deleting, or when its item is gone). Defaults to [onClose]. */
    onDone: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val finish = onDone ?: onClose
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as DaysembleApp).container
    val p = LocalTimePrefs.current
    val flow = androidx.compose.runtime.remember(eventId, occurrenceKey, p.zone) { container.events.observeOccurrence(eventId, occurrenceKey, p.zone) }
    val loaded by produceState<Pair<Boolean, EventOccurrence?>>(false to null, flow) { flow.collect { value = true to it } }
    // Re-read whenever the occurrence changes (an edit of the series), not only when the screen opens.
    val rrule by produceState<String?>(null, eventId, loaded.second) { value = container.events.get(eventId)?.rrule }
    val calendarName by produceState<String?>(null, loaded.second?.calendarId) {
        value = loaded.second?.calendarId?.let { container.events.calendar(it)?.name }
    }
    val scope = rememberCoroutineScope()
    val (isLoaded, event) = loaded
    if (!isLoaded) { Column(Modifier.fillMaxSize().background(DsTheme.colors.background)) {}; return }
    if (event == null) {
        Column(Modifier.fillMaxSize().background(DsTheme.colors.background)) {
            DsTopBar("", onClose)
            StateMessage(Icons.Rounded.EventBusy, stringResource(R.string.event_missing), Modifier.padding(top = Space.x3))
        }
        return
    }
    EventDetailScreen(
        event = event, rrule = rrule, calendarName = calendarName, onClose = onClose, onEdit = { onEdit(eventId) },
        onDeleteSeries = { scope.launch { container.events.delete(eventId); finish() } },
        onDeleteOccurrence = { scope.launch { container.events.deleteOccurrence(eventId, event.occurrenceKey); finish() } },
    )
}

@Composable
private fun EventDetailScreen(
    event: EventOccurrence,
    rrule: String?,
    calendarName: String?,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onDeleteSeries: () -> Unit,
    onDeleteOccurrence: () -> Unit,
) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    val ctx = LocalContext.current
    val now = rememberMinuteTicker()
    var confirm by rememberSaveable { mutableStateOf(false) }
    var mapsError by rememberSaveable { mutableStateOf(false) }
    val color = eventColor(event.colorArgb)

    // Rises over the page it was opened from (like the editors), so it closes with an X.
    Column(Modifier.fillMaxSize().background(c.background)) {
        DsTopBar("", onClose, close = true) {
            if (!event.isReadOnly) {
                DsIconButton(Icons.Rounded.DeleteOutline, stringResource(R.string.action_delete), { confirm = true })
                DsIconButton(Icons.Rounded.Edit, stringResource(R.string.action_edit), onEdit)
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = Space.x3),
        ) {
            // Header, centred as in the reference: the time pill, the title, the date, and how
            // soon it is.
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TimePill(event, p)
                Spacer(Modifier.height(Space.l))
                Text(
                    event.title.ifBlank { stringResource(R.string.untitled_event) },
                    style = DsTheme.type.headline, color = c.textPrimary, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(Space.xs))
                Text(whenText(event, p), style = DsTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center)
                val rel = RelativeTime.of(event.start, event.end, event.isAllDay, now, p.zone)
                if (rel !is app.daysemble.core.time.Relative.Ended) {
                    Spacer(Modifier.height(Space.m))
                    CountdownChip(rel)
                }
            }
            Spacer(Modifier.height(Space.xl))
            // Grouped tiles: only what the event has. No invented people, avatars or agenda.
            Column(Modifier.padding(horizontal = Space.gutter - Space.xs), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Tile {
                    DetailLine(Icons.Rounded.Schedule, durationLine(event), zoneLine(event, p))
                    rrule?.let { RRule.parse(it) }?.let { DetailLine(Icons.Rounded.Repeat, repeatSummary(it), null) }
                }
                if (event.location.isNotBlank()) {
                    Tile {
                        DetailLine(Icons.Rounded.Place, event.location, null)
                        Row(Modifier.padding(start = Space.l + 38.dp, bottom = Space.m)) {
                            DsButton(
                                stringResource(R.string.open_in_maps),
                                onClick = {
                                    val uri = ("geo:0,0?q=" + Uri.encode(event.location)).toUri()
                                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) { mapsError = true }
                                },
                                style = DsButtonStyle.Secondary, icon = Icons.Rounded.Map,
                            )
                        }
                        if (mapsError) {
                            Text(
                                stringResource(R.string.no_maps_app), style = DsTheme.type.label, color = c.error,
                                modifier = Modifier.padding(start = Space.l + 38.dp, bottom = Space.m),
                            )
                        }
                    }
                }
                if (event.description.isNotBlank()) {
                    Tile { DetailLine(Icons.AutoMirrored.Rounded.Notes, event.description, null) }
                }
                if (calendarName != null || event.isReadOnly) {
                    Tile {
                        calendarName?.let {
                            DetailLine(null, it, if (event.isGoogle) stringResource(R.string.google_calendar) else stringResource(R.string.on_this_device), marker = color)
                        }
                        if (event.isReadOnly) DetailLine(Icons.Rounded.Lock, stringResource(R.string.event_read_only), null)
                    }
                }
            }
        }
    }

    if (confirm) {
        if (event.isRecurring) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                shape = Radii.dialog,
                containerColor = c.surfaceRaised,
                title = { Text(stringResource(R.string.delete_recurring_title), style = DsTheme.type.titleLarge, color = c.textPrimary) },
                text = { Text(stringResource(R.string.delete_recurring_body), style = DsTheme.type.body, color = c.textSecondary) },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { confirm = false; onDeleteOccurrence() }) { Text(stringResource(R.string.delete_this_event), color = c.error) }
                        TextButton(onClick = { confirm = false; onDeleteSeries() }) { Text(stringResource(R.string.delete_all_events), color = c.error) }
                        TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel), color = c.textSecondary) }
                    }
                },
            )
        } else {
            DsConfirmDialog(
                stringResource(R.string.delete_event_title),
                stringResource(if (event.isGoogle) R.string.delete_event_google_body else R.string.delete_event_body),
                stringResource(R.string.action_delete), onConfirm = onDeleteSeries, onDismiss = { confirm = false }, destructive = true,
            )
        }
    }
}

/**
 * The inverted time pill at the top: "09:00 – 10:30" or "All day", with the calendar's colour
 * marker. The marker uses the tone made for the opposite theme, as the pill is inverted.
 */
@Composable
private fun TimePill(e: EventOccurrence, p: TimePrefs) {
    val c = DsTheme.colors
    val text = if (e.isAllDay) stringResource(R.string.all_day) else stringResource(R.string.time_range, Fmt.time(e.start, p), Fmt.time(e.end, p))
    Row(
        Modifier
            // A fixed radius rather than 50 %: with very large text the times wrap, and a
            // two-line pill keeps soft corners instead of turning into a lozenge.
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
            .background(c.inverse)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        EventMarker(EventSwatch.nearest(e.colorArgb).color(dark = !c.isDark), e.isAllDay)
        Text(text, style = DsTheme.type.numeric, color = c.onInverse, textAlign = TextAlign.Center)
    }
}

/** A rounded group of detail lines. */
@Composable
private fun Tile(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radii.card)
            .background(DsTheme.colors.surfaceVariant)
            .padding(vertical = Space.xs),
    ) { content() }
}

@Composable
private fun DetailLine(icon: ImageVector?, text: String, sub: String?, marker: androidx.compose.ui.graphics.Color? = null) {
    val c = DsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Space.l, vertical = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        if (marker != null) EventMarker(marker, false, Modifier.padding(top = 5.dp, start = 5.dp, end = 5.dp))
        else if (icon != null) Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(22.dp))
        Column {
            Text(text, style = DsTheme.type.body, color = c.textPrimary)
            if (sub != null) Text(sub, style = DsTheme.type.bodySmall, color = c.textSecondary)
        }
    }
}

/** "1 h 30 min", "All day", "3 days". */
@Composable
private fun durationLine(e: EventOccurrence): String {
    if (e.isAllDay) {
        val days = (e.endDate.toEpochDay() - e.startDate.toEpochDay()).toInt().coerceAtLeast(1)
        return if (days == 1) stringResource(R.string.all_day) else androidx.compose.ui.res.pluralStringResource(R.plurals.cd_days, days, days)
    }
    return durationText(Duration.between(e.start, e.end).toMinutes())
}

/** The start in the event's own zone, when that isn't the device's. */
@Composable
private fun zoneLine(e: EventOccurrence, p: TimePrefs): String? {
    if (e.isAllDay || e.timeZone.id == p.zone.id) return null
    return stringResource(R.string.in_zone, Fmt.time(e.start.atZone(e.timeZone).toLocalTime(), p), e.timeZone.id)
}

@Composable
private fun whenText(e: EventOccurrence, p: TimePrefs): String {
    if (e.isAllDay) {
        val last = e.endDate.minusDays(1)
        return if (last == e.startDate) Fmt.fullDate(e.startDate, p)
        else stringResource(R.string.date_range, Fmt.mediumDate(e.startDate, p), Fmt.mediumDate(last, p))
    }
    val sDate = e.start.atZone(p.zone).toLocalDate()
    val eDate = e.end.atZone(p.zone).toLocalDate()
    return if (sDate == eDate) Fmt.fullDate(sDate, p)
    else stringResource(R.string.date_range, Fmt.mediumDate(sDate, p) + " " + Fmt.time(e.start, p), Fmt.mediumDate(eDate, p) + " " + Fmt.time(e.end, p))
}

@Composable
fun repeatSummary(r: RRule): String {
    if (!r.isSupported) return stringResource(R.string.repeat_custom)
    val p = LocalTimePrefs.current
    val unit = androidx.compose.ui.res.pluralStringResource(freqUnit(r.freq), r.interval, r.interval)
    val base = if (r.interval == 1) stringResource(freqLabel(r.freq)) else stringResource(R.string.repeat_every_x, unit)
    val days = if (r.byDay.isNotEmpty()) " · " + r.byDay.joinToString(", ") { Fmt.weekdayShort(it.day, p) } else ""
    val end = when {
        r.count != null -> " · " + androidx.compose.ui.res.pluralStringResource(R.plurals.repeat_times, r.count, r.count)
        r.untilDate != null -> " · " + stringResource(R.string.repeat_until_date, Fmt.mediumDate(r.untilDate, p))
        r.untilInstant != null -> " · " + stringResource(R.string.repeat_until_date, Fmt.mediumDate(r.untilInstant.atZone(p.zone).toLocalDate(), p))
        else -> ""
    }
    return base + days + end
}

