package app.daysemble.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.BuildConfig
import app.daysemble.DaysembleApp
import app.daysemble.R
import app.daysemble.data.db.SourceKind
import app.daysemble.data.db.SyncService
import app.daysemble.data.google.auth.AuthResult
import app.daysemble.data.google.sync.SyncStatus
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsConfirmDialog
import app.daysemble.ui.components.DsRow
import app.daysemble.ui.components.DsSwitchRow
import app.daysemble.ui.components.DsTopBar
import app.daysemble.ui.components.EventMarker
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.components.SyncBadge
import app.daysemble.ui.components.SyncIndicator
import app.daysemble.ui.components.eventColor
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.TimePrefs
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.Instant

private sealed interface ConnectUi {
    data object Idle : ConnectUi
    data object Working : ConnectUi
    data object SetupRequired : ConnectUi
    data object NoPlayServices : ConnectUi
    data class Error(val reason: String) : ConnectUi
    /** Connected, but the user didn't allow these services on Google's consent screen. */
    data class Partial(val missing: Set<SyncService>) : ConnectUi
}

/**
 * Applies a successful consent: connects [email] with the services Google granted (only those in
 * [requested] follow the answer) and starts syncing. Shows which requested services weren't allowed.
 */
private suspend fun onAuthorized(c: app.daysemble.AppContainer, r: AuthResult.Authorized, email: String?, requested: Set<SyncService>): ConnectUi {
    if (email == null) return ConnectUi.Error("no_account")
    c.sync.connect(email, r.services, requested)
    c.syncScheduler.enablePeriodic()
    c.syncScheduler.requestSoon(0)
    val missing = requested - r.services
    return if (missing.isEmpty()) ConnectUi.Idle else ConnectUi.Partial(missing)
}

