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
        cancelOwner(ownerId)
        val s = settings.current()
        if (!s.remindersEnabled) return@withLock
        schedule(plan(type, ownerId, s.defaultTaskReminderMinuteOfDay))
    }

    suspend fun rescheduleAll() = mutex.withLock {
        dao.all().forEach { alarms.cancel(it.requestCode); dao.delete(it.requestCode) }
        val s = settings.current()
        dao.pruneDelivered(clock.now().minusSeconds(14L * 24 * 3600).toEpochMilli())
        if (!s.remindersEnabled) return@withLock
        val now = clock.now()
        val zone = clock.zone()
        val reminders = db.reminders().all().groupBy { it.ownerType to it.ownerId }
        val planned = ArrayList<PlannedAlarm>()
        val hidden = db.calendars().all().filter { !it.isVisible }.map { it.id }.toSet()
        db.events().activeAfter(now.toEpochMilli()).filter { it.recurringParentId == null && it.calendarId !in hidden }.forEach { e ->
            val r = reminders[OwnerType.EVENT to e.id] ?: return@forEach
            val ov = if (e.rrule != null) db.events().overridesFor(listOf(e.id)) else emptyList()
            planned += ReminderPlanner.forEvent(e, r, ov, now, zone, s.defaultTaskReminderMinuteOfDay)
        }
        db.tasks().openTasks().forEach { t ->
            val r = reminders[OwnerType.TASK to t.id] ?: return@forEach
            planned += ReminderPlanner.forTask(t, r, now, zone, s.defaultTaskReminderMinuteOfDay)
        }
        schedule(planned.sortedBy { it.triggerAt }.take(ReminderPlanner.MAX_ALARMS))
    }

    /** Schedules a one-off snooze for an alarm that just fired. */
    suspend fun snooze(type: OwnerType, ownerId: String, reminderId: String, occurrenceKey: String, minutes: Long) = mutex.withLock {
        val at = clock.now().plusSeconds(minutes * 60)
        schedule(listOf(PlannedAlarm(reminderId, type, ownerId, "$occurrenceKey#snooze${at.epochSecond}", at)))
    }

    suspend fun cancelOwnerAlarms(ownerId: String) = mutex.withLock { cancelOwner(ownerId) }

    /** Scheduled alarms for an owner, for "next reminder at …" UI. */
    suspend fun scheduledFor(ownerId: String): List<ScheduledAlarmEntity> = dao.forOwner(ownerId)

    private suspend fun plan(type: OwnerType, ownerId: String, defaultMinute: Int): List<PlannedAlarm> {
        val now = clock.now()
        val zone: ZoneId = clock.zone()
        val reminders = db.reminders().forOwner(type, ownerId)
        if (reminders.isEmpty()) return emptyList()
        return when (type) {
            OwnerType.EVENT -> {
                val e = db.events().get(ownerId) ?: return emptyList()
                if (db.calendars().get(e.calendarId)?.isVisible == false) return emptyList()
                val ov = if (e.rrule != null) db.events().overridesFor(listOf(e.id)) else emptyList()
                ReminderPlanner.forEvent(e, reminders, ov, now, zone, defaultMinute)
            }
            OwnerType.TASK -> {
                val t = db.tasks().get(ownerId) ?: return emptyList()
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

    private suspend fun cancelOwner(ownerId: String) {
        dao.forOwner(ownerId).forEach { alarms.cancel(it.requestCode); dao.delete(it.requestCode) }
    }

    private suspend fun schedule(plan: List<PlannedAlarm>) {
        if (plan.isEmpty()) return
        val exact = alarms.canScheduleExact()
        for (p in plan) {
            val key = deliveredKey(p.reminderId, p.occurrenceKey)
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
        fun deliveredKey(reminderId: String, occurrenceKey: String) = "$reminderId|$occurrenceKey"

        fun notificationsAllowed(context: Context): Boolean {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return false
            val ch = nm.getNotificationChannelCompat(Notifications.CHANNEL_REMINDERS) ?: return true
            return ch.importance != NotificationManagerCompat.IMPORTANCE_NONE
        }
    }
}
