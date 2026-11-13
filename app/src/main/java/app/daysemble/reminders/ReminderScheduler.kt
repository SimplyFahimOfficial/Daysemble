package app.daysemble.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import app.daysemble.core.time.AppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.ScheduledAlarmEntity
import app.daysemble.data.prefs.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/** Abstraction over AlarmManager so scheduling logic is testable. */
interface AlarmGateway {
    fun canScheduleExact(): Boolean
    /** Returns false if the platform refused the alarm. */
    fun set(requestCode: Int, triggerAt: Instant, exact: Boolean): Boolean
    fun cancel(requestCode: Int)
}

class AndroidAlarmGateway(private val context: Context) : AlarmGateway {
    private val am = context.getSystemService(AlarmManager::class.java)

    override fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    override fun set(requestCode: Int, triggerAt: Instant, exact: Boolean): Boolean = try {
        val pi = pendingIntent(requestCode)
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pi)
        true
    } catch (_: SecurityException) {
        false
    }

    override fun cancel(requestCode: Int) = am.cancel(pendingIntent(requestCode))

    // The data URI makes every alarm's Intent distinct under Intent.filterEquals, so PendingIntents
    // for different alarms can never be merged (extras alone don't count for PendingIntent identity).
    private fun pendingIntent(code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE)
            .setData(android.net.Uri.parse("daysemble://alarm/$code"))
            .putExtra(AlarmReceiver.EXTRA_CODE, code),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** How reliable reminders currently are, for honest UI. */
enum class ReminderHealth { OK, INEXACT, NOTIFICATIONS_BLOCKED, DISABLED_IN_APP }

/**
 * Keeps the scheduled_alarm table and AlarmManager in step with the data.
 * Every reschedule cancels the owner's old alarms first, so edits/deletes/completions never leave
 * stale reminders behind. All operations are serialised by a mutex.
 */
