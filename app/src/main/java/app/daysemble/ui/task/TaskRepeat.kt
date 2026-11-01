package app.daysemble.ui.task

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import app.daysemble.domain.model.SeriesScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.daysemble.R
import app.daysemble.ui.components.DsDialogAction
import app.daysemble.ui.components.DsDialogButtons
import app.daysemble.core.recurrence.Freq
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.recurrence.WeekdayNum
import app.daysemble.domain.model.RepeatEnd
import app.daysemble.ui.components.DsChip
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

/** The repeat choices offered for tasks. Everything maps to a [RRule] that TaskRecurrence supports. */
enum class RepeatPreset { NONE, DAILY, WEEKDAYS, WEEKLY, BIWEEKLY, MONTHLY, YEARLY, CUSTOM }

private val WORKDAYS = setOf(1, 2, 3, 4, 5)

/** Repeat settings as edited (serialisable with the rest of the task form). */
@Serializable
data class TaskRepeatForm(
    val preset: String = RepeatPreset.NONE.name,
    /** Custom: every [interval] [unit]s. */
    val interval: Int = 2,
    val unit: String = Freq.DAILY.name,
    /** ISO weekday numbers for weekly repeats; empty = the due date's weekday. */
    val weekdays: Set<Int> = emptySet(),
    val end: String = RepeatEnd.NEVER.name,
    val count: Int = 10,
    val untilEpochDay: Long? = null,
) {
    val kind: RepeatPreset get() = runCatching { RepeatPreset.valueOf(preset) }.getOrDefault(RepeatPreset.NONE)
    val repeats: Boolean get() = kind != RepeatPreset.NONE
    val freq: Freq get() = runCatching { Freq.valueOf(unit) }.getOrDefault(Freq.DAILY)
    val repeatEnd: RepeatEnd get() = runCatching { RepeatEnd.valueOf(end) }.getOrDefault(RepeatEnd.NEVER)
    /** Weekly kinds pick days. */
    val picksDays: Boolean get() = kind == RepeatPreset.WEEKLY || kind == RepeatPreset.BIWEEKLY || (kind == RepeatPreset.CUSTOM && freq == Freq.WEEKLY)

    fun toRRule(due: LocalDate?): RRule? {
        if (!repeats || due == null) return null
        val days = { weekdays.ifEmpty { setOf(due.dayOfWeek.value) }.sorted().map { WeekdayNum(DayOfWeek.of(it)) } }
        val base = when (kind) {
            RepeatPreset.NONE -> return null
            RepeatPreset.DAILY -> RRule(Freq.DAILY)
            RepeatPreset.WEEKDAYS -> RRule(Freq.WEEKLY, byDay = WORKDAYS.sorted().map { WeekdayNum(DayOfWeek.of(it)) })
            RepeatPreset.WEEKLY -> RRule(Freq.WEEKLY, byDay = days())
            RepeatPreset.BIWEEKLY -> RRule(Freq.WEEKLY, interval = 2, byDay = days())
            RepeatPreset.MONTHLY -> RRule(Freq.MONTHLY)
            RepeatPreset.YEARLY -> RRule(Freq.YEARLY)
            RepeatPreset.CUSTOM -> RRule(freq, interval = interval.coerceIn(1, 99), byDay = if (freq == Freq.WEEKLY) days() else emptyList())
        }
        return base.copy(
            count = count.coerceIn(1, 999).takeIf { repeatEnd == RepeatEnd.COUNT },
            untilDate = untilEpochDay?.let(LocalDate::ofEpochDay)?.takeIf { repeatEnd == RepeatEnd.UNTIL },
        )
    }

    /**
     * After the due date moves from [old] to [new]: a weekly repeat on just the old date's weekday
     * follows it to the new weekday (moving Tuesday's weekly task to Thursday makes it a Thursday task).
     */
    fun followDue(old: LocalDate?, new: LocalDate?): TaskRepeatForm =
        if (picksDays && old != null && new != null && weekdays == setOf(old.dayOfWeek.value)) copy(weekdays = setOf(new.dayOfWeek.value)) else this

    companion object {
        fun from(rule: RRule?): TaskRepeatForm {
            rule ?: return TaskRepeatForm()
            val days = rule.byDay.map { it.day.value }.toSet()
            val preset = when {
                rule.freq == Freq.DAILY && rule.interval == 1 -> RepeatPreset.DAILY
                rule.freq == Freq.WEEKLY && rule.interval == 1 && days == WORKDAYS -> RepeatPreset.WEEKDAYS
                rule.freq == Freq.WEEKLY && rule.interval == 1 -> RepeatPreset.WEEKLY
                rule.freq == Freq.WEEKLY && rule.interval == 2 -> RepeatPreset.BIWEEKLY
                rule.freq == Freq.MONTHLY && rule.interval == 1 -> RepeatPreset.MONTHLY
                rule.freq == Freq.YEARLY && rule.interval == 1 -> RepeatPreset.YEARLY
                else -> RepeatPreset.CUSTOM
            }
            return TaskRepeatForm(
                preset = preset.name, interval = rule.interval, unit = rule.freq.name,
                weekdays = if (preset == RepeatPreset.WEEKDAYS) emptySet() else days,
                end = when {
                    rule.count != null -> RepeatEnd.COUNT.name
                    rule.untilDate != null -> RepeatEnd.UNTIL.name
                    else -> RepeatEnd.NEVER.name
                },
                count = rule.count ?: 10, untilEpochDay = rule.untilDate?.toEpochDay(),
            )
        }
    }
}

