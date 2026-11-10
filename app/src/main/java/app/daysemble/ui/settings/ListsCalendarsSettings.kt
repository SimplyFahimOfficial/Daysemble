package app.daysemble.ui.settings

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daysemble.data.device.DeviceCalendarEvent
import app.daysemble.data.device.DeviceCalendarReader
import app.daysemble.ui.components.CardDivider
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import java.time.ZoneId
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.DaysembleApp
import app.daysemble.R
import app.daysemble.ui.components.DsDialogAction
import app.daysemble.ui.components.DsDialogButtons
import app.daysemble.data.db.SourceKind
import app.daysemble.data.repo.toArgbInt
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsConfirmDialog
import app.daysemble.ui.components.DsRow
import app.daysemble.ui.components.SettingsCard
import app.daysemble.ui.components.DsSwitchRow
import app.daysemble.ui.components.DsTextField
import app.daysemble.ui.components.DsTopBar
import app.daysemble.ui.components.EventMarker
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.components.eventColor
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.EventSwatch
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.launch

private sealed interface Editing {
    data class NewList(val dummy: Int = 0) : Editing
    data class RenameList(val id: String, val title: String) : Editing
    data class NewCalendar(val dummy: Int = 0) : Editing
    data class EditCalendar(val id: String, val name: String, val color: Int, val isLocal: Boolean) : Editing
}

@Composable
fun ListsCalendarsRoute(onBack: () -> Unit) {
    val c = (LocalContext.current.applicationContext as DaysembleApp).container
    val listsFlow = remember(c) { c.tasks.allLists }
    val loadedLists by listsFlow.collectAsStateWithLifecycle(initialValue = null)
    val lists = loadedLists.orEmpty()
    val calsFlow = remember(c) { c.events.allCalendars }
    val loadedCals by calsFlow.collectAsStateWithLifecycle(initialValue = null)
    val cals = loadedCals.orEmpty()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Editing?>(null) }
    var confirmDelete by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // id, isList
    val colors = DsTheme.colors

    Column(Modifier.fillMaxSize().background(colors.background)) {
        DsTopBar(stringResource(R.string.settings_lists_calendars), onBack)
        SettingsBody(loaded = loadedLists != null && loadedCals != null) {
            SectionHeader(stringResource(R.string.calendars_title))
            cals.forEach { cal ->
                DsSwitchRow(
                    cal.name, cal.isVisible, { v -> scope.launch { c.events.setCalendarVisible(cal.id, v) } },
                    subtitle = stringResource(if (cal.kind == SourceKind.GOOGLE) R.string.calendar_google_sub else R.string.on_this_device),
                )
                Box(Modifier.padding(start = Space.gutter, bottom = Space.xs)) {
                    Row2(cal.colorArgb, onEdit = {
                        editing = Editing.EditCalendar(cal.id, cal.name, cal.colorArgb, cal.kind == SourceKind.LOCAL)
                    })
                }
            }
            Hint(stringResource(R.string.calendar_hidden_note))
            DsButton(stringResource(R.string.new_calendar), { editing = Editing.NewCalendar() }, style = DsButtonStyle.Secondary, icon = Icons.Rounded.Add, modifier = Modifier.padding(horizontal = Space.gutter))

            DeviceCalendarSection()

            SectionHeader(stringResource(R.string.task_lists_title), Modifier.padding(top = Space.xl))
            SettingsCard {
                lists.forEach { l ->
                    DsRow(
                        l.title,
                        subtitle = stringResource(if (l.kind == SourceKind.GOOGLE) R.string.list_google_sub else R.string.on_this_device),
                        onClick = if (l.kind == SourceKind.LOCAL) ({ editing = Editing.RenameList(l.id, l.title) }) else null,
                    )
                }
            }
            Hint(stringResource(R.string.list_google_note))
            DsButton(stringResource(R.string.new_list), { editing = Editing.NewList() }, style = DsButtonStyle.Secondary, icon = Icons.Rounded.Add, modifier = Modifier.padding(horizontal = Space.gutter))
        }
    }

    when (val e = editing) {
        is Editing.NewList -> NameDialog(stringResource(R.string.new_list), "", null, onDismiss = { editing = null }) { name, _ ->
            scope.launch { c.tasks.createList(name) }
        }
        is Editing.RenameList -> NameDialog(stringResource(R.string.edit_list), e.title, null, onDismiss = { editing = null },
            onDelete = { confirmDelete = e.id to true }) { name, _ -> scope.launch { c.tasks.renameList(e.id, name) } }
        is Editing.NewCalendar -> NameDialog(stringResource(R.string.new_calendar), "", EventSwatch.VIOLET.light.toArgbInt(), onDismiss = { editing = null }) { name, color ->
            scope.launch { c.events.createCalendar(name, color!!) }
        }
        is Editing.EditCalendar -> NameDialog(
            stringResource(R.string.edit_calendar), e.name, e.color, onDismiss = { editing = null }, nameEditable = e.isLocal,
            onDelete = if (e.isLocal) ({ confirmDelete = e.id to false }) else null,
        ) { name, color ->
            scope.launch {
                if (e.isLocal && name != e.name) c.events.renameCalendar(e.id, name)
                if (color != null && color != e.color) c.events.setCalendarColor(e.id, color)
            }
        }
        null -> Unit
    }
    confirmDelete?.let { (id, isList) ->
        var refused by remember(id) { mutableStateOf(false) }
        if (refused) {
            AlertDialog(
                onDismissRequest = { confirmDelete = null }, containerColor = colors.surfaceRaised,
                text = { Text(stringResource(if (isList) R.string.list_last_refused else R.string.calendar_last_refused), style = DsTheme.type.body) },
                confirmButton = { DsDialogButtons(listOf(DsDialogAction(stringResource(R.string.action_done), { confirmDelete = null }))) },
            )
        } else {
            DsConfirmDialog(
                stringResource(if (isList) R.string.delete_list_title else R.string.delete_calendar_title),
                stringResource(if (isList) R.string.delete_list_body else R.string.delete_calendar_body),
                stringResource(R.string.action_delete),
                onConfirm = {
                    scope.launch {
                        val ok = if (isList) c.tasks.deleteList(id) else c.events.deleteCalendar(id)
                        if (ok) { confirmDelete = null; editing = null } else refused = true
                    }
                },
                onDismiss = { if (!refused) confirmDelete = null },
                destructive = true,
            )
        }
    }
}


