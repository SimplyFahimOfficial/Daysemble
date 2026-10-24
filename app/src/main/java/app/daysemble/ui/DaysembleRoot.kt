package app.daysemble.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.daysemble.AppContainer
import app.daysemble.R
import app.daysemble.data.prefs.AppSettings
import app.daysemble.ui.add.QuickAddSheet
import app.daysemble.ui.calendar.CalendarRoute as CalendarScreenRoute
import app.daysemble.ui.components.DsFab
import app.daysemble.ui.event.EventDetailRoute as EventDetailScreenRoute
import app.daysemble.ui.event.EventEditorRoute as EventEditorScreenRoute
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.rememberTimePrefs
import app.daysemble.ui.navigation.EventDetailRoute
import app.daysemble.ui.navigation.EventEditorRoute
import app.daysemble.ui.navigation.NavMotion
import app.daysemble.ui.navigation.SearchRoute
import app.daysemble.ui.navigation.SettingsPageRoute
import app.daysemble.ui.navigation.SettingsRoute
import app.daysemble.ui.navigation.TaskEditorRoute
import app.daysemble.ui.navigation.MainRoute
import app.daysemble.ui.search.SearchRoute as SearchScreenRoute
import app.daysemble.ui.settings.AboutRoute
import app.daysemble.ui.settings.AppearanceRoute
import app.daysemble.ui.settings.DataSettingsRoute
import app.daysemble.ui.settings.DateTimeSettingsRoute
import app.daysemble.ui.settings.SettingsHomeRoute
import app.daysemble.ui.settings.SettingsPage
import app.daysemble.ui.settings.TaskSettingsRoute
import app.daysemble.ui.task.TaskEditorRoute as TaskEditorScreenRoute
import app.daysemble.ui.tasks.TasksRoute as TasksScreenRoute
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space
import app.daysemble.ui.today.TodayRoute as TodayScreenRoute
import java.time.LocalDate
import kotlinx.coroutines.launch
import java.time.ZoneId

val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/**
 * The settings the app is currently running with (already loaded before the first frame). Settings
 * pages use it as their initial value, so they never render an empty frame while their own flow
 * starts. Null outside [DaysembleRoot] (e.g. in screen tests).
 */
val LocalAppSettings = staticCompositionLocalOf<AppSettings?> { null }

/**
 * Sync status collected once at the app root, which stays alive across navigation, so screens have
 * it on their first frame when you return to them. Null outside [DaysembleRoot]: collect locally.
 */
val LocalSyncStatus = staticCompositionLocalOf<androidx.compose.runtime.State<app.daysemble.data.google.sync.SyncStatus?>?> { null }

/**
 * Call after a task was soft-deleted. Shows "Task deleted · Undo" from the root scope, so the
 * snackbar and undo survive the calling screen closing or the user switching tabs.
 */
val LocalTaskDeleted = staticCompositionLocalOf<(String) -> Unit> { {} }

private enum class Tab(val icon: ImageVector, val label: Int) {
    TODAY(Icons.Rounded.WbSunny, R.string.tab_today),
    CALENDAR(Icons.Rounded.CalendarMonth, R.string.tab_calendar),
    TASKS(Icons.Rounded.CheckCircleOutline, R.string.tab_tasks),
}

/** True while the main screen (tabs + bottom bar) is the current destination, or before the graph exists. */
private fun NavDestination?.isMain(): Boolean = this == null || hasRoute<MainRoute>()

