package app.daysemble.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.BuildConfig
import app.daysemble.DaysembleApp
import app.daysemble.R
import app.daysemble.data.prefs.AppSettings
import app.daysemble.data.prefs.ClockFormat
import app.daysemble.data.prefs.TaskSort
import androidx.compose.ui.unit.dp
import app.daysemble.ui.brand.BrandLogo
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsConfirmDialog
import app.daysemble.ui.components.DsRow
import app.daysemble.ui.components.DsRadioRow
import androidx.compose.foundation.selection.selectableGroup
import app.daysemble.ui.components.DsSegmented
import app.daysemble.ui.components.DsSwitchRow
import app.daysemble.ui.components.DsTopBar
import app.daysemble.ui.components.HairlineDivider
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.durationText
import app.daysemble.ui.tasks.sortLabel
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.DayOfWeek

enum class SettingsPage { APPEARANCE, NOTIFICATIONS, ACCOUNTS, CONFLICTS, LISTS_CALENDARS, DATE_TIME, TASKS, DATA, ABOUT }

@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(DsTheme.colors.background)) {
        DsTopBar(title, onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = Space.x3),
        ) { content() }
    }
}

@Composable
private fun rememberSettings(): Pair<AppSettings?, app.daysemble.AppContainer> {
    val container = (LocalContext.current.applicationContext as DaysembleApp).container
    val flow = remember(container) { container.settings.settings.map<AppSettings, AppSettings?> { it } }
    val s by flow.collectAsStateWithLifecycle(initialValue = null)
    return s to container
}

@Composable
fun SettingsHomeRoute(onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    SettingsScaffold(stringResource(R.string.settings), onBack) {
        val chevron: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = DsTheme.colors.textTertiary)
        }
        SectionHeader(stringResource(R.string.settings_section_sync))
        DsRow(stringResource(R.string.settings_accounts), subtitle = stringResource(R.string.settings_accounts_sub), icon = Icons.Rounded.Sync, onClick = { onOpen(SettingsPage.ACCOUNTS) }, trailing = chevron)
        SectionHeader(stringResource(R.string.settings_section_general), Modifier.padding(top = Space.l))
        DsRow(stringResource(R.string.settings_appearance), subtitle = stringResource(R.string.settings_appearance_sub), icon = Icons.Rounded.Palette, onClick = { onOpen(SettingsPage.APPEARANCE) }, trailing = chevron)
        DsRow(stringResource(R.string.settings_notifications), subtitle = stringResource(R.string.settings_notifications_sub), icon = Icons.Rounded.Notifications, onClick = { onOpen(SettingsPage.NOTIFICATIONS) }, trailing = chevron)
        DsRow(stringResource(R.string.settings_date_time), subtitle = stringResource(R.string.settings_date_time_sub), icon = Icons.Rounded.CalendarMonth, onClick = { onOpen(SettingsPage.DATE_TIME) }, trailing = chevron)
        DsRow(stringResource(R.string.settings_lists_calendars), subtitle = stringResource(R.string.settings_lists_calendars_sub), icon = Icons.Rounded.CalendarMonth, onClick = { onOpen(SettingsPage.LISTS_CALENDARS) }, trailing = chevron)
        DsRow(stringResource(R.string.settings_tasks), subtitle = stringResource(R.string.settings_tasks_sub), icon = Icons.Rounded.TaskAlt, onClick = { onOpen(SettingsPage.TASKS) }, trailing = chevron)
        SectionHeader(stringResource(R.string.settings_section_privacy), Modifier.padding(top = Space.l))
        DsRow(stringResource(R.string.settings_data), subtitle = stringResource(R.string.settings_data_sub), icon = Icons.Rounded.Shield, onClick = { onOpen(SettingsPage.DATA) }, trailing = chevron)
        DsRow(stringResource(R.string.settings_about), subtitle = stringResource(R.string.version_label, BuildConfig.VERSION_NAME), icon = Icons.Rounded.Info, onClick = { onOpen(SettingsPage.ABOUT) }, trailing = chevron)
    }
}

@Composable
fun DateTimeSettingsRoute(onBack: () -> Unit) {
    val (s, container) = rememberSettings()
    val scope = rememberCoroutineScope()
    val p = LocalTimePrefs.current
    SettingsScaffold(stringResource(R.string.settings_date_time), onBack) {
        val settings = s ?: return@SettingsScaffold
        SectionHeader(stringResource(R.string.setting_clock))
        DsSegmented(
            ClockFormat.entries, settings.clockFormat,
            label = { stringResource(when (it) { ClockFormat.SYSTEM -> R.string.theme_system; ClockFormat.H12 -> R.string.clock_12; ClockFormat.H24 -> R.string.clock_24 }) },
            onSelect = { scope.launch { container.settings.setClockFormat(it) } },
            modifier = Modifier.padding(horizontal = Space.gutter),
        )
        SectionHeader(stringResource(R.string.setting_first_day), Modifier.padding(top = Space.xl))
        val options = listOf<DayOfWeek?>(null, DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY)
        Column(Modifier.selectableGroup()) {
            options.forEach { d ->
                DsRadioRow(
                    if (d == null) stringResource(R.string.first_day_locale) else Fmt.weekdayFull(d, p),
                    settings.firstDayOfWeek == d, { scope.launch { container.settings.setFirstDayOfWeek(d) } },
                )
            }
        }
        SectionHeader(stringResource(R.string.setting_event_duration), Modifier.padding(top = Space.xl))
        Column(Modifier.selectableGroup()) {
            listOf(15, 30, 45, 60, 90, 120).forEach { m ->
                DsRadioRow(durationText(m.toLong()), settings.defaultEventDurationMinutes == m, { scope.launch { container.settings.setDefaultEventDuration(m) } })
            }
        }
        Text(
            stringResource(R.string.setting_zone_note, p.zone.id), style = DsTheme.type.bodySmall, color = DsTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l),
        )
    }
}

