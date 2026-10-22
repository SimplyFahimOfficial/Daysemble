package app.daysemble.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.daysemble.DaysembleApp
import app.daysemble.R
import app.daysemble.data.db.DeliveredReminderEntity
import app.daysemble.data.db.OwnerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Runs [block] off the main thread while keeping the broadcast alive (≤ 9 s budget). */
private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            withTimeout(9_000) { block() }
        } catch (e: Exception) {
            // Never crash the receiver. Log only the failure type, never item content.
            android.util.Log.w("Daysemble", "reminder receiver failed: ${e.javaClass.simpleName}")
        } finally {
            pending.finish()
        }
    }
}

/**
 * Fires a reminder. The scheduled_alarm row is the source of truth: if it's gone (item edited,
 * completed or deleted since), nothing is shown. Delivery is recorded so one reminder occurrence
 * is posted at most once, even across reschedules.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val code = intent.getIntExtra(EXTRA_CODE, -1)
        if (code < 0) return
        val c = (context.applicationContext as DaysembleApp).container
        async {
            val dao = c.database.alarms()
            val alarm = dao.get(code) ?: return@async
            dao.delete(code)
            // An inexact alarm can arrive after the item was edited; post only what the data still asks for.
            if (!c.reminders.isStillPlanned(alarm)) { c.reminders.rescheduleFor(alarm.ownerType, alarm.ownerId); return@async }
            val key = ReminderScheduler.deliveredKey(alarm.reminderId, alarm.occurrenceKey, alarm.triggerAt)
            if (dao.markDelivered(DeliveredReminderEntity(key, c.clock.now().toEpochMilli())) == -1L) return@async
            val s = c.settings.current()
            if (!s.remindersEnabled) return@async
            val content = when (alarm.ownerType) {
                OwnerType.TASK -> {
                    val t = c.database.tasks().get(alarm.ownerId)?.takeIf { !it.isDeleted && !it.isCompleted } ?: return@async
                    Notifications.Content(
                        OwnerType.TASK, t.id, alarm.occurrenceKey, alarm.reminderId, t.title,
                        if (t.notes.isNotBlank()) t.notes.lineSequence().first() else context.getString(R.string.notif_task_due),
                        null, null,
                    )
                }
                OwnerType.EVENT -> {
                    val e = c.database.events().get(alarm.ownerId)?.takeIf { !it.isDeleted && !it.isCancelled } ?: return@async
                    val now = c.clock.now()
                    // Very late delivery (device off for hours) of a timed event that already ended: drop it.
                    // Start = trigger + lead time (correct for moved overrides); snoozes fall back to the occurrence key.
                    val lead = c.database.reminders().forOwner(OwnerType.EVENT, e.id).firstOrNull { it.id == alarm.reminderId }?.minutesBefore
                    val start = when {
                        e.isAllDay -> null
                        '#' !in alarm.occurrenceKey && lead != null -> Instant.ofEpochMilli(alarm.triggerAt).plusSeconds(lead * 60L)
                        else -> occurrenceStart(alarm.occurrenceKey, e.timeZone)
                    }
                    val duration = Duration.ofMillis(e.endUtc - e.startUtc)
                    if (start != null && now.isAfter(start.plus(duration)) && now.isAfter(Instant.ofEpochMilli(alarm.triggerAt).plusSeconds(3600))) return@async
                    val zone = c.clock.zone()
                    val text = when {
                        e.isAllDay -> context.getString(R.string.all_day)
                        start != null -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(start.atZone(zone)) +
                            if (e.location.isNotBlank()) " · " + e.location else ""
                        else -> e.location
                    }
                    Notifications.Content(OwnerType.EVENT, e.id, alarm.occurrenceKey, alarm.reminderId, e.title.ifBlank { context.getString(R.string.untitled_event) }, text, start?.toEpochMilli(), null)
                }
            }
            val id = Notifications.notificationId(alarm.reminderId, alarm.occurrenceKey)
            Notifications.post(context, id, Notifications.build(context, content, s.hideNotificationDetails))
            // Keep the rolling horizon filled (e.g. next occurrence of a series).
            c.reminders.rescheduleFor(alarm.ownerType, alarm.ownerId)
        }
    }

    private fun occurrenceStart(key: String, zoneId: String): Instant? = runCatching {
        java.time.LocalDateTime.parse(key.substringBefore('#')).atZone(ZoneId.of(zoneId)).toInstant()
    }.getOrNull()

    companion object {
        const val ACTION_FIRE = "app.daysemble.action.REMINDER"
        const val EXTRA_CODE = "code"
    }
}

/** Notification actions: complete a task, or snooze for 10 minutes. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as DaysembleApp).container
        val type = intent.getStringExtra(EXTRA_TYPE)?.let { runCatching { OwnerType.valueOf(it) }.getOrNull() } ?: return
        val owner = intent.getStringExtra(EXTRA_OWNER) ?: return
        val reminder = intent.getStringExtra(EXTRA_REMINDER) ?: return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val nid = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        NotificationManagerCompat.from(context).cancel(nid)
        async {
            when (intent.action) {
                ACTION_COMPLETE -> if (type == OwnerType.TASK) c.tasks.setCompleted(owner, true)
                ACTION_SNOOZE -> c.reminders.snooze(type, owner, reminder, key, SNOOZE_MINUTES)
            }
        }
    }

    companion object {
        const val ACTION_COMPLETE = "app.daysemble.action.COMPLETE"
        const val ACTION_SNOOZE = "app.daysemble.action.SNOOZE"
        const val EXTRA_TYPE = "type"
        const val EXTRA_OWNER = "owner"
        const val EXTRA_REMINDER = "reminder"
        const val EXTRA_KEY = "key"
        const val EXTRA_NOTIFICATION_ID = "nid"
        const val SNOOZE_MINUTES = 10L
    }
}

/** Boot, clock/zone changes, app update, exact-alarm permission changes: re-plan everything. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            -> {
                if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) java.util.TimeZone.setDefault(null)
                val c = (context.applicationContext as DaysembleApp).container
                async { c.reminders.rescheduleAll() }
            }
        }
    }
}