@Composable
fun AccountSettingsRoute(onBack: () -> Unit, onOpenConflicts: () -> Unit) {
    val ctx = LocalContext.current
    val c = (ctx.applicationContext as DaysembleApp).container
    val statusFlow = remember(c) { c.sync.status }
    // Starts from the root's last status (if any) so the page body doesn't pop in after the transition.
    val status by statusFlow.collectAsStateWithLifecycle(initialValue = app.daysemble.ui.LocalSyncStatus.current?.value)
    val calsFlow = remember(c) { c.database.calendars().observeAll() }
    val loadedCals by calsFlow.collectAsStateWithLifecycle(initialValue = null)
    val calendars = loadedCals.orEmpty()
    val listsFlow = remember(c) { c.database.taskLists().observeAll() }
    val loadedLists by listsFlow.collectAsStateWithLifecycle(initialValue = null)
    val lists = loadedLists.orEmpty()
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<ConnectUi>(ConnectUi.Idle) }
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    // The services whose on/off follows the current consent; Google may grant fewer than asked for.
    // Saved: consent runs in another activity, and this one may be recreated before it returns.
    var requestedNames by rememberSaveable { mutableStateOf(SyncService.entries.joinToString(",") { it.name }) }
    fun requested(): Set<SyncService> = requestedNames.split(',').let { names -> SyncService.entries.filter { it.name in names }.toSet() }

    suspend fun handle(r: AuthResult, launch: (IntentSenderRequest) -> Unit) {
        ui = when (r) {
            is AuthResult.Authorized -> onAuthorized(c, r, r.email ?: status?.account?.email, requested())
            is AuthResult.NeedsConsent -> { launch(IntentSenderRequest.Builder(r.intent.intentSender).build()); ConnectUi.Working }
            AuthResult.SetupRequired -> ConnectUi.SetupRequired
            AuthResult.PlayServicesUnavailable -> ConnectUi.NoPlayServices
            is AuthResult.Failed -> if (r.reason == "canceled") ConnectUi.Idle else ConnectUi.Error(r.reason)
        }
    }

    lateinit var launcherRef: (IntentSenderRequest) -> Unit
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch { handle(c.googleAuth.fromIntent(res.data), launcherRef) }
    }
    launcherRef = { launcher.launch(it) }
    /** Asks Google for [services]; the result turns on or off only those in [apply]. */
    fun authorize(services: Set<SyncService>, apply: Set<SyncService> = services) {
        requestedNames = apply.joinToString(",") { it.name }
        ui = ConnectUi.Working
        scope.launch { handle(c.googleAuth.authorize(status?.account?.email, services), launcherRef) }
    }
    // Connect asks for everything. Reconnecting asks again for what is turned on; with both services
    // off it still needs some scope to ask for, but the answer mustn't turn either back on.
    val connect: () -> Unit = {
        val account = status?.account?.takeIf { it.isConnected }
        if (account == null) authorize(SyncService.entries.toSet())
        else authorize(account.services.ifEmpty { SyncService.entries.toSet() }, apply = account.services)
    }
    // Turning a service on may need consent for its scopes; turning one off never does.
    fun toggle(service: SyncService, on: Boolean) {
        val current = status?.account?.services.orEmpty()
        if (on) authorize(current + service)
        else scope.launch {
            if (service == SyncService.CALENDAR) c.sync.setCalendarEnabled(false) else c.sync.setTasksEnabled(false)
            c.syncScheduler.requestSoon(0)
        }
    }

    val colors = DsTheme.colors
    Column(Modifier.fillMaxSize().background(colors.background)) {
        DsTopBar(stringResource(R.string.settings_accounts), onBack)
        SettingsBody(loaded = status != null && loadedCals != null && loadedLists != null) {
            val s = status ?: return@SettingsBody
            if (!s.account.isConnected) {
                NotConnected(ui, connect)
            } else {
                Connected(s, ui, connect, c.syncScheduler::requestSoonNow, onOpenConflicts, onToggleCalendar = { v -> toggle(SyncService.CALENDAR, v) },
                    onToggleTasks = { v -> toggle(SyncService.TASKS, v) },
                    onRetryParked = { scope.launch { c.sync.retryParked(); c.syncScheduler.requestSoonNow() } })

                if (s.calendar.enabled) {
                    SectionHeader(stringResource(R.string.sync_calendars), Modifier.padding(top = Space.l))
                    calendars.filter { it.kind == SourceKind.GOOGLE }.forEach { cal ->
                        app.daysemble.ui.components.DsCheckRow(
                            cal.name, cal.isSyncEnabled,
                            { v -> scope.launch { c.database.calendars().setSyncEnabled(cal.id, v); c.reminders.rescheduleAll(); if (v) c.syncScheduler.requestSoon(0) } },
                            subtitle = stringResource(roleLabel(cal.accessRole)),
                            leading = { EventMarker(eventColor(cal.colorArgb), false) },
                        )
                    }
                    if (calendars.none { it.kind == SourceKind.GOOGLE }) Hint(stringResource(R.string.sync_lists_pending))
                }
                if (s.tasks.enabled) {
                    SectionHeader(stringResource(R.string.sync_task_lists), Modifier.padding(top = Space.l))
                    lists.filter { it.kind == SourceKind.GOOGLE }.forEach { l ->
                        app.daysemble.ui.components.DsCheckRow(
                            l.title, l.isSyncEnabled,
                            { v -> scope.launch { c.database.taskLists().setSyncEnabled(l.id, v); c.reminders.rescheduleAll(); if (v) c.syncScheduler.requestSoon(0) } },
                        )
                    }
                    if (lists.none { it.kind == SourceKind.GOOGLE }) Hint(stringResource(R.string.sync_lists_pending))
                }
                Hint(stringResource(R.string.sync_unchecked_note))

                SectionHeader(stringResource(R.string.sync_how), Modifier.padding(top = Space.l))
                Hint(stringResource(R.string.sync_how_body))

                Spacer(Modifier.height(Space.xl))
                DsButton(stringResource(R.string.disconnect), { confirmDisconnect = true }, style = DsButtonStyle.Destructive, modifier = Modifier.padding(horizontal = Space.gutter))
            }
        }
    }
    if (confirmDisconnect) {
        val pending = status?.pendingChanges ?: 0
        DsConfirmDialog(
            stringResource(R.string.disconnect_title),
            stringResource(R.string.disconnect_body) + if (pending > 0) "\n\n" + pluralStringResource(R.plurals.disconnect_pending, pending, pending) else "",
            stringResource(R.string.disconnect),
            onConfirm = { scope.launch { c.syncScheduler.cancelAll(); c.sync.disconnect() } },
            onDismiss = { confirmDisconnect = false }, destructive = true,
        )
    }
}

