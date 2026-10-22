package app.daysemble.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.daysemble.DaysembleApp
import app.daysemble.data.db.OwnerType
import app.daysemble.data.google.sync.SyncWorker
import app.daysemble.domain.model.TaskDraft
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * End-to-end on the real Android plumbing (Robolectric): AlarmManager registration, the
 * manifest-registered receivers, goAsync, notification posting and actions, boot rescheduling,
 * and the sync worker. Closest available substitute for an on-device check in this environment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderDeliveryTest {
    private val app = ApplicationProvider.getApplicationContext<DaysembleApp>()
    private val c get() = app.container
    private val am get() = app.getSystemService(AlarmManager::class.java)
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @Before fun grant() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { c.clearLocalData() }
    }

    private fun waitFor(timeoutMs: Long = 8_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(50)
        }
        error("condition not met")
    }

    private fun createTaskDueInAnHour(): String = runBlocking {
        val now = LocalDateTime.now().plusHours(1).withSecond(0).withNano(0)
        val id = c.tasks.create(
            TaskDraft(title = "Call the bank", dueDate = now.toLocalDate(), dueTime = now.toLocalTime(), reminderMinutesBefore = listOf(30)),
            c.defaults().taskListId,
        )
        c.reminders.rescheduleFor(OwnerType.TASK, id)
        id
    }

    @Test fun `alarm is registered and firing it posts one notification with actions`() {
        val id = createTaskDueInAnHour()
        val scheduled = shadowOf(am).scheduledAlarms
        assertThat(scheduled).hasSize(1)
        val alarm = scheduled.single()
        assertThat(alarm.type).isEqualTo(AlarmManager.RTC_WAKEUP)

        // Fire the exact PendingIntent the platform would fire.
        val intent = shadowOf(alarm.operation!!).savedIntent
        app.sendBroadcast(intent)
        waitFor { shadowOf(nm).allNotifications.isNotEmpty() }
        val n = shadowOf(nm).allNotifications.single()
        assertThat(n.extras.getString("android.title")).isEqualTo("Call the bank")
        assertThat(n.actions.map { it.title.toString() }).containsExactly("Complete", "Snooze 10 min")

        // Delivering the same alarm again must not post a duplicate.
        app.sendBroadcast(intent)
        Thread.sleep(300); shadowOf(Looper.getMainLooper()).idle()
        assertThat(shadowOf(nm).allNotifications).hasSize(1)

        // "Complete" action completes the task.
        n.actions.first { it.title.toString() == "Complete" }.actionIntent.send()
        waitFor { runBlocking { c.tasks.get(id)?.isCompleted == true } }
    }

    @Test fun `completing the task before the alarm cancels it`() {
        val id = createTaskDueInAnHour()
        assertThat(shadowOf(am).scheduledAlarms).hasSize(1)
        runBlocking { c.tasks.setCompleted(id, true); c.reminders.rescheduleFor(OwnerType.TASK, id) }
        assertThat(shadowOf(am).scheduledAlarms).isEmpty()
    }

    @Test fun `boot broadcast reschedules alarms`() {
        createTaskDueInAnHour()
        // Simulate a reboot: the platform forgets all alarms.
        shadowOf(am).scheduledAlarms.toList().forEach { it.operation?.let(am::cancel) }
        assertThat(shadowOf(am).scheduledAlarms).isEmpty()
        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.packageName))
        waitFor { shadowOf(am).scheduledAlarms.size == 1 }
    }

    @Test fun `sync worker succeeds without an account and does no network work`() = runBlocking {
        val result = TestListenableWorkerBuilder<SyncWorker>(app).build().doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test fun `reminder refresh worker reschedules`() = runBlocking {
        createTaskDueInAnHour()
        shadowOf(am).scheduledAlarms.toList().forEach { it.operation?.let(am::cancel) }
        val result = TestListenableWorkerBuilder<ReminderRefreshWorker>(app).build().doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(shadowOf(am).scheduledAlarms).hasSize(1)
    }
}
