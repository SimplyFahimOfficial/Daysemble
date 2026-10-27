package app.daysemble.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
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
import app.daysemble.data.google.sync.SyncStatus
import app.daysemble.data.prefs.AppSettings
import app.daysemble.ui.add.QuickAddSheet
import app.daysemble.ui.brand.BrandNameplate
import app.daysemble.ui.calendar.CalendarRoute as CalendarScreenRoute
import app.daysemble.ui.components.DsFab
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.components.SyncBadge
import app.daysemble.ui.event.EventDetailRoute as EventDetailScreenRoute
import app.daysemble.ui.event.EventEditorRoute as EventEditorScreenRoute
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.rememberTimePrefs
import app.daysemble.ui.navigation.EventDetailRoute
import app.daysemble.ui.navigation.EventEditorRoute
import app.daysemble.ui.navigation.MainRoute
import app.daysemble.ui.navigation.NavMotion
import app.daysemble.ui.navigation.SearchRoute
import app.daysemble.ui.navigation.SettingsPageRoute
import app.daysemble.ui.navigation.SettingsRoute
import app.daysemble.ui.navigation.TaskEditorRoute
import app.daysemble.ui.search.SearchRoute as SearchScreenRoute
import app.daysemble.ui.settings.AboutRoute
import app.daysemble.ui.settings.AppearanceRoute
import app.daysemble.ui.settings.DataSettingsRoute
import app.daysemble.ui.settings.DateTimeSettingsRoute
import app.daysemble.ui.settings.SettingsHomeRoute
import app.daysemble.ui.settings.SettingsPage
import app.daysemble.ui.settings.TaskSettingsRoute
import app.daysemble.ui.settings.syncIndicator
import app.daysemble.ui.settings.syncLabel
import app.daysemble.ui.task.TaskEditorRoute as TaskEditorScreenRoute
import app.daysemble.ui.tasks.TasksRoute as TasksScreenRoute
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space
import app.daysemble.ui.today.TodayRoute as TodayScreenRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.runtime.setValue

val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/**
 * The settings the app is currently running with (already loaded before the first frame). Settings
 * pages use it as their initial value, so they never render an empty frame while their own flow
 * starts. Null outside [DaysembleRoot] (e.g. in screen tests).
 */
val LocalAppSettings = staticCompositionLocalOf<AppSettings?> { null }

/**
 * Sync status collected once at the app root, which stays alive across navigation, so screens have
 * it on their first frame. Null outside [DaysembleRoot]: collect locally.
 */
val LocalSyncStatus = staticCompositionLocalOf<State<SyncStatus?>?> { null }

/**
 * Call after a task was soft-deleted. Shows "Task deleted · Undo" from the root scope, so the
 * snackbar and undo survive the calling screen closing or the user switching tabs.
 */
val LocalTaskDeleted = staticCompositionLocalOf<(String) -> Unit> { {} }

internal enum class Tab(val icon: ImageVector, val label: Int) {
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
    var openAccountsAfterOnboarding by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { container.defaults() }
    LaunchedEffect(prefs.zone, prefs.firstDayOfWeek) {
        container.timeContext.value = app.daysemble.core.time.TimeContext(prefs.zone, prefs.firstDayOfWeek)
    }
    val open by openRequests.collectAsStateWithLifecycle()
    val syncFlow = remember(container) { container.sync.status }
    val syncStatus = syncFlow.collectAsStateWithLifecycle(initialValue = null)

