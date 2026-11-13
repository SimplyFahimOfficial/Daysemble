package app.daysemble.ui

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import app.daysemble.ui.components.LocalNewItem
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
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.IntOffset
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import app.daysemble.data.prefs.AppSettings
import app.daysemble.ui.add.QuickAddSheet
import app.daysemble.ui.brand.BrandNameplate
import app.daysemble.ui.calendar.CalendarRoute as CalendarScreenRoute
import app.daysemble.ui.components.DsFab
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.components.pressScale
import app.daysemble.ui.event.EventDetailRoute as EventDetailScreenRoute
import app.daysemble.ui.event.EventEditorRoute as EventEditorScreenRoute
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.rememberTimePrefs
import app.daysemble.ui.navigation.EventDetailRoute
import app.daysemble.ui.navigation.EventEditorRoute
import app.daysemble.ui.navigation.MainRoute
import app.daysemble.ui.navigation.NavMotion
import app.daysemble.ui.navigation.SettingsPageRoute
import app.daysemble.ui.navigation.SettingsRoute
import app.daysemble.ui.navigation.TaskDetailRoute
import app.daysemble.ui.navigation.TaskEditorRoute
import app.daysemble.ui.search.SearchResults
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
import app.daysemble.ui.theme.Elevation
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space
import app.daysemble.ui.today.TodayRoute as TodayScreenRoute
import androidx.compose.runtime.Stable
import app.daysemble.ui.theme.DsMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
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

/**
 * Call after a one-off task was completed from a list that it then leaves (the Tasks tab). Shows
 * "Task completed · Undo" from the root scope, like [LocalTaskDeleted].
 */
