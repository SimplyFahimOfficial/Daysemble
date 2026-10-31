package app.daysemble.ui.sleep

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.daysemble.DaysembleApp
import app.daysemble.R
import app.daysemble.ui.components.DsDialogAction
import app.daysemble.ui.components.DsDialogButtons
import app.daysemble.core.time.SleepLog
import app.daysemble.core.time.SleepRecord
import app.daysemble.data.repo.SleepRepository
import app.daysemble.ui.LocalSnackbar
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsDatePickerDialog
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.components.DsTimePickerDialog
import app.daysemble.ui.components.DsValueChip
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.TimePrefs
import app.daysemble.ui.format.dayLabel
import app.daysemble.ui.format.durationText
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The two sleep buttons and everything they can ask: "already asleep / already awake" questions
 * instead of duplicate records, the editor for correcting times, and the snackbar with Undo.
 * One instance per screen that shows the buttons; call [Prompts] once in that screen.
 */
@Stable
class SleepActions internal constructor(
    private val repo: SleepRepository,
    private val zone: () -> ZoneId,
    private val launch: (suspend () -> Unit) -> Unit,
    private val notify: suspend (message: String, undo: (suspend () -> Unit)?) -> Unit,
    private val messages: Messages,
) {
    internal data class Messages(val bedtimeSaved: String, val wakeSaved: String, val updated: String, val deleted: String, val fmt: (Instant) -> String, val dur: (Duration) -> String)

    internal var prompt by mutableStateOf<Prompt?>(null)
    internal var editing by mutableStateOf<SleepRecord?>(null)
    internal var adding by mutableStateOf(false)

    internal sealed interface Prompt {
        data class AlreadyAsleep(val open: SleepRecord) : Prompt
        data class AlreadyAwake(val last: SleepRecord) : Prompt
    }

    fun goToSleep() = launch {
        when (val r = repo.goToSleep(zone())) {
            is SleepRepository.Result.AlreadyAsleep -> prompt = Prompt.AlreadyAsleep(r.open)
            is SleepRepository.Result.Saved -> notify(messages.bedtimeSaved.format(messages.fmt(r.record.bedtime!!))) { repo.delete(r.record.id) }
            is SleepRepository.Result.AlreadyAwake -> Unit
        }
    }

    fun wakeUp() = launch {
        when (val r = repo.wakeUp(zone())) {
            is SleepRepository.Result.AlreadyAwake -> prompt = Prompt.AlreadyAwake(r.last)
            is SleepRepository.Result.Saved -> {
                val rec = r.record
                val text = rec.duration?.let { messages.wakeSaved.format(messages.dur(it)) } ?: messages.updated
                notify(text) {
                    // Undo: a closed bedtime is open again; a wake-up recorded alone goes away.
                    if (rec.bedtime != null) repo.update(rec.id, rec.bedtime, null, zone()) else repo.delete(rec.id)
                }
            }
            is SleepRepository.Result.AlreadyAsleep -> Unit
        }
    }

    fun edit(record: SleepRecord) { editing = record }
    fun add() { adding = true }

    internal fun moveToNow(p: Prompt) = launch {
        when (p) {
            is Prompt.AlreadyAsleep -> repo.setBedtimeNow(p.open.id, zone())
            is Prompt.AlreadyAwake -> repo.setWakeNow(p.last.id, zone())
        }
        notify(messages.updated, null)
    }

    internal suspend fun save(id: String?, bedtime: Instant?, wake: Instant?): SleepLog.EditError? =
        if (id == null) repo.add(bedtime, wake, zone()) else repo.update(id, bedtime, wake, zone())

    internal fun delete(record: SleepRecord) = launch {
        repo.delete(record.id)
        notify(messages.deleted) { repo.restore(record) }
    }
}

