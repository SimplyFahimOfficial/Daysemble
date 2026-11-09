package app.daysemble.ui.add

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Stable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp as lerpFloat
import app.daysemble.ui.theme.DsMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.daysemble.ui.components.rememberHaptics
import app.daysemble.ui.components.staggeredEntrance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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

/**
 * Quick Add. It is not a window (a ModalBottomSheet could not take part in a shared transition): it
 * is drawn in the app's own tree, and the FAB's container grows into it and shrinks back into the
 * FAB when it closes, saved or not (see [AddSheetFrame]). Call it inside the AnimatedVisibility
 * that shows it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(
    sheetState: AddSheetState,
    open: Boolean,
    container: AppContainer,
    initialDate: LocalDate?,
    onDismiss: () -> Unit,
    /** The id of the task or event that was just created, before the sheet closes. */
    onSaved: (String, AddKind) -> Unit,
    onMoreEventOptions: (EventPrefill) -> Unit,
    /** The typed title, the chosen due date and minute of day (the time only with a date). */
    onMoreTaskOptions: (String, LocalDate?, Int?) -> Unit,
    /** Which kind the sheet opens on (the + button: task; the "New event" shortcut: event). */
    initialKind: AddKind = AddKind.TASK,
) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    val clock = LocalAppClock.current
    val today = clock.now().atZone(p.zone).toLocalDate()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    var kind by rememberSaveable { mutableStateOf(initialKind) }
    var title by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf<Long?>(initialDate?.toEpochDay()) }
    val (rollDays, firstSlot) = nextSlot(clock.now().atZone(p.zone).toLocalTime())
    var eventDate by rememberSaveable { mutableLongStateOf((initialDate ?: today.plusDays(rollDays)).toEpochDay()) }
    var startMinute by rememberSaveable { mutableIntStateOf(firstSlot) }
    // A task's due time (minute of day); only kept while it has a date.
    var taskMinute by rememberSaveable { mutableStateOf<Int?>(null) }
    var high by rememberSaveable { mutableStateOf(false) }
    var showError by rememberSaveable { mutableStateOf(false) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var pickTaskTime by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }

    fun submit() {
        if (title.isBlank()) { showError = true; return }
        if (saving) return
        saving = true
        scope.launch {
          try {
            val defaults = container.defaults()
            val id = when (kind) {
                AddKind.TASK -> container.tasks.create(
                    TaskDraft(
                        title = title, dueDate = date?.let(LocalDate::ofEpochDay),
                        dueTime = taskMinute?.takeIf { date != null }?.let { LocalTime.of(it / 60, it % 60) },
                        priority = if (high) Priority.HIGH else Priority.NONE,
                    ),
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
            haptics.confirm()
            onSaved(id, kind)
            onDismiss()
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (_: Exception) {
            saveFailed = true
          } finally {
            saving = false
          }
        }
    }

    val focusDelay = DsTheme.motion.medium.toLong()
    AddSheetFrame(sheetState, open, onDismiss) {
        // After the container has mostly grown, so the keyboard doesn't fight the transition.
        LaunchedEffect(Unit) { delay(focusDelay); runCatching { focus.requestFocus() } }
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
                modifier = Modifier.staggeredEntrance(0, baseDelay = ENTRANCE_DELAY),
            )
            Spacer(Modifier.height(Space.l))
            DsTextField(
                value = title, onValueChange = { title = it; if (it.isNotBlank()) showError = false },
                placeholder = stringResource(if (kind == AddKind.TASK) R.string.quick_add_task_hint else R.string.quick_add_event_hint),
                error = if (showError) stringResource(R.string.error_title_required) else null,
                large = true, imeAction = ImeAction.Done, onImeAction = ::submit, focusRequester = focus,
                modifier = Modifier.staggeredEntrance(1, baseDelay = ENTRANCE_DELAY),
            )
            Spacer(Modifier.height(Space.m))
            val motion = DsTheme.motion
            AnimatedContent(
                kind,
                // The options for the other kind fade through; the sheet's height follows smoothly.
                transitionSpec = { motion.fadeThrough(size = motion.contentSize()) },
                label = "addKind",
                modifier = Modifier.staggeredEntrance(2, baseDelay = ENTRANCE_DELAY),
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
                        // A time needs a date: picking one with no date sets the task for today
                        // (tomorrow after 23:30, when the next half hour is midnight).
                        val m = taskMinute?.takeIf { d != null }
                        DsChip(
                            m?.let { Fmt.time(LocalTime.of(it / 60, it % 60), p) } ?: stringResource(R.string.add_time),
                            m != null, { pickTaskTime = true }, icon = Icons.Rounded.Schedule,
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
                Modifier.fillMaxWidth().staggeredEntrance(3, baseDelay = ENTRANCE_DELAY),
                horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                DsButton(
                    stringResource(R.string.more_options),
                    onClick = {
                        if (kind == AddKind.EVENT) onMoreEventOptions(EventPrefill(title, LocalDate.ofEpochDay(eventDate), startMinute))
                        else onMoreTaskOptions(title, date?.let(LocalDate::ofEpochDay), taskMinute?.takeIf { date != null })
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
    if (pickTaskTime) {
        // The next half hour, and its day: after 23:30 that is midnight tomorrow, not 00:00 today
        // (which would make the task overdue as soon as it's added).
        val slotDay = today.plusDays(rollDays).toEpochDay()
        DsTimePickerDialog(
            // Start from the next half hour on its day, from 09:00 for another day.
            initial = taskMinute?.let { LocalTime.of(it / 60, it % 60) }
                ?: if (date == null || date == slotDay) LocalTime.of(firstSlot / 60, firstSlot % 60) else LocalTime.of(9, 0),
            onPick = { taskMinute = it.hour * 60 + it.minute; if (date == null) date = slotDay },
            onDismiss = { pickTaskTime = false },
            onClear = if (taskMinute != null) ({ taskMinute = null }) else null,
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

/** Frames in which the form starts to appear: after the container has begun to grow out of the FAB. */
private const val ENTRANCE_DELAY = 60

/**
 * The container of [QuickAddSheet]: a scrim, and a sheet at the bottom whose background is one
 * shape that grows out of the FAB (its bounds, colour and corners are interpolated by
 * [AddSheetState.progress]) and shrinks back into it on close. The + turns 45 degrees as it
 * grows and fades out; the form fades in once the container is big enough to hold it. Only the
 * draw phase reads the progress, so nothing recomposes or re-measures per frame. The sheet can be
 * pulled down by its handle, and the back gesture shrinks it under the finger.
 */
@Composable
private fun AddSheetFrame(state: AddSheetState, open: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var pull by remember { mutableFloatStateOf(0f) }
    var back by remember { mutableFloatStateOf(0f) }
    var host by remember { mutableStateOf(IntSize.Zero) }
    var hostPos by remember { mutableStateOf(Offset.Zero) }
    var sheet by remember { mutableStateOf(IntSize.Zero) }
    val dismiss by rememberUpdatedState(onDismiss)
    val closeLabel = stringResource(R.string.action_cancel)
    val pullClosePx = with(density) { PULL_TO_CLOSE.dp.toPx() }
    val pullVelocityPx = with(density) { PULL_VELOCITY.dp.toPx() }
    val corner = with(density) { Radii.sheetCorner.toPx() }
    val glyph = with(density) { PLUS_SIZE.dp.toPx() }
    val plus = rememberVectorPainter(Icons.Rounded.Add)
    val path = remember { Path() }

    PredictiveBackHandler(enabled = open) { progress ->
        try {
            progress.collect { back = it.progress }
            dismiss()
        } catch (e: CancellationException) {
            animate(back, 0f, animationSpec = m.spring()) { v, _ -> back = v }
            throw e
        }
    }

    /** The container's bounds in the sheet's own coordinates for [p]: the FAB at 0, the whole sheet at 1. */
    fun bounds(p: Float): Rect {
        val full = Rect(0f, 0f, sheet.width.toFloat(), sheet.height.toFloat())
        val top = (host.height - sheet.height).toFloat()
        val fab = state.fab?.translate(-hostPos.x, -hostPos.y - top)
            ?: Rect(full.center.x - glyph, full.bottom - 2 * glyph, full.center.x + glyph, full.bottom)
        return lerp(fab, full, p)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { host = it.size; hostPos = it.positionInRoot() }
            // While it is closing, taps must not reach the form.
            .then(
                if (open) Modifier
                else Modifier.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                },
            ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = state.progress.value.coerceIn(0f, 1f) * (1f - back) }
                .background(c.scrim)
                .clickable(onClickLabel = closeLabel, role = Role.Button, interactionSource = null, indication = null) { dismiss() },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset { IntOffset(0, pull.roundToInt()) }
                .graphicsLayer {
                    val k = 1f - BACK_SHRINK * back
                    scaleX = k; scaleY = k
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .onSizeChanged { sheet = it }
                .drawBehind {
                    val p = state.progress.value
                    val r = bounds(p)
                    val fabRadius = (state.fab?.height ?: (2 * glyph)) / 2f
                    val top = lerpFloat(fabRadius, corner, p.coerceIn(0f, 1f))
                    val bottom = lerpFloat(fabRadius, 0f, p.coerceIn(0f, 1f))
                    path.reset()
                    path.addRoundRect(RoundRect(r, CornerRadius(top), CornerRadius(top), CornerRadius(bottom), CornerRadius(bottom)))
                    drawPath(path, lerp(c.accentFill, c.surfaceRaised, (p * 2f).coerceIn(0f, 1f)))
                    // The FAB's + turns into an x while the container grows, and is gone by 40 %.
                    val seen = (1f - p / PLUS_GONE).coerceIn(0f, 1f)
                    if (seen > 0f) {
                        withTransform({
                            translate(r.center.x - glyph / 2, r.center.y - glyph / 2)
                            rotate(OPEN_ROTATION * p.coerceIn(0f, 1f), Offset(glyph / 2, glyph / 2))
                        }) { with(plus) { draw(Size(glyph, glyph), seen, ColorFilter.tint(c.onAccent)) } }
                    }
                }
                .graphicsLayer { alpha = ((state.progress.value - FORM_FROM) / FORM_SPAN).coerceIn(0f, 1f) }
                .drawWithContent {
                    val r = bounds(state.progress.value)
                    clipRect(r.left, r.top, r.right, r.bottom) { this@drawWithContent.drawContent() }
                },
        ) {
            // The handle: pull it down to close.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .draggable(
                        rememberDraggableState { pull = (pull + it).coerceAtLeast(0f) },
                        Orientation.Vertical,
                        onDragStopped = { velocity ->
                            if (pull > pullClosePx || velocity > pullVelocityPx) dismiss()
                            else scope.launch { animate(pull, 0f, animationSpec = m.spring()) { v, _ -> pull = v } }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(Radii.pill).background(c.outlineStrong.copy(alpha = 0.45f)))
            }
            content()
        }
    }
}

/** How far (dp) or how fast (dp/s) the handle must be pulled for the sheet to close. */
private const val PULL_TO_CLOSE = 96
private const val PULL_VELOCITY = 700
/** The sheet shrinks by this fraction at the end of the back gesture. */
private const val BACK_SHRINK = 0.08f
/** The + glyph: size (dp), the share of the open progress after which it is gone, and its turn (degrees). */
private const val PLUS_SIZE = 26
private const val PLUS_GONE = 0.4f
private const val OPEN_ROTATION = 45f
/** The form fades in over [FORM_SPAN] of the open progress, starting at [FORM_FROM]. */
private const val FORM_FROM = 0.3f
private const val FORM_SPAN = 0.5f

/**
 * The open/close progress of Quick Add's container (0 = the FAB, 1 = the sheet), and the FAB's
 * bounds it grows from. [present] is true from the first frame of opening until the last frame of
 * closing: the FAB is hidden meanwhile (the container is drawn in its place).
 */
@Stable
class AddSheetState {
    val progress = Animatable(0f)
    var present by mutableStateOf(false)
        private set
    /** Set by the FAB's slot (root coordinates); not state, it is only read when drawing. */
    var fab: Rect? = null

    suspend fun open(m: DsMotion) {
        present = true
        progress.animateTo(1f, m.spring())
    }

    suspend fun close(m: DsMotion) {
        progress.animateTo(0f, m.spring())
        present = false
    }
}