val LocalTaskDone = staticCompositionLocalOf<(String) -> Unit> { {} }

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
    shortcutRequests: kotlinx.coroutines.flow.StateFlow<app.daysemble.AppShortcut?> = kotlinx.coroutines.flow.MutableStateFlow(null),
    onShortcutHandled: () -> Unit = {},
) {
    val zone = rememberSystemZone()
    val prefs = rememberTimePrefs(settings.clockFormat, settings.firstDayOfWeek, zone)
    val snackbar = remember { SnackbarHostState() }
    val nav = rememberNavController()

    LaunchedEffect(Unit) { container.defaults() }
    app.daysemble.ui.theme.SyncSystemNightMode(settings.themeMode)
    LaunchedEffect(prefs.zone, prefs.firstDayOfWeek) {
        container.timeContext.value = app.daysemble.core.time.TimeContext(prefs.zone, prefs.firstDayOfWeek)
    }
    val open by openRequests.collectAsStateWithLifecycle()
    val shortcut by shortcutRequests.collectAsStateWithLifecycle()

    // The phone's calendars are mirrored into Daysemble. Refresh whenever the app comes to the front
    // (also after the permission dialog or a visit to Android settings), and while it is on screen
    // whenever the Calendar Provider reports a change.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { container.deviceCalendars.requestRefresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { container.deviceCalendars.startObserving() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { container.deviceCalendars.stopObserving() }

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
    val completedMsg = stringResource(R.string.task_completed)
    val onTaskDone: (String) -> Unit = remember(container, completedMsg, undoLabel) {
        { id ->
            rootScope.launch {
                val res = snackbar.showSnackbar(completedMsg, undoLabel, duration = SnackbarDuration.Short)
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
        LocalTaskDone provides onTaskDone,
        LocalAppSettings provides settings,
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
                app.daysemble.ui.onboarding.OnboardingScreen(initialName = settings.displayName) { name ->
                    scope.launch { container.settings.completeOnboarding(name) }
                }
            } else {
                AppContent(
                    nav, container, settings, open, onOpenHandled, snackbar,
                    shortcut, onShortcutHandled,
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
    open: app.daysemble.OpenRequest?,
    onOpenHandled: () -> Unit,
    snackbar: SnackbarHostState,
    shortcut: app.daysemble.AppShortcut? = null,
    onShortcutHandled: () -> Unit = {},
) {
    val c = DsTheme.colors
    val motion = DsTheme.motion
    val entry by nav.currentBackStackEntryAsState()
    val onMain = entry?.destination.isMain()
    // The selected tab lives here (saved across rotation and process death), not in the back stack.
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    // The header search: the icon expands into a field and the results cover the tabs.
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var addDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var addKind by rememberSaveable { mutableStateOf(app.daysemble.ui.add.AddKind.TASK) }
    // What Quick Add last created: the + on Today opens on it (Tasks opens on Task, Calendar on Event).
    var lastKind by rememberSaveable { mutableStateOf(app.daysemble.ui.add.AddKind.TASK) }
    // The task or event just created, tinted briefly where it appears (see newItemHighlight).
    var newItem by remember { mutableStateOf<String?>(null) }
    val addSheet = remember { app.daysemble.ui.add.AddSheetState() }
    LaunchedEffect(showAdd) { if (showAdd) addSheet.open(motion) else if (addSheet.present) addSheet.close(motion) }
    LaunchedEffect(newItem) { if (newItem != null) { delay((motion.long * 4).toLong()); newItem = null } }
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
    CompositionLocalProvider(LocalNewItem provides newItem) {
    // Test tags are exposed as resource ids so the macrobenchmarks (UiAutomator) can find tabs and lists.
    Box(Modifier.fillMaxSize().background(c.background).semantics { testTagsAsResourceId = true }) {
        // Paused (lifecycle CREATED) while a page covers it completely: see VisibilityLifecycle.
        app.daysemble.ui.components.VisibilityLifecycle(visible = onMain || mainShown > 0) {
        MainScreen(
            tab = tab,
            onTab = { tab = it },
            backEnabled = onMain,
            displayName = settings.displayName,
            onSearch = { searching = true },
            searching = searching,
            searchQuery = searchQuery,
            onSearchQuery = { searchQuery = it },
            onCloseSearch = { searching = false; searchQuery = "" },
            searchResults = {
                SearchResults(
                    searchQuery,
                    onOpenEvent = { id, key -> nav.openFromMain(EventDetailRoute(id, key)) },
                    onOpenTask = { id -> nav.openFromMain(TaskDetailRoute(id)) },
                )
            },
            onSettings = { nav.openFromMain(SettingsRoute) },
            onAdd = {
                addDate = when (tab) { Tab.TODAY -> todayFocus; Tab.CALENDAR -> calendarFocus; Tab.TASKS -> null }
                addKind = when (tab) {
                    Tab.TASKS -> app.daysemble.ui.add.AddKind.TASK
                    Tab.CALENDAR -> app.daysemble.ui.add.AddKind.EVENT
                    Tab.TODAY -> lastKind
                }
                showAdd = true
            },
            addOpen = addSheet.present,
            addSheet = addSheet,
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
                Tab.TASKS -> TasksScreenRoute(
                    bottomPadding, onOpenTask = openTask,
                    onAdd = { addDate = null; addKind = app.daysemble.ui.add.AddKind.TASK; showAdd = true },
                )
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
        // Launcher shortcuts. On the main screen, New task / New event open Quick Add (the same sheet
        // as the + button) for today. Over another page they open the full editor on top instead,
        // so nothing the user has open, such as an editor with unsaved changes, is closed for it.
        LaunchedEffect(shortcut) {
            val s = shortcut ?: return@LaunchedEffect
            when (s) {
                app.daysemble.AppShortcut.SEARCH -> {
                    // Back to the main screen first if a page is open on top of it.
                    if (!nav.currentBackStackEntry?.destination.isMain()) nav.popBackStack(MainRoute, inclusive = false)
                    searching = true
                }
                app.daysemble.AppShortcut.NEW_TASK, app.daysemble.AppShortcut.NEW_EVENT -> {
                    val task = s == app.daysemble.AppShortcut.NEW_TASK
                    if (nav.currentBackStackEntry?.destination.isMain()) {
                        addDate = null
                        addKind = if (task) app.daysemble.ui.add.AddKind.TASK else app.daysemble.ui.add.AddKind.EVENT
                        showAdd = true
                    } else {
                        nav.navigate(if (task) TaskEditorRoute() else EventEditorRoute(dateEpochDay = container.clock.today().toEpochDay()))
                    }
                }
            }
            onShortcutHandled()
        }
        // Quick Add: the FAB's container grows into the sheet, and shrinks back into it on close.
        if (addSheet.present) {
            QuickAddSheet(
                sheetState = addSheet,
                open = showAdd,
                container = container,
                initialDate = addDate?.let(LocalDate::ofEpochDay),
                initialKind = addKind,
                onDismiss = { showAdd = false },
                onSaved = { id, kind -> newItem = id; lastKind = kind },
                onMoreEventOptions = { pre ->
                    nav.openFromMain(EventEditorRoute(dateEpochDay = pre.date.toEpochDay(), startMinute = pre.startMinute, title = pre.title))
                },
                onMoreTaskOptions = { title, d, minute ->
                    nav.openFromMain(TaskEditorRoute(dueEpochDay = d?.toEpochDay(), dueMinute = minute, title = title.takeIf { it.isNotBlank() }))
                },
            )
        }
        // Moves with the bottom bar instead of jumping when the bar shows or hides. Read in placement
        // only: as a padding it was read in composition, so the content around it recomposed on
        // every frame of each page open and close.
        val snackbarLift = animateDpAsState(
            if (onMain) Sizes.bottomBar else 0.dp,
            if (motion.reduced) snap() else motion.standard(motion.long), label = "snackbarLift",
        )
        SnackbarHost(
            snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = Space.l)
                .offset { IntOffset(0, -snackbarLift.value.roundToPx()) },
        ) { data ->
            Snackbar(
                data, shape = Radii.control,
                containerColor = c.inverse, contentColor = c.onInverse, actionColor = c.inverseAccent,
            )
        }
    }
    }
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
private fun NavHostController.backFrom(from: NavBackStackEntry, guard: PageGuard) {
    closeFrom(from, guard)
}

/**
 * A screen closing itself (after a save or delete, or because its item is gone). Not tied to
 * RESUMED, so it also works while the page is paused or still entering; it pops only if [from] is
 * still on top, so it can't close a different page. The main screen is never popped.
 */
private fun NavHostController.closeFrom(from: NavBackStackEntry, guard: PageGuard) {
    // A page is still animating out: a second tap would land on the page being revealed under it.
    if (guard.leaving > 0) return
    val top = currentBackStackEntry ?: return
    if (top.id == from.id && !top.destination.hasRoute<MainRoute>()) popBackStack()
}

/**
 * How many pages of one NavHost are animating out because Back popped them. Pages slide sideways, so
 * the page being revealed is not fully covered by the one leaving; [closeFrom] ignores taps meanwhile.
 * It belongs to the NavHost (a new one for a new activity or window), not to the process.
 */
@Stable
private class PageGuard {
    var leaving = 0

    /** Ids of the pages Back has popped, which stay on screen until their exit transition ends. */
    var popped by mutableStateOf(emptySet<String>())
        private set
    private val stack = ArrayList<String>()

    /** Follows the top of the back stack: an entry seen again is a pop of everything above it, a new one is a push. */
    fun top(id: String) {
        val i = stack.indexOf(id)
        if (i < 0) {
            stack += id
            if (id in popped) popped = popped - id
        } else if (i < stack.lastIndex) {
            val gone = stack.subList(i + 1, stack.size)
            popped = popped + gone
            gone.clear()
        }
    }
}

/**
 * A page opened over the main screen. It takes all touches inside its bounds (pages draw an opaque
 * background, but a background alone doesn't stop a tap from reaching the main screen underneath),
 * and once it is leaving it ignores taps, so a quick tap after Back can't act on the page that is
 * fading out.
 *
 * "Leaving" means popped by Back, not merely covered: a page under a newly opened one also ends in
 * its exit state, and counting it made the new page's own Back and Cancel do nothing until the
 * opening transition had finished.
 */
@Composable
private fun AnimatedContentScope.Page(entry: NavBackStackEntry, guard: PageGuard, content: @Composable () -> Unit) {
    val leaving = transition.targetState == EnterExitState.PostExit && entry.id in guard.popped
    DisposableEffect(leaving) {
        if (leaving) guard.leaving++
        onDispose { if (leaving) guard.leaving-- }
    }
    Box(Modifier.fillMaxSize().then(if (leaving) Modifier.blockTouches() else Modifier)) { content() }
}

@Composable
private fun DaysembleNavHost(nav: NavHostController, mainLayer: GraphicsLayer, onMainShown: (Boolean) -> Unit) {
    val m = DsTheme.motion
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val guard = remember { PageGuard() }
    nav.currentBackStackEntryAsState().value?.let { guard.top(it.id) }

    NavHost(
        navController = nav,
        startDestination = MainRoute,
        enterTransition = { with(NavMotion) { enter(m, rtl, density) } },
        exitTransition = { with(NavMotion) { exit(m, rtl, density) } },
        popEnterTransition = { with(NavMotion) { popEnter(m, rtl, density) } },
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
            Page(e, guard) {
                app.daysemble.ui.task.TaskDetailRoute(
                    r.taskId, onEdit = { id -> nav.openFrom(e, TaskEditorRoute(taskId = id)) },
                    onDone = { nav.closeFrom(e, guard) }, onClose = { nav.backFrom(e, guard) },
                )
            }
        }
        composable<TaskEditorRoute> { e ->
            val r = e.toRoute<TaskEditorRoute>()
            Page(e, guard) {
                TaskEditorScreenRoute(
                    r.taskId, r.parentId, r.dueEpochDay?.let(LocalDate::ofEpochDay), initialDueMinute = r.dueMinute, initialTitle = r.title,
                    onDone = { nav.closeFrom(e, guard) }, onClose = { nav.backFrom(e, guard) },
                )
            }
        }
        composable<EventDetailRoute> { e ->
            val r = e.toRoute<EventDetailRoute>()
            Page(e, guard) {
                EventDetailScreenRoute(
                    r.eventId, r.occurrenceKey, onEdit = { id, key -> nav.openFrom(e, EventEditorRoute(eventId = id, occurrenceKey = key)) },
                    onDone = { nav.closeFrom(e, guard) }, onClose = { nav.backFrom(e, guard) },
                )
            }
        }
        composable<EventEditorRoute> { e ->
            val r = e.toRoute<EventEditorRoute>()
            Page(e, guard) {
                EventEditorScreenRoute(
                    r.eventId, r.dateEpochDay?.let(LocalDate::ofEpochDay), r.startMinute, r.title, occurrenceKey = r.occurrenceKey,
                    onDone = { nav.closeFrom(e, guard) }, onClose = { nav.backFrom(e, guard) },
                )
            }
        }
        composable<SettingsRoute> { e ->
            Page(e, guard) { SettingsHomeRoute(onBack = { nav.backFrom(e, guard) }, onOpen = { nav.openFrom(e, SettingsPageRoute(it.name)) }) }
        }
        composable<SettingsPageRoute> { e ->
            val back: () -> Unit = { nav.backFrom(e, guard) }
            Page(e, guard) {
                when (runCatching { SettingsPage.valueOf(e.toRoute<SettingsPageRoute>().page) }.getOrNull()) {
                    SettingsPage.APPEARANCE -> AppearanceRoute(onBack = back)
                    SettingsPage.LISTS_CALENDARS -> app.daysemble.ui.settings.ListsCalendarsRoute(onBack = back)
                    SettingsPage.NOTIFICATIONS -> app.daysemble.ui.settings.NotificationSettingsRoute(onBack = back)
                    SettingsPage.DATE_TIME -> DateTimeSettingsRoute(onBack = back)
                    SettingsPage.TASKS -> TaskSettingsRoute(onBack = back)
                    SettingsPage.DATA -> DataSettingsRoute(onBack = back)
                    SettingsPage.ABOUT -> AboutRoute(onBack = back)
                    SettingsPage.HELP -> app.daysemble.ui.settings.HelpRoute(
                        onBack = back,
                        onOpenSupport = { nav.openFrom(e, SettingsPageRoute(SettingsPage.SUPPORT.name)) },
                    )
                    SettingsPage.SUPPORT -> app.daysemble.ui.settings.SupportRoute(onBack = back)
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
    onSearch: () -> Unit,
    searching: Boolean,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onCloseSearch: () -> Unit,
    searchResults: @Composable () -> Unit,
    onSettings: () -> Unit,
    onAdd: () -> Unit,
    addOpen: Boolean,
    addSheet: app.daysemble.ui.add.AddSheetState,
    modifier: Modifier = Modifier,
    content: @Composable (Tab) -> Unit,
) {
    BackHandler(enabled = backEnabled && tab != Tab.TODAY) { onTab(Tab.TODAY) }
    // Registered after the one above, so it wins: Back closes the search first.
    BackHandler(enabled = backEnabled && searching, onBack = onCloseSearch)
    // The FAB scales away while the content scrolls down and returns when it scrolls up, on another
    // tab, or when Quick Add opens. The connection only watches; it never consumes a scroll.
    var fabShown by remember { mutableStateOf(true) }
    LaunchedEffect(tab, addOpen) { fabShown = true }
    val fabWatcher = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    if (available.y < -FAB_SCROLL_SLOP) fabShown = false else if (available.y > FAB_SCROLL_SLOP) fabShown = true
                }
                return Offset.Zero
            }
        }
    }
    Box(modifier.background(DsTheme.colors.background)) {
        Column(Modifier.fillMaxSize()) {
            MainHeader(
                displayName, onSearch, onSettings,
                searching = searching, query = searchQuery, onQuery = onSearchQuery, onCloseSearch = onCloseSearch,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TabHost(
                    tab,
                    Modifier.fillMaxSize().nestedScroll(fabWatcher).then(if (searching) Modifier.clearAndSetSemantics { } else Modifier),
                    content,
                )
                // The results cover the tabs, which stay composed (and keep their state) underneath.
                val m = DsTheme.motion
                val reveal by animateFloatAsState(if (searching) 1f else 0f, m.spring(), label = "searchReveal")
                // Composition only sees whether the results are on screen, not every frame of the fade.
                val resultsShown by remember { derivedStateOf { reveal > 0.001f } }
                if (resultsShown || searching) {
                    val shift = with(LocalDensity.current) { m.contentShift.toPx() }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = reveal.coerceIn(0f, 1f); translationY = (1f - reveal) * shift }
                            .then(if (searching) Modifier else Modifier.clearAndSetSemantics { }),
                    ) { searchResults() }
                }
            }
        }
        BottomBar(
            current = tab,
            onSelect = { t -> if (t != tab) onTab(t) },
            onAdd = onAdd,
            addOpen = addOpen,
            addSheet = addSheet,
            fabShown = fabShown,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The identity bar shared by Today, Calendar and Tasks: the logo with the user's name (or the app
 * name), and Search and Settings. The name takes the free
 * space and ends with "…"; the actions always keep their size.
 */
@Composable
internal fun MainHeader(
    displayName: String?,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    searching: Boolean = false,
    query: String = "",
    onQuery: (String) -> Unit = {},
    onCloseSearch: () -> Unit = {},
) {
    val m = DsTheme.motion
    // 0 = the search icon, 1 = the open field: the field grows out of the icon toward the start.
    val open by animateFloatAsState(if (searching) 1f else 0f, m.spring(), label = "searchOpen")
    val fieldShown by remember { derivedStateOf { open > 0.001f } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) runCatching { focus.requestFocus() } }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 60.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val settingsSlot = Sizes.touch + Space.xs
        // The field spans from the gutter to the settings button.
        val fullWidth = maxWidth - Space.gutter - settingsSlot
        Row(
            Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).graphicsLayer { alpha = (1f - open * 2f).coerceIn(0f, 1f) }
                    .then(if (searching) Modifier.clearAndSetSemantics { } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandNameplate(displayName, Modifier.weight(1f).padding(end = Space.s))
            }
            // The icon holds the field's place; once open it is replaced by the field's own icon.
            DsIconButton(
                Icons.Rounded.Search, stringResource(R.string.cd_search), onSearch,
                Modifier.graphicsLayer { alpha = 1f - open }, enabled = !searching,
            )
            DsIconButton(Icons.Rounded.Settings, stringResource(R.string.cd_settings), onSettings)
        }
        if (fieldShown || searching) {
            HeaderSearchField(
                query, onQuery, onCloseSearch, focus,
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = settingsSlot)
                    // The width follows the animation in the layout phase: the header and the field
                    // are not recomposed per frame, only the field is measured again.
                    .layout { measurable, constraints ->
                        val w = androidx.compose.ui.unit.lerp(Sizes.touch, fullWidth, open.coerceIn(0f, 1f)).roundToPx()
                        val p = measurable.measure(Constraints(w, w, constraints.minHeight, constraints.maxHeight))
                        layout(w, p.height) { p.place(0, 0) }
                    }
                    .graphicsLayer { alpha = (open * 3f).coerceIn(0f, 1f) },
            )
        }
    }
}

/** The search field that grows out of the header's search icon: icon, text, and a button that closes the search. */
@Composable
private fun HeaderSearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit, focus: FocusRequester, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val hint = stringResource(R.string.search_hint)
    // The field's own text, updated synchronously on every keystroke: the app-level query comes back
    // a recomposition later, and typing quickly would otherwise lose characters. The field leaves the
    // composition when the search closes, so it always starts from the current query.
    var text by remember { mutableStateOf(query) }
    Row(
        modifier
            .heightIn(min = Sizes.touch)
            .clip(Radii.pill)
            .background(c.surfaceVariant)
            .padding(start = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.textSecondary, modifier = Modifier.size(Sizes.iconSmall))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(hint, style = DsTheme.type.body, color = c.textTertiary, maxLines = 1)
            BasicTextField(
                value = text, onValueChange = { text = it; onQuery(it) }, singleLine = true,
                textStyle = DsTheme.type.body.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = hint },
            )
        }
        DsIconButton(Icons.Rounded.Close, stringResource(R.string.cd_close_search), onClose, tint = c.textSecondary)
    }
}