/** Short label for cards and rows: "Daily", "Weekdays", "Every 2 weeks", "Weekly · Tue, Thu". */
@Composable
fun repeatLabel(rule: RRule): String {
    val p = LocalTimePrefs.current
    val f = TaskRepeatForm.from(rule)
    val days = rule.byDay.map { it.day }
    return when (f.kind) {
        RepeatPreset.DAILY -> stringResource(R.string.repeat_daily)
        RepeatPreset.WEEKDAYS -> stringResource(R.string.repeat_weekdays)
        RepeatPreset.WEEKLY -> if (days.size > 1) stringResource(R.string.repeat_weekly) + " · " + days.joinToString(", ") { Fmt.weekdayShort(it, p) }
            else stringResource(R.string.repeat_weekly)
        RepeatPreset.BIWEEKLY -> stringResource(R.string.repeat_every_x, pluralStringResource(R.plurals.unit_weeks, 2, 2))
        RepeatPreset.MONTHLY -> stringResource(R.string.repeat_monthly)
        RepeatPreset.YEARLY -> stringResource(R.string.repeat_yearly)
        else -> stringResource(R.string.repeat_every_x, pluralStringResource(app.daysemble.ui.event.freqUnit(rule.freq), rule.interval, rule.interval))
    }
}

/**
 * Repeat section of the task editor: one row of presets for the common cases; weekdays, the custom
 * interval and the end only appear when they apply. [disabledReason] explains why repeating isn't
 * available (a Google Tasks list).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskRepeatSection(
    form: TaskRepeatForm,
    due: LocalDate?,
    onChange: (TaskRepeatForm) -> Unit,
    onPickUntil: () -> Unit,
    disabledReason: String?,
) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    val m = DsTheme.motion
    if (disabledReason != null) {
        Text(disabledReason, style = DsTheme.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter))
        return
    }
    FlowRow(
        Modifier.padding(horizontal = Space.gutter - Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        RepeatPreset.entries.forEach { pr ->
            DsChip(stringResource(presetLabel(pr)), form.kind == pr, { onChange(form.copy(preset = pr.name)) })
        }
    }
    AnimatedVisibility(form.kind == RepeatPreset.CUSTOM, enter = m.expandIn(), exit = m.collapseOut()) {
        Column {
            Stepper(
                text = pluralStringResource(app.daysemble.ui.event.freqUnit(form.freq), form.interval, form.interval),
                onMinus = { onChange(form.copy(interval = (form.interval - 1).coerceAtLeast(1))) },
                onPlus = { onChange(form.copy(interval = (form.interval + 1).coerceAtMost(99))) },
                minusEnabled = form.interval > 1, label = stringResource(R.string.repeat_every),
            )
            FlowRow(Modifier.padding(horizontal = Space.gutter - Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                listOf(Freq.DAILY to R.string.repeat_unit_days, Freq.WEEKLY to R.string.repeat_unit_weeks, Freq.MONTHLY to R.string.repeat_unit_months, Freq.YEARLY to R.string.repeat_unit_years)
                    .forEach { (fr, label) -> DsChip(stringResource(label), form.freq == fr, { onChange(form.copy(unit = fr.name)) }) }
            }
        }
    }
    AnimatedVisibility(form.picksDays, enter = m.expandIn(), exit = m.collapseOut()) {
        val selected = form.weekdays.ifEmpty { setOfNotNull(due?.dayOfWeek?.value) }
        val order = (0 until 7).map { p.firstDayOfWeek.plus(it.toLong()) }
        FlowRow(
            Modifier.padding(horizontal = Space.gutter - Space.xs, vertical = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            order.forEach { d ->
                val on = d.value in selected
                DsChip(Fmt.weekdayShort(d, p), on, {
                    val next = if (on) selected - d.value else selected + d.value
                    if (next.isNotEmpty()) onChange(form.copy(weekdays = next))
                }, role = Role.Checkbox)
            }
        }
    }
    AnimatedVisibility(form.repeats, enter = m.expandIn(), exit = m.collapseOut()) {
        Column {
            FlowRow(
                Modifier.padding(horizontal = Space.gutter - Space.xs, vertical = Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                DsChip(stringResource(R.string.repeat_forever), form.repeatEnd == RepeatEnd.NEVER, { onChange(form.copy(end = RepeatEnd.NEVER.name)) })
                DsChip(stringResource(R.string.repeat_count), form.repeatEnd == RepeatEnd.COUNT, { onChange(form.copy(end = RepeatEnd.COUNT.name)) })
                DsChip(
                    form.untilEpochDay?.takeIf { form.repeatEnd == RepeatEnd.UNTIL }
                        ?.let { stringResource(R.string.repeat_until_date, Fmt.mediumDate(LocalDate.ofEpochDay(it), p)) }
                        ?: stringResource(R.string.repeat_until),
                    form.repeatEnd == RepeatEnd.UNTIL, onPickUntil,
                )
            }
            if (form.repeatEnd == RepeatEnd.COUNT) {
                Stepper(
                    text = pluralStringResource(R.plurals.repeat_times, form.count, form.count),
                    onMinus = { onChange(form.copy(count = (form.count - 1).coerceAtLeast(1))) },
                    onPlus = { onChange(form.copy(count = (form.count + 1).coerceAtMost(999))) },
                    minusEnabled = form.count > 1,
                )
            }
            Text(
                stringResource(R.string.repeat_task_note), style = DsTheme.type.label, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xs),
            )
        }
    }
}

/**
 * Asks which occurrences of a repeating task a save or delete applies to. Each choice says what it
 * does; tapping one applies it. [allowThis] is false when "only this one" can't apply (the repeat
 * rule itself was changed).
 */
