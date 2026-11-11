package app.daysemble

import android.content.Context
import app.daysemble.core.time.AppClock
import app.daysemble.core.time.SystemAppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.device.DeviceCalendarSync
import app.daysemble.data.prefs.SettingsRepository
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.data.repo.EventRepository
import app.daysemble.data.repo.TaskRepository
import app.daysemble.data.db.OwnerType
import app.daysemble.data.sync.ChangeListener
import app.daysemble.data.sync.CompositeChangeListener
import app.daysemble.reminders.AndroidAlarmGateway
import app.daysemble.reminders.ReminderScheduler
import kotlinx.coroutines.launch
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

    /** Reminders register here to react to local changes. */
    val changes = CompositeChangeListener()

    val tasks: TaskRepository by lazy { TaskRepository(database, clock, changes) }

    val events: EventRepository by lazy { EventRepository(database, clock, changes) }

    /** The phone's calendars, mirrored read-only into [events]' calendars. */
    val deviceCalendars: DeviceCalendarSync by lazy { DeviceCalendarSync(appContext, database, clock, appScope) }

    val taskSeries: app.daysemble.data.repo.TaskSeriesReader by lazy { app.daysemble.data.repo.TaskSeriesReader(database) }

    val sleep: app.daysemble.data.repo.SleepRepository by lazy { app.daysemble.data.repo.SleepRepository(database, clock) }

    val reminders: ReminderScheduler by lazy {
        ReminderScheduler(database, settings, AndroidAlarmGateway(appContext), clock) { ReminderScheduler.notificationsAllowed(appContext) }
    }

    val backup: app.daysemble.data.backup.BackupManager by lazy {
        app.daysemble.data.backup.BackupManager(database, clock) { reminders.rescheduleAll() }
    }

    init {
        // Any local change re-plans that item's reminders (cancel + schedule).
        changes.add(object : ChangeListener {
            override fun onTaskChanged(taskId: String) { appScope.launch { reminders.rescheduleFor(OwnerType.TASK, taskId) } }
            override fun onEventChanged(eventId: String) { appScope.launch { reminders.rescheduleFor(OwnerType.EVENT, eventId) } }
        })
    }

    /** Ids of the on-device calendar and task list, created on first launch. */
    suspend fun defaults(): Defaults = Defaults(
        calendarId = events.ensureDefaultCalendar(appContext.getString(R.string.default_calendar_name)),
        taskListId = tasks.ensureDefaultList(appContext.getString(R.string.default_task_list_name)),
    )

    /** Deletes all events and tasks on this device (preferences are kept). */
    suspend fun clearLocalData() {
        kotlinx.coroutines.withContext(Dispatchers.IO) { database.clearAllTables() }
        reminders.rescheduleAll() // cancels every registered alarm
        defaults()
    }

    /**
     * Where new tasks go: the "New tasks go to" setting if that list still exists, otherwise the
     * default list.
     */
    suspend fun newTaskListId(): String {
        val fallback = defaults().taskListId
        val chosen = settings.current().defaultTaskListId ?: return fallback
        return if (database.taskLists().get(chosen) != null) chosen else fallback
    }

    data class Defaults(val calendarId: String, val taskListId: String)
}