/**
 * Hosts the three tabs. Each tab is composed once and then kept (with its ViewModel, scroll
 * position and in-progress UI state); switching only changes which one is drawn on top. Tabs not
 * opened yet are composed in the background, one at a time, when the main thread is idle (never
 * during the splash), so even the first switch has nothing to build.
 *
 * Switching is a cross-fade: the new tab fades in over the old one, which stays fully drawn
 * underneath, so no frame ever shows less content than the screens have at rest (see [TabFade]).
 * The fade is read only in the draw phase, so nothing recomposes per frame. With motion off the
 * new tab appears at once.
 */
@Composable
private fun TabHost(current: Tab, modifier: Modifier, content: @Composable (Tab) -> Unit) {
    val m = DsTheme.motion
    val c = DsTheme.colors
    val holder = rememberSaveableStateHolder()
    var visited by rememberSaveable { mutableStateOf(listOf(current.name)) }
    androidx.compose.runtime.SideEffect { if (current.name !in visited) visited = visited + current.name }
    LaunchedEffect(Unit) {
        Tab.entries.forEach { t ->
            if (t.name in visited) return@forEach
            // Compose it only in a gap between frames, after the start-up work has settled.
            delay(PRELOAD_DELAY_MS)
            awaitMainThreadIdle()
            if (t.name !in visited) visited = visited + t.name
        }
    }
    val composed = Tab.entries.filter { it.name in visited || it == current }
    // Tabs the user has looked at. A tab composed in the background but never shown keeps loading
    // (so its first appearance has data); once seen, it pauses whenever it is hidden.
    var seen by rememberSaveable { mutableStateOf(listOf(current.name)) }
    androidx.compose.runtime.SideEffect { if (current.name !in seen) seen = seen + current.name }

    val fade = remember { TabFade(current) }
    LaunchedEffect(current) { fade.show(current, m) }
    val currentTab by rememberUpdatedState(current)
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
                            .graphicsLayer { this.alpha = fade.alphaOf(t) }
                            // A tab that is only still fading out must not take taps meant for the one above or below it.
                            .then(if (t != current) Modifier.blockTouches() else Modifier)
                            // Opaque, so the tab being covered never shows through the finished one.
                            .background(c.background)
                            .then(if (t != current) Modifier.clearAndSetSemantics { } else Modifier)
                            .onFocusChanged { tabHasFocus[t] = it.hasFocus }
                            .focusProperties { onEnter = { if (t != currentTab) cancelFocusChange() } }
                            .focusGroup(),
                    ) {
                        app.daysemble.ui.components.VisibilityLifecycle(visible = t == current || t in fade.order || t.name !in seen) {
                            holder.SaveableStateProvider(t.name) {
                                CompositionLocalProvider(app.daysemble.ui.components.LocalTabVisible provides (t == current)) { content(t) }
                            }
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
            // The stack as it is drawn; the current tab, not in it for the frame before its fade starts, goes on top (still transparent).
            (fade.order + currentTab).distinct().forEachIndexed { z, t -> placeables[t]?.place(0, 0, zIndex = z.toFloat()) }
        }
    }
}

