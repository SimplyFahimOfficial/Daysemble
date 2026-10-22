package app.daysemble.data.google.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.daysemble.DaysembleApp
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as DaysembleApp).container
        // Re-run while local changes keep arriving during a sync (bounded).
        repeat(3) {
            when (c.sync.syncNow()) {
                SyncOutcome.RETRY_LATER -> return if (runAttemptCount < 8) Result.retry() else Result.failure()
                SyncOutcome.NEEDS_REAUTH, SyncOutcome.FAILED -> return Result.failure()
                SyncOutcome.NOT_CONNECTED -> return Result.success()
                SyncOutcome.OK -> if (c.database.sync().outbox().none { it.attempts < app.daysemble.data.google.calendar.CalendarSyncer.MAX_ATTEMPTS }) return Result.success()
            }
        }
        return Result.success()
    }
}

/** WorkManager scheduling for sync: hourly when connected, plus a debounced run after local edits. */
class SyncScheduler(private val context: Context) {
    private val wm get() = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enablePeriodic() {
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    /** Debounced push/pull. KEEP: edits made before it runs are included, since it reads the outbox at run time. */
    fun requestSoon(delaySeconds: Long = 5) {
        wm.enqueueUniqueWork(
            SOON, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    /** Manual "Sync now": runs immediately, replacing any pending delayed run. */
    fun requestSoonNow() {
        wm.enqueueUniqueWork(
            NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build(),
        )
    }

    fun cancelAll() {
        wm.cancelUniqueWork(NOW)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(SOON)
    }

    private companion object {
        const val PERIODIC = "google-sync-periodic"
        const val SOON = "google-sync-soon"
        const val NOW = "google-sync-now"
    }
}
