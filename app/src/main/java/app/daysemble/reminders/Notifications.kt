package app.daysemble.reminders

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.daysemble.MainActivity
import app.daysemble.R
import app.daysemble.data.db.OwnerType

object Notifications {
    const val CHANNEL_REMINDERS = "reminders"

    fun createChannels(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_REMINDERS, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.channel_reminders))
                .setDescription(context.getString(R.string.channel_reminders_desc))
                .build(),
        )
    }

    data class Content(
        val type: OwnerType,
        val ownerId: String,
        val occurrenceKey: String,
        val reminderId: String,
        val title: String,
        val text: String,
        val whenMillis: Long?,
        val colorArgb: Int?,
    )

    /** Stable id per reminder occurrence, so a re-delivery replaces instead of duplicating. */
    fun notificationId(reminderId: String, occurrenceKey: String) = ("$reminderId|${occurrenceKey.substringBefore('#')}").hashCode()

    fun build(context: Context, c: Content, hideDetails: Boolean): Notification {
        val id = notificationId(c.reminderId, c.occurrenceKey)
        // PendingIntents match on action and data, not extras: a unique URI keeps two reminders whose
        // ids collide from rewriting each other's target.
        val uri = Uri.Builder().scheme("daysemble").authority("reminder")
            .appendPath(c.reminderId).appendPath(c.occurrenceKey.substringBefore('#')).build()
        val open = PendingIntent.getActivity(
            context, id,
            MainActivity.openIntent(context, c.type, c.ownerId, c.occurrenceKey.substringBefore('#')).setData(uri),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(kind: String, code: Int) = PendingIntent.getBroadcast(
            context, id * 31 + code,
            Intent(context, ReminderActionReceiver::class.java).setAction(kind).setData(uri)
                .putExtra(ReminderActionReceiver.EXTRA_TYPE, c.type.name)
                .putExtra(ReminderActionReceiver.EXTRA_OWNER, c.ownerId)
                .putExtra(ReminderActionReceiver.EXTRA_REMINDER, c.reminderId)
                .putExtra(ReminderActionReceiver.EXTRA_KEY, c.occurrenceKey.substringBefore('#'))
                .putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val public = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(context.getString(if (c.type == OwnerType.TASK) R.string.notif_public_task else R.string.notif_public_event))
            .build()
        return NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(c.title)
            .setContentText(c.text)
            .setCategory(if (c.type == OwnerType.TASK) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .apply { c.colorArgb?.let { setColor(it) } }
            .apply { c.whenMillis?.let { setWhen(it); setShowWhen(true) } }
            .setVisibility(if (hideDetails) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(public)
            .apply {
                if (c.type == OwnerType.TASK) addAction(0, context.getString(R.string.notif_action_complete), action(ReminderActionReceiver.ACTION_COMPLETE, 1))
                addAction(0, context.getString(R.string.notif_action_snooze), action(ReminderActionReceiver.ACTION_SNOOZE, 2))
            }
            .build()
    }

    fun post(context: Context, id: Int, n: Notification): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return false
        return try {
            NotificationManagerCompat.from(context).notify(id, n); true
        } catch (_: SecurityException) { false }
    }
}
