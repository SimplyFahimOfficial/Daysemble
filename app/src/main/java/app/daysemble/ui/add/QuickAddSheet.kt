package app.daysemble.ui.add

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.OutlinedFlag
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import app.daysemble.AppContainer
import app.daysemble.R
import app.daysemble.domain.model.EventDraft
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.TaskDraft
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsChip
import app.daysemble.ui.components.DsDatePickerDialog
import app.daysemble.ui.components.DsSegmented
import app.daysemble.ui.components.DsTextField
import app.daysemble.ui.components.DsTimePickerDialog
import app.daysemble.ui.components.DsValueChip
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.dayLabel
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

enum class AddKind { TASK, EVENT }

/** Prefill for the full event editor when the user wants more options. */
data class EventPrefill(val title: String, val date: LocalDate, val startMinute: Int?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(
    container: AppContainer,
    initialDate: LocalDate?,
    onDismiss: () -> Unit,
    onMoreEventOptions: (EventPrefill) -> Unit,
    onMoreTaskOptions: (LocalDate?) -> Unit,
) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    val clock = LocalAppClock.current
    val today = clock.now().atZone(p.zone).toLocalDate()
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var kind by rememberSaveable { mutableStateOf(AddKind.TASK) }
    var title by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf<Long?>(initialDate?.toEpochDay()) }
    val (rollDays, firstSlot) = nextSlot(clock.now().atZone(p.zone).toLocalTime())
    var eventDate by rememberSaveable { mutableStateOf((initialDate ?: today.plusDays(rollDays)).toEpochDay()) }
    var startMinute by rememberSaveable { mutableStateOf(firstSlot) }
    var high by rememberSaveable { mutableStateOf(false) }
    var showError by rememberSaveable { mutableStateOf(false) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }

    fun close() = scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }

    fun submit() {
        if (title.isBlank()) { showError = true; return }
        if (saving) return
        saving = true
        scope.launch {
          try {
            val defaults = container.defaults()
            when (kind) {
                AddKind.TASK -> container.tasks.create(
                    TaskDraft(title = title, dueDate = date?.let(LocalDate::ofEpochDay), priority = if (high) Priority.HIGH else Priority.NONE),
                    container.newTaskListId(),
                )
                AddKind.EVENT -> {
                    val s = container.settings.current()
                    // The default reminder needs notification permission; ask in context (Android 13+).
                    if (s.defaultEventReminderMinutes != null && s.remindersEnabled && android.os.Build.VERSION.SDK_INT >= 33 &&
                        androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    val d = LocalDate.ofEpochDay(eventDate)
                    val start = d.atTime(startMinute / 60, startMinute % 60)
                    val end = start.plusMinutes(s.defaultEventDurationMinutes.toLong())
                    container.events.create(
                        EventDraft(
                            title = title, startDate = d, startTime = start.toLocalTime(),
                            endDate = end.toLocalDate(), endTime = end.toLocalTime(), zone = p.zone,
                            reminderMinutesBefore = listOfNotNull(s.defaultEventReminderMinutes),
                        ),
                        defaults.calendarId,
                    )
                }
            }
            close()
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (_: Exception) {
            saveFailed = true
          } finally {
            saving = false
          }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        shape = Radii.sheet,
        containerColor = c.surfaceRaised,
        scrimColor = c.scrim,
    ) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Space.gutter)
                .padding(bottom = Space.l),
        ) {
            DsSegmented(
                options = AddKind.entries, selected = kind,
                label = { stringResource(if (it == AddKind.TASK) R.string.kind_task else R.string.kind_event) },
                onSelect = { kind = it },
            )
            Spacer(Modifier.height(Space.l))
            DsTextField(
                value = title, onValueChange = { title = it; if (it.isNotBlank()) showError = false },
                placeholder = stringResource(if (kind == AddKind.TASK) R.string.quick_add_task_hint else R.string.quick_add_event_hint),
                error = if (showError) stringResource(R.string.error_title_required) else null,
                large = true, imeAction = ImeAction.Done, onImeAction = ::submit, focusRequester = focus,
            )
            Spacer(Modifier.height(Space.m))
            val motion = DsTheme.motion
            AnimatedContent(
                kind,
                transitionSpec = { fadeIn(motion.enter()) togetherWith fadeOut(motion.exit()) },
                label = "addKind",
            ) { k ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    if (k == AddKind.TASK) {
                        val d = date?.let(LocalDate::ofEpochDay)
                        DsChip(stringResource(R.string.day_today), d == today, { date = if (d == today) null else today.toEpochDay() })
                        DsChip(stringResource(R.string.day_tomorrow), d == today.plusDays(1), {
                            date = if (d == today.plusDays(1)) null else today.plusDays(1).toEpochDay()
                        })
                        DsChip(
                            if (d != null && d != today && d != today.plusDays(1)) dayLabel(d, today, p) else stringResource(R.string.pick_date),
                            d != null && d != today && d != today.plusDays(1), { pickDate = true }, icon = Icons.Rounded.CalendarToday,
                        )
                        DsChip(
                            stringResource(R.string.priority_high), high, { high = !high },
                            icon = if (high) Icons.Rounded.Flag else Icons.Rounded.OutlinedFlag,
                        )
                    } else {
                        val d = LocalDate.ofEpochDay(eventDate)
                        DsValueChip(dayLabel(d, today, p), { pickDate = true }, icon = Icons.Rounded.CalendarToday)
                        DsValueChip(Fmt.time(LocalTime.of(startMinute / 60, startMinute % 60), p), { pickTime = true }, icon = Icons.Rounded.Schedule)
                    }
                }
            }
            Spacer(Modifier.height(Space.xl))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                DsButton(
                    stringResource(R.string.more_options),
                    onClick = {
                        if (kind == AddKind.EVENT) onMoreEventOptions(EventPrefill(title, LocalDate.ofEpochDay(eventDate), startMinute))
                        else onMoreTaskOptions(date?.let(LocalDate::ofEpochDay))
                        onDismiss()
                    },
                    style = DsButtonStyle.Ghost,
                )
                DsButton(
                    stringResource(if (kind == AddKind.TASK) R.string.add_task else R.string.add_event),
                    onClick = ::submit, style = DsButtonStyle.Primary, enabled = !saving,
                )
            }
            if (saveFailed) {
                Text(stringResource(R.string.error_save_failed), style = DsTheme.type.label, color = c.error, modifier = Modifier.padding(top = Space.m))
            }
            if (kind == AddKind.EVENT) {
                Text(
                    stringResource(R.string.quick_add_event_note),
                    style = DsTheme.type.label, color = c.textTertiary, modifier = Modifier.padding(top = Space.m),
                )
            }
        }
    }

    if (pickDate) {
        DsDatePickerDialog(
            initial = if (kind == AddKind.TASK) date?.let(LocalDate::ofEpochDay) ?: today else LocalDate.ofEpochDay(eventDate),
            onPick = { if (kind == AddKind.TASK) date = it.toEpochDay() else eventDate = it.toEpochDay() },
            onDismiss = { pickDate = false },
        )
    }
    if (pickTime) {
        DsTimePickerDialog(
            initial = LocalTime.of(startMinute / 60, startMinute % 60),
            onPick = { startMinute = it.hour * 60 + it.minute },
            onDismiss = { pickTime = false },
        )
    }
}

/** Next :00 or :30 after [t] as (days to add, minute of day); after 23:30 it rolls to tomorrow 00:00. */
internal fun nextSlot(t: LocalTime): Pair<Long, Int> {
    val next = (t.hour * 60 + t.minute) / 30 * 30 + 30
    return if (next >= 24 * 60) 1L to 0 else 0L to next
}