@Composable
private fun NotConnected(ui: ConnectUi, connect: () -> Unit) {
    val c = DsTheme.colors
    Column(Modifier.padding(horizontal = Space.gutter)) {
        Text(stringResource(R.string.google_intro_title), style = DsTheme.type.titleLarge, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Space.s))
        Text(stringResource(R.string.google_intro_body), style = DsTheme.type.body, color = c.textSecondary)
        Spacer(Modifier.height(Space.l))
        DsButton(
            stringResource(if (ui == ConnectUi.Working) R.string.connecting else R.string.connect_google), connect,
            icon = Icons.Rounded.AccountCircle, enabled = ui != ConnectUi.Working,
        )
        Spacer(Modifier.height(Space.l))
        when (ui) {
            ConnectUi.SetupRequired -> Callout(stringResource(R.string.google_setup_required_title), stringResource(R.string.google_setup_required_body, BuildConfig.APPLICATION_ID))
            ConnectUi.NoPlayServices -> Callout(stringResource(R.string.google_no_play_title), stringResource(R.string.google_no_play_body))
            is ConnectUi.Error -> Callout(stringResource(R.string.google_connect_failed), stringResource(R.string.google_error_code, ui.reason))
            else -> Unit
        }
        Text(stringResource(R.string.google_privacy_note), style = DsTheme.type.bodySmall, color = c.textTertiary)
    }
}

@Composable
private fun Connected(
    s: SyncStatus,
    ui: ConnectUi,
    reconnect: () -> Unit,
    syncNow: () -> Unit,
    onOpenConflicts: () -> Unit,
    onToggleCalendar: (Boolean) -> Unit,
    onToggleTasks: (Boolean) -> Unit,
    onRetryParked: () -> Unit = {},
) {
    val p = LocalTimePrefs.current
    DsRow(s.account.email!!, subtitle = stringResource(R.string.google_account), icon = Icons.Rounded.AccountCircle) {
        SyncBadge(syncIndicator(s), syncLabel(s, p))
    }
    when (ui) {
        is ConnectUi.Partial -> Callout(
            stringResource(R.string.google_partial_title),
            listOfNotNull(
                stringResource(R.string.google_partial_calendar).takeIf { SyncService.CALENDAR in ui.missing },
                stringResource(R.string.google_partial_tasks).takeIf { SyncService.TASKS in ui.missing },
            ).joinToString("\n"),
            Modifier.padding(horizontal = Space.gutter),
        )
        is ConnectUi.Error -> Callout(
            stringResource(R.string.google_connect_failed), stringResource(R.string.google_error_code, ui.reason),
            Modifier.padding(horizontal = Space.gutter),
        )
        else -> Unit
    }
    if (s.needsReauth) {
        Callout(stringResource(R.string.reauth_title), stringResource(R.string.reauth_body), Modifier.padding(horizontal = Space.gutter))
        DsButton(stringResource(R.string.reconnect), reconnect, modifier = Modifier.padding(Space.gutter), enabled = ui != ConnectUi.Working)
    }
    DsSwitchRow(stringResource(R.string.google_calendar), s.calendar.enabled, onToggleCalendar, subtitle = serviceLine(s.calendar.lastSuccessAt, s.calendar.lastError, p), icon = Icons.Rounded.CalendarMonth)
    DsSwitchRow(stringResource(R.string.google_tasks), s.tasks.enabled, onToggleTasks, subtitle = serviceLine(s.tasks.lastSuccessAt, s.tasks.lastError, p), icon = Icons.Rounded.CheckCircleOutline)
    DsRow(
        stringResource(R.string.sync_now),
        subtitle = if (s.pendingChanges > 0) pluralStringResource(R.plurals.pending_changes, s.pendingChanges, s.pendingChanges) else stringResource(R.string.sync_up_to_date),
        icon = Icons.Rounded.Sync, onClick = syncNow, enabled = !s.running,
    )
    if (s.parkedChanges > 0) {
        DsRow(
            stringResource(R.string.retry_parked), subtitle = pluralStringResource(R.plurals.parked_changes, s.parkedChanges, s.parkedChanges),
            icon = Icons.Rounded.ErrorOutline, iconTint = DsTheme.colors.error, onClick = onRetryParked,
        )
    }
    DsRow(stringResource(R.string.conflicts_title), subtitle = stringResource(R.string.conflicts_sub), icon = Icons.AutoMirrored.Rounded.CompareArrows, onClick = onOpenConflicts)
}

