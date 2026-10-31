package app.daysemble.ui.event

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.R
import app.daysemble.core.recurrence.Freq
import app.daysemble.domain.model.EventValidation
import app.daysemble.domain.model.RepeatEnd
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsChip
import app.daysemble.ui.components.DsConfirmDialog
import app.daysemble.ui.components.DsDatePickerDialog
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.components.DsSwitchRow
import app.daysemble.ui.components.DsTextField
import app.daysemble.ui.components.DsTimePickerDialog
import app.daysemble.ui.components.DsTopBar
import app.daysemble.ui.components.DsValueChip
import app.daysemble.ui.components.EventMarker
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.components.SkeletonRows
import app.daysemble.ui.components.EventReminderOptions
import app.daysemble.ui.components.ReminderPicker
import app.daysemble.ui.components.eventColor
import app.daysemble.ui.daysembleViewModel
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun EventEditorRoute(
    eventId: String?,
    date: LocalDate?,
    startMinute: Int?,
    title: String?,
    /** With [eventId] of a series: edit only this occurrence. */
    occurrenceKey: String? = null,
    /** Called when the screen closes itself (after saving, deleting, or when its item is gone). Defaults to [onClose]. */
    onDone: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val prefs = LocalTimePrefs.current
    val vm = daysembleViewModel(key = "event:$eventId:$occurrenceKey") { c, h ->
        EventEditorViewModel(
            c.events, { c.defaults().calendarId }, eventId,
            newDefaults = {
                val s = c.settings.current()
                val now = c.clock.now().atZone(prefs.zone)
                val (roll, slot) = app.daysemble.ui.add.nextSlot(now.toLocalTime())
                val d = date ?: now.toLocalDate().plusDays(if (startMinute == null) roll else 0)
                val m = startMinute ?: slot
                newEventForm(d, m, s.defaultEventDurationMinutes, prefs.zone, title.orEmpty(), s.defaultEventReminderMinutes)
            },
            deviceZone = prefs.zone, handle = h, occurrenceKey = occurrenceKey,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    LaunchedEffect(done) { if (done) (onDone ?: onClose)() }
    EventEditorScreen(state, vm::update, { vm.save() }, onClose)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventEditorScreen(
    state: EventEditorUiState,
    update: ((EventForm) -> EventForm) -> Unit,
    save: () -> Unit,
    onClose: () -> Unit,
) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    var picker by rememberSaveable { mutableStateOf<String?>(null) } // "sd","st","ed","et","until"
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val attemptClose = { if (state.isDirty) confirmDiscard = true else onClose() }
    BackHandler(enabled = state.isDirty) { confirmDiscard = true }

    Column(Modifier.fillMaxSize().background(c.background).imePadding()) {
        DsTopBar(stringResource(if (state.isNew) R.string.new_event else if (state.isOccurrence) R.string.edit_this_event else R.string.edit_event), attemptClose) {
            DsButton(stringResource(R.string.action_save), save, modifier = Modifier.padding(end = Space.s), enabled = state.form != null && !state.saving)
        }
        val f = state.form
        if (f == null) {
            SkeletonRows(Modifier.padding(top = Space.xl)); return@Column
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = Space.x3),
        ) {
            DsTextField(
                value = f.title, onValueChange = { v -> update { it.copy(title = v) } },
                placeholder = stringResource(R.string.event_title_hint), large = true, imeAction = ImeAction.Done,
                error = if (state.showErrors && state.validation == EventValidation.TITLE_REQUIRED) stringResource(R.string.error_title_required) else null,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
            )
            DsSwitchRow(stringResource(R.string.all_day), f.isAllDay, { v -> update { it.copy(isAllDay = v) } })

            SectionHeader(stringResource(R.string.field_starts))
            DateTimeRow(f.startDate, if (f.isAllDay) null else f.startTime, { picker = "sd" }, { picker = "st" })
            SectionHeader(stringResource(R.string.field_ends), Modifier.padding(top = Space.s))
            DateTimeRow(f.endDate, if (f.isAllDay) null else f.endTime, { picker = "ed" }, { picker = "et" })
            if (state.validation == EventValidation.END_BEFORE_START) {
                Text(
                    stringResource(R.string.error_end_before_start), style = DsTheme.type.label, color = c.error,
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xs),
                )
            }
            if (!f.isAllDay) {
                SectionHeader(stringResource(R.string.field_time_zone), Modifier.padding(top = Space.s))
                Row(Modifier.padding(horizontal = Space.gutter)) {
                    DsValueChip(
                        f.zone.replace('_', ' '), { picker = "zone" }, icon = Icons.Rounded.Public,
                        contentDescription = stringResource(R.string.field_time_zone),
                    )
                }
                if (f.zone != p.zone.id) {
                    val local = f.startDate.atTime(f.startTime).atZone(f.zoneId).withZoneSameInstant(p.zone)
                    Text(
                        stringResource(R.string.event_zone_local, Fmt.mediumDate(local.toLocalDate(), p) + " " + Fmt.time(local.toLocalTime(), p)),
                        style = DsTheme.type.label, color = c.textSecondary,
                        modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xs),
                    )
                }
            }

            if (!state.isOccurrence) RepeatSection(f, update, onPickUntil = { picker = "until" })

            SectionHeader(stringResource(R.string.field_reminders), Modifier.padding(top = Space.l))
            if (state.isOccurrence) {
                Text(
                    stringResource(R.string.occurrence_reminders_note), style = DsTheme.type.bodySmall, color = c.textSecondary,
                    modifier = Modifier.padding(horizontal = Space.gutter),
                )
            } else ReminderPicker(
                selected = f.reminders, options = EventReminderOptions, forTask = false, allDay = f.isAllDay,
                onChange = { r -> update { it.copy(reminders = r) } },
            )

            SectionHeader(stringResource(R.string.field_location), Modifier.padding(top = Space.l))
            DsTextField(
                value = f.location, onValueChange = { v -> update { it.copy(location = v) } },
                placeholder = stringResource(R.string.location_hint), modifier = Modifier.padding(horizontal = Space.gutter),
            )

            if (state.calendars.size > 1 && state.canChangeCalendar) {
                SectionHeader(stringResource(R.string.field_calendar), Modifier.padding(top = Space.l))
                FlowRow(Modifier.padding(horizontal = Space.gutter - Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    state.calendars.forEach { cal ->
                        DsChip(cal.name, f.calendarId == cal.id, { update { it.copy(calendarId = cal.id) } })
                    }
                }
            } else state.calendar?.let { cal ->
                Row(
                    Modifier.padding(horizontal = Space.gutter, vertical = Space.l),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    EventMarker(eventColor(cal.colorArgb), false)
                    Text(cal.name, style = DsTheme.type.bodySmall, color = c.textSecondary)
                }
            }

            SectionHeader(stringResource(R.string.field_notes), Modifier.padding(top = Space.l))
            DsTextField(
                value = f.description, onValueChange = { v -> update { it.copy(description = v) } },
                placeholder = stringResource(R.string.notes_hint), singleLine = false,
                modifier = Modifier.padding(horizontal = Space.gutter),
            )
            if (state.saveError) {
                Text(
                    stringResource(R.string.error_save_failed), style = DsTheme.type.label, color = c.error,
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.m),
                )
            }
        }

        when (picker) {
            "sd" -> DsDatePickerDialog(f.startDate, { d -> update { it.withStart(d, it.startTime) } }, { picker = null })
            "st" -> DsTimePickerDialog(f.startTime, { t -> update { it.withStart(it.startDate, t) } }, { picker = null })
            "ed" -> DsDatePickerDialog(f.endDate, { d -> update { it.copy(endDay = d.toEpochDay()) } }, { picker = null })
            "et" -> DsTimePickerDialog(f.endTime, { t -> update { it.copy(endMinute = t.hour * 60 + t.minute) } }, { picker = null })
            // Changing the zone keeps the wall-clock times ("9:00 in Tokyo"), like Google Calendar.
            "zone" -> app.daysemble.ui.components.ZonePickerDialog(f.zone, { z -> update { it.copy(zone = z) } }, { picker = null })
            "until" -> DsDatePickerDialog(
                f.repeat.untilEpochDay?.let(LocalDate::ofEpochDay) ?: f.startDate.plusMonths(1),
                { d -> update { it.copy(repeat = it.repeat.copy(end = RepeatEnd.UNTIL.name, untilEpochDay = d.toEpochDay())) } },
                { picker = null },
            )
        }
    }
    if (confirmDiscard) {
        DsConfirmDialog(
            stringResource(R.string.discard_changes_title), null, stringResource(R.string.action_discard),
            onConfirm = onClose, onDismiss = { confirmDiscard = false }, destructive = true,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateTimeRow(date: LocalDate, time: LocalTime?, onDate: () -> Unit, onTime: () -> Unit) {
    val p = LocalTimePrefs.current
    FlowRow(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        DsValueChip(Fmt.mediumDate(date, p), onDate, icon = Icons.Rounded.CalendarToday)
        if (time != null) DsValueChip(Fmt.time(time, p), onTime, icon = Icons.Rounded.Schedule)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatSection(f: EventForm, update: ((EventForm) -> EventForm) -> Unit, onPickUntil: () -> Unit) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    val r = f.repeat
    SectionHeader(stringResource(R.string.field_repeat), Modifier.padding(top = Space.l))
    if (r.unsupportedRaw != null) {
        Text(
            stringResource(R.string.repeat_unsupported), style = DsTheme.type.bodySmall, color = c.textSecondary,
            modifier = Modifier.padding(horizontal = Space.gutter),
        )
        return
    }
    FlowRow(Modifier.padding(horizontal = Space.gutter - Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        DsChip(stringResource(R.string.repeat_none), r.frequency == null, { update { it.copy(repeat = RepeatForm()) } })
        Freq.entries.forEach { fr ->
            DsChip(stringResource(freqLabel(fr)), r.frequency == fr, { update { it.copy(repeat = it.repeat.copy(freq = fr.name)) } })
        }
    }
    val freq = r.frequency ?: return
    Stepper(
        text = pluralStringResource(freqUnit(freq), r.interval, r.interval),
        onMinus = { update { it.copy(repeat = it.repeat.copy(interval = (it.repeat.interval - 1).coerceAtLeast(1))) } },
        onPlus = { update { it.copy(repeat = it.repeat.copy(interval = (it.repeat.interval + 1).coerceAtMost(99))) } },
        minusEnabled = r.interval > 1,
        label = stringResource(R.string.repeat_every),
    )
    if (freq == Freq.WEEKLY) {
        val days = r.weekdays.ifEmpty { setOf(f.startDate.dayOfWeek.value) }
        val order = (0 until 7).map { p.firstDayOfWeek.plus(it.toLong()) }
        FlowRow(Modifier.padding(horizontal = Space.gutter - Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            order.forEach { d: DayOfWeek ->
                val on = d.value in days
                DsChip(Fmt.weekdayShort(d, p), on, {
                    val next = if (on) days - d.value else days + d.value
                    if (next.isNotEmpty()) update { it.copy(repeat = it.repeat.copy(weekdays = next)) }
                }, role = androidx.compose.ui.semantics.Role.Checkbox)
            }
        }
    }
    FlowRow(Modifier.padding(horizontal = Space.gutter - Space.xs, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        DsChip(stringResource(R.string.repeat_forever), r.repeatEnd == RepeatEnd.NEVER, { update { it.copy(repeat = it.repeat.copy(end = RepeatEnd.NEVER.name)) } })
        DsChip(stringResource(R.string.repeat_count), r.repeatEnd == RepeatEnd.COUNT, {
            update { it.copy(repeat = it.repeat.copy(end = RepeatEnd.COUNT.name)) }
        })
        DsChip(
            r.untilEpochDay?.takeIf { r.repeatEnd == RepeatEnd.UNTIL }?.let { stringResource(R.string.repeat_until_date, Fmt.mediumDate(LocalDate.ofEpochDay(it), p)) }
                ?: stringResource(R.string.repeat_until),
            r.repeatEnd == RepeatEnd.UNTIL, onPickUntil,
        )
    }
    if (r.repeatEnd == RepeatEnd.COUNT) {
        Stepper(
            text = pluralStringResource(R.plurals.repeat_times, r.count, r.count),
            onMinus = { update { it.copy(repeat = it.repeat.copy(count = (it.repeat.count - 1).coerceAtLeast(1))) } },
            onPlus = { update { it.copy(repeat = it.repeat.copy(count = (it.repeat.count + 1).coerceAtMost(999))) } },
            minusEnabled = r.count > 1,
        )
    }
}

@Composable
private fun Stepper(text: String, onMinus: () -> Unit, onPlus: () -> Unit, minusEnabled: Boolean, label: String? = null) {
    val c = DsTheme.colors
    Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.xs)) {
        if (label != null) Text(label, style = DsTheme.type.body, color = c.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            DsIconButton(Icons.Rounded.Remove, stringResource(R.string.cd_decrease), onMinus, enabled = minusEnabled)
            Text(text, style = DsTheme.type.bodyStrong, color = c.textPrimary, modifier = Modifier.weight(1f, fill = false))
            DsIconButton(Icons.Rounded.Add, stringResource(R.string.cd_increase), onPlus)
        }
    }
}

fun freqLabel(f: Freq) = when (f) {
    Freq.DAILY -> R.string.repeat_daily
    Freq.WEEKLY -> R.string.repeat_weekly
    Freq.MONTHLY -> R.string.repeat_monthly
    Freq.YEARLY -> R.string.repeat_yearly
}

fun freqUnit(f: Freq) = when (f) {
    Freq.DAILY -> R.plurals.unit_days
    Freq.WEEKLY -> R.plurals.unit_weeks
    Freq.MONTHLY -> R.plurals.unit_months
    Freq.YEARLY -> R.plurals.unit_years
}
