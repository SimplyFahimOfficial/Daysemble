package app.daysemble.ui

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.platform.LocalFocusManager
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
import app.daysemble.ui.navigation.TaskDetailRoute
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
 *
 * Dynamic, not static: settings change while the app runs (every switch in Settings), and a static
 * local invalidates its whole subtree on each change. That recomposed the entire app, including the
 * three tabs kept composed under Settings, on every toggle, while the toggle was animating. Now only
 * the few places that read it recompose.
 */
val LocalAppSettings = androidx.compose.runtime.compositionLocalOf<AppSettings?> { null }

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

/**
 * Call after an event (or one occurrence of a series) was deleted, with what the delete returned.
 * Shows "Event deleted · Undo" from the root scope, like [LocalTaskDeleted].
 */
val LocalEventDeleted = staticCompositionLocalOf<(app.daysemble.data.repo.EventRepository.Deleted?, Boolean) -> Unit> { { _, _ -> } }

/**
 * Call after a task's due date was changed from a quick action, with the previous date and time.
 * Shows "Moved to Tomorrow · Undo" from the root scope.
 */
val LocalTaskRescheduled = staticCompositionLocalOf<(String, java.time.LocalDate, java.time.LocalDate?, java.time.LocalTime?) -> Unit> { { _, _, _, _ -> } }

/**
 * Call after a task was checked off in a list. For a repeating task it shows where the series went
 * ("Done · next one Fri, Oct 30", or that the repeat has ended) with Undo, from the root scope like
 * [LocalTaskDeleted]. Undo reopens the occurrence, which also removes the next one again if it
 * hasn't been touched. One-off tasks stay in place, struck through, so they need no snackbar.
 */