/** Wait before composing the tabs that aren't open yet, so the start-up frames are not delayed. */
private const val PRELOAD_DELAY_MS = 300L

/**
 * Suspends until the main thread has nothing queued right now (an idle gap between frames). It
 * waits as long as that takes: composing a whole tab when the thread is still busy would only
 * delay the frame in progress, and a tab that isn't preloaded simply composes when it is opened.
 */
private suspend fun awaitMainThreadIdle() {
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val queue = android.os.Looper.getMainLooper().queue
        val handler = android.os.MessageQueue.IdleHandler { if (cont.isActive) cont.resumeWith(Result.success(Unit)); false }
        queue.addIdleHandler(handler)
        cont.invokeOnCancellation { queue.removeIdleHandler(handler) }
    }
}

/**
 * The cross-fade between tabs, kept continuous however the taps come. The drawn tabs are a stack
 * ([order], bottom to top), each with its own alpha, the bottom one opaque, and every change is made
 * so that the picture on screen is the same in the frame before and after it:
 * - a new tab is pushed on top at alpha 0 and fades in; whatever was fading underneath stays exactly
 *   as it is (so a third tab tapped mid-fade does not jump to the tab that was arriving);
 * - going back to a tab already in the stack fades the tabs above it out (and it in, if it was
 *   partly shown), instead of reordering the stack, which would change the picture.
 * When the fades end the stack is just the chosen tab. The alphas are animatables written only by
 * [show], which a newer tap cancels, so each continues from its live value.
 */