@Composable
fun rememberSleepActions(): SleepActions {
    val container = (LocalContext.current.applicationContext as DaysembleApp).container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val p = LocalTimePrefs.current
    val undo = stringResource(R.string.action_undo)
    val hm = stringResource(R.string.hours_minutes_short)
    val messages = SleepActions.Messages(
        bedtimeSaved = stringResource(R.string.sleep_bedtime_saved),
        wakeSaved = stringResource(R.string.sleep_wake_saved),
        updated = stringResource(R.string.sleep_saved),
        deleted = stringResource(R.string.sleep_deleted),
        fmt = { Fmt.time(it, p) },
        dur = { d -> hm.format(d.toHours(), d.toMinutes() % 60) },
    )
    val zone = p.zone
    return remember(container, snackbar, zone, messages.bedtimeSaved) {
        SleepActions(
            repo = container.sleep,
            zone = { zone },
            launch = { block -> scope.launch { block() } },
            notify = { msg, undoAction ->
                // The snackbar outlives the screen (it's the app's), so Undo still works after leaving.
                container.appScope.launch {
                    val r = snackbar.showSnackbar(msg, if (undoAction != null) undo else null, duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) undoAction?.invoke()
                }
            },
            messages = messages,
        )
    }
}

/** The questions and editors of [actions]; place once in the screen that uses them. */
@Composable
fun SleepPrompts(actions: SleepActions) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    actions.prompt?.let { prompt ->
        val (title, body) = when (prompt) {
            is SleepActions.Prompt.AlreadyAsleep ->
                stringResource(R.string.sleep_already_asleep_title) to stringResource(R.string.sleep_already_asleep_body, Fmt.time(prompt.open.bedtime!!, p))
            is SleepActions.Prompt.AlreadyAwake ->
                stringResource(R.string.sleep_already_awake_title) to stringResource(R.string.sleep_already_awake_body, Fmt.time(prompt.last.wake!!, p))
        }
        val record = when (prompt) {
            is SleepActions.Prompt.AlreadyAsleep -> prompt.open
            is SleepActions.Prompt.AlreadyAwake -> prompt.last
        }
        AlertDialog(
            onDismissRequest = { actions.prompt = null },
            shape = Radii.dialog, containerColor = c.surfaceRaised,
            title = { Text(title, style = DsTheme.type.titleLarge, color = c.textPrimary) },
            text = { Text(body, style = DsTheme.type.body, color = c.textSecondary) },
            confirmButton = { DsDialogButtons(promptActions(actions, prompt, record)) },
        )
    }
    actions.editing?.let { r -> SleepEditDialog(r, actions, onDismiss = { actions.editing = null }) }
    if (actions.adding) SleepEditDialog(null, actions, onDismiss = { actions.adding = false })
}

/** Update to now (the main answer), Edit times, Cancel. */
@Composable
private fun promptActions(actions: SleepActions, prompt: SleepActions.Prompt, record: SleepRecord): List<DsDialogAction> = listOf(
    DsDialogAction(stringResource(R.string.sleep_update_to_now), { actions.prompt = null; actions.moveToNow(prompt) }),
    DsDialogAction(stringResource(R.string.sleep_edit_times), { actions.prompt = null; actions.edit(record) }, DsDialogAction.Kind.Alternative),
    DsDialogAction(stringResource(R.string.action_cancel), { actions.prompt = null }, DsDialogAction.Kind.Cancel),
)

/** Cancel, Delete (for an existing record), Save. */
@Composable
private fun editActions(canDelete: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: () -> Unit): List<DsDialogAction> {
    val cancel = DsDialogAction(stringResource(R.string.action_cancel), onDismiss, DsDialogAction.Kind.Cancel)
    val delete = DsDialogAction(stringResource(R.string.action_delete), onDelete, DsDialogAction.Kind.Destructive)
    val save = DsDialogAction(stringResource(R.string.action_save), onSave)
    return if (canDelete) listOf(cancel, delete, save) else listOf(cancel, save)
}

/** The two buttons, side by side; the one that fits the moment comes first and is filled. */
@Composable
fun SleepButtons(actions: SleepActions, asleep: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        val wake: @Composable () -> Unit = {
            DsButton(
                stringResource(R.string.sleep_woke_up), actions::wakeUp, icon = Icons.Rounded.WbSunny,
                style = if (asleep) DsButtonStyle.Primary else DsButtonStyle.Secondary,
            )
        }
        val sleep: @Composable () -> Unit = {
            DsButton(
                stringResource(R.string.sleep_going_to_sleep), actions::goToSleep, icon = Icons.Rounded.Bedtime,
                style = if (asleep) DsButtonStyle.Secondary else DsButtonStyle.Primary,
            )
        }
        if (asleep) { wake(); sleep() } else { sleep(); wake() }
    }
}