/** Explicit, read-only preview: no events are copied into Room or sent through Google sync. */
@Composable
private fun DeviceCalendarSection() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var allowed by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
    }
    var refresh by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<List<DeviceCalendarEvent>?>(null) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
        refresh++
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                refresh++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(allowed, refresh) {
        preview = null
        if (allowed) preview = DeviceCalendarReader.upcoming(context)
    }

    SectionHeader(stringResource(R.string.device_calendar_title), Modifier.padding(top = Space.xl))
    SettingsCard {
        DsRow(
            stringResource(if (allowed) R.string.device_calendar_allowed else R.string.device_calendar_permission),
            subtitle = stringResource(R.string.device_calendar_explanation),
            onClick = if (allowed) null else ({ permissionRequest.launch(Manifest.permission.READ_CALENDAR) }),
        )
    }
    if (allowed) {
        Hint(stringResource(R.string.device_calendar_note))
        SectionHeader(stringResource(R.string.device_calendar_preview), Modifier.padding(top = Space.l))
        when (val items = preview) {
            null -> Hint(stringResource(R.string.loading))
            else -> if (items.isEmpty()) {
                Hint(stringResource(R.string.device_calendar_none))
            } else {
                val zone = ZoneId.systemDefault()
                val prefs = LocalTimePrefs.current
                SettingsCard {
                    items.forEachIndexed { index, event ->
                        if (index > 0) CardDivider()
                        val date = Fmt.mediumDate(event.localDate(zone), prefs)
                        val time = if (event.allDay) stringResource(R.string.device_calendar_all_day)
                            else Fmt.time(event.startsAt.atZone(zone).toLocalTime(), prefs)
                        DsRow(event.title, subtitle = "$date · $time", onClick = {
                            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            if (runCatching { context.startActivity(intent) }.isFailure) {
                                Toast.makeText(context, R.string.device_calendar_no_viewer, Toast.LENGTH_SHORT).show()
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun Row2(color: Int, onEdit: () -> Unit) {
    val label = stringResource(R.string.edit_calendar)
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        EventMarker(eventColor(color), false)
        DsButton(label, onEdit, style = DsButtonStyle.Ghost)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    initialColor: Int?,
    onDismiss: () -> Unit,
    nameEditable: Boolean = true,
    onDelete: (() -> Unit)? = null,
    onSave: (String, Int?) -> Unit,
) {
    val c = DsTheme.colors
    var name by rememberSaveable { mutableStateOf(initial) }
    var color by rememberSaveable { mutableStateOf(initialColor) }
    AlertDialog(
        onDismissRequest = onDismiss, shape = Radii.dialog, containerColor = c.surfaceRaised,
        title = { Text(title, style = DsTheme.type.titleLarge, color = c.textPrimary) },
        text = {
            Column {
                if (nameEditable) DsTextField(name, { name = it }, placeholder = stringResource(R.string.name_hint))
                else Text(initial, style = DsTheme.type.bodyStrong, color = c.textPrimary)
                if (color != null) {
                    FlowRow(Modifier.padding(top = Space.l).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        EventSwatch.entries.forEach { s ->
                            val argb = s.light.toArgbInt()
                            val selected = EventSwatch.nearest(color!!) == s
                            val label = stringResource(s.label)
                            Box(
                                Modifier.size(Sizes.touch).clip(CircleShape).background(s.color(c.isDark))
                                    .then(if (selected) Modifier.border(3.dp, c.textPrimary, CircleShape) else Modifier)
                                    .selectable(selected, role = Role.RadioButton) { color = argb }
                                    .semantics { contentDescription = label },
                                contentAlignment = Alignment.Center,
                            ) { if (selected) Icon(Icons.Rounded.Check, null, tint = c.background, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
                if (onDelete != null) {
                    DsButton(
                        stringResource(R.string.action_delete), onDelete, Modifier.padding(top = Space.m),
                        style = DsButtonStyle.Destructive,
                    )
                }
            }
        },
        confirmButton = {
            DsDialogButtons(
                listOf(
                    DsDialogAction(stringResource(R.string.action_cancel), onDismiss, DsDialogAction.Kind.Cancel),
                    DsDialogAction(stringResource(R.string.action_save), { onSave(name, color); onDismiss() }, enabled = !nameEditable || name.isNotBlank()),
                ),
            )
        },
    )
}

@Composable
private fun Hint(text: String) = Text(
    text, style = DsTheme.type.bodySmall, color = DsTheme.colors.textSecondary,
    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
)
