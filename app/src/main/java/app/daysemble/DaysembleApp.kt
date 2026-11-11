package app.daysemble

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
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
        // shortcut: drop this once no install can still hold work queued by the removed Google sync.
        listOf("google-sync-periodic", "google-sync-soon", "google-sync-now").forEach { WorkManager.getInstance(this).cancelUniqueWork(it) }
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