/** Recorded sleep, newest first; tapping a record edits it. */
@Composable
fun SleepHistory(records: List<SleepRecord>, actions: SleepActions, modifier: Modifier = Modifier, max: Int = 14) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    val today = LocalAppClock.current.now().atZone(p.zone).toLocalDate()
    Column(modifier) {
        if (records.isEmpty()) {
            Text(
                stringResource(R.string.sleep_history_empty), style = DsTheme.type.bodySmall, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
            )
        }
        records.take(max).forEach { r ->
            val night = r.nightOf(p.zone)
            val label = night?.let { dayLabel(it, today, p) } ?: ""
            val editLabel = stringResource(R.string.sleep_edit_times)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = app.daysemble.ui.theme.Sizes.rowMin)
                    .clickable(role = Role.Button, onClickLabel = editLabel) { actions.edit(r) }
                    .semantics(mergeDescendants = true) {}
                    .padding(start = Space.gutter, end = Space.xs, top = Space.s, bottom = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = DsTheme.type.bodyStrong, color = c.textPrimary, maxLines = 1)
                    Text(recordLine(r, p), style = DsTheme.type.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                r.duration?.let { Text(durationText(it.toMinutes()), style = DsTheme.type.numeric, color = c.textPrimary) }
                DsIconButton(Icons.Rounded.Edit, editLabel, { actions.edit(r) }, tint = c.textTertiary)
            }
        }
    }
}

/** "11:08 PM – 6:50 AM", or which half is missing. */
@Composable
fun recordLine(r: SleepRecord, p: TimePrefs): String {
    val b = r.bedtime?.let { Fmt.time(it.atZone(r.bedZone ?: p.zone).toLocalTime(), p) }
    val w = r.wake?.let { Fmt.time(it.atZone(r.wakeZone ?: p.zone).toLocalTime(), p) }
    return when {
        b != null && w != null -> stringResource(R.string.time_range, b, w)
        b != null -> stringResource(R.string.sleep_no_wake, b)
        w != null -> stringResource(R.string.sleep_no_bedtime, w)
        else -> ""
    }
}