@Composable
fun DaysembleRoot(
    container: AppContainer,
    settings: AppSettings,
    openRequests: kotlinx.coroutines.flow.StateFlow<app.daysemble.OpenRequest?> = kotlinx.coroutines.flow.MutableStateFlow(null),
    onOpenHandled: () -> Unit = {},
) {
    val zone = rememberSystemZone()
    val prefs = rememberTimePrefs(settings.clockFormat, settings.firstDayOfWeek, zone)
    val snackbar = remember { SnackbarHostState() }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val onMain = entry?.destination.isMain()
    // The selected tab lives here (saved across rotation and process death), not in the back stack.
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var addDate by rememberSaveable { mutableStateOf<Long?>(null) }
    // Date the Today/Calendar screen is focused on, used to prefill quick add.
    var focusedDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var openAccountsAfterOnboarding by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { container.defaults() }
    LaunchedEffect(prefs.zone, prefs.firstDayOfWeek) {
        container.timeContext.value = app.daysemble.core.time.TimeContext(prefs.zone, prefs.firstDayOfWeek)
    }
    val open by openRequests.collectAsStateWithLifecycle()
    // Collected here (alive across navigation) so Today has the sync badge on its first frame when
    // you come back to it, instead of the badge popping in after the transition.
    val syncFlow = remember(container) { container.sync.status }
    val syncStatus = syncFlow.collectAsStateWithLifecycle(initialValue = null)

    val rootScope = androidx.compose.runtime.rememberCoroutineScope()
    val deletedMsg = stringResource(R.string.task_deleted)
    val undoLabel = stringResource(R.string.action_undo)
    val onTaskDeleted: (String) -> Unit = remember(container, deletedMsg, undoLabel) {
        { id ->
            rootScope.launch {
                val r = snackbar.showSnackbar(deletedMsg, undoLabel, duration = androidx.compose.material3.SnackbarDuration.Short)
                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) container.tasks.restore(id)
                else container.tasks.purgeExpiredTombstones()
            }
        }
    }
    CompositionLocalProvider(
        LocalTimePrefs provides prefs,
        LocalAppClock provides container.clock,
        LocalSnackbar provides snackbar,
        LocalTaskDeleted provides onTaskDeleted,
        LocalAppSettings provides settings,
        LocalSyncStatus provides syncStatus,
    ) {
        val m = DsTheme.motion
        // Finishing setup: the app fades in over the last onboarding step (no blank frame between).
        // Returning users start directly in the app; this only animates when the value changes.
        AnimatedContent(
            settings.onboardingDone,
            transitionSpec = { androidx.compose.animation.ContentTransform(fadeIn(m.enter(m.long)), m.hold(m.long), sizeTransform = null) },
            label = "firstUse",
        ) { done ->
            if (!done) {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                app.daysemble.ui.onboarding.OnboardingScreen(initialName = settings.displayName) { name, openGoogle ->
                    openAccountsAfterOnboarding = openGoogle
                    scope.launch { container.settings.completeOnboarding(name) }
                }
            } else {
                AppContent(nav, onMain, tab, { tab = it }, container, settings, open, onOpenHandled,
                    openAccountsAfterOnboarding, { openAccountsAfterOnboarding = false }, snackbar,
                    onAdd = { addDate = if (tab == Tab.TASKS) null else focusedDate; showAdd = true },
                    onFocusedDate = { focusedDate = it.toEpochDay() })
            }
        }

        if (showAdd) {
            QuickAddSheet(
                container = container,
                initialDate = addDate?.let(LocalDate::ofEpochDay),
                onDismiss = { showAdd = false },
                onMoreEventOptions = { pre ->
                    nav.navigate(EventEditorRoute(dateEpochDay = pre.date.toEpochDay(), startMinute = pre.startMinute, title = pre.title))
                },
                onMoreTaskOptions = { d -> nav.navigate(TaskEditorRoute(dueEpochDay = d?.toEpochDay())) },
            )
        }
    }
}

