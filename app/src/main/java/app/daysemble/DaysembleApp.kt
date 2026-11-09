package app.daysemble

import android.app.Application
import androidx.work.Configuration
import app.daysemble.reminders.Notifications
import app.daysemble.reminders.ReminderRefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DaysembleApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (BuildConfig.BENCHMARK_DATA) container.appScope.launch(Dispatchers.IO) { app.daysemble.data.BenchmarkSeed.seed(container) }
        Notifications.createChannels(this)
        reconcileLauncherIcon()
        ReminderRefreshWorker.schedule(this)
        // Re-plan once per process start: covers force-stops, which clear all alarms.
        container.appScope.launch(Dispatchers.IO) { container.reminders.rescheduleAll() }
        // Sync on foreground when the last success is older than 15 minutes.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                container.appScope.launch(Dispatchers.IO) {
                    if (!container.accounts.current().isConnected) return@launch
                    container.syncScheduler.enablePeriodic()
                    val last = container.database.sync().state(app.daysemble.data.db.SyncService.CALENDAR)?.lastSuccessAt ?: 0
                    if (container.clock.now().toEpochMilli() - last > 15 * 60_000) container.syncScheduler.requestSoon(0)
                }
            }
        })
    }

    /**
     * The launcher alias state lives in PackageManager, the preference in DataStore. They can
     * diverge (e.g. settings restored from backup on a new device), so align them on start.
     */
    private fun reconcileLauncherIcon() {
        container.appScope.launch(Dispatchers.IO) {
            val wanted = container.settings.current().logo
            if (container.launcherIcon.current() != wanted) container.launcherIcon.apply(wanted)
        }
    }
}
