package app.daysemble.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
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
import app.daysemble.ui.navigation.CalendarRoute
import app.daysemble.ui.navigation.EventDetailRoute
import app.daysemble.ui.navigation.EventEditorRoute
import app.daysemble.ui.navigation.SearchRoute
import app.daysemble.ui.navigation.SettingsPageRoute
import app.daysemble.ui.navigation.SettingsRoute
import app.daysemble.ui.navigation.TaskEditorRoute
import app.daysemble.ui.navigation.TasksRoute
import app.daysemble.ui.navigation.TodayRoute
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
import app.daysemble.ui.theme.DsMotion
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
 * Call after a task was soft-deleted. Shows "Task deleted · Undo" from the root scope, so the
 * snackbar and undo survive the calling screen closing or the user switching tabs.
 */
val LocalTaskDeleted = staticCompositionLocalOf<(String) -> Unit> { {} }

private enum class Tab(val icon: ImageVector, val label: Int) {
    TODAY(Icons.Rounded.WbSunny, R.string.tab_today),
    CALENDAR(Icons.Rounded.CalendarMonth, R.string.tab_calendar),
    TASKS(Icons.Rounded.CheckCircleOutline, R.string.tab_tasks),
}

private fun NavDestination?.tab(): Tab? = when {
    this == null -> null
    hasRoute<TodayRoute>() -> Tab.TODAY
    hasRoute<CalendarRoute>() -> Tab.CALENDAR
    hasRoute<TasksRoute>() -> Tab.TASKS
    else -> null
}

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
    val tab = entry?.destination.tab()
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
    ) {
        if (!settings.onboardingDone) {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            app.daysemble.ui.onboarding.OnboardingScreen { openGoogle ->
                openAccountsAfterOnboarding = openGoogle
                scope.launch { container.settings.setOnboardingDone() }
            }
            return@CompositionLocalProvider
        }
        val c = DsTheme.colors
        val bottomPadding = PaddingValues(bottom = Sizes.bottomBar + Space.xl + 24.dp)
        Box(Modifier.fillMaxSize().background(c.background)) {
            DaysembleNavHost(
                nav, bottomPadding,
                onCreateEventAt = { d, m -> nav.navigate(EventEditorRoute(dateEpochDay = d.toEpochDay(), startMinute = m)) },
                onFocusedDate = { focusedDate = it.toEpochDay() },
            )

            val motion = DsTheme.motion
            AnimatedVisibility(
                visible = tab != null,
                enter = fadeIn(motion.enter()) + slideInVertically(motion.enter()) { if (motion.reduced) 0 else it / 2 },
                exit = fadeOut(motion.exit()) + slideOutVertically(motion.exit()) { if (motion.reduced) 0 else it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                BottomBar(
                    current = tab ?: Tab.TODAY,
                    onSelect = { t -> nav.navigateToTab(t) },
                    onAdd = {
                        addDate = if (tab == Tab.TASKS) null else focusedDate
                        showAdd = true
                    },
                )
            }
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
                    openAccountsAfterOnboarding = false
                    nav.navigate(SettingsPageRoute(SettingsPage.ACCOUNTS.name))
                }
            }
            SnackbarHost(
                snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (tab != null) Sizes.bottomBar + Space.l else Space.l),
            ) { data ->
                Snackbar(
                    data, shape = Radii.control,
                    containerColor = c.inverse, contentColor = c.onInverse, actionColor = c.accentFill,
                )
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

private fun NavHostController.navigateToTab(t: Tab) {
    val route: Any = when (t) { Tab.TODAY -> TodayRoute; Tab.CALENDAR -> CalendarRoute; Tab.TASKS -> TasksRoute }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun DaysembleNavHost(
    nav: NavHostController,
    contentPadding: PaddingValues,
    onCreateEventAt: (LocalDate, Int?) -> Unit,
    onFocusedDate: (LocalDate) -> Unit,
) {
    val m = DsTheme.motion
    val openEvent: (String, String) -> Unit = { id, key -> nav.navigate(EventDetailRoute(id, key)) }
    val openTask: (String) -> Unit = { id -> nav.navigate(TaskEditorRoute(taskId = id)) }
    val back: () -> Unit = { nav.popBackStack() }

    NavHost(
        navController = nav,
        startDestination = TodayRoute,
        enterTransition = { detailEnter(m) },
        exitTransition = { fadeOut(m.exit()) },
        popEnterTransition = { fadeIn(m.enter()) },
        popExitTransition = { detailExit(m) },
    ) {
        composable<TodayRoute>(enterTransition = { tabEnter(m) }, exitTransition = { tabExit(m) }, popEnterTransition = { tabEnter(m) }) {
            TodayScreenRoute(
                contentPadding, onOpenEvent = openEvent, onOpenTask = openTask,
                onSearch = { nav.navigate(SearchRoute) }, onSettings = { nav.navigate(SettingsRoute) },
                onFocusedDate = onFocusedDate,
                onOpenSync = { nav.navigate(SettingsPageRoute(SettingsPage.ACCOUNTS.name)) },
            )
        }
        composable<CalendarRoute>(enterTransition = { tabEnter(m) }, exitTransition = { tabExit(m) }, popEnterTransition = { tabEnter(m) }) {
            CalendarScreenRoute(contentPadding, onOpenEvent = openEvent, onOpenTask = openTask, onCreateAt = onCreateEventAt, onFocusedDate = onFocusedDate)
        }
        composable<TasksRoute>(enterTransition = { tabEnter(m) }, exitTransition = { tabExit(m) }, popEnterTransition = { tabEnter(m) }) {
            TasksScreenRoute(contentPadding, onOpenTask = openTask)
        }
        composable<TaskEditorRoute>(enterTransition = { editorEnter(m) }, popExitTransition = { editorExit(m) }) { e ->
            val r = e.toRoute<TaskEditorRoute>()
            TaskEditorScreenRoute(r.taskId, r.parentId, r.dueEpochDay?.let(LocalDate::ofEpochDay), onClose = back)
        }
        composable<EventDetailRoute> { e ->
            val r = e.toRoute<EventDetailRoute>()
            EventDetailScreenRoute(r.eventId, r.occurrenceKey, onEdit = { id -> nav.navigate(EventEditorRoute(eventId = id)) }, onClose = back)
        }
        composable<EventEditorRoute>(enterTransition = { editorEnter(m) }, popExitTransition = { editorExit(m) }) { e ->
            val r = e.toRoute<EventEditorRoute>()
            EventEditorScreenRoute(r.eventId, r.dateEpochDay?.let(LocalDate::ofEpochDay), r.startMinute, r.title, onClose = back)
        }
        composable<SearchRoute> { SearchScreenRoute(onBack = back, onOpenEvent = openEvent, onOpenTask = openTask) }
        composable<SettingsRoute> { SettingsHomeRoute(onBack = back, onOpen = { nav.navigate(SettingsPageRoute(it.name)) }) }
        composable<SettingsPageRoute> { e ->
            when (runCatching { SettingsPage.valueOf(e.toRoute<SettingsPageRoute>().page) }.getOrNull()) {
                SettingsPage.APPEARANCE -> AppearanceRoute(onBack = back)
                SettingsPage.ACCOUNTS -> app.daysemble.ui.settings.AccountSettingsRoute(onBack = back, onOpenConflicts = { nav.navigate(SettingsPageRoute(SettingsPage.CONFLICTS.name)) })
                SettingsPage.LISTS_CALENDARS -> app.daysemble.ui.settings.ListsCalendarsRoute(onBack = back)
                SettingsPage.CONFLICTS -> app.daysemble.ui.settings.ConflictLogRoute(onBack = back)
                SettingsPage.NOTIFICATIONS -> app.daysemble.ui.settings.NotificationSettingsRoute(onBack = back)
                SettingsPage.DATE_TIME -> DateTimeSettingsRoute(onBack = back)
                SettingsPage.TASKS -> TaskSettingsRoute(onBack = back)
                SettingsPage.DATA -> DataSettingsRoute(onBack = back)
                SettingsPage.ABOUT -> AboutRoute(onBack = back)
                null -> LaunchedEffect(Unit) { back() }
            }
        }
    }
}

// ---- Transitions (one vocabulary, see design.md §6) -----------------------------------------

private fun tabEnter(m: DsMotion): EnterTransition =
    if (m.reduced) fadeIn(m.enter(m.short)) else fadeIn(m.enter(m.long)) + scaleIn(m.enter(m.long), initialScale = 0.985f)

private fun tabExit(m: DsMotion): ExitTransition = fadeOut(m.exit(m.short))

private fun AnimatedContentTransitionScope<*>.detailEnter(m: DsMotion): EnterTransition =
    if (m.reduced) fadeIn(m.enter(m.short)) else fadeIn(m.enter(m.long)) + slideInHorizontally(m.enter(m.long)) { it / 10 }

private fun AnimatedContentTransitionScope<*>.detailExit(m: DsMotion): ExitTransition =
    if (m.reduced) fadeOut(m.exit()) else fadeOut(m.exit(m.medium)) + slideOutHorizontally(m.exit(m.medium)) { it / 10 }

private fun AnimatedContentTransitionScope<*>.editorEnter(m: DsMotion): EnterTransition =
    if (m.reduced) fadeIn(m.enter(m.short)) else fadeIn(m.enter(m.long)) + slideInVertically(m.enter(m.long)) { it / 12 }

private fun AnimatedContentTransitionScope<*>.editorExit(m: DsMotion): ExitTransition =
    if (m.reduced) fadeOut(m.exit()) else fadeOut(m.exit(m.medium)) + slideOutVertically(m.exit(m.medium)) { it / 12 }

// ---- Bottom bar -----------------------------------------------------------------------------

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit, onAdd: () -> Unit) {
    val c = DsTheme.colors
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = Space.gutter, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Row(
            Modifier
                .weight(1f, fill = false)
                .heightIn(min = Sizes.bottomBar - 8.dp)
                .widthIn(max = 360.dp)
                .clip(Radii.pill)
                .background(c.surfaceRaised)
                .border(Sizes.hairline, c.outline, Radii.pill)
                .padding(4.dp)
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
    val bg by animateColorAsState(if (selected) c.inverse else androidx.compose.ui.graphics.Color.Transparent, m.indicator(), label = "tabBg")
    val fg by animateColorAsState(if (selected) c.onInverse else c.textSecondary, m.indicator(), label = "tabFg")
    Column(
        modifier
            .heightIn(min = Sizes.bottomBar - 16.dp)
            .widthIn(min = 64.dp)
            .clip(Radii.pill)
            .background(bg)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = Space.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(tab.icon, null, tint = fg, modifier = Modifier.size(20.dp))
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
