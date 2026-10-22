package app.daysemble.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.DaysembleApp
import app.daysemble.R
import app.daysemble.data.prefs.AppSettings
import app.daysemble.reminders.ReminderHealth
import app.daysemble.reminders.ReminderScheduler
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsRow
import app.daysemble.ui.components.DsSwitchRow
import app.daysemble.ui.components.DsTimePickerDialog
import app.daysemble.ui.components.DsTopBar
import app.daysemble.ui.components.EventReminderOptions
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.components.reminderLabel
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable
fun NotificationSettingsRoute(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as DaysembleApp).container
    val flow = remember(container) { container.settings.settings }
    val loaded by remember(flow) { flow.map<AppSettings, AppSettings?> { it } }.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val p = LocalTimePrefs.current
    val c = DsTheme.colors
    var resume by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resume++ }
    val s = loaded ?: return
    val notificationsAllowed = remember(resume) { ReminderScheduler.notificationsAllowed(ctx) }
    val health = remember(resume, s.remindersEnabled) { container.reminders.health(s.remindersEnabled) }
    val ignoringBattery = remember(resume) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resume++ }
    var pickTime by remember { mutableStateOf(false) }
    var rescheduled by remember { mutableStateOf(false) }
    val open: (Intent) -> Unit = { runCatching { ctx.startActivity(it) } }

    Column(Modifier.fillMaxSize().background(c.background)) {
        DsTopBar(stringResource(R.string.settings_notifications), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Space.x3)) {
            DsSwitchRow(
                stringResource(R.string.setting_reminders_enabled), s.remindersEnabled,
                { v -> scope.launch { container.settings.setRemindersEnabled(v); container.reminders.rescheduleAll() } },
                subtitle = stringResource(R.string.setting_reminders_enabled_sub),
            )

            SectionHeader(stringResource(R.string.setting_permissions), Modifier.padding(top = Space.l))
            DsRow(
                stringResource(R.string.perm_notifications),
                subtitle = stringResource(if (notificationsAllowed) R.string.perm_allowed else R.string.perm_blocked),
                icon = Icons.Rounded.Notifications, iconTint = if (notificationsAllowed) c.success else c.error,
            ) {
                if (!notificationsAllowed) DsButton(stringResource(R.string.action_fix), {
                    if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }, style = DsButtonStyle.Secondary)
            }
            val exact = health != ReminderHealth.INEXACT
            DsRow(
                stringResource(R.string.perm_exact),
                subtitle = stringResource(if (exact) R.string.perm_exact_on else R.string.perm_exact_off),
                icon = Icons.Rounded.Schedule, iconTint = if (exact) c.success else c.warning,
            ) {
                if (!exact && Build.VERSION.SDK_INT >= 31) DsButton(stringResource(R.string.action_allow), {
                    open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, ("package:" + ctx.packageName).toUri()))
                }, style = DsButtonStyle.Secondary)
            }
            DsRow(
                stringResource(R.string.perm_battery),
                subtitle = stringResource(if (ignoringBattery) R.string.perm_battery_unrestricted else R.string.perm_battery_optimized),
                icon = Icons.Rounded.BatteryAlert, iconTint = c.textSecondary,
            ) {
                if (!ignoringBattery) DsButton(stringResource(R.string.action_open), {
                    open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }, style = DsButtonStyle.Ghost)
            }

            SectionHeader(stringResource(R.string.setting_default_event_reminder), Modifier.padding(top = Space.l))
            Column(Modifier.selectableGroup()) {
                (listOf<Int?>(null) + EventReminderOptions).forEach { m ->
                    app.daysemble.ui.components.DsRadioRow(
                        if (m == null) stringResource(R.string.reminder_none) else reminderLabel(m, forTask = false),
                        s.defaultEventReminderMinutes == m, { scope.launch { container.settings.setDefaultEventReminder(m) } },
                    )
                }
            }
            Text(
                stringResource(R.string.setting_default_event_reminder_note), style = DsTheme.type.bodySmall, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xs),
            )

            SectionHeader(stringResource(R.string.setting_default_task_time), Modifier.padding(top = Space.l))
            val t = LocalTime.of(s.defaultTaskReminderMinuteOfDay / 60, s.defaultTaskReminderMinuteOfDay % 60)
            DsRow(Fmt.time(t, p), subtitle = stringResource(R.string.setting_default_task_time_sub), onClick = { pickTime = true })

            SectionHeader(stringResource(R.string.setting_privacy_lock), Modifier.padding(top = Space.l))
            DsSwitchRow(
                stringResource(R.string.setting_hide_details), s.hideNotificationDetails,
                { v -> scope.launch { container.settings.setHideNotificationDetails(v) } },
                subtitle = stringResource(R.string.setting_hide_details_sub),
            )

            SectionHeader(stringResource(R.string.about_troubleshooting), Modifier.padding(top = Space.l))
            DsRow(
                stringResource(R.string.reschedule_reminders),
                subtitle = stringResource(if (rescheduled) R.string.reschedule_done else R.string.reschedule_sub),
                icon = Icons.Rounded.Refresh,
                onClick = { scope.launch { container.reminders.rescheduleAll(); rescheduled = true } },
            )
            Text(
                stringResource(R.string.reminder_limits_note), style = DsTheme.type.bodySmall, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.m),
            )
        }
    }
    if (pickTime) {
        DsTimePickerDialog(
            LocalTime.of(s.defaultTaskReminderMinuteOfDay / 60, s.defaultTaskReminderMinuteOfDay % 60),
            { v -> scope.launch { container.settings.setDefaultTaskReminderTime(v.hour * 60 + v.minute); container.reminders.rescheduleAll() } },
            { pickTime = false },
        )
    }
}
