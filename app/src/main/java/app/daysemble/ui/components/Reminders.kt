package app.daysemble.ui.components

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.DaysembleApp
import app.daysemble.R
import app.daysemble.data.prefs.AppSettings
import app.daysemble.reminders.ReminderHealth
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Space

val EventReminderOptions = listOf(0, 5, 10, 15, 30, 60, 120, 1440)
val TaskReminderOptions = listOf(0, 15, 30, 60, 1440)

/** Current reminder reliability; re-checked whenever the screen resumes (permissions can change in Settings). */
@Composable
fun rememberReminderHealth(): ReminderHealth {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as DaysembleApp).container
    val flow = remember(container) { container.settings.settings }
    val settings by flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return remember(settings.remindersEnabled, tick) { container.reminders.health(settings.remindersEnabled) }
}

@Composable
fun reminderLabel(minutes: Int, forTask: Boolean, allDay: Boolean = false): String = when {
    minutes == 0 -> stringResource(if (forTask || allDay) R.string.reminder_on_time_task else R.string.reminder_at_start)
    minutes % 1440 == 0 -> pluralStringResource(R.plurals.reminder_days_before, minutes / 1440, minutes / 1440)
    minutes % 60 == 0 -> pluralStringResource(R.plurals.reminder_hours_before, minutes / 60, minutes / 60)
    else -> pluralStringResource(R.plurals.reminder_minutes_before, minutes, minutes)
}

/**
 * Multi-select reminder chips plus an honest status line. Selecting the first reminder asks for
 * the notification permission in context (Android 13+). When notifications are blocked or
 * reminders are off, the UI says so rather than implying reminders will arrive.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderPicker(
    selected: List<Int>,
    options: List<Int>,
    onChange: (List<Int>) -> Unit,
    forTask: Boolean,
    modifier: Modifier = Modifier,
    allDay: Boolean = false,
    enabledReason: String? = null,
) {
    val ctx = LocalContext.current
    val health = rememberReminderHealth()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val all = (options + selected).distinct().sorted()
    Column(modifier) {
        if (enabledReason != null) {
            Text(enabledReason, style = DsTheme.type.bodySmall, color = DsTheme.colors.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter))
            return@Column
        }
        FlowRow(
            Modifier.padding(horizontal = Space.gutter - Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            all.forEach { m ->
                val on = m in selected
                DsChip(reminderLabel(m, forTask, allDay), on, {
                    val next = if (on) selected - m else (selected + m).distinct().take(5)
                    if (!on && selected.isEmpty() && health == ReminderHealth.NOTIFICATIONS_BLOCKED && Build.VERSION.SDK_INT >= 33) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    onChange(next.sorted())
                }, role = androidx.compose.ui.semantics.Role.Checkbox)
            }
        }
        if (selected.isNotEmpty()) ReminderStatus(health, Modifier.padding(horizontal = Space.gutter, vertical = Space.s)) { intent ->
            runCatching { ctx.startActivity(intent) }
        }
    }
}

@Composable
fun ReminderStatus(health: ReminderHealth, modifier: Modifier = Modifier, onFix: (Intent) -> Unit) {
    val ctx = LocalContext.current
    val c = DsTheme.colors
    val (text, fix) = when (health) {
        ReminderHealth.OK -> return
        ReminderHealth.INEXACT -> stringResource(R.string.reminder_status_inexact) to
            (if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + ctx.packageName)) else null)
        ReminderHealth.NOTIFICATIONS_BLOCKED -> stringResource(R.string.reminder_status_blocked) to
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        ReminderHealth.DISABLED_IN_APP -> stringResource(R.string.reminder_status_disabled) to null
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(Radii.control)
            .background(if (health == ReminderHealth.INEXACT) c.surfaceVariant else c.errorContainer)
            .padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Icon(
            if (health == ReminderHealth.INEXACT) Icons.Rounded.Schedule else Icons.Rounded.NotificationsOff, null,
            tint = if (health == ReminderHealth.INEXACT) c.textSecondary else c.error, modifier = Modifier.size(18.dp),
        )
        Text(text, style = DsTheme.type.bodySmall, color = c.textPrimary, modifier = Modifier.weight(1f))
        if (fix != null) DsButton(stringResource(R.string.action_fix), { onFix(fix) }, style = DsButtonStyle.Secondary)
    }
}