@Stable
internal class TabFade(first: Tab) {
    var order by mutableStateOf(listOf(first))
        private set
    private val alphas = Tab.entries.associateWith { Animatable(if (it == first) 1f else 0f) }

    /** Alpha of [tab] now; one that is not drawn, or [current] before its fade has started, is transparent. */
    fun alphaOf(tab: Tab): Float = if (tab in order) alphas.getValue(tab).value else 0f

    suspend fun show(tab: Tab, m: DsMotion) {
        if (tab !in order) {
            alphas.getValue(tab).snapTo(0f)
            order = order + tab
        }
        val above = order.subList(order.indexOf(tab) + 1, order.size)
        if (m.enabled) {
            coroutineScope {
                above.forEach { launch { alphas.getValue(it).animateTo(0f, m.tabFade()) } }
                alphas.getValue(tab).animateTo(1f, m.tabFade())
            }
        } else {
            above.forEach { alphas.getValue(it).snapTo(0f) }
            alphas.getValue(tab).snapTo(1f)
        }
        order = listOf(tab)
        Tab.entries.forEach { if (it != tab) alphas.getValue(it).snapTo(0f) }
    }
}

/** Takes every touch inside the node without acting on it (a screen that is fading out or leaving). */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
}

// ---- Bottom bar -----------------------------------------------------------------------------

