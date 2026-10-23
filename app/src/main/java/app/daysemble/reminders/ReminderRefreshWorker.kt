package app.daysemble.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.daysemble.DaysembleApp
import java.util.concurrent.TimeUnit

/**
 * Rolls the 8-day reminder horizon forward and purges expired local tombstones. Runs about twice
 * a day; no network or charging constraints, as it only touches the local database.
 */
class ReminderRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as DaysembleApp).container
        c.reminders.rescheduleAll()
        c.tasks.purgeExpiredTombstones()
        return Result.success()
    }

    companion object {
        private const val NAME = "reminder-refresh"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReminderRefreshWorker>(12, TimeUnit.HOURS).build(),
            )
        }
    }
}
