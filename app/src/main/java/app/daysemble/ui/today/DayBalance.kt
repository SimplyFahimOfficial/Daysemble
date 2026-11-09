package app.daysemble.ui.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daysemble.R
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsChip
import app.daysemble.ui.components.HairlineDivider
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.TimePrefs
import app.daysemble.ui.format.durationText
import app.daysemble.ui.sleep.SleepActions
import app.daysemble.ui.sleep.SleepButtons
import app.daysemble.ui.sleep.recordLine
import app.daysemble.ui.sleep.sleepSummaryText
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space
import java.time.Duration
import java.time.Instant

object BalanceTags {
    const val FREE = "balance_free"
    const val INFO = "balance_info"
    const val SLEEP = "balance_sleep"
    const val SHEET = "balance_sheet"
}

internal fun Duration.roundToMinutes(): Long = (toMillis() + 30_000) / 60_000

/**
 * The day's free time, booked time and planned sleep in one card. Tapping the card opens it inline
 * to show the waking hours, free time and planned sleep, with a link to Settings → Daily schedule
 * and sleep; the info button opens the breakdown. Below it, the sleep line (today: with the button
 * that fits the moment). [interactive] is false while the overview is folded away (its buttons must
 * not take taps).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DayBalance(
    s: TodayUiState,
    now: Instant,
    p: TimePrefs,
    interactive: Boolean,
    onInfo: () -> Unit,
    onSleep: () -> Unit,
    sleepActions: SleepActions?,
    onOpenSchedule: () -> Unit = {},
    sleepHintDismissed: Boolean = false,
    onDismissSleepHint: () -> Unit = {},
) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val a = s.availability
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, m.spring(), label = "balanceChevron")
    val toggleLabel = stringResource(if (expanded) R.string.balance_collapse else R.string.balance_expand)
    Spacer(Modifier.height(Space.l))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radii.tile)
            .background(c.surfaceVariant)
            .clickable(enabled = interactive, role = Role.Button, onClickLabel = toggleLabel) { expanded = !expanded }
            .padding(start = Space.l, top = Space.m, bottom = Space.xs, end = Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (a != null) {
                    Metric(Icons.Rounded.Schedule, c.success, stringResource(R.string.metric_free, durationText(a.free.roundToMinutes())), Modifier.testTag(BalanceTags.FREE))
                    if (!a.isFree) Metric(Icons.Rounded.EventBusy, c.violet, stringResource(R.string.metric_busy, durationText(a.busy.roundToMinutes())))
                }
                if (!s.scheduledSleep.isZero) {
                    Metric(Icons.Rounded.Bedtime, c.textSecondary, stringResource(R.string.metric_sleep_planned, durationText(s.scheduledSleep.roundToMinutes())))
                }
                s.nextEvent?.let { e ->
                    Metric(
                        Icons.AutoMirrored.Rounded.ArrowForward, c.accent,
                        stringResource(R.string.metric_next, e.title.ifBlank { stringResource(R.string.untitled_event) }, Fmt.time(e.start, p)),
                    )
                }
            }
            Icon(
                Icons.Rounded.ExpandMore, null, tint = c.textTertiary,
                modifier = Modifier.padding(horizontal = Space.s).graphicsLayer { rotationZ = chevron },
            )
        }
        val w = s.window
        if (w != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val note = when {
                    a == null -> stringResource(R.string.metric_window_over, Fmt.time(w.start, p), Fmt.time(w.end, p))
                    a.countsFromNow -> stringResource(R.string.metric_window_from_now, Fmt.time(w.start, p), Fmt.time(w.end, p))
                    else -> stringResource(R.string.metric_window, Fmt.time(w.start, p), Fmt.time(w.end, p))
                }
                Text(note, style = DsTheme.type.label, color = c.textTertiary, modifier = Modifier.weight(1f, fill = false))
                app.daysemble.ui.components.DsIconButton(
                    Icons.Outlined.Info, stringResource(R.string.cd_free_time_breakdown), onInfo, tint = c.textTertiary,
                    enabled = interactive, modifier = Modifier.testTag(BalanceTags.INFO),
                )
            }
        }
        if (a != null && !a.estimatedWork.isZero) {
            Text(
                stringResource(R.string.metric_after_estimates, durationText(a.freeAfterEstimates.roundToMinutes()), durationText(a.estimatedWork.roundToMinutes())),
                style = DsTheme.type.label, color = c.textSecondary, modifier = Modifier.padding(bottom = Space.s),
            )
        }
        AnimatedVisibility(expanded, enter = m.expandIn(), exit = m.collapseOut()) {
            Column(Modifier.padding(end = Space.m, bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                HairlineDivider(Modifier.padding(bottom = Space.xs))
                s.window?.let { w -> DetailLine(stringResource(R.string.balance_waking_hours), stringResource(R.string.time_range, Fmt.time(w.start, p), Fmt.time(w.end, p))) }
                a?.let { DetailLine(stringResource(R.string.balance_free_time), durationText(it.free.roundToMinutes())) }
                if (!s.scheduledSleep.isZero) DetailLine(stringResource(R.string.balance_planned_sleep), durationText(s.scheduledSleep.roundToMinutes()))
                DsButton(
                    stringResource(R.string.balance_open_schedule), onOpenSchedule, style = DsButtonStyle.Text,
                    icon = Icons.AutoMirrored.Rounded.ArrowForward, enabled = interactive,
                )
            }
        }
    }
    SleepLine(s, now, p, interactive, onSleep, sleepActions, sleepHintDismissed, onDismissSleepHint)
}

@Composable
private fun DetailLine(label: String, value: String) {
    val c = DsTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = DsTheme.type.label, color = c.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = DsTheme.type.numeric, color = c.textPrimary)
    }
}

/** "Slept 7 h 42 min · 11:08 PM – 6:50 AM" (or what is known), with the button that fits the moment. */
@Composable
private fun SleepLine(
    s: TodayUiState, now: Instant, p: TimePrefs, interactive: Boolean, onSleep: () -> Unit, actions: SleepActions?,
    hintDismissed: Boolean, onDismissHint: () -> Unit,
) {
    val c = DsTheme.colors
    val sl = s.sleep
    val isToday = s.date == s.today
    if (!isToday && sl.lastNight == null && sl.wakeOnly == null) return
    // Nothing recorded for last night: a single quiet line with "Log sleep", which can be dismissed for the day.
    val nothing = sl.lastNight == null && sl.open == null && sl.wakeOnly == null
    if (nothing && !hintDismissed) {
        val m = DsTheme.motion
        Row(
            Modifier.fillMaxWidth().heightIn(min = Sizes.touch).testTag(BalanceTags.SLEEP),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(stringResource(R.string.sleep_none_recorded), style = DsTheme.type.label, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            DsButton(
                stringResource(R.string.sleep_log), onSleep, style = DsButtonStyle.Text, enabled = interactive,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.s, vertical = Space.xs),
            )
            app.daysemble.ui.components.DsIconButton(Icons.Rounded.Close, stringResource(R.string.cd_dismiss_hint), onDismissHint, tint = c.textTertiary, enabled = interactive)
        }
        return
    }
    val text = sleepSummaryText(sl.lastNight, sl.open, sl.wakeOnly, p)
    val detail = (sl.lastNight ?: sl.wakeOnly)?.takeIf { sl.open == null }?.let { recordLine(it, p) }
    val diff = sl.lastNight?.duration?.let { it - s.scheduledSleep }?.takeIf { !s.scheduledSleep.isZero && sl.open == null }
    Spacer(Modifier.height(Space.s))
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.touch)
            .clickable(enabled = interactive, role = Role.Button, onClickLabel = stringResource(R.string.sleep_open), onClick = onSleep)
            .testTag(BalanceTags.SLEEP),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Icon(Icons.Rounded.Bedtime, null, tint = c.textSecondary, modifier = Modifier.size(Sizes.iconSmall - 2.dp))
        Column(Modifier.weight(1f)) {
            if (!nothing) Text(text, style = DsTheme.type.label, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                detail,
                diff?.let { d ->
                    val m = d.roundToMinutes()
                    when {
                        m > 0 -> stringResource(R.string.sleep_more_than_planned, durationText(m))
                        m < 0 -> stringResource(R.string.sleep_less_than_planned, durationText(-m))
                        else -> stringResource(R.string.sleep_as_planned)
                    }
                },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = DsTheme.type.label, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isToday && actions != null && interactive) {
            // One contextual action: wake up while a bedtime is open; going to sleep in the evening
            // (from 2 h before the planned bedtime); waking up in the morning if nothing is recorded.
            val evening = s.window?.let { w -> !now.isBefore(w.end.minus(Duration.ofHours(2))) } ?: false
            // Morning: up to 4 h after the planned wake-up (or before it).
            val morning = s.window?.let { w -> now.isBefore(w.start.plus(Duration.ofHours(4))) } ?: false
            when {
                sl.open != null -> DsChip(stringResource(R.string.sleep_woke_up), true, actions::wakeUp, icon = Icons.Rounded.WbSunny, role = Role.Button)
                evening -> DsChip(stringResource(R.string.sleep_going_to_sleep), false, actions::goToSleep, icon = Icons.Rounded.Bedtime, role = Role.Button)
                morning && sl.lastNight == null && sl.wakeOnly == null -> DsChip(stringResource(R.string.sleep_woke_up), false, actions::wakeUp, icon = Icons.Rounded.WbSunny, role = Role.Button)
            }
        }
    }
}