/** How far above the bottom bar's row the scroll-edge fade begins. */
private val ScrollEdgeFade = 28.dp

@Composable
private fun BottomBar(
    current: Tab, onSelect: (Tab) -> Unit, onAdd: () -> Unit, addOpen: Boolean, addSheet: app.daysemble.ui.add.AddSheetState,
    fabShown: Boolean, modifier: Modifier = Modifier,
) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    // Soft: with the scroll-edge fade the bar no longer needs a heavy shadow to stand off the list.
    val shadow = c.scrim.copy(alpha = c.scrim.alpha * Elevation.SHADOW_ALPHA)
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
        val presses = remember { Tab.entries.associateWith { MutableInteractionSource() } }
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
                .shadow(Elevation.floating, Radii.pill, ambientColor = shadow, spotColor = shadow)
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
                    TabItem(
                        t, t == current, { onSelect(t) }, c.textSecondary, presses.getValue(t), Modifier.weight(1f),
                        labelAlpha = { TAB_REST_ALPHA + (1f - TAB_REST_ALPHA) * (1f - kotlin.math.abs(index - t.ordinal)).coerceIn(0f, 1f) },
                    )
                }
            }
            // The light copy: drawing only (no input, not read out). It shares each tab's press, so
            // both copies of a pressed label shrink together.
            Row(
                Modifier
                    .matchParentSize()
                    .clearAndSetSemantics { }
                    .drawWithContent { clipPath(pillPath()) { this@drawWithContent.drawContent() } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { t ->
                    TabLabel(t, c.onInverse, Modifier.weight(1f).pressScale(presses.getValue(t)))
                }
            }
        }
        AddFab(addOpen, addSheet, fabShown, onAdd, shadow)
    }
}

