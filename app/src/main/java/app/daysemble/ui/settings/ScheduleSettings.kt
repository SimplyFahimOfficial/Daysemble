package app.daysemble.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.DaysembleApp
import app.daysemble.R
import app.daysemble.ui.components.DsDialogAction
import app.daysemble.ui.components.DsDialogButtons
import app.daysemble.core.time.DaySchedulePrefs
import app.daysemble.core.time.SleepLog
import app.daysemble.core.time.SleepSchedule
import app.daysemble.data.prefs.AppSettings
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsConfirmDialog
import app.daysemble.ui.components.DsRow
import app.daysemble.ui.components.CardDivider
import app.daysemble.ui.components.SettingsCard
import app.daysemble.ui.components.DsSwitchRow
import app.daysemble.ui.components.DsTimePickerDialog
import app.daysemble.ui.components.DsTopBar
import app.daysemble.ui.components.HairlineDivider
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.TimePrefs
import app.daysemble.ui.format.durationText
import app.daysemble.ui.sleep.SleepButtons
import app.daysemble.ui.sleep.SleepHistory
import app.daysemble.ui.sleep.SleepPrompts
import app.daysemble.ui.sleep.rememberSleepActions
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime

object ScheduleTags {
    const val WAKE = "schedule_wake"
    const val BED = "schedule_bed"
    const val SUMMARY = "schedule_summary"
    const val RESET = "schedule_reset"
}

/**
 * Settings → Daily schedule & sleep. The waking hours (wake-up, bedtime, optional per-weekday
 * hours) set the window Today counts free time in; the sleep buttons and records below are the
 * user's actual times and never change these settings.
 */
@Composable
fun ScheduleSettingsRoute(onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as DaysembleApp).container
    val flow = remember(container) { container.settings.settings.map<AppSettings, AppSettings?> { it } }
    val s by flow.collectAsStateWithLifecycle(initialValue = app.daysemble.ui.LocalAppSettings.current)
    val records by container.sleep.recent.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val p = LocalTimePrefs.current
    val clock = LocalAppClock.current
    val actions = rememberSleepActions()
    var pick by rememberSaveable { mutableStateOf<String?>(null) } // "wake" | "bed"
    var weekday by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var sameTimeError by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(DsTheme.colors.background)) {
        DsTopBar(stringResource(R.string.settings_schedule), onBack)
        SettingsBody(loaded = s != null && records != null) {
            val schedule = s!!.schedule
            val d = schedule.default
            SectionHeader(stringResource(R.string.schedule_waking_hours))
            SettingsCard {
                DsRow(
                    stringResource(R.string.schedule_wake), subtitle = Fmt.time(d.wake, p), icon = Icons.Rounded.WbSunny,
                    onClick = { pick = "wake" }, modifier = Modifier.testTag(ScheduleTags.WAKE),
                )
                CardDivider()
                DsRow(
                    stringResource(R.string.schedule_bed), subtitle = Fmt.time(d.bed, p), icon = Icons.Rounded.Bedtime,
                    onClick = { pick = "bed" }, modifier = Modifier.testTag(ScheduleTags.BED),
                )
            }
            Text(
                scheduleSummary(d, p), style = DsTheme.type.bodySmall, color = DsTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s).testTag(ScheduleTags.SUMMARY),
            )
            if (sameTimeError) {
                Text(
                    stringResource(R.string.schedule_error_same), style = DsTheme.type.label, color = DsTheme.colors.error,
                    modifier = Modifier.padding(horizontal = Space.gutter),
                )
            }
            SettingsCard {
            DsSwitchRow(
                stringResource(R.string.schedule_per_weekday), schedule.perWeekday,
                { on -> scope.launch { container.settings.setPerWeekdaySchedule(on) } },
                subtitle = stringResource(if (schedule.perWeekday) R.string.schedule_per_weekday_on else R.string.schedule_per_weekday_off),
            )
            if (schedule.perWeekday) {
                for (day in weekOrder(p.firstDayOfWeek)) {
                    val own = schedule.overrides[day]
                    val eff = own ?: d
                    CardDivider()
                    DsRow(
                        Fmt.weekdayFull(day, p),
                        subtitle = stringResource(R.string.time_range, Fmt.time(eff.wake, p), Fmt.time(eff.bed, p)) +
                            if (own == null) " · " + stringResource(R.string.schedule_uses_default) else "",
                        onClick = { weekday = day.value },
                    )
                }
            }
            }
            Spacer(Modifier.height(Space.s))
            DsButton(
                stringResource(R.string.schedule_reset), { confirmReset = true }, style = DsButtonStyle.Secondary,
                icon = Icons.Rounded.RestartAlt, modifier = Modifier.padding(horizontal = Space.gutter).testTag(ScheduleTags.RESET),
            )
            Text(
                stringResource(R.string.schedule_free_time_note), style = DsTheme.type.label, color = DsTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.m),
            )

            HairlineDivider(Modifier.padding(top = Space.s))
            SectionHeader(stringResource(R.string.sleep_section), Modifier.padding(top = Space.l))
            val list = records.orEmpty()
            val asleep = SleepLog.openSession(list, clock.now()) != null
            SleepButtons(actions, asleep, Modifier.padding(horizontal = Space.gutter))
            Text(
                stringResource(R.string.sleep_manual_note), style = DsTheme.type.label, color = DsTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
            )
            SectionHeader(
                stringResource(R.string.sleep_history), Modifier.padding(top = Space.s),
                trailing = { app.daysemble.ui.components.DsIconButton(Icons.Rounded.Add, stringResource(R.string.sleep_add_title), actions::add) },
            )
            SleepHistory(list, actions)
        }
    }
    SleepPrompts(actions)

    pick?.let { which ->
        val d = s?.schedule?.default ?: SleepSchedule.DEFAULT
        DsTimePickerDialog(
            initial = if (which == "wake") d.wake else d.bed,
            onPick = { t ->
                val m = t.hour * 60 + t.minute
                scope.launch {
                    val ok = if (which == "wake") container.settings.setDefaultSchedule(m, d.bedMinute) else container.settings.setDefaultSchedule(d.wakeMinute, m)
                    sameTimeError = !ok
                }
            },
            onDismiss = { pick = null },
        )
    }
    weekday?.let { v ->
        val day = DayOfWeek.of(v)
        WeekdayDialog(day, s?.schedule ?: DaySchedulePrefs(), p, onDismiss = { weekday = null }) { sched ->
            scope.launch { container.settings.setWeekdaySchedule(day, sched) }
        }
    }
    if (confirmReset) {
        DsConfirmDialog(
            stringResource(R.string.schedule_reset_title), stringResource(R.string.schedule_reset_body),
            stringResource(R.string.schedule_reset), onConfirm = { scope.launch { container.settings.resetSchedule(); sameTimeError = false } },
            onDismiss = { confirmReset = false },
        )
    }
}

