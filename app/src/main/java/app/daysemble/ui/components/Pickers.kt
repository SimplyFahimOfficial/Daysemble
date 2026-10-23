package app.daysemble.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPick(java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    onDismiss()
                },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(R.string.action_done), color = c.textPrimary) }
        },
        dismissButton = {
            Row {
                if (onClear != null) {
                    TextButton(onClick = { onClear(); onDismiss() }) { Text(stringResource(R.string.action_clear), color = c.textSecondary) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = c.textSecondary) }
            }
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
            TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }) {
                Text(stringResource(R.string.action_done), color = c.textPrimary)
            }
        },
        dismissButton = {
            Row {
                if (onClear != null) {
                    TextButton(onClick = { onClear(); onDismiss() }) { Text(stringResource(R.string.action_clear), color = c.textSecondary) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = c.textSecondary) }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().padding(top = Space.s), verticalArrangement = Arrangement.Center) {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = c.surfaceVariant,
                        selectorColor = c.inverse,
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
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (destructive) c.error else c.textPrimary, style = DsTheme.type.label)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = c.textSecondary, style = DsTheme.type.label) }
        },
    )
}