@Composable
fun TaskSettingsRoute(onBack: () -> Unit) {
    val (s, container) = rememberSettings()
    val scope = rememberCoroutineScope()
    val lists by container.tasks.taskLists.collectAsStateWithLifecycle(emptyList())
    SettingsScaffold(stringResource(R.string.settings_tasks), onBack) {
        val settings = s ?: return@SettingsScaffold
        DsSwitchRow(
            stringResource(R.string.setting_show_completed_today), settings.showCompletedOnToday,
            { scope.launch { container.settings.setShowCompletedOnToday(it) } },
            subtitle = stringResource(R.string.setting_show_completed_today_sub),
        )
        SectionHeader(stringResource(R.string.setting_default_sort), Modifier.padding(top = Space.l))
        Column(Modifier.selectableGroup()) {
            TaskSort.entries.forEach { srt ->
                DsRadioRow(stringResource(sortLabel(srt)), settings.taskSort == srt, { scope.launch { container.settings.setTaskSort(srt) } })
            }
        }
        if (lists.size > 1) {
            SectionHeader(stringResource(R.string.setting_default_list), Modifier.padding(top = Space.l))
            Column(Modifier.selectableGroup()) {
                lists.forEach { l ->
                    val selected = settings.defaultTaskListId == l.id || (settings.defaultTaskListId == null && !l.isGoogle && l == lists.firstOrNull { !it.isGoogle })
                    DsRadioRow(l.title, selected, { scope.launch { container.settings.setDefaultTaskList(l.id) } }, subtitle = l.accountEmail)
                }
            }
        }
    }
}

@Composable
fun DataSettingsRoute(onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as DaysembleApp).container
    val scope = rememberCoroutineScope()
    var confirm by rememberSaveable { mutableStateOf(false) }
    var cleared by rememberSaveable { mutableStateOf(false) }
    val tasks by container.tasks.allTasks.collectAsStateWithLifecycle(emptyList())
    SettingsScaffold(stringResource(R.string.settings_data), onBack) {
        SectionHeader(stringResource(R.string.data_what_stored))
        Text(
            stringResource(R.string.data_what_stored_body), style = DsTheme.type.body, color = DsTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = Space.gutter),
        )
        Text(
            pluralStringResource(R.plurals.data_task_count, tasks.size, tasks.size), style = DsTheme.type.bodySmall, color = DsTheme.colors.textTertiary,
            modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
        )
        SectionHeader(stringResource(R.string.data_delete_title), Modifier.padding(top = Space.xl))
        Text(
            stringResource(R.string.data_delete_body), style = DsTheme.type.bodySmall, color = DsTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = Space.gutter),
        )
        Spacer(Modifier.height(Space.m))
        DsButton(stringResource(R.string.data_delete_action), { confirm = true }, style = DsButtonStyle.Destructive, modifier = Modifier.padding(horizontal = Space.gutter))
        if (cleared) Text(stringResource(R.string.data_deleted), style = DsTheme.type.label, color = DsTheme.colors.success, modifier = Modifier.padding(Space.gutter))
    }
    if (confirm) {
        DsConfirmDialog(
            stringResource(R.string.data_delete_confirm_title), stringResource(R.string.data_delete_confirm_body),
            stringResource(R.string.action_delete), onConfirm = {
                scope.launch { container.clearLocalData(); cleared = true }
            }, onDismiss = { confirm = false }, destructive = true,
        )
    }
}

@Composable
fun AboutRoute(onBack: () -> Unit) {
    SettingsScaffold(stringResource(R.string.settings_about), onBack) {
        Column(Modifier.padding(Space.gutter)) {
            app.daysemble.ui.brand.BrandWordmark(Modifier.semantics { heading() }, height = 48.dp)
            Text(stringResource(R.string.version_label, BuildConfig.VERSION_NAME), style = DsTheme.type.bodySmall, color = DsTheme.colors.textSecondary)
            Spacer(Modifier.height(Space.l))
            Text(stringResource(R.string.about_body), style = DsTheme.type.body, color = DsTheme.colors.textSecondary)
        }
        HairlineDivider()
        SectionHeader(stringResource(R.string.about_troubleshooting), Modifier.padding(top = Space.l))
        DsRow(stringResource(R.string.about_zone), subtitle = LocalTimePrefs.current.zone.id)
        DsRow(stringResource(R.string.about_build), subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}")
        if (BuildConfig.DEBUG) {
            val container = (LocalContext.current.applicationContext as DaysembleApp).container
            val scope = rememberCoroutineScope()
            var loaded by rememberSaveable { mutableStateOf(false) }
            DsRow(
                stringResource(R.string.debug_sample_data),
                subtitle = stringResource(if (loaded) R.string.debug_sample_data_done else R.string.debug_sample_data_sub),
                onClick = { if (!loaded) scope.launch { app.daysemble.data.SampleData.load(container); loaded = true } },
            )
        }
    }
}