@Composable
private fun AppContent(
    nav: NavHostController,
    onMain: Boolean,
    tab: Tab,
    onTab: (Tab) -> Unit,
    container: AppContainer,
    settings: AppSettings,
    open: app.daysemble.OpenRequest?,
    onOpenHandled: () -> Unit,
    openAccountsAfterOnboarding: Boolean,
    onAccountsOpened: () -> Unit,
    snackbar: SnackbarHostState,
    onAdd: () -> Unit,
    onFocusedDate: (LocalDate) -> Unit,
) {
        val c = DsTheme.colors
        val bottomPadding = PaddingValues(bottom = Sizes.bottomBar + Space.xl + 24.dp)
        Box(Modifier.fillMaxSize().background(c.background)) {
            DaysembleNavHost(
                nav, bottomPadding, tab, onTab, onAdd,
                onCreateEventAt = { d, m -> nav.navigate(EventEditorRoute(dateEpochDay = d.toEpochDay(), startMinute = m)) },
                onFocusedDate = onFocusedDate,
            )

            val motion = DsTheme.motion
            LaunchedEffect(open) {
                val r = open ?: return@LaunchedEffect
                when (r.type) {
                    app.daysemble.data.db.OwnerType.TASK -> nav.navigate(TaskEditorRoute(taskId = r.id))
                    app.daysemble.data.db.OwnerType.EVENT -> nav.navigate(EventDetailRoute(r.id, r.occurrenceKey))
                }
                onOpenHandled()
            }
            LaunchedEffect(openAccountsAfterOnboarding) {
                // Runs only once the NavHost above exists, so its graph is set.
                if (openAccountsAfterOnboarding) {
                    onAccountsOpened()
                    nav.navigate(SettingsPageRoute(SettingsPage.ACCOUNTS.name))
                }
            }
            // Moves with the bottom bar instead of jumping when the bar shows or hides.
            val snackbarLift by androidx.compose.animation.core.animateDpAsState(
                if (onMain) Sizes.bottomBar + Space.l else Space.l,
                if (motion.reduced) androidx.compose.animation.core.snap() else motion.standard(motion.long), label = "snackbarLift",
            )
            SnackbarHost(
                snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = snackbarLift),
            ) { data ->
                Snackbar(
                    data, shape = Radii.control,
                    containerColor = c.inverse, contentColor = c.onInverse, actionColor = c.accentFill,
                )
            }
        }
}

/**
 * Opens [route] only if [from] is still the screen the user is looking at. A second tap that lands
 * while the first transition is still running (the source is no longer RESUMED) is ignored, so
 * double taps can't stack two copies of the same page.
 */
private fun NavHostController.openFrom(from: androidx.navigation.NavBackStackEntry, route: Any) {
    if (from.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED) navigate(route)
}

/**
 * Pops [from], but only while it is still the current, settled screen. A second back tap that lands
 * while the first pop is animating (the page is no longer RESUMED, or is no longer on top) is ignored,
 * so two quick taps can't pop both Appearance and Settings. The main screen is never popped.
 */
private fun NavHostController.backFrom(from: androidx.navigation.NavBackStackEntry) {
    val top = currentBackStackEntry ?: return
    if (top.id != from.id || top.destination.hasRoute<MainRoute>()) return
    if (from.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED) popBackStack()
}

