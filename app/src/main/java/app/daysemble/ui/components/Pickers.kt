package app.daysemble.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.daysemble.R
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Space
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Material date picker; dates are exchanged as UTC-midnight millis, never shifted by zone. */
@Composable
fun DsDatePickerDialog(
    initial: LocalDate?,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val c = DsTheme.colors
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        shape = Radii.dialog,
        confirmButton = {
            DsDialogButtons(
                pickerActions(
                    onClear = onClear?.let { { it(); onDismiss() } },
                    onDismiss = onDismiss,
                    done = DsDialogAction(
                        stringResource(R.string.action_done),
                        onClick = {
                            state.selectedDateMillis?.let { onPick(java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                            onDismiss()
                        },
                        enabled = state.selectedDateMillis != null,
                    ),
                ),
                Modifier.padding(start = Space.l),
                stacked = false,
            )
        },
        colors = DatePickerDefaults.colors(containerColor = c.surfaceRaised),
    ) {
        DatePicker(
            state = state,
            colors = DatePickerDefaults.colors(
                containerColor = c.surfaceRaised,
                selectedDayContainerColor = c.inverse,
                selectedDayContentColor = c.onInverse,
                todayDateBorderColor = c.accent,
                todayContentColor = c.accent,
                selectedYearContainerColor = c.inverse,
                selectedYearContentColor = c.onInverse,
            ),
        )
    }
}

@Composable
fun DsTimePickerDialog(
    initial: LocalTime,
    onPick: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val c = DsTheme.colors
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = LocalTimePrefs.current.use24h)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Radii.dialog,
        containerColor = c.surfaceRaised,
        confirmButton = {
            DsDialogButtons(
                pickerActions(
                    onClear = onClear?.let { { it(); onDismiss() } },
                    onDismiss = onDismiss,
                    done = DsDialogAction(stringResource(R.string.action_done), { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }),
                ),
                stacked = false,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth().padding(top = Space.s), verticalArrangement = Arrangement.Center) {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = c.surfaceVariant,
                        selectorColor = c.inverse,
                        // The number under the selector; Material's default (onPrimary) assumes a primary selector.
                        clockDialSelectedContentColor = c.onInverse,
                        timeSelectorSelectedContainerColor = c.inverse,
                        timeSelectorSelectedContentColor = c.onInverse,
                        timeSelectorUnselectedContainerColor = c.surfaceVariant,
                        periodSelectorSelectedContainerColor = c.inverse,
                        periodSelectorSelectedContentColor = c.onInverse,
                    ),
                )
            }
        },
    )
}

@Composable
fun DsConfirmDialog(
    title: String,
    body: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val c = DsTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Radii.dialog,
        containerColor = c.surfaceRaised,
        title = { Text(title, style = DsTheme.type.titleLarge, color = c.textPrimary) },
        text = body?.let { { Text(it, style = DsTheme.type.body, color = c.textSecondary) } },
        confirmButton = {
            DsDialogButtons(
                listOf(
                    DsDialogAction(stringResource(R.string.action_cancel), onDismiss, DsDialogAction.Kind.Cancel),
                    DsDialogAction(
                        confirm, { onConfirm(); onDismiss() },
                        if (destructive) DsDialogAction.Kind.Destructive else DsDialogAction.Kind.Confirm,
                    ),
                ),
            )
        },
    )
}

/** Cancel, then Clear when offered, then Done. Clear is an alternative, so it's outlined, not plain. */
@Composable
private fun pickerActions(onClear: (() -> Unit)?, onDismiss: () -> Unit, done: DsDialogAction): List<DsDialogAction> = buildList {
    add(DsDialogAction(stringResource(R.string.action_cancel), onDismiss, DsDialogAction.Kind.Cancel))
    if (onClear != null) add(DsDialogAction(stringResource(R.string.action_clear), onClear, DsDialogAction.Kind.Alternative))
    add(done)
}