/**
 * The + button. While Quick Add is present the sheet's container is drawn over it (it grows from
 * these bounds and shrinks back to them), so the button itself is invisible and does not respond.
 */
@Composable
private fun AddFab(open: Boolean, sheet: app.daysemble.ui.add.AddSheetState, fabShown: Boolean, onAdd: () -> Unit, shadow: Color) {
    val m = DsTheme.motion
    val scale by animateFloatAsState(if (fabShown) 1f else 0f, m.snappy(), label = "fabScale")
    DsFab(
        Icons.Rounded.Add, stringResource(R.string.cd_add), onAdd,
        Modifier
            .requiredSize(Sizes.fab)
            .onGloballyPositioned { sheet.fab = it.boundsInRoot() }
            .graphicsLayer {
                val k = if (open) 1f else scale
                scaleX = k; scaleY = k
                alpha = if (open) 0f else 1f
            }
            .then(if (fabShown && !open) Modifier else Modifier.clearAndSetSemantics { })
            .shadow(Elevation.floating, Radii.pill, ambientColor = shadow, spotColor = shadow),
    )
}

@Composable
private fun TabItem(
    tab: Tab, selected: Boolean, onClick: () -> Unit, color: Color, press: MutableInteractionSource, modifier: Modifier = Modifier,
    labelAlpha: () -> Float = { 1f },
) {
    TabLabel(
        tab, color,
        modifier
            .pressScale(press)
            .clip(Radii.pill)
            .testTag("tab_" + tab.name.lowercase())
            .selectable(selected = selected, interactionSource = press, indication = LocalIndication.current, role = Role.Tab, onClick = onClick),
        labelAlpha,
    )
}

/** The scroll distance (px) that hides or shows the FAB. */
private const val FAB_SCROLL_SLOP = 2f

/** The opacity of a tab label that is not selected. */
private const val TAB_REST_ALPHA = 0.72f

/** Icon and label of a tab; they never move or resize. */
@Composable
private fun TabLabel(tab: Tab, color: Color, modifier: Modifier = Modifier, labelAlpha: () -> Float = { 1f }) {
    Column(
        modifier
            .heightIn(min = Sizes.bottomBar - 16.dp)
            .widthIn(min = 64.dp)
            .padding(horizontal = Space.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(tab.icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(
            stringResource(tab.label), style = DsTheme.type.caps, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer { alpha = labelAlpha() },
        )
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