@Composable
private fun DaysembleNavHost(
    nav: NavHostController,
    contentPadding: PaddingValues,
    tab: Tab,
    onTab: (Tab) -> Unit,
    onAdd: () -> Unit,
    onCreateEventAt: (LocalDate, Int?) -> Unit,
    onFocusedDate: (LocalDate) -> Unit,
) {
    val m = DsTheme.motion
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl

    NavHost(
        navController = nav,
        startDestination = MainRoute,
        // See NavMotion: only the screen on top fades, so there are no blank or double-exposed frames.
        enterTransition = { with(NavMotion) { enter(m, rtl) } },
        exitTransition = { with(NavMotion) { exit(m, rtl) } },
        popEnterTransition = { with(NavMotion) { popEnter(m, rtl) } },
        popExitTransition = { with(NavMotion) { popExit(m, rtl) } },
    ) {
        composable<MainRoute> { e ->
            val openEvent: (String, String) -> Unit = { id, key -> nav.openFrom(e, EventDetailRoute(id, key)) }
            val openTask: (String) -> Unit = { id -> nav.openFrom(e, TaskEditorRoute(taskId = id)) }
            MainScreen(
                tab = tab, onTab = onTab, onAdd = onAdd, rtl = rtl,
            ) { t ->
                when (t) {
                    Tab.TODAY -> TodayScreenRoute(
                        contentPadding, onOpenEvent = openEvent, onOpenTask = openTask,
                        onSearch = { nav.openFrom(e, SearchRoute) }, onSettings = { nav.openFrom(e, SettingsRoute) },
                        onFocusedDate = onFocusedDate,
                        onOpenSync = { nav.openFrom(e, SettingsPageRoute(SettingsPage.ACCOUNTS.name)) },
                    )
                    Tab.CALENDAR -> CalendarScreenRoute(
                        contentPadding, onOpenEvent = openEvent, onOpenTask = openTask,
                        onCreateAt = { d, min -> if (e.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED) onCreateEventAt(d, min) },
                        onFocusedDate = onFocusedDate,
                    )
                    Tab.TASKS -> TasksScreenRoute(contentPadding, onOpenTask = openTask)
                }
            }
        }
        composable<TaskEditorRoute> { e ->
            val back: () -> Unit = { nav.backFrom(e) }
            val r = e.toRoute<TaskEditorRoute>()
            TaskEditorScreenRoute(r.taskId, r.parentId, r.dueEpochDay?.let(LocalDate::ofEpochDay), onClose = back)
        }
        composable<EventDetailRoute> { e ->
            val back: () -> Unit = { nav.backFrom(e) }
            val r = e.toRoute<EventDetailRoute>()
            EventDetailScreenRoute(r.eventId, r.occurrenceKey, onEdit = { id -> nav.openFrom(e, EventEditorRoute(eventId = id)) }, onClose = back)
        }
        composable<EventEditorRoute> { e ->
            val back: () -> Unit = { nav.backFrom(e) }
            val r = e.toRoute<EventEditorRoute>()
            EventEditorScreenRoute(r.eventId, r.dateEpochDay?.let(LocalDate::ofEpochDay), r.startMinute, r.title, onClose = back)
        }
        composable<SearchRoute> { e ->
            val back: () -> Unit = { nav.backFrom(e) }
            SearchScreenRoute(
                onBack = back,
                onOpenEvent = { id, key -> nav.openFrom(e, EventDetailRoute(id, key)) },
                onOpenTask = { id -> nav.openFrom(e, TaskEditorRoute(taskId = id)) },
            )
        }
        composable<SettingsRoute> { e ->
            val back: () -> Unit = { nav.backFrom(e) }
            SettingsHomeRoute(onBack = back, onOpen = { nav.openFrom(e, SettingsPageRoute(it.name)) })
        }
        composable<SettingsPageRoute> { e ->
            val back: () -> Unit = { nav.backFrom(e) }
            when (runCatching { SettingsPage.valueOf(e.toRoute<SettingsPageRoute>().page) }.getOrNull()) {
                SettingsPage.APPEARANCE -> AppearanceRoute(onBack = back)
                SettingsPage.ACCOUNTS -> app.daysemble.ui.settings.AccountSettingsRoute(onBack = back, onOpenConflicts = { nav.openFrom(e, SettingsPageRoute(SettingsPage.CONFLICTS.name)) })
                SettingsPage.LISTS_CALENDARS -> app.daysemble.ui.settings.ListsCalendarsRoute(onBack = back)
                SettingsPage.CONFLICTS -> app.daysemble.ui.settings.ConflictLogRoute(onBack = back)
                SettingsPage.NOTIFICATIONS -> app.daysemble.ui.settings.NotificationSettingsRoute(onBack = back)
                SettingsPage.DATE_TIME -> DateTimeSettingsRoute(onBack = back)
                SettingsPage.TASKS -> TaskSettingsRoute(onBack = back)
                SettingsPage.DATA -> DataSettingsRoute(onBack = back)
                SettingsPage.ABOUT -> AboutRoute(onBack = back)
                null -> LaunchedEffect(Unit) { nav.popBackStack(e.destination.id, inclusive = true) }
            }
        }
    }
}