/** Corrects (or with [record] null, adds) one record: bedtime and wake-up, each a date and a time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepEditDialog(record: SleepRecord?, actions: SleepActions, onDismiss: () -> Unit) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    val clock = LocalAppClock.current
    val scope = rememberCoroutineScope()
    val now = remember { clock.now() }
    val today = now.atZone(p.zone).toLocalDate()
    var bed by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf((record?.bedtime ?: if (record == null) today.minusDays(1).atTime(23, 0).atZone(p.zone).toInstant() else null)?.toEpochMilli())
    }
    var wake by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf((record?.wake ?: if (record == null) today.atTime(7, 0).atZone(p.zone).toInstant() else null)?.toEpochMilli())
    }
    var error by remember { mutableStateOf<SleepLog.EditError?>(null) }
    var pick by remember { mutableStateOf<Pick?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss, shape = Radii.dialog, containerColor = c.surfaceRaised,
        title = { Text(stringResource(if (record == null) R.string.sleep_add_title else R.string.sleep_edit_title), style = DsTheme.type.titleLarge, color = c.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                TimeField(stringResource(R.string.sleep_bedtime), bed, p, today, onDate = { pick = Pick(true, false) }, onTime = { pick = Pick(true, true) }, onClear = { bed = null })
                TimeField(stringResource(R.string.sleep_wake), wake, p, today, onDate = { pick = Pick(false, false) }, onTime = { pick = Pick(false, true) }, onClear = { wake = null })
                val b = bed; val w = wake
                if (b != null && w != null && w > b) {
                    Text(stringResource(R.string.sleep_duration_line, durationText((w - b) / 60_000)), style = DsTheme.type.bodySmall, color = c.textSecondary)
                }
                error?.let { Text(stringResource(editErrorText(it)), style = DsTheme.type.label, color = c.error) }
                Text(stringResource(R.string.sleep_manual_note), style = DsTheme.type.label, color = c.textTertiary)
            }
        },
        confirmButton = {
            val save: () -> Unit = {
                scope.launch {
                    val e = actions.save(record?.id, bed?.let(Instant::ofEpochMilli), wake?.let(Instant::ofEpochMilli))
                    if (e == null) onDismiss() else error = e
                }
            }
            // Cancel, Delete, Save are short: keep them on one line.
            DsDialogButtons(editActions(canDelete = record != null, onDismiss, onDelete = { confirmDelete = true }, save), stacked = false)
        },
    )
    if (confirmDelete && record != null) {
        app.daysemble.ui.components.DsConfirmDialog(
            stringResource(R.string.sleep_delete_title), null, stringResource(R.string.action_delete),
            onConfirm = { actions.delete(record); onDismiss() }, onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
    pick?.let { pk ->
        val current = (if (pk.bed) bed else wake)?.let { Instant.ofEpochMilli(it).atZone(p.zone).toLocalDateTime() }
            ?: (if (pk.bed) today.minusDays(1).atTime(23, 0) else today.atTime(7, 0))
        val set: (LocalDateTime) -> Unit = { dt ->
            val ms = dt.atZone(p.zone).toInstant().toEpochMilli()
            if (pk.bed) bed = ms else wake = ms
            error = null
        }
        if (pk.time) {
            DsTimePickerDialog(current.toLocalTime(), onPick = { t -> set(current.toLocalDate().atTime(t)) }, onDismiss = { pick = null })
        } else {
            DsDatePickerDialog(current.toLocalDate(), onPick = { d -> set(d.atTime(current.toLocalTime())) }, onDismiss = { pick = null })
        }
    }
}

private data class Pick(val bed: Boolean, val time: Boolean)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeField(label: String, epochMs: Long?, p: TimePrefs, today: LocalDate, onDate: () -> Unit, onTime: () -> Unit, onClear: () -> Unit) {
    val c = DsTheme.colors
    val dt = epochMs?.let { Instant.ofEpochMilli(it).atZone(p.zone) }
    Column {
        Text(label, style = DsTheme.type.label, color = c.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            DsValueChip(dt?.toLocalDate()?.let { dayLabel(it, today, p) } ?: stringResource(R.string.sleep_not_recorded), onDate, emphasized = dt != null)
            DsValueChip(dt?.toLocalTime()?.let { Fmt.time(it, p) } ?: stringResource(R.string.add_time), onTime, emphasized = dt != null)
            if (dt != null) DsIconButton(androidx.compose.material.icons.Icons.Rounded.Close, stringResource(R.string.action_clear), onClear, tint = c.textTertiary)
        }
    }
}

fun editErrorText(e: SleepLog.EditError) = when (e) {
    SleepLog.EditError.WAKE_NOT_AFTER_BEDTIME -> R.string.sleep_error_order
    SleepLog.EditError.TOO_LONG -> R.string.sleep_error_long
    SleepLog.EditError.IN_FUTURE -> R.string.sleep_error_future
    SleepLog.EditError.OVERLAPS -> R.string.sleep_error_overlap
    SleepLog.EditError.EMPTY -> R.string.sleep_error_empty
}

/** "Slept 7 h 42 min", or what is known when a record is incomplete. Used by Today and Settings. */
@Composable
fun sleepSummaryText(lastNight: SleepRecord?, open: SleepRecord?, wakeOnly: SleepRecord?, p: TimePrefs): String = when {
    open != null -> stringResource(R.string.sleep_asleep_since, Fmt.time(open.bedtime!!, p))
    lastNight != null -> stringResource(R.string.sleep_slept, durationText(lastNight.duration!!.toMinutes()))
    wakeOnly != null -> stringResource(R.string.sleep_woke_at, Fmt.time(wakeOnly.wake!!, p))
    else -> stringResource(R.string.sleep_none_recorded)
}