@Composable
private fun Metric(icon: ImageVector, tint: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs + Space.xxs)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(Sizes.iconSmall - 2.dp))
        Text(text, style = DsTheme.type.label, color = DsTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** How the free time was counted, line by line, from the same numbers the overview shows. */
@Composable
internal fun FreeTimeSheet(s: TodayUiState, p: TimePrefs, onDismiss: () -> Unit, onChangeHours: () -> Unit) {
    val c = DsTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surfaceRaised, shape = Radii.sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = Space.xl)
                .testTag(BalanceTags.SHEET),
        ) {
            Text(
                stringResource(R.string.breakdown_title), style = DsTheme.type.titleLarge, color = c.textPrimary,
                modifier = Modifier.padding(horizontal = Space.gutter).semantics { heading() },
            )
            Spacer(Modifier.height(Space.m))
            val w = s.window
            val a = s.availability
            if (w != null) {
                Line(
                    stringResource(R.string.breakdown_window),
                    stringResource(R.string.time_range, Fmt.time(w.start, p), Fmt.time(w.end, p)),
                    durationText(w.duration.roundToMinutes()),
                )
            }
            if (a == null) {
                Note(stringResource(R.string.breakdown_over))
            } else {
                if (a.countsFromNow) Line(stringResource(R.string.breakdown_from_now, Fmt.time(a.from, p)), null, durationText(a.counted.roundToMinutes()))
                if (a.eventBusy.roundToMinutes() > 0) Line(stringResource(R.string.breakdown_events), null, "− " + durationText(a.eventBusy.roundToMinutes()))
                if (a.taskBusy.roundToMinutes() > 0) Line(stringResource(R.string.breakdown_task_blocks), null, "− " + durationText(a.taskBusy.roundToMinutes()))
                if (a.busy.isZero) Note(stringResource(R.string.breakdown_nothing_busy))
                val overlap = a.eventBusy + a.taskBusy - a.busy
                if (overlap.roundToMinutes() > 0) Line(stringResource(R.string.breakdown_overlap), null, "+ " + durationText(overlap.roundToMinutes()))
                HairlineDivider(Modifier.padding(horizontal = Space.gutter, vertical = Space.xs))
                Line(stringResource(R.string.breakdown_free), null, durationText(a.free.roundToMinutes()), strong = true)
                if (!a.estimatedWork.isZero) {
                    Line(
                        stringResource(R.string.breakdown_estimates), stringResource(R.string.breakdown_estimates_sub),
                        durationText(a.estimatedWork.roundToMinutes()),
                    )
                    Line(stringResource(R.string.breakdown_after_estimates), null, durationText(a.freeAfterEstimates.roundToMinutes()))
                }
                if (a.unestimatedTasks > 0) Note(pluralStringResource(R.plurals.breakdown_unestimated, a.unestimatedTasks, a.unestimatedTasks))
            }
            if (!s.scheduledSleep.isZero) Line(stringResource(R.string.breakdown_sleep), null, durationText(s.scheduledSleep.roundToMinutes()))

            // Only what is still inside the counted span (today: what hasn't ended yet).
            val from = a?.from
            val entries = s.freeTimeEntries.filter { e ->
                when (e) {
                    is FreeTimeEntry.Event -> from == null || e.end.isAfter(from)
                    is FreeTimeEntry.TaskBlock -> from == null || e.end.isAfter(from)
                    is FreeTimeEntry.Estimate -> true
                }
            }
            if (a != null && entries.isNotEmpty()) {
                SectionHeader(stringResource(R.string.breakdown_items), Modifier.padding(top = Space.m))
                entries.forEach { e ->
                    when (e) {
                        is FreeTimeEntry.Event -> Line(e.title.ifBlank { stringResource(R.string.untitled_event) }, stringResource(R.string.breakdown_kind_event), stringResource(R.string.time_range, Fmt.time(e.start, p), Fmt.time(e.end, p)))
                        is FreeTimeEntry.TaskBlock -> Line(
                            e.title, stringResource(if (e.projected) R.string.breakdown_kind_repeat else R.string.breakdown_kind_task),
                            stringResource(R.string.time_range, Fmt.time(e.start, p), Fmt.time(e.end, p)),
                        )
                        is FreeTimeEntry.Estimate -> Line(e.title, stringResource(R.string.breakdown_kind_estimate), durationText(e.minutes.toLong()))
                    }
                }
            }
            Note(stringResource(R.string.breakdown_rules))
            DsButton(
                stringResource(R.string.breakdown_change_hours), { onDismiss(); onChangeHours() }, style = DsButtonStyle.Secondary,
                modifier = Modifier.padding(horizontal = Space.gutter),
            )
        }
    }
}

