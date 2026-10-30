package app.daysemble.data.google.sync

import androidx.room.withTransaction
import app.daysemble.core.time.AppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.SyncService
import app.daysemble.data.db.SyncStateEntity
import app.daysemble.data.google.ApiError
import app.daysemble.data.google.auth.AccountStore
import app.daysemble.data.google.auth.GoogleAccountState
import app.daysemble.data.google.auth.GoogleAuthManager
import app.daysemble.data.google.calendar.CalendarSyncer
import app.daysemble.data.google.tasks.TasksSyncer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncOutcome { OK, NOT_CONNECTED, RETRY_LATER, NEEDS_REAUTH, FAILED }

data class ServiceStatus(val enabled: Boolean, val lastSuccessAt: Long?, val lastError: String?, val needsReauth: Boolean)

data class SyncStatus(
    val account: GoogleAccountState,
    val running: Boolean,
    val calendar: ServiceStatus,
    val tasks: ServiceStatus,
    val pendingChanges: Int,
    /** Changes Google refused repeatedly; waiting for "Retry". */
    val parkedChanges: Int = 0,
) {
    val needsReauth get() = calendar.needsReauth || tasks.needsReauth
    val lastSuccessAt get() = listOfNotNull(calendar.lastSuccessAt.takeIf { calendar.enabled }, tasks.lastSuccessAt.takeIf { tasks.enabled }).minOrNull()
    val hasError get() = (calendar.enabled && calendar.lastError != null) || (tasks.enabled && tasks.lastError != null)
}

/**
 * Orchestrates Google sync: one run at a time, per-service status bookkeeping, and the
 * connect/disconnect lifecycle. Network work happens only through the syncers.
 */
class SyncManager(
    private val db: DaysembleDatabase,
    private val accounts: AccountStore,
    private val auth: GoogleAuthManager,
    private val calendar: CalendarSyncer,
    private val tasks: TasksSyncer,
    private val clock: AppClock,
    private val onLocalDataChanged: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private val running = MutableStateFlow(false)

    val status: Flow<SyncStatus> = combine(
        accounts.state, db.sync().observeStates(), running, db.sync().observeOutboxCount(),
        db.sync().observeParkedCount(app.daysemble.data.google.calendar.CalendarSyncer.MAX_ATTEMPTS),
    ) { acc, states, run, pending, parked ->
        fun s(svc: SyncService, enabled: Boolean) = states.firstOrNull { it.service == svc }.let {
            ServiceStatus(enabled, it?.lastSuccessAt, it?.lastError, it?.needsReauth == true)
        }
        SyncStatus(acc, run, s(SyncService.CALENDAR, acc.calendarEnabled), s(SyncService.TASKS, acc.tasksEnabled), pending, parked)
    }

    suspend fun syncNow(): SyncOutcome = mutex.withLock {
        val acc = accounts.current()
        val email = acc.email ?: return SyncOutcome.NOT_CONNECTED
        auth.accountEmail = email
        running.value = true
        try {
            var outcome = SyncOutcome.OK
            if (acc.calendarEnabled) outcome = worst(outcome, runService(SyncService.CALENDAR) { calendar.sync(email).failedOps + parked(OwnerType.EVENT) })
            if (acc.tasksEnabled) outcome = worst(outcome, runService(SyncService.TASKS) { tasks.sync(email).failedOps + parked(OwnerType.TASK) })
            onLocalDataChanged()
            return outcome
        } finally {
            running.value = false
        }
    }

    /**
     * Changes refused often enough to be parked. Syncers skip them (so they don't count in failedOps),
     * but they're still unsynced: keep reporting them until the user retries or they're resolved.
     */
    private suspend fun parked(type: OwnerType): Int {
        val ops = db.sync().outbox(type)
        return ops.count { it.permanentFailures >= CalendarSyncer.MAX_ATTEMPTS }
    }

    /** [block] returns the number of local changes Google rejected or still holds parked. */
    private suspend fun runService(svc: SyncService, block: suspend () -> Int): SyncOutcome {
        val prev = db.sync().state(svc) ?: SyncStateEntity(svc)
        db.sync().upsertState(prev.copy(lastAttemptAt = now()))
        return try {
            val rejected = block()
            if (rejected > 0) {
                // Pull succeeded but some local changes were refused: not "synced".
                db.sync().upsertState(prev.copy(lastAttemptAt = now(), lastSuccessAt = now(), lastError = "rejected_changes", needsReauth = false))
                SyncOutcome.FAILED
            } else {
                db.sync().upsertState(prev.copy(lastAttemptAt = now(), lastSuccessAt = now(), lastError = null, needsReauth = false))
                SyncOutcome.OK
            }
        } catch (e: ApiError) {
            val reauth = e is ApiError.Auth
            db.sync().upsertState(prev.copy(lastAttemptAt = now(), lastError = errorCode(e), needsReauth = reauth))
            when {
                reauth -> SyncOutcome.NEEDS_REAUTH
                e.isRetryable -> SyncOutcome.RETRY_LATER
                else -> SyncOutcome.FAILED
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            db.sync().upsertState(prev.copy(lastAttemptAt = now(), lastError = "unexpected_response"))
            SyncOutcome.FAILED
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Anything else (a malformed item, a database error) is recorded as this service's error
            // instead of escaping: the worker then reports a failure the UI can show, and the other
            // service still syncs. Only the type is kept, never item content.
            db.sync().upsertState(prev.copy(lastAttemptAt = now(), lastError = "internal"))
            SyncOutcome.FAILED
        }
    }

    private fun errorCode(e: ApiError) = when (e) {
        is ApiError.Network -> "offline"
        is ApiError.Auth -> "auth"
        is ApiError.RateLimited -> "rate_limited"
        is ApiError.Server -> "server"
        else -> e.message ?: "error"
    }

    private fun worst(a: SyncOutcome, b: SyncOutcome) = if (b.ordinal > a.ordinal) b else a

    suspend fun connect(email: String) {
        accounts.setConnected(email)
        auth.accountEmail = email
        db.sync().clearStates()
    }

    /** Re-arms changes Google rejected repeatedly, so the next sync tries them again. */
    suspend fun retryParked() = db.sync().resetFailures()

    suspend fun setCalendarEnabled(v: Boolean) = accounts.setCalendarEnabled(v)
    suspend fun setTasksEnabled(v: Boolean) = accounts.setTasksEnabled(v)

    /**
     * Revokes access and removes Google-sourced copies from this device. Google data is untouched.
     * Unsynced local changes to Google items are discarded (the UI warns with the pending count first).
     */
    suspend fun disconnect() = mutex.withLock {
        val email = accounts.current().email
        if (email != null) auth.revoke(email)
        db.withTransaction {
            db.calendars().deleteAllGoogle() // cascades to events
            db.taskLists().deleteAllGoogle() // cascades to tasks
            db.sync().clearOutbox()
            db.sync().clearStates()
            db.sync().clearConflicts()
        }
        accounts.clear()
        auth.accountEmail = null
        onLocalDataChanged()
    }

    private fun now() = clock.now().toEpochMilli()
}
