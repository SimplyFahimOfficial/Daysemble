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
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

enum class SettingsPage { APPEARANCE, NOTIFICATIONS, ACCOUNTS, CONFLICTS, LISTS_CALENDARS, DATE_TIME, TASKS, DATA, ABOUT, SCHEDULE }

@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, loaded: Boolean = true, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(DsTheme.colors.background)) {
        DsTopBar(title, onBack)
        SettingsBody(loaded) { content() }
    }
}

/**
 * The scrolling body of a Settings page, laid out only once [loaded] (its data from the database
 * is there). Coming back to a page restores its scroll position, but a ScrollState is clamped to
 * the content's height at its first layout, so laying out the still-empty page first threw the
 * saved position away and every return started at the top. The state is created (and restored)
 * either way, and the page is fading in while it loads, so nothing visible waits for it.
 */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.SettingsBody(loaded: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    if (!loaded) return
    Column(
        Modifier
            .weight(1f)
            .verticalScroll(scroll)
            .navigationBarsPadding()
            .padding(bottom = Space.x3),
        content = content,
    )
}

@Composable
private fun rememberSettings(): Pair<AppSettings?, app.daysemble.AppContainer> {
    val container = (LocalContext.current.applicationContext as DaysembleApp).container
    val flow = remember(container) { container.settings.settings.map<AppSettings, AppSettings?> { it } }
    // Starts from the settings the app is already running with, so the page body is there on its first frame.
    val s by flow.collectAsStateWithLifecycle(initialValue = app.daysemble.ui.LocalAppSettings.current)
    return s to container
}

@Composable
fun SettingsHomeRoute(onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val (s, container) = rememberSettings()
    val scope = rememberCoroutineScope()
    var editName by rememberSaveable { mutableStateOf(false) }
    if (editName) {
        DisplayNameDialog(
            initial = s?.displayName.orEmpty(),
            onDismiss = { editName = false },
            onSave = { name -> editName = false; scope.launch { container.settings.setDisplayName(name) } },
        )
    }
    SettingsScaffold(stringResource(R.string.settings), onBack) {
        val chevron: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = DsTheme.colors.textTertiary)
        }
        SectionHeader(stringResource(R.string.settings_section_sync))
        DsRow(stringResource(R.string.settings_accounts), subtitle = stringResource(R.string.settings_accounts_sub), icon = Icons.Rounded.Sync, onClick = { onOpen(SettingsPage.ACCOUNTS) }, trailing = chevron)
        SectionHeader(stringResource(R.string.settings_section_general), Modifier.padding(top = Space.l))
        DsRow(
            stringResource(R.string.settings_your_name),
            subtitle = s?.displayName ?: stringResource(R.string.settings_your_name_unset),
            icon = Icons.Rounded.Person, onClick = { editName = true }, trailing = chevron,
        )
        DsRow(
            stringResource(R.string.settings_schedule),
            subtitle = s?.schedule?.default?.let { d -> stringResource(R.string.time_range, Fmt.time(d.wake, LocalTimePrefs.current), Fmt.time(d.bed, LocalTimePrefs.current)) }
                ?: stringResource(R.string.settings_schedule_sub),
            icon = androidx.compose.material.icons.Icons.Rounded.Bedtime, onClick = { onOpen(SettingsPage.SCHEDULE) }, trailing = chevron,
        )
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

/** Edits the display name shown beside the logo on Today. Saving an empty name clears it. */
@Composable
private fun DisplayNameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = DsTheme.colors
    var name by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, shape = app.daysemble.ui.theme.Radii.dialog, containerColor = c.surfaceRaised,
        title = { Text(stringResource(R.string.settings_your_name), style = DsTheme.type.titleLarge, color = c.textPrimary) },
        text = {
            app.daysemble.ui.components.DsTextField(
                name, { name = it.take(app.daysemble.data.prefs.DISPLAY_NAME_MAX) },
                placeholder = stringResource(R.string.onboarding_name_hint),
                imeAction = androidx.compose.ui.text.input.ImeAction.Done, onImeAction = { onSave(name) },
                focusRequester = focus, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
            )
        },
        confirmButton = { app.daysemble.ui.components.DsButton(stringResource(R.string.action_save), { onSave(name) }, style = app.daysemble.ui.components.DsButtonStyle.Ghost) },
        dismissButton = { app.daysemble.ui.components.DsButton(stringResource(R.string.action_cancel), onDismiss, style = app.daysemble.ui.components.DsButtonStyle.Ghost) },
    )
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
    val loadedLists by container.tasks.taskLists.collectAsStateWithLifecycle(null)
    val lists = loadedLists.orEmpty()
    SettingsScaffold(stringResource(R.string.settings_tasks), onBack, loaded = loadedLists != null) {
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