val LocalTaskCompleted = staticCompositionLocalOf<(String, app.daysemble.data.repo.TaskRepository.Completion) -> Unit> { { _, _ -> } }

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
    app.daysemble.ui.theme.SyncSystemNightMode(settings.themeMode)
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
    val eventDeletedMsg = stringResource(R.string.event_deleted)
    val occurrenceDeletedMsg = stringResource(R.string.occurrence_deleted)
    val onEventDeleted: (app.daysemble.data.repo.EventRepository.Deleted?, Boolean) -> Unit =
        remember(container, eventDeletedMsg, occurrenceDeletedMsg, undoLabel) {
            { deleted, occurrence ->
                if (deleted != null) rootScope.launch {
                    val r = snackbar.showSnackbar(if (occurrence) occurrenceDeletedMsg else eventDeletedMsg, undoLabel, duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) container.events.restore(deleted)
                }
            }
        }
    val movedMsg = stringResource(R.string.task_rescheduled)
    val onTaskRescheduled: (String, java.time.LocalDate, java.time.LocalDate?, java.time.LocalTime?) -> Unit =
        remember(container, movedMsg, undoLabel, prefs) {
            { id, newDate, oldDate, oldTime ->
                rootScope.launch {
                    val msg = movedMsg.format(app.daysemble.ui.format.Fmt.smartDate(newDate, container.clock.today(), prefs))
                    val r = snackbar.showSnackbar(msg, undoLabel, duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) {
                        // The snackbar outlives the screen: if the due date or time was changed again
                        // meanwhile, that newer change stands.
                        val cur = container.tasks.get(id)
                        if (cur != null && cur.dueDate == newDate && cur.dueTime == oldTime) container.tasks.setDue(id, oldDate, oldTime)
                    }
                }
            }
        }
    val nextMsg = stringResource(R.string.task_done_next)
    val endedMsg = stringResource(R.string.task_done_series_ended)
    val doneMsg = stringResource(R.string.task_done)
    val onTaskCompleted: (String, app.daysemble.data.repo.TaskRepository.Completion) -> Unit = remember(container, nextMsg, endedMsg, doneMsg, undoLabel, prefs) {
        { id, r ->
            if (r.repeating) rootScope.launch {
                val today = container.clock.today()
                // "The last one" only when the series really has nothing left; an open next
                // occurrence without a due date gets a plain "Done".
                val msg = when {
                    r.nextDue != null -> nextMsg.format(app.daysemble.ui.format.Fmt.smartDate(r.nextDue, today, prefs))
                    r.seriesEnded -> endedMsg
                    else -> doneMsg
                }
                val res = snackbar.showSnackbar(msg, undoLabel, duration = SnackbarDuration.Short)
                if (res == SnackbarResult.ActionPerformed) container.tasks.setCompleted(id, false)
            }
        }
    }
    CompositionLocalProvider(
        LocalTimePrefs provides prefs,
        LocalAppClock provides container.clock,
        LocalSnackbar provides snackbar,
        LocalTaskDeleted provides onTaskDeleted,
        LocalEventDeleted provides onEventDeleted,
        LocalTaskRescheduled provides onTaskRescheduled,
        LocalTaskCompleted provides onTaskCompleted,
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

    // The main screen stays composed under pages, so it would keep keyboard focus (and the keyboard)
    // if a field on it was focused when a page opened. Focus is cleared from it as a page opens, and
    // it can't take focus again (e.g. Tab key traversal) until it is the top screen once more.
    val focusManager = LocalFocusManager.current
    var mainHasFocus by remember { mutableStateOf(false) }
    val onMainNow by rememberUpdatedState(onMain)
    LaunchedEffect(onMain) { if (!onMain && mainHasFocus) focusManager.clearFocus(force = true) }

    val bottomPadding = remember { PaddingValues(bottom = Sizes.bottomBar + Space.xl + 24.dp) }
    // Long-press on any task or agenda item (main tabs and Search) opens one shared menu.
    app.daysemble.ui.actions.ItemActionsHost(
        container,
        onOpenTask = { id -> TaskDetailRoute(id).let { if (!nav.isShowing(it)) nav.navigate(it) } },
        onEditTask = { id -> nav.navigate(TaskEditorRoute(taskId = id)) },
        onOpenEvent = { id, key -> EventDetailRoute(id, key).let { if (!nav.isShowing(it)) nav.navigate(it) } },
        onEditEvent = { id, key -> nav.navigate(EventEditorRoute(eventId = id, occurrenceKey = key)) },
    ) {
    Box(Modifier.fillMaxSize().background(c.background)) {
        // Paused (lifecycle CREATED) while a page covers it completely: see VisibilityLifecycle.
        app.daysemble.ui.components.VisibilityLifecycle(visible = onMain || mainShown > 0) {
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
                .onFocusChanged { mainHasFocus = it.hasFocus }
                .focusProperties { onEnter = { if (!onMainNow) cancelFocusChange() } }
                .focusGroup()
                // While a page covers it completely, it is invisible and must not be read out or focused.
                .then(if (mainShown == 0) Modifier.clearAndSetSemantics { } else Modifier),
        ) { t ->
            val openEvent: (String, String) -> Unit = { id, key -> nav.openFromMain(EventDetailRoute(id, key)) }
            // A tap opens the detail view; editing is the long-press menu or the detail's pencil.
            val openTask: (String) -> Unit = { id -> nav.openFromMain(TaskDetailRoute(id)) }
            when (t) {
                Tab.TODAY -> TodayScreenRoute(
                    bottomPadding, onOpenEvent = openEvent, onOpenTask = openTask,
                    onFocusedDate = { todayFocus = it.toEpochDay() },
                    onAddEvent = { d -> nav.openFromMain(EventEditorRoute(dateEpochDay = d.toEpochDay())) },
                    onAddTask = { d -> nav.openFromMain(TaskEditorRoute(dueEpochDay = d.toEpochDay())) },
                    onOpenSchedule = { nav.openFromMain(SettingsPageRoute(SettingsPage.SCHEDULE.name)) },
                )
                Tab.CALENDAR -> CalendarScreenRoute(
                    bottomPadding, onOpenEvent = openEvent, onOpenTask = openTask,
                    onCreateAt = { d, min -> nav.openFromMain(EventEditorRoute(dateEpochDay = d.toEpochDay(), startMinute = min)) },
                    onFocusedDate = { calendarFocus = it.toEpochDay() },
                )
                Tab.TASKS -> TasksScreenRoute(bottomPadding, onOpenTask = openTask)
            }
        }
        }

        DaysembleNavHost(nav, mainLayer, onMainShown)

        LaunchedEffect(open) {
            val r = open ?: return@LaunchedEffect
            val route: Any = when (r.type) {
                app.daysemble.data.db.OwnerType.TASK -> TaskDetailRoute(r.id)
                app.daysemble.data.db.OwnerType.EVENT -> EventDetailRoute(r.id, r.occurrenceKey)
            }
            // Tapping the notification of the item that's already open (or tapping it twice) doesn't
            // stack a second copy of the same page.
            if (!nav.isShowing(route)) nav.navigate(route)
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
                containerColor = c.inverse, contentColor = c.onInverse, actionColor = c.inverseAccent,
            )
        }
    }
    }

    if (showAdd) {
        QuickAddSheet(
            container = container,
            initialDate = addDate?.let(LocalDate::ofEpochDay),
            onDismiss = { showAdd = false },
            onMoreEventOptions = { pre ->
                nav.openFromMain(EventEditorRoute(dateEpochDay = pre.date.toEpochDay(), startMinute = pre.startMinute, title = pre.title))
            },
            onMoreTaskOptions = { title, d, minute ->
                nav.openFromMain(TaskEditorRoute(dueEpochDay = d?.toEpochDay(), dueMinute = minute, title = title.takeIf { it.isNotBlank() }))
            },
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

/** Whether the top page is already [route] (same destination and arguments). */
private fun NavHostController.isShowing(route: Any): Boolean {
    val top = currentBackStackEntry ?: return false
    return when (route) {
        is TaskEditorRoute -> top.destination.hasRoute<TaskEditorRoute>() && top.toRoute<TaskEditorRoute>() == route
        is TaskDetailRoute -> top.destination.hasRoute<TaskDetailRoute>() && top.toRoute<TaskDetailRoute>() == route
        is EventDetailRoute -> top.destination.hasRoute<EventDetailRoute>() && top.toRoute<EventDetailRoute>() == route
        else -> false
    }
}

/**
 * Opens [route] from page [from] only while [from] is the top of the back stack. A second tap
 * after the first one already opened a page is ignored, so double taps can't stack two pages.
 * It doesn't wait for [from]'s own opening animation: a tap is acted on at once.
 */
private fun NavHostController.openFrom(from: NavBackStackEntry, route: Any) {
    if (currentBackStackEntry?.id == from.id) navigate(route)
}

/**
 * Back from a user tap (app-bar arrow, Cancel): pops [from] only while it is the top of the back
 * stack. A second quick tap lands while the first pop is animating, on the page that is leaving
 * (it is drawn on top and takes all touches), and is ignored instead of popping the page under it.
 * Like [openFrom] it works during the page's opening animation too (it used to wait for RESUMED,
 * so Back did nothing for the first 300 ms after a page opened).
 */
private fun NavHostController.backFrom(from: NavBackStackEntry) {
    closeFrom(from)
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
        exitTransition = { with(NavMotion) { exit(m) } },
        popEnterTransition = { with(NavMotion) { popEnter() } },
        popExitTransition = { with(NavMotion) { popExit(m, rtl, density) } },
        predictivePopEnterTransition = { NavMotion.predictivePopEnter() },
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
        composable<TaskDetailRoute> { e ->
            val r = e.toRoute<TaskDetailRoute>()
            Page {
                app.daysemble.ui.task.TaskDetailRoute(
                    r.taskId, onEdit = { id -> nav.openFrom(e, TaskEditorRoute(taskId = id)) },
                    onDone = { nav.closeFrom(e) }, onClose = { nav.backFrom(e) },
                )
            }
        }
        composable<TaskEditorRoute> { e ->
            val r = e.toRoute<TaskEditorRoute>()
            Page {
                TaskEditorScreenRoute(
                    r.taskId, r.parentId, r.dueEpochDay?.let(LocalDate::ofEpochDay), initialDueMinute = r.dueMinute, initialTitle = r.title,
                    onDone = { nav.closeFrom(e) }, onClose = { nav.backFrom(e) },
                )
            }
        }
        composable<EventDetailRoute> { e ->
            val r = e.toRoute<EventDetailRoute>()
            Page {
                EventDetailScreenRoute(
                    r.eventId, r.occurrenceKey, onEdit = { id, key -> nav.openFrom(e, EventEditorRoute(eventId = id, occurrenceKey = key)) },
                    onDone = { nav.closeFrom(e) }, onClose = { nav.backFrom(e) },
                )
            }
        }
        composable<EventEditorRoute> { e ->
            val r = e.toRoute<EventEditorRoute>()
            Page {
                EventEditorScreenRoute(
                    r.eventId, r.dateEpochDay?.let(LocalDate::ofEpochDay), r.startMinute, r.title, occurrenceKey = r.occurrenceKey,
                    onDone = { nav.closeFrom(e) }, onClose = { nav.backFrom(e) },
                )
            }
        }
        composable<SearchRoute> { e ->
            Page {
                SearchScreenRoute(
                    onBack = { nav.backFrom(e) },
                    onOpenEvent = { id, key -> nav.openFrom(e, EventDetailRoute(id, key)) },
                    onOpenTask = { id -> nav.openFrom(e, TaskDetailRoute(id)) },
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
                    SettingsPage.SCHEDULE -> app.daysemble.ui.settings.ScheduleSettingsRoute(onBack = back)
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
 * position and in-progress UI state); switching only changes which one is drawn on top. Tabs not
 * opened yet are composed in the background, one at a time, when the main thread is idle (never
 * during a scroll, a transition or the splash), so even the first switch has nothing to build.
 *
 * Switching: the new tab is placed on top and fades in over the old one, which stays fully drawn
 * underneath until it is covered and is then hidden. So no frame is ever blank. (The previous
 * fade-through faded the old tab out before the new one came in, which left one or two nearly
 * empty frames on every switch, seen as a flash.) The new tab comes in on a fast-starting curve
 * with a small shift in tab order. Alpha and shift are read only in the draw phase, so nothing
 * recomposes per frame; a switch that interrupts another simply continues from the current values.
 */
@Composable
private fun TabHost(current: Tab, modifier: Modifier, content: @Composable (Tab) -> Unit) {
    val m = DsTheme.motion
    val c = DsTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val holder = rememberSaveableStateHolder()
    var visited by rememberSaveable { mutableStateOf(listOf(current.name)) }
    androidx.compose.runtime.SideEffect { if (current.name !in visited) visited = visited + current.name }
    LaunchedEffect(Unit) {
        Tab.entries.forEach { t ->
            if (t.name in visited) return@forEach
            // Wait for a moment with nothing else to do (no frame, input or animation queued).
            delay(300)
            awaitMainThreadIdle(timeoutMs = 3_000)
            if (t.name !in visited) visited = visited + t.name
        }
    }
    val composed = Tab.entries.filter { it.name in visited || it == current }
    // Tabs the user has looked at. A tab composed in the background but never shown keeps loading
    // (so its first appearance has data); once seen, it pauses whenever it is hidden.
    var seen by rememberSaveable { mutableStateOf(listOf(current.name)) }
    androidx.compose.runtime.SideEffect { if (current.name !in seen) seen = seen + current.name }

    val alpha = remember { Tab.entries.associateWith { Animatable(if (it == current) 1f else 0f) } }
    // Tabs being drawn, bottom to top. Usually just the current one; during a switch the one being
    // covered is under it.
    var stack by remember { mutableStateOf(listOf(current)) }
    var direction by remember { mutableIntStateOf(0) }
    LaunchedEffect(current) {
        if (stack.last() == current) return@LaunchedEffect
        direction = Integer.signum(current.ordinal - stack.last().ordinal)
        stack = stack.filter { it != current } + current
        val incoming = alpha.getValue(current)
        if (m.reduced) incoming.snapTo(1f) else incoming.animateTo(1f, m.tabEnter())
        // Covered completely: the others are hidden (and unplaced) until they are shown again.
        Tab.entries.forEach { t -> if (t != current) alpha.getValue(t).snapTo(0f) }
        stack = listOf(current)
    }
    val currentTab by rememberUpdatedState(current)
    val shift = with(LocalDensity.current) { m.tabShift.toPx() }
    // A hidden tab stays composed, so it must not keep keyboard focus (typing would go on editing
    // it, e.g. the Tasks search) or take focus while hidden. Focus held by the tab you leave is
    // cleared as you switch; hidden tabs refuse focus moving into them.
    val focusManager = LocalFocusManager.current
    val tabHasFocus = remember { mutableStateMapOf<Tab, Boolean>() }
    LaunchedEffect(current) {
        if (Tab.entries.any { it != current && tabHasFocus[it] == true }) focusManager.clearFocus(force = true)
    }

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
                            // Opaque, so the tab being covered never shows through the finished one.
                            .background(c.background)
                            .then(if (t != current) Modifier.clearAndSetSemantics { } else Modifier)
                            .onFocusChanged { tabHasFocus[t] = it.hasFocus }
                            .focusProperties { onEnter = { if (t != currentTab) cancelFocusChange() } }
                            .focusGroup(),
                    ) {
                        app.daysemble.ui.components.VisibilityLifecycle(visible = t == current || t in stack || t.name !in seen) {
                            holder.SaveableStateProvider(t.name) { content(t) }
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val child = Constraints.fixed(w, h)
        // Every composed tab is measured (so a hidden tab is laid out and ready), only drawn ones are placed.
        val placeables = measurables.associate { (it.layoutId as Tab) to it.measure(child) }
        layout(w, h) {
            // The current tab is always drawn, on top (also in the frame before its switch starts).
            val order = stack.filter { it != currentTab } + currentTab
            order.forEachIndexed { z, t -> placeables[t]?.place(0, 0, zIndex = z.toFloat()) }
        }
    }
}

/**
 * Suspends until the main thread has nothing queued (no frame, input or animation pending), or
 * [timeoutMs] passes. Used to do deferrable composition work where it can't delay a frame.
 */
private suspend fun awaitMainThreadIdle(timeoutMs: Long) {
    kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val queue = android.os.Looper.getMainLooper().queue
            val handler = android.os.MessageQueue.IdleHandler { if (cont.isActive) cont.resumeWith(Result.success(Unit)); false }
            queue.addIdleHandler(handler)
            cont.invokeOnCancellation { queue.removeIdleHandler(handler) }
        }
    }
}

// ---- Bottom bar -----------------------------------------------------------------------------

/** How far above the bottom bar's row the scroll-edge fade begins. */
private val ScrollEdgeFade = 28.dp

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    Row(
        modifier
            // Scroll edge: content dissolves into the background as it reaches the floating bar,
            // rather than showing sliced cards and dividers around and under the pills. It starts
            // a little above the bar and is opaque from the pills' middle down. One static draw,
            // no layer, no blur.
            .drawBehind {
                val above = ScrollEdgeFade.toPx()
                drawRect(
                    Brush.verticalGradient(
                        0f to c.background.copy(alpha = 0f),
                        0.45f to c.background.copy(alpha = 0.9f),
                        1f to c.background,
                        startY = -above,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, -above),
                    size = Size(size.width, size.height + above),
                )
            }
            .navigationBarsPadding()
            .padding(horizontal = Space.gutter, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        // One selection pill that glides between tabs. The labels are drawn twice, each copy clipped to
        // one side of the pill's edge: dark outside the pill, light inside it. So a label's colour is
        // always the one that reads on what's under it, at every frame of the glide. (Animating each
        // label's own colour instead turned the target white on the white bar before the pill got
        // there, and left the tab the pill passed over dark on black.) Read in the draw phase only.
        val index by animateFloatAsState(current.ordinal.toFloat(), m.indicator(), label = "tabIndicator")
        val pill = c.inverse
        val pillPath = remember { Path() }
        fun DrawScope.pillPath(): Path {
            val w = size.width / Tab.entries.size
            val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w * (index + 1) else w * index
            pillPath.reset()
            pillPath.addRoundRect(RoundRect(Rect(Offset(x, 0f), Size(w, size.height)), CornerRadius(size.height / 2)))
            return pillPath
        }
        Box(
            Modifier
                .weight(1f, fill = false)
                .heightIn(min = Sizes.bottomBar - 8.dp)
                .widthIn(max = 360.dp)
                .shadow(10.dp, Radii.pill, ambientColor = c.scrim, spotColor = c.scrim)
                .clip(Radii.pill)
                .background(c.surfaceRaised)
                .border(Sizes.hairline, c.outline, Radii.pill)
                .padding(4.dp)
                .drawBehind { drawPath(pillPath(), pill) },
        ) {
            Row(
                Modifier
                    .selectableGroup()
                    .drawWithContent { clipPath(pillPath(), ClipOp.Difference) { this@drawWithContent.drawContent() } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { t ->
                    TabItem(t, t == current, { onSelect(t) }, c.textSecondary, Modifier.weight(1f))
                }
            }
            // The light copy: drawing only (no input, not read out).
            Row(
                Modifier
                    .matchParentSize()
                    .clearAndSetSemantics { }
                    .drawWithContent { clipPath(pillPath()) { this@drawWithContent.drawContent() } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { t -> TabLabel(t, c.onInverse, Modifier.weight(1f)) }
            }
        }
        DsFab(
            Icons.Rounded.Add, stringResource(R.string.cd_add), onAdd,
            Modifier.requiredSize(Sizes.fab).shadow(10.dp, Radii.pill, ambientColor = c.scrim, spotColor = c.scrim),
        )
    }
}

@Composable
private fun TabItem(tab: Tab, selected: Boolean, onClick: () -> Unit, color: Color, modifier: Modifier = Modifier) {
    TabLabel(
        tab, color,
        modifier.clip(Radii.pill).selectable(selected = selected, role = Role.Tab, onClick = onClick),
    )
}

/** Icon and label of a tab; they never move or resize. */
@Composable
private fun TabLabel(tab: Tab, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .heightIn(min = Sizes.bottomBar - 16.dp)
            .widthIn(min = 64.dp)
            .padding(horizontal = Space.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(tab.icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(stringResource(tab.label), style = DsTheme.type.caps, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