/**
 * Today, Calendar and Tasks plus the bottom bar, as one screen. The bar is part of this screen, so it
 * is simply there when you come back from Settings or a detail page (including mid predictive-back
 * gesture) instead of animating in on its own afterwards.
 *
 * Each tab keeps its scroll positions and other saved UI state in [rememberSaveableStateHolder]; its
 * ViewModel lives in this destination's store, so its data is already loaded when you return and the
 * first frame is complete. Tapping the selected tab does nothing (no rebuild).
 * Back on Calendar or Tasks goes to Today first, then leaves the app, as before.
 */
@Composable
private fun MainScreen(
    tab: Tab,
    onTab: (Tab) -> Unit,
    onAdd: () -> Unit,
    rtl: Boolean,
    content: @Composable (Tab) -> Unit,
) {
    val m = DsTheme.motion
    val holder = rememberSaveableStateHolder()
    BackHandler(enabled = tab != Tab.TODAY) { onTab(Tab.TODAY) }
    Box(Modifier.fillMaxSize().background(DsTheme.colors.background)) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = { m.cover(Integer.signum(targetState.ordinal - initialState.ordinal), rtl) },
            label = "tabs",
            modifier = Modifier.fillMaxSize(),
        ) { t ->
            holder.SaveableStateProvider(t.name) { content(t) }
        }
        BottomBar(
            current = tab,
            onSelect = { t -> if (t != tab) onTab(t) },
            onAdd = onAdd,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ---- Bottom bar -----------------------------------------------------------------------------

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    Row(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = Space.gutter, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        // One selection pill that glides between tabs (instead of each tab fading its own background),
        // so the bar shows where you came from and where you went.
        val m = DsTheme.motion
        // With reduced motion the pill jumps to the new tab instead of travelling (the colours still fade).
        val index by androidx.compose.animation.core.animateFloatAsState(
            current.ordinal.toFloat(), if (m.reduced) androidx.compose.animation.core.snap() else m.indicator(), label = "tabIndicator",
        )
        val pill = c.inverse
        Row(
            Modifier
                .weight(1f, fill = false)
                .heightIn(min = Sizes.bottomBar - 8.dp)
                .widthIn(max = 360.dp)
                .clip(Radii.pill)
                .background(c.surfaceRaised)
                .border(Sizes.hairline, c.outline, Radii.pill)
                .padding(4.dp)
                .drawBehind {
                    val w = size.width / Tab.entries.size
                    val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - w * (index + 1) else w * index
                    drawRoundRect(
                        pill, topLeft = Offset(x, 0f), size = Size(w, size.height),
                        cornerRadius = CornerRadius(size.height / 2),
                    )
                }
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { t -> TabItem(t, t == current, { onSelect(t) }, Modifier.weight(1f)) }
        }
        DsFab(Icons.Rounded.Add, stringResource(R.string.cd_add), onAdd, Modifier.requiredSize(Sizes.fab))
    }
}

@Composable
private fun TabItem(tab: Tab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val fg by animateColorAsState(if (selected) c.onInverse else c.textSecondary, m.indicator(), label = "tabFg")
    // A small lift of the selected icon; nothing at all with reduced motion.
    val lift by androidx.compose.animation.core.animateFloatAsState(if (selected && !m.reduced) 1f else 0f, m.indicator(), label = "tabLift")
    Column(
        modifier
            .heightIn(min = Sizes.bottomBar - 16.dp)
            .widthIn(min = 64.dp)
            .clip(Radii.pill)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = Space.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            tab.icon, null, tint = fg,
            modifier = Modifier.size(20.dp).graphicsLayer {
                val s = 1f + 0.06f * lift
                scaleX = s; scaleY = s; translationY = -1.5.dp.toPx() * lift
            },
        )
        Text(stringResource(tab.label), style = DsTheme.type.caps, color = fg, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** The device zone, updated live when the user (or the network) changes it. */
@Composable
private fun rememberSystemZone(): ZoneId {
    val ctx = LocalContext.current
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                java.util.TimeZone.setDefault(null)
                zone = ZoneId.systemDefault()
            }
        }
        ContextCompat.registerReceiver(
            ctx, receiver,
            IntentFilter().apply { addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { ctx.unregisterReceiver(receiver) }
    }
    return zone
}