@Composable
fun syncIndicator(s: SyncStatus) = when {
    s.running -> SyncIndicator.Syncing
    s.needsReauth -> SyncIndicator.NeedsAttention
    (s.calendar.enabled && s.calendar.lastError == "offline") || (s.tasks.enabled && s.tasks.lastError == "offline") -> SyncIndicator.Offline
    s.hasError -> SyncIndicator.Error
    s.lastSuccessAt != null -> SyncIndicator.Synced
    else -> SyncIndicator.Idle
}

@Composable
fun syncLabel(s: SyncStatus, p: TimePrefs): String = when (syncIndicator(s)) {
    SyncIndicator.Syncing -> stringResource(R.string.sync_state_syncing)
    SyncIndicator.NeedsAttention -> stringResource(R.string.sync_state_reauth)
    SyncIndicator.Offline -> stringResource(R.string.sync_state_offline)
    SyncIndicator.Error -> stringResource(R.string.sync_state_error)
    SyncIndicator.Synced -> stringResource(R.string.sync_state_synced, Fmt.time(Instant.ofEpochMilli(s.lastSuccessAt!!), p))
    SyncIndicator.Idle -> stringResource(R.string.sync_state_waiting)
}

@Composable
private fun serviceLine(last: Long?, error: String?, p: TimePrefs): String = when {
    error != null -> stringResource(R.string.sync_last_error, errorText(error))
    last != null -> stringResource(R.string.sync_last_success, Fmt.mediumDate(Instant.ofEpochMilli(last).atZone(p.zone).toLocalDate(), p) + " " + Fmt.time(Instant.ofEpochMilli(last), p))
    else -> stringResource(R.string.sync_never)
}

@Composable
private fun errorText(code: String) = stringResource(
    when (code) {
        "offline" -> R.string.err_offline
        "auth" -> R.string.err_auth
        "rate_limited" -> R.string.err_rate
        "server" -> R.string.err_server
        "rejected_changes" -> R.string.err_rejected
        else -> R.string.err_other
    },
)

private fun roleLabel(role: String) = when (role) {
    "owner" -> R.string.role_owner
    "writer", "writerWithoutPrivateAccess" -> R.string.role_writer
    else -> R.string.role_reader
}

@Composable
private fun Hint(text: String) = Text(
    text, style = DsTheme.type.bodySmall, color = DsTheme.colors.textSecondary,
    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
)

@Composable
private fun Callout(title: String, body: String, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    Row(
        modifier.fillMaxWidth().padding(bottom = Space.l).clip(Radii.control).background(c.surfaceVariant).padding(Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = c.warning)
        Column {
            Text(title, style = DsTheme.type.bodyStrong, color = c.textPrimary)
            Text(body, style = DsTheme.type.bodySmall, color = c.textSecondary)
        }
    }
}

@Composable
fun ConflictLogRoute(onBack: () -> Unit) {
    val c = (LocalContext.current.applicationContext as DaysembleApp).container
    val flow = remember(c) { c.database.sync().observeConflicts() }
    val loadedItems by flow.collectAsStateWithLifecycle(initialValue = null)
    val items = loadedItems.orEmpty()
    val p = LocalTimePrefs.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(DsTheme.colors.background)) {
        DsTopBar(stringResource(R.string.conflicts_title), onBack)
        SettingsBody(loaded = loadedItems != null) {
            Hint(stringResource(R.string.conflicts_rule))
            if (items.isEmpty()) Hint(stringResource(R.string.conflicts_empty))
            items.forEach { e ->
                DsRow(
                    e.title.ifBlank { stringResource(R.string.untitled_event) },
                    subtitle = e.losingVersion + "\n" + stringResource(if (e.resolvedWith == "LOCAL") R.string.conflict_kept_local else R.string.conflict_kept_remote) +
                        " · " + Fmt.mediumDate(Instant.ofEpochMilli(e.at).atZone(p.zone).toLocalDate(), p),
                )
            }
            if (items.isNotEmpty()) {
                DsButton(stringResource(R.string.conflicts_clear), { scope.launch { c.database.sync().clearConflicts() } }, style = DsButtonStyle.Ghost, modifier = Modifier.padding(Space.gutter))
            }
        }
    }
}
