package app.daysemble

import android.content.Context
import app.daysemble.core.time.AppClock
import app.daysemble.core.time.SystemAppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.prefs.SettingsRepository
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.data.repo.EventRepository
import app.daysemble.data.repo.TaskRepository
import app.daysemble.data.db.OwnerType
import app.daysemble.data.sync.ChangeListener
import app.daysemble.data.sync.CompositeChangeListener
import app.daysemble.data.google.GoogleHttp
import app.daysemble.data.google.auth.AccountStore
import app.daysemble.data.google.auth.GoogleAuthManager
import app.daysemble.data.google.auth.accountDataStore
import app.daysemble.data.google.calendar.CalendarApi
import app.daysemble.data.google.calendar.CalendarSyncer
import app.daysemble.data.google.sync.SyncManager
import app.daysemble.data.google.sync.SyncScheduler
import app.daysemble.data.google.tasks.TasksApi
import app.daysemble.data.google.tasks.TasksSyncer
import app.daysemble.reminders.AndroidAlarmGateway
import app.daysemble.reminders.ReminderScheduler
import kotlinx.coroutines.launch
import app.daysemble.data.sync.Outbox
import app.daysemble.ui.brand.LauncherIconSwitcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph. Everything is created lazily and lives for the process lifetime.
 * Constructor injection everywhere else keeps classes testable without a DI framework.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Scope for work that must outlive a screen (e.g. writes triggered from notifications). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val clock: AppClock = SystemAppClock

    /** Current zone + first day of week; see [TimeContext]. */
    val timeContext = kotlinx.coroutines.flow.MutableStateFlow(app.daysemble.core.time.TimeContext())

    val settings: SettingsRepository by lazy { SettingsRepository(appContext.settingsDataStore) }

    val launcherIcon: LauncherIconSwitcher by lazy { LauncherIconSwitcher(appContext) }

    val database: DaysembleDatabase by lazy { DaysembleDatabase.build(appContext) }

    /** Reminders (M6) and sync (M9) register here to react to local changes. */
    val changes = CompositeChangeListener()

    private val outbox by lazy { Outbox(database.sync()) { clock.now().toEpochMilli() } }

    val tasks: TaskRepository by lazy { TaskRepository(database, outbox, clock, changes) }

    val events: EventRepository by lazy { EventRepository(database, outbox, clock, changes) }

    val reminders: ReminderScheduler by lazy {
        ReminderScheduler(database, settings, AndroidAlarmGateway(appContext), clock) { ReminderScheduler.notificationsAllowed(appContext) }
    }

    // ---- Google ----
    val accounts: AccountStore by lazy { AccountStore(appContext.accountDataStore) }
    val googleAuth: GoogleAuthManager by lazy { GoogleAuthManager(appContext) }
    private val googleHttp by lazy { GoogleHttp(googleAuth) }
    val syncScheduler: SyncScheduler by lazy { SyncScheduler(appContext) }
    val sync: SyncManager by lazy {
        SyncManager(
            database, accounts, googleAuth,
            CalendarSyncer(database, CalendarApi(googleHttp), clock),
            TasksSyncer(database, TasksApi(googleHttp), clock),
            clock,
            onLocalDataChanged = { reminders.rescheduleAll() },
        )
    }

    init {
        // Any local change re-plans that item's reminders (cancel + schedule) and, for Google items, schedules a push.
        changes.add(object : ChangeListener {
            override fun onTaskChanged(taskId: String) { appScope.launch { reminders.rescheduleFor(OwnerType.TASK, taskId) } }
            override fun onEventChanged(eventId: String) { appScope.launch { reminders.rescheduleFor(OwnerType.EVENT, eventId) } }
            override fun onPendingSync() = syncScheduler.requestSoon()
        })
    }

    /** Ids of the on-device calendar and task list, created on first launch. */
    suspend fun defaults(): Defaults = Defaults(
        calendarId = events.ensureDefaultCalendar(appContext.getString(R.string.default_calendar_name)),
        taskListId = tasks.ensureDefaultList(appContext.getString(R.string.default_task_list_name)),
    )

    /** Deletes all events, tasks and sync metadata on this device (preferences are kept). */
    suspend fun clearLocalData() {
        kotlinx.coroutines.withContext(Dispatchers.IO) { database.clearAllTables() }
        reminders.rescheduleAll() // cancels every registered alarm
        defaults()
    }

    /**
     * Where new tasks go: the "New tasks go to" setting if that list still exists and is synced (or
     * local), otherwise the on-device default list.
     */
    suspend fun newTaskListId(): String {
        val fallback = defaults().taskListId
        val chosen = settings.current().defaultTaskListId ?: return fallback
        val list = database.taskLists().get(chosen) ?: return fallback
        return if (list.kind == app.daysemble.data.db.SourceKind.LOCAL || list.isSyncEnabled) chosen else fallback
    }

    data class Defaults(val calendarId: String, val taskListId: String)
}