    val rootScope = rememberCoroutineScope()
    val deletedMsg = stringResource(R.string.task_deleted)
    val undoLabel = stringResource(R.string.action_undo)
    val onTaskDeleted: (String) -> Unit = remember(container, deletedMsg, undoLabel) {
        { id ->
            rootScope.launch {
                val r = snackbar.showSnackbar(deletedMsg, undoLabel, duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) container.tasks.restore(id)
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
            transitionSpec = { m.fadeThrough() },
            label = "firstUse",
        ) { done ->
            if (!done) {
                val scope = rememberCoroutineScope()
                app.daysemble.ui.onboarding.OnboardingScreen(initialName = settings.displayName) { name, openGoogle ->
                    openAccountsAfterOnboarding = openGoogle
                    scope.launch { container.settings.completeOnboarding(name) }
                }
            } else {
                AppContent(
                    nav, container, settings, syncStatus.value, open, onOpenHandled,
                    openAccountsAfterOnboarding, { openAccountsAfterOnboarding = false }, snackbar,
                )
            }
        }
    }
}

/**
 * The app once setup is done.
 *
 * Structure (the fix for the tab and back-navigation jank):
 * - [MainScreen] (header, Today/Calendar/Tasks, bottom bar) is composed once and stays composed
 *   for the life of the activity. It used to be a NavHost destination, so it was disposed whenever
 *   Settings or a detail page covered it and rebuilt from scratch, in the first frame of the
 *   animation, every time you came back (and at the start of every predictive-back gesture).
 * - The [NavHost] only hosts the pages opened on top. Its start destination ([MainRoute]) is a
 *   placeholder that draws the main screen's picture ([mainLayer]), so the NavHost's own enter,
 *   exit and predictive-back transitions move that picture exactly like any other page, while the
 *   real main screen keeps its state, scroll positions and data underneath.
 */
@Composable
private fun AppContent(
    nav: NavHostController,
    container: AppContainer,
    settings: AppSettings,
    syncStatus: SyncStatus?,
    open: app.daysemble.OpenRequest?,
    onOpenHandled: () -> Unit,
    openAccountsAfterOnboarding: Boolean,
    onAccountsOpened: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val c = DsTheme.colors
    val motion = DsTheme.motion
    val entry by nav.currentBackStackEntryAsState()
    val onMain = entry?.destination.isMain()
    // The selected tab lives here (saved across rotation and process death), not in the back stack.
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var addDate by rememberSaveable { mutableStateOf<Long?>(null) }
    // The day each date-based tab is showing, used to prefill quick add for that tab.
    var todayFocus by rememberSaveable { mutableStateOf<Long?>(null) }
    var calendarFocus by rememberSaveable { mutableStateOf<Long?>(null) }

    val mainLayer = rememberGraphicsLayer()
    // How many times the NavHost currently shows the main placeholder (0 = fully covered by a page).
    var mainShown by remember { mutableIntStateOf(0) }
    val onMainShown = remember { { shown: Boolean -> mainShown += if (shown) 1 else -1 } }

    val bottomPadding = remember { PaddingValues(bottom = Sizes.bottomBar + Space.xl + 24.dp) }
    Box(Modifier.fillMaxSize().background(c.background)) {
        MainScreen(
            tab = tab,
            onTab = { tab = it },
            backEnabled = onMain,
            displayName = settings.displayName,
            syncStatus = syncStatus?.takeIf { it.account.isConnected },
            onSearch = { nav.openFromMain(SearchRoute) },
            onSettings = { nav.openFromMain(SettingsRoute) },
            onOpenSync = { nav.openFromMain(SettingsPageRoute(SettingsPage.ACCOUNTS.name)) },
            onAdd = {
                addDate = when (tab) { Tab.TODAY -> todayFocus; Tab.CALENDAR -> calendarFocus; Tab.TASKS -> null }
                showAdd = true
            },
            modifier = Modifier
                .fillMaxSize()
                // Recorded, not drawn here: the NavHost placeholder draws it (see AppContent's doc).
                .drawWithContent { mainLayer.record { this@drawWithContent.drawContent() } }
                .graphicsLayer()
                // While a page covers it completely, it is invisible and must not be read out or focused.
                .then(if (mainShown == 0) Modifier.clearAndSetSemantics { } else Modifier),
        ) { t ->
            val openEvent: (String, String) -> Unit = { id, key -> nav.openFromMain(EventDetailRoute(id, key)) }
            val openTask: (String) -> Unit = { id -> nav.openFromMain(TaskEditorRoute(taskId = id)) }
            when (t) {
                Tab.TODAY -> TodayScreenRoute(
                    bottomPadding, onOpenEvent = openEvent, onOpenTask = openTask,
                    onFocusedDate = { todayFocus = it.toEpochDay() },
                )
                Tab.CALENDAR -> CalendarScreenRoute(
                    bottomPadding, onOpenEvent = openEvent, onOpenTask = openTask,
                    onCreateAt = { d, min -> nav.openFromMain(EventEditorRoute(dateEpochDay = d.toEpochDay(), startMinute = min)) },
                    onFocusedDate = { calendarFocus = it.toEpochDay() },
                )
                Tab.TASKS -> TasksScreenRoute(bottomPadding, onOpenTask = openTask)
            }
        }

        DaysembleNavHost(nav, mainLayer, onMainShown)

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
        val snackbarLift by animateDpAsState(
            if (onMain) Sizes.bottomBar + Space.l else Space.l,
            if (motion.reduced) snap() else motion.standard(motion.long), label = "snackbarLift",
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

/**
 * Opens [route] from the main screen, only while the main screen is the top destination. A second
 * tap that lands after the first one already pushed a page is ignored, so double taps can't stack
 * two copies of the same page. Taps during the animation back to the main screen do work.
 */
private fun NavHostController.openFromMain(route: Any) {
    if (currentBackStackEntry?.destination.isMain()) navigate(route)
}

/**
 * Opens [route] from page [from] only while [from] is the screen the user is looking at. A second
 * tap that lands while the first transition is still running (the source is no longer RESUMED) is
 * ignored.
 */
private fun NavHostController.openFrom(from: NavBackStackEntry, route: Any) {
    if (from.lifecycle.currentState == Lifecycle.State.RESUMED) navigate(route)
}

/**
 * Back from a user tap (app-bar arrow, Cancel): pops [from] only while it is RESUMED and on top. A
 * second quick tap lands while the first pop is animating, often on the page underneath, which
 * isn't RESUMED yet, so it is ignored instead of popping Settings after Appearance.
 */
private fun NavHostController.backFrom(from: NavBackStackEntry) {
    if (from.lifecycle.currentState == Lifecycle.State.RESUMED) closeFrom(from)
}

/**
 * A screen closing itself (after a save or delete, or because its item is gone). Not tied to
 * RESUMED, so it also works while the page is paused or still entering; it pops only if [from] is
 * still on top, so it can't close a different page. The main screen is never popped.
 */
private fun NavHostController.closeFrom(from: NavBackStackEntry) {
    val top = currentBackStackEntry ?: return
    if (top.id == from.id && !top.destination.hasRoute<MainRoute>()) popBackStack()
}

/**
 * A page opened over the main screen. It takes all touches inside its bounds (pages draw an opaque
 * background, but a background alone doesn't stop a tap from reaching the main screen underneath),
 * and once it is leaving it ignores taps, so a quick tap after Back can't act on the page that is
 * fading out.
 */
@Composable
private fun AnimatedContentScope.Page(content: @Composable () -> Unit) {
    val leaving = transition.targetState == EnterExitState.PostExit
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(leaving) {
                if (leaving) awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            },
    ) { content() }
}

@Composable
private fun DaysembleNavHost(nav: NavHostController, mainLayer: GraphicsLayer, onMainShown: (Boolean) -> Unit) {
    val m = DsTheme.motion
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current

    NavHost(
        navController = nav,
        startDestination = MainRoute,
        enterTransition = { with(NavMotion) { enter(m, rtl, density) } },
        exitTransition = { with(NavMotion) { exit(m, rtl, density) } },
        popEnterTransition = { with(NavMotion) { popEnter(m, rtl, density) } },
        popExitTransition = { with(NavMotion) { popExit(m, rtl, density) } },
        predictivePopEnterTransition = { NavMotion.predictivePopEnter(m, rtl, density) },
        predictivePopExitTransition = { edge -> NavMotion.predictivePopExit(m, density, edge) },
    ) {
        composable<MainRoute> {
            // Placeholder for the always-composed main screen: draws its picture, so it takes part
            // in the transitions. It has no input of its own; touches reach the real main screen.
            DisposableEffect(Unit) {
                onMainShown(true)
                onDispose { onMainShown(false) }
            }
            Box(Modifier.fillMaxSize().drawBehind { drawLayer(mainLayer) })
        }
        composable<TaskEditorRoute> { e ->
            val r = e.toRoute<TaskEditorRoute>()
            Page { TaskEditorScreenRoute(r.taskId, r.parentId, r.dueEpochDay?.let(LocalDate::ofEpochDay), onDone = { nav.closeFrom(e) }, onClose = { nav.backFrom(e) }) }
        }
        composable<EventDetailRoute> { e ->
            val r = e.toRoute<EventDetailRoute>()
            Page {
                EventDetailScreenRoute(
                    r.eventId, r.occurrenceKey, onEdit = { id -> nav.openFrom(e, EventEditorRoute(eventId = id)) },
                    onDone = { nav.closeFrom(e) }, onClose = { nav.backFrom(e) },
                )
            }
        }
        composable<EventEditorRoute> { e ->
            val r = e.toRoute<EventEditorRoute>()
            Page {
                EventEditorScreenRoute(
                    r.eventId, r.dateEpochDay?.let(LocalDate::ofEpochDay), r.startMinute, r.title,
                    onDone = { nav.closeFrom(e) }, onClose = { nav.backFrom(e) },
                )
            }
        }
        composable<SearchRoute> { e ->
            Page {
                SearchScreenRoute(
                    onBack = { nav.backFrom(e) },
                    onOpenEvent = { id, key -> nav.openFrom(e, EventDetailRoute(id, key)) },
                    onOpenTask = { id -> nav.openFrom(e, TaskEditorRoute(taskId = id)) },
                )
            }
        }
        composable<SettingsRoute> { e ->
            Page { SettingsHomeRoute(onBack = { nav.backFrom(e) }, onOpen = { nav.openFrom(e, SettingsPageRoute(it.name)) }) }
        }
        composable<SettingsPageRoute> { e ->
            val back: () -> Unit = { nav.backFrom(e) }
            Page {
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
}

/**
 * The shared header, Today/Calendar/Tasks and the bottom bar, as one surface. Header and bottom
 * bar never move when the tab changes; only the content between them does (see [TabHost]).
 * Back on Calendar or Tasks goes to Today first, then leaves the app.
 */
@Composable
private fun MainScreen(
    tab: Tab,
    onTab: (Tab) -> Unit,
    backEnabled: Boolean,
    displayName: String?,
    syncStatus: SyncStatus?,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onOpenSync: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Tab) -> Unit,
) {
    BackHandler(enabled = backEnabled && tab != Tab.TODAY) { onTab(Tab.TODAY) }
    Box(modifier.background(DsTheme.colors.background)) {
        Column(Modifier.fillMaxSize()) {
            MainHeader(displayName, syncStatus, onSearch, onSettings, onOpenSync)
            TabHost(tab, Modifier.weight(1f).fillMaxWidth(), content)
        }
        BottomBar(
            current = tab,
            onSelect = { t -> if (t != tab) onTab(t) },
            onAdd = onAdd,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The identity bar shared by Today, Calendar and Tasks: the logo with the user's name (or the app
 * name), the sync state when Google is connected, and Search and Settings. The name takes the free
 * space and ends with "…"; the actions always keep their size.
 */
@Composable
internal fun MainHeader(
    displayName: String?,
    syncStatus: SyncStatus?,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onOpenSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalTimePrefs.current
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 60.dp)
            .padding(start = Space.gutter, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandNameplate(displayName, Modifier.weight(1f).padding(end = Space.s))
        if (syncStatus != null) {
            SyncBadge(
                syncIndicator(syncStatus), syncLabel(syncStatus, p),
                Modifier
                    .weight(1f, fill = false)
                    .minimumInteractiveComponentSize()
                    .clip(Radii.pill)
                    .clickable(role = Role.Button, onClick = onOpenSync),
            )
        }
        DsIconButton(Icons.Rounded.Search, stringResource(R.string.cd_search), onSearch)
        DsIconButton(Icons.Rounded.Settings, stringResource(R.string.cd_settings), onSettings)
    }
}

/**
 * Hosts the three tabs. Each tab is composed once and then kept (with its ViewModel, scroll
 * position and in-progress UI state); switching only changes which one is drawn. The tabs not yet
 * opened are composed in the background shortly after launch, so even the first switch has nothing
 * to build. This replaces an AnimatedContent that disposed the old tab and composed the new one from
 * nothing in the first frame of every switch, which was the main source of tab jank.
 *
 * The switch is a fade through ([DsMotion.tabExit], [DsMotion.tabEnter]): the old tab fades out in
 * 90 ms, the new one fades in right after with a small shift in tab order. Values are animated with
 * one Animatable per tab and read only in the draw phase, so nothing recomposes per frame, and a
 * switch that interrupts another simply continues from the current values.
 */
@Composable
private fun TabHost(current: Tab, modifier: Modifier, content: @Composable (Tab) -> Unit) {
    val m = DsTheme.motion
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val holder = rememberSaveableStateHolder()
    var visited by rememberSaveable { mutableStateOf(listOf(current.name)) }
    androidx.compose.runtime.SideEffect { if (current.name !in visited) visited = visited + current.name }
    LaunchedEffect(Unit) {
        // After launch work and the first frames, one tab at a time so no single frame does it all.
        delay(700)
        Tab.entries.forEach { t ->
            if (t.name !in visited) { visited = visited + t.name; delay(250) }
        }
    }
    val composed = Tab.entries.filter { it.name in visited || it == current }

    val alpha = remember { Tab.entries.associateWith { Animatable(if (it == current) 1f else 0f) } }
    val drawn = remember { Tab.entries.associateWith { t -> derivedStateOf { alpha.getValue(t).value > 0f } } }
    var direction by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(current) }
    LaunchedEffect(current) {
        if (shown == current) return@LaunchedEffect
        direction = Integer.signum(current.ordinal - shown.ordinal)
        shown = current
        Tab.entries.forEach { t -> if (t != current) launch { alpha.getValue(t).animateTo(0f, m.tabExit()) } }
        val incoming = alpha.getValue(current)
        // Coming back to a tab that is still fading out: continue from there without the delay.
        launch { incoming.animateTo(1f, if (incoming.value > 0.2f) m.enter(m.tabEnterMillis) else m.tabEnter()) }
    }
    val currentTab by rememberUpdatedState(current)
    val shift = with(LocalDensity.current) { m.tabShift.toPx() }

    Layout(
        modifier = modifier,
        content = {
            composed.forEach { t ->
                key(t) {
                    Box(
                        Modifier
                            .layoutId(t)
                            .graphicsLayer {
                                val a = alpha.getValue(t).value
                                this.alpha = a
                                val dir = if (rtl) -direction else direction
                                translationX = if (t == currentTab) dir * shift * (1f - a) else 0f
                            }
                            .then(if (t != current) Modifier.clearAndSetSemantics { } else Modifier),
                    ) { holder.SaveableStateProvider(t.name) { content(t) } }
                }
            }
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val child = Constraints.fixed(w, h)
        // Every composed tab is measured (so a hidden tab is laid out and ready), only visible ones are placed.
        val placeables = measurables.map { (it.layoutId as Tab) to it.measure(child) }
        layout(w, h) {
            placeables.forEach { (t, p) ->
                val top = t == currentTab
                if (top || drawn.getValue(t).value) p.place(0, 0, zIndex = if (top) 1f else 0f)
            }
        }
    }
}

// ---- Bottom bar -----------------------------------------------------------------------------

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    Row(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = Space.gutter, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        // One selection pill that glides between tabs, so the bar shows where you came from and where
        // you went. Read in the draw phase only. With reduced motion it moves without the glide.
        val index by animateFloatAsState(current.ordinal.toFloat(), m.indicator(), label = "tabIndicator")
        val pill = c.inverse
        Row(
            Modifier
                .weight(1f, fill = false)
                .heightIn(min = Sizes.bottomBar - 8.dp)
                .widthIn(max = 360.dp)
                .shadow(10.dp, Radii.pill, ambientColor = c.scrim, spotColor = c.scrim)
                .clip(Radii.pill)
                .background(c.surfaceRaised)
                .border(Sizes.hairline, c.outline, Radii.pill)
                .padding(4.dp)
                .drawBehind {
                    val w = size.width / Tab.entries.size
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w * (index + 1) else w * index
                    drawRoundRect(pill, topLeft = Offset(x, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2))
                }
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { t -> TabItem(t, t == current, { onSelect(t) }, Modifier.weight(1f)) }
        }
        DsFab(
            Icons.Rounded.Add, stringResource(R.string.cd_add), onAdd,
            Modifier.requiredSize(Sizes.fab).shadow(10.dp, Radii.pill, ambientColor = c.scrim, spotColor = c.scrim),
        )
    }
}

@Composable
private fun TabItem(tab: Tab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    // Colour follows the pill; the label and icon never move or resize.
    val fg by animateColorAsState(if (selected) c.onInverse else c.textSecondary, m.indicator(), label = "tabFg")
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
        Icon(tab.icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Text(stringResource(tab.label), style = DsTheme.type.caps, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