@Composable
fun SeriesScopeDialog(
    forDelete: Boolean,
    allowThis: Boolean,
    onPick: (SeriesScope) -> Unit,
    onDismiss: () -> Unit,
    /** A completed occurrence: "this and future" only applies from the current one. */
    pastOccurrence: Boolean = false,
) {
    val c = DsTheme.colors
    val options = buildList {
        val thisDesc = when {
            !forDelete -> R.string.scope_this_desc
            pastOccurrence -> R.string.scope_this_delete_done_desc
            else -> R.string.scope_this_delete_desc
        }
        if (allowThis) add(Triple(SeriesScope.THIS, R.string.scope_this, thisDesc))
        if (!pastOccurrence) add(Triple(SeriesScope.FUTURE, R.string.scope_future, if (forDelete) R.string.scope_future_delete_desc else R.string.scope_future_desc))
        add(Triple(SeriesScope.ALL, R.string.scope_all, if (forDelete) R.string.scope_all_delete_desc else R.string.scope_all_desc))
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = app.daysemble.ui.theme.Radii.dialog,
        containerColor = c.surfaceRaised,
        title = {
            Text(
                stringResource(if (forDelete) R.string.scope_delete_title else R.string.scope_save_title),
                style = DsTheme.type.titleLarge, color = c.textPrimary,
            )
        },
        text = {
            // Scrolls: with very large text the three choices don't fit on a small screen.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                options.forEach { (scope, title, desc) ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(app.daysemble.ui.theme.Radii.card)
                            .background(c.surfaceVariant)
                            .clickable(role = Role.Button) { onPick(scope); onDismiss() }
                            .padding(horizontal = Space.l, vertical = Space.m),
                    ) {
                        Text(stringResource(title), style = DsTheme.type.bodyStrong, color = if (forDelete) c.error else c.textPrimary)
                        Text(stringResource(desc), style = DsTheme.type.bodySmall, color = c.textSecondary)
                    }
                }
            }
        },
        confirmButton = { DsDialogButtons(listOf(DsDialogAction(stringResource(R.string.action_cancel), onDismiss, DsDialogAction.Kind.Cancel))) },
    )
}

private fun presetLabel(p: RepeatPreset) = when (p) {
    RepeatPreset.NONE -> R.string.repeat_does_not
    RepeatPreset.DAILY -> R.string.repeat_daily
    RepeatPreset.WEEKDAYS -> R.string.repeat_weekdays
    RepeatPreset.WEEKLY -> R.string.repeat_weekly
    RepeatPreset.BIWEEKLY -> R.string.repeat_biweekly
    RepeatPreset.MONTHLY -> R.string.repeat_monthly
    RepeatPreset.YEARLY -> R.string.repeat_yearly
    RepeatPreset.CUSTOM -> R.string.repeat_custom_short
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
