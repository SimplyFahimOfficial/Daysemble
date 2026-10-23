package app.daysemble.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import app.daysemble.ui.navigation.NavMotion
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
    /** True on a fresh launch: the app settles in while the splash closes around the logo (SplashExit). */
    animateIntro: Boolean = false,
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
    // Fresh launch only (not after rotation or when returning to the app), and never with reduced motion.
    val introMotion = DsTheme.motion
    val intro = remember { androidx.compose.animation.core.Animatable(if (animateIntro && !introMotion.reduced) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (intro.value < 1f) intro.animateTo(1f, androidx.compose.animation.core.tween(420, easing = app.daysemble.ui.theme.DsMotion.EmphasizedDecelerate))
    }
    val introModifier = Modifier.graphicsLayer {
        val t = intro.value
        val sc = 1.03f - 0.03f * t
        scaleX = sc; scaleY = sc
        alpha = 0.6f + 0.4f * t
    }
    CompositionLocalProvider(
        LocalTimePrefs provides prefs,
        LocalAppClock provides container.clock,
        LocalSnackbar provides snackbar,
        LocalTaskDeleted provides onTaskDeleted,
        LocalAppSettings provides settings,
        LocalSyncStatus provides syncStatus,
    ) {
        if (!settings.onboardingDone) {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            Box(introModifier) {
                app.daysemble.ui.onboarding.OnboardingScreen { openGoogle ->
                    openAccountsAfterOnboarding = openGoogle
                    scope.launch { container.settings.setOnboardingDone() }
                }
            }
            return@CompositionLocalProvider
        }
        val c = DsTheme.colors
        val bottomPadding = PaddingValues(bottom = Sizes.bottomBar + Space.xl + 24.dp)
        Box(Modifier.fillMaxSize().background(c.background).then(introModifier)) {
            DaysembleNavHost(
                nav, bottomPadding,
                onCreateEventAt = { d, m -> nav.navigate(EventEditorRoute(dateEpochDay = d.toEpochDay(), startMinute = m)) },
                onFocusedDate = { focusedDate = it.toEpochDay() },
            )

            val motion = DsTheme.motion
            AnimatedVisibility(
                visible = tab != null,
                // Same length as the back transition it usually accompanies (NavMotion popEnter).
                enter = fadeIn(motion.enter(motion.long)) + slideInVertically(motion.enter(motion.long)) { if (motion.reduced) 0 else it / 2 },
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
            // Moves with the bottom bar instead of jumping when the bar shows or hides.
            val snackbarLift by androidx.compose.animation.core.animateDpAsState(
                if (tab != null) Sizes.bottomBar + Space.l else Space.l,
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
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val openEvent: (String, String) -> Unit = { id, key -> nav.navigate(EventDetailRoute(id, key)) }
    val openTask: (String) -> Unit = { id -> nav.navigate(TaskEditorRoute(taskId = id)) }
    val back: () -> Unit = { nav.popBackStack() }

    NavHost(
        navController = nav,
        startDestination = TodayRoute,
        // See NavMotion: only the screen on top fades, so there are no blank or double-exposed frames.
        enterTransition = { with(NavMotion) { enter(m, rtl) } },
        exitTransition = { with(NavMotion) { exit(m, rtl) } },
        popEnterTransition = { with(NavMotion) { popEnter(m, rtl) } },
        popExitTransition = { with(NavMotion) { popExit(m, rtl) } },
    ) {
        composable<TodayRoute> {
            TodayScreenRoute(
                contentPadding, onOpenEvent = openEvent, onOpenTask = openTask,
                onSearch = { nav.navigate(SearchRoute) }, onSettings = { nav.navigate(SettingsRoute) },
                onFocusedDate = onFocusedDate,
                onOpenSync = { nav.navigate(SettingsPageRoute(SettingsPage.ACCOUNTS.name)) },
            )
        }
        composable<CalendarRoute> {
            CalendarScreenRoute(contentPadding, onOpenEvent = openEvent, onOpenTask = openTask, onCreateAt = onCreateEventAt, onFocusedDate = onFocusedDate)
        }
        composable<TasksRoute> {
            TasksScreenRoute(contentPadding, onOpenTask = openTask)
        }
        composable<TaskEditorRoute> { e ->
            val r = e.toRoute<TaskEditorRoute>()
            TaskEditorScreenRoute(r.taskId, r.parentId, r.dueEpochDay?.let(LocalDate::ofEpochDay), onClose = back)
        }
        composable<EventDetailRoute> { e ->
            val r = e.toRoute<EventDetailRoute>()
            EventDetailScreenRoute(r.eventId, r.occurrenceKey, onEdit = { id -> nav.navigate(EventEditorRoute(eventId = id)) }, onClose = back)
        }
        composable<EventEditorRoute> { e ->
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