@Composable
private fun Line(title: String, sub: String?, value: String, strong: Boolean = false) {
    val c = DsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Space.gutter, vertical = Space.xs + Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = if (strong) DsTheme.type.bodyStrong else DsTheme.type.body, color = c.textPrimary)
            if (sub != null) Text(sub, style = DsTheme.type.label, color = c.textSecondary)
        }
        Text(value, style = if (strong) DsTheme.type.bodyStrong else DsTheme.type.numeric, color = if (strong) c.textPrimary else c.textSecondary)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text, style = DsTheme.type.label, color = DsTheme.colors.textSecondary,
        modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
    )
}

/** Sleep records and the two buttons, from the sleep line on Today. */
@Composable
internal fun SleepSheet(records: List<app.daysemble.core.time.SleepRecord>, asleep: Boolean, actions: SleepActions, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val c = DsTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surfaceRaised, shape = Radii.sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Space.xl)) {
            Text(
                stringResource(R.string.sleep_section), style = DsTheme.type.titleLarge, color = c.textPrimary,
                modifier = Modifier.padding(horizontal = Space.gutter).semantics { heading() },
            )
            Spacer(Modifier.height(Space.m))
            SleepButtons(actions, asleep, Modifier.padding(horizontal = Space.gutter))
            Note(stringResource(R.string.sleep_manual_note))
            SectionHeader(stringResource(R.string.sleep_history))
            app.daysemble.ui.sleep.SleepHistory(records, actions, max = 7)
            DsButton(
                stringResource(R.string.sleep_schedule_settings), { onDismiss(); onOpenSettings() }, style = DsButtonStyle.Ghost,
                modifier = Modifier.padding(horizontal = Space.gutter - Space.xs),
            )
        }
    }
}