class ReminderScheduler(
    private val db: DaysembleDatabase,
    private val settings: SettingsRepository,
    private val alarms: AlarmGateway,
    private val clock: AppClock,
    private val notificationsAllowed: () -> Boolean,
) {
    private val mutex = Mutex()
    private val dao = db.alarms()

    fun health(remindersEnabled: Boolean): ReminderHealth = when {
        !remindersEnabled -> ReminderHealth.DISABLED_IN_APP
        !notificationsAllowed() -> ReminderHealth.NOTIFICATIONS_BLOCKED
        !alarms.canScheduleExact() -> ReminderHealth.INEXACT
        else -> ReminderHealth.OK
    }

    suspend fun rescheduleFor(type: OwnerType, ownerId: String) = mutex.withLock {
        val s = settings.current()
        val plan = if (s.remindersEnabled) plan(type, ownerId, s.defaultTaskReminderMinuteOfDay) else emptyList()
        // Snoozes survive edits while the item is still active; completing/deleting/hiding cancels them.
        val keepSnoozes = s.remindersEnabled && isActive(type, ownerId)
        reconcile(plan, dao.forOwner(ownerId), keepSnoozes)
    }

    /**
     * Rebuilds all reminders without a cancel-everything gap: alarms that are already due are left
     * for AlarmReceiver (the process may have just been started to deliver one), pending snoozes for
     * still-active items are kept, and only stale future alarms are cancelled.
     */
    suspend fun rescheduleAll() = mutex.withLock {
        val s = settings.current()
        val now = clock.now()
        dao.pruneDelivered(now.minusSeconds(14L * 24 * 3600).toEpochMilli())
        val existing = dao.all()
        if (!s.remindersEnabled) { reconcile(emptyList(), existing, keepSnoozes = false); return@withLock }
        val zone = clock.zone()
        val reminders = db.reminders().all().groupBy { it.ownerType to it.ownerId }
        val inactiveCals = db.calendars().all().filter { !it.isVisible }.map { it.id }.toSet()
        val planned = ArrayList<PlannedAlarm>()
        db.events().activeAfter(now.toEpochMilli()).filter { it.recurringParentId == null && it.calendarId !in inactiveCals }.forEach { e ->
            val r = reminders[OwnerType.EVENT to e.id] ?: return@forEach
            val ov = if (e.rrule != null) db.events().overridesFor(listOf(e.id)) else emptyList()
            planned += ReminderPlanner.forEvent(e, r, ov, now, zone, s.defaultTaskReminderMinuteOfDay)
        }
        db.tasks().openTasks().forEach { t ->
            val r = reminders[OwnerType.TASK to t.id] ?: return@forEach
            planned += ReminderPlanner.forTask(t, r, now, zone, s.defaultTaskReminderMinuteOfDay)
        }
        val activeOwners = existing.filter { isSnooze(it) }.map { it.ownerType to it.ownerId }.distinct()
            .filter { (t, id) -> isActive(t, id) }.toSet()
        reconcile(planned.sortedBy { it.triggerAt }.take(ReminderPlanner.MAX_ALARMS), existing, keepSnoozes = true, activeOwners)
    }

    private fun isSnooze(a: ScheduledAlarmEntity) = '#' in a.occurrenceKey

    /**
     * True if the current data would still notify for [a] at its trigger time: the item is active and
     * re-planning just before the trigger yields the same occurrence at the same instant, from any of
     * its current reminders. Deliberately independent of reminder ids, which a rewrite may replace.
     */
    suspend fun isStillPlanned(a: ScheduledAlarmEntity): Boolean {
        if (isSnooze(a)) return isActive(a.ownerType, a.ownerId)
        val s = settings.current()
        if (!s.remindersEnabled || !isActive(a.ownerType, a.ownerId)) return false
        val reminders = db.reminders().forOwner(a.ownerType, a.ownerId)
        if (reminders.isEmpty()) return false
        val before = Instant.ofEpochMilli(a.triggerAt - 1_000)
        val zone = clock.zone()
        val plan = when (a.ownerType) {
            OwnerType.EVENT -> {
                val e = db.events().get(a.ownerId) ?: return false
                val ov = if (e.rrule != null) db.events().overridesFor(listOf(e.id)) else emptyList()
                ReminderPlanner.forEvent(e, reminders, ov, before, zone, s.defaultTaskReminderMinuteOfDay)
            }
            OwnerType.TASK -> {
                val t = db.tasks().get(a.ownerId) ?: return false
                ReminderPlanner.forTask(t, reminders, before, zone, s.defaultTaskReminderMinuteOfDay)
            }
        }
        return plan.any { it.occurrenceKey == a.occurrenceKey && it.triggerAt.toEpochMilli() == a.triggerAt }
    }

    /** Whether reminders may still fire for this item (exists, not completed/deleted, its calendar shown). */
    private suspend fun isActive(type: OwnerType, ownerId: String): Boolean = when (type) {
        OwnerType.TASK -> db.tasks().get(ownerId)?.let { t -> !t.isDeleted && !t.isCompleted && db.taskLists().get(t.listId) != null } == true
        OwnerType.EVENT -> db.events().get(ownerId)?.let { e ->
            !e.isDeleted && !e.isCancelled && db.calendars().get(e.calendarId)?.isVisible == true
        } == true
    }

    /**
     * Makes [existing] match [plan]: keeps rows that are due now (delivery in progress), keeps
     * snoozes when allowed, cancels everything else not in the plan, then schedules the plan.
     */
    private suspend fun reconcile(
        plan: List<PlannedAlarm>,
        existing: List<ScheduledAlarmEntity>,
        keepSnoozes: Boolean,
        snoozeOwners: Set<Pair<OwnerType, String>>? = null,
    ) {
        val now = clock.now().toEpochMilli()
        // Already-delivered occurrences are never wanted again (prevents duplicate notifications).
        val plan = plan.filter { dao.wasDelivered(deliveredKey(it.reminderId, it.occurrenceKey, it.triggerAt.toEpochMilli())) == 0 }
        val wanted = plan.map { it.reminderId to it.occurrenceKey }.toSet()
        for (a in existing) {
            val due = a.triggerAt <= now
            val snoozeKept = isSnooze(a) && keepSnoozes && (snoozeOwners == null || (a.ownerType to a.ownerId) in snoozeOwners)
            when {
                // Due but not yet delivered: leave it for AlarmReceiver, but only while the data still
                // asks for exactly this notification (an edit may have removed or moved it).
                due && a.triggerAt > now - STALE_MS && (if (isSnooze(a)) snoozeKept else isStillPlanned(a)) -> Unit
                snoozeKept && !due -> Unit
                (a.reminderId to a.occurrenceKey) in wanted && !due -> Unit // re-registered below with the same code
                else -> { alarms.cancel(a.requestCode); dao.delete(a.requestCode) }
            }
        }
        schedule(plan)
    }

    /** Schedules a one-off snooze for an alarm that just fired. */
    suspend fun snooze(type: OwnerType, ownerId: String, reminderId: String, occurrenceKey: String, minutes: Long) = mutex.withLock {
        val at = clock.now().plusSeconds(minutes * 60)
        schedule(listOf(PlannedAlarm(reminderId, type, ownerId, "$occurrenceKey#snooze${at.epochSecond}", at)))
    }

    private suspend fun plan(type: OwnerType, ownerId: String, defaultMinute: Int): List<PlannedAlarm> {
        val now = clock.now()
        val zone: ZoneId = clock.zone()
        val reminders = db.reminders().forOwner(type, ownerId)
        if (reminders.isEmpty()) return emptyList()
        return when (type) {
            OwnerType.EVENT -> {
                val e = db.events().get(ownerId) ?: return emptyList()
                if (!isActive(OwnerType.EVENT, ownerId)) return emptyList()
                val ov = if (e.rrule != null) db.events().overridesFor(listOf(e.id)) else emptyList()
                ReminderPlanner.forEvent(e, reminders, ov, now, zone, defaultMinute)
            }
            OwnerType.TASK -> {
                val t = db.tasks().get(ownerId) ?: return emptyList()
                if (!isActive(OwnerType.TASK, ownerId)) return emptyList()
                ReminderPlanner.forTask(t, reminders, now, zone, defaultMinute)
            }
        }
    }

    /**
     * Stable request code for an alarm, so re-planning the same occurrence reuses the same
     * PendingIntent. On the (rare) hash collision with a different alarm, probe the next value.
     */
    private suspend fun requestCodeFor(reminderId: String, occurrenceKey: String): Int {
        var code = ("$reminderId|$occurrenceKey".hashCode() and 0x7FFFFFFF)
        while (true) {
            val existing = dao.get(code) ?: return code
            if (existing.reminderId == reminderId && existing.occurrenceKey == occurrenceKey) return code
            code = (code + 1) and 0x7FFFFFFF
        }
    }


    private suspend fun schedule(plan: List<PlannedAlarm>) {
        if (plan.isEmpty()) return
        val exact = alarms.canScheduleExact()
        for (p in plan) {
            val key = deliveredKey(p.reminderId, p.occurrenceKey, p.triggerAt.toEpochMilli())
            if (dao.wasDelivered(key) > 0) continue
            val code = requestCodeFor(p.reminderId, p.occurrenceKey)
            dao.insert(
                ScheduledAlarmEntity(
                    requestCode = code, reminderId = p.reminderId, ownerType = p.ownerType, ownerId = p.ownerId,
                    occurrenceKey = p.occurrenceKey, triggerAt = p.triggerAt.toEpochMilli(), isExact = exact,
                ),
            )
            if (!alarms.set(code, p.triggerAt, exact)) {
                // Exact permission revoked between check and call: fall back rather than drop.
                if (!(exact && alarms.set(code, p.triggerAt, false))) dao.delete(code)
            }
        }
    }

    companion object {
        /**
         * Identity of one delivered notification. The trigger time is part of it: re-planning an
         * unchanged reminder yields the same key (no duplicate), while moving the item (e.g. a task from
         * 10:00 to 15:00 on the same day, or a moved occurrence that keeps its original key) yields a
         * new key, so the new reminder is not suppressed.
         */
        fun deliveredKey(reminderId: String, occurrenceKey: String, triggerAt: Long) = "$reminderId|$occurrenceKey|$triggerAt"

        /** Due alarms older than this were missed (e.g. force-stop) and are cleaned up. */
        const val STALE_MS = 60 * 60_000L

        fun notificationsAllowed(context: Context): Boolean {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return false
            val ch = nm.getNotificationChannelCompat(Notifications.CHANNEL_REMINDERS) ?: return true
            return ch.importance != NotificationManagerCompat.IMPORTANCE_NONE
        }
    }
}