/** "Awake 16 h (7:00 AM – 11:00 PM) · 8 h planned sleep". */
@Composable
fun scheduleSummary(s: SleepSchedule, p: TimePrefs): String = stringResource(
    R.string.schedule_summary,
    durationText(s.awakeMinutes.toLong()), Fmt.time(s.wake, p), Fmt.time(s.bed, p), durationText(s.sleepMinutes.toLong()),
)

/** One weekday's own hours, or back to the default. */
@Composable
private fun WeekdayDialog(day: DayOfWeek, prefs: DaySchedulePrefs, p: TimePrefs, onDismiss: () -> Unit, onSave: (SleepSchedule?) -> Unit) {
    val c = DsTheme.colors
    val start = prefs.overrides[day] ?: prefs.default
    var wake by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(start.wakeMinute) }
    var bed by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(start.bedMinute) }
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss, shape = Radii.dialog, containerColor = c.surfaceRaised,
        title = { Text(Fmt.weekdayFull(day, p), style = DsTheme.type.titleLarge, color = c.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                DsRow(stringResource(R.string.schedule_wake), subtitle = Fmt.time(LocalTime.of(wake / 60, wake % 60), p), icon = Icons.Rounded.WbSunny, onClick = { pick = "wake" })
                DsRow(stringResource(R.string.schedule_bed), subtitle = Fmt.time(LocalTime.of(bed / 60, bed % 60), p), icon = Icons.Rounded.Bedtime, onClick = { pick = "bed" })
                if (wake == bed) Text(stringResource(R.string.schedule_error_same), style = DsTheme.type.label, color = c.error)
                else Text(scheduleSummary(SleepSchedule(wake, bed), p), style = DsTheme.type.bodySmall, color = c.textSecondary)
            }
        },
        confirmButton = {
            DsDialogButtons(
                listOf(
                    DsDialogAction(stringResource(R.string.action_save), { onSave(SleepSchedule(wake, bed)); onDismiss() }, enabled = wake != bed),
                    DsDialogAction(stringResource(R.string.schedule_use_default), { onSave(null); onDismiss() }, DsDialogAction.Kind.Alternative),
                    DsDialogAction(stringResource(R.string.action_cancel), onDismiss, DsDialogAction.Kind.Cancel),
                ),
            )
        },
    )
    pick?.let { which ->
        val m = if (which == "wake") wake else bed
        DsTimePickerDialog(LocalTime.of(m / 60, m % 60), onPick = { t -> if (which == "wake") wake = t.hour * 60 + t.minute else bed = t.hour * 60 + t.minute }, onDismiss = { pick = null })
    }
}

/** The seven weekdays, starting at [first]. */
internal fun weekOrder(first: DayOfWeek): List<DayOfWeek> = List(DAYS_PER_WEEK) { first.plus(it.toLong()) }

private const val DAYS_PER_WEEK = 7
