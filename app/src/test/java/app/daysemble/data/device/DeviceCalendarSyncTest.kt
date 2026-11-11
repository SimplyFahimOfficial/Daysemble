package app.daysemble.data.device

import android.Manifest
import android.app.Application
import android.provider.CalendarContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.SourceKind
import app.daysemble.data.device.FakeCalendarProvider.Companion.calendar
import app.daysemble.data.device.FakeCalendarProvider.Companion.instance
import app.daysemble.data.repo.EventRepository
import app.daysemble.data.repo.TaskRepository
import app.daysemble.data.sync.ChangeListener
import app.daysemble.domain.model.EventDraft
import app.daysemble.domain.model.TaskDraft
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The phone's calendars (what calendar apps and synced accounts put in the Calendar Provider) are
 * mirrored into Room read-only, so they show in Today, Calendar and search like any other event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceCalendarSyncTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val berlin = ZoneId.of("Europe/Berlin")
    private val clock = FixedAppClock(Instant.parse("2026-10-22T08:00:00Z"), berlin)
    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var db: DaysembleDatabase
    private lateinit var sync: DeviceCalendarSync
    private lateinit var events: EventRepository
    private val day = LocalDate.parse("2026-10-22")

    @Before fun setUp() {
        FakeCalendarProvider.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        db = Room.inMemoryDatabaseBuilder(app, DaysembleDatabase::class.java).allowMainThreadQueries().build()
        sync = DeviceCalendarSync(app, db, clock, scope)
        events = EventRepository(db, clock, ChangeListener.None)
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun t(iso: String) = Instant.parse(iso).toEpochMilli()
    private suspend fun titlesOn(d: LocalDate, zone: ZoneId = berlin) = events.occurrences(d, d, zone).first().map { it.title }
    private suspend fun deviceEventCount() = db.events().deviceEvents().size

    @Test fun `events of synced phone calendars show up in the calendar views`() = runTest {
        FakeCalendarProvider.calendars = listOf(calendar(1, "Work"), calendar(2, "Family", account = "me@example.com"))
        FakeCalendarProvider.instances = listOf(
            instance(10, 1, "Standup", t("2026-10-22T07:00:00Z"), t("2026-10-22T07:15:00Z"), description = "Daily", location = "Room 4"),
            instance(11, 2, "Dinner", t("2026-10-22T17:00:00Z"), t("2026-10-22T19:00:00Z")),
        )

        assertThat(sync.refresh()).isEqualTo(DeviceCalendarSync.Result.SYNCED)

        val shown = events.occurrences(day, day, berlin).first()
        assertThat(shown.map { it.title }).containsExactly("Standup", "Dinner")
        val standup = shown.first { it.title == "Standup" }
        assertThat(standup.location).isEqualTo("Room 4")
        assertThat(standup.description).isEqualTo("Daily")
        assertThat(standup.isDevice).isTrue()
        assertThat(standup.isReadOnly).isTrue()
        assertThat(standup.start).isEqualTo(Instant.parse("2026-10-22T07:00:00Z"))
        assertThat(events.search("dinn", clock.now(), berlin).first().map { it.title }).containsExactly("Dinner") // search finds them too
    }

    @Test fun `phone calendars appear in the calendar selection with their account and can be hidden`() = runTest {
        FakeCalendarProvider.calendars = listOf(calendar(1, "Work", account = "work@example.com"), calendar(2, "Holidays", visible = false))
        FakeCalendarProvider.instances = listOf(
            instance(10, 1, "Standup", t("2026-10-22T07:00:00Z"), t("2026-10-22T07:15:00Z")),
            instance(11, 2, "Public holiday", t("2026-10-22T00:00:00Z"), t("2026-10-23T00:00:00Z"), allDay = true),
        )
        sync.refresh()

        val list = events.allCalendars.first()
        assertThat(list.filter { it.kind == SourceKind.DEVICE }.map { it.name }).containsExactly("Work", "Holidays")
        assertThat(list.first { it.name == "Work" }.accountEmail).isEqualTo("work@example.com")
        // A calendar hidden in the phone's own calendar app starts hidden, but it was read, so it can be switched on.
        assertThat(titlesOn(day)).containsExactly("Standup")
        events.setCalendarVisible("device:2", true)
        assertThat(titlesOn(day)).containsExactly("Public holiday", "Standup")
        events.setCalendarVisible("device:1", false)
        assertThat(titlesOn(day)).containsExactly("Public holiday")
    }

    @Test fun `refreshing again changes nothing and never duplicates`() = runTest {
        FakeCalendarProvider.calendars = listOf(calendar(1, "Work"))
        FakeCalendarProvider.instances = listOf(
            instance(10, 1, "Standup", t("2026-10-22T07:00:00Z"), t("2026-10-22T07:15:00Z")),
            instance(10, 1, "Standup", t("2026-10-23T07:00:00Z"), t("2026-10-23T07:15:00Z")), // the next occurrence of a repeating event
            instance(10, 1, "Standup", t("2026-10-24T07:00:00Z"), t("2026-10-24T07:15:00Z")),
        )
        sync.refresh()
        val first = db.events().deviceEvents().map { it.id }.sorted()
        repeat(3) { sync.refresh() }
        coroutineScope { listOf(async { sync.refresh() }, async { sync.refresh() }).awaitAll() }

        assertThat(db.events().deviceEvents().map { it.id }.sorted()).isEqualTo(first)
        assertThat(first).hasSize(3) // one row per occurrence of the repeating event
        assertThat(titlesOn(day)).containsExactly("Standup")
        assertThat(db.calendars().byKind(SourceKind.DEVICE)).hasSize(1)
    }

    @Test fun `edited and deleted events follow the phone`() = runTest {
        FakeCalendarProvider.calendars = listOf(calendar(1, "Work"))
        FakeCalendarProvider.instances = listOf(
            instance(10, 1, "Standup", t("2026-10-22T07:00:00Z"), t("2026-10-22T07:15:00Z")),
            instance(11, 1, "Lunch", t("2026-10-22T10:00:00Z"), t("2026-10-22T11:00:00Z")),
        )
        sync.refresh()

        FakeCalendarProvider.instances = listOf(
            instance(10, 1, "Standup (moved)", t("2026-10-22T07:00:00Z"), t("2026-10-22T07:30:00Z")), // renamed and longer, same event
            instance(12, 1, "New", t("2026-10-22T12:00:00Z"), t("2026-10-22T12:30:00Z")),
        )
        sync.refresh()

        assertThat(titlesOn(day)).containsExactly("Standup (moved)", "New")
        assertThat(deviceEventCount()).isEqualTo(2) // Lunch is gone, the renamed one replaced its old row
    }

    @Test fun `a cancelled instance is not shown`() = runTest {
        FakeCalendarProvider.calendars = listOf(calendar(1, "Work"))
        FakeCalendarProvider.instances = listOf(
            instance(10, 1, "Kept", t("2026-10-22T07:00:00Z"), t("2026-10-22T08:00:00Z")),
            instance(11, 1, "Cancelled", t("2026-10-22T09:00:00Z"), t("2026-10-22T10:00:00Z"), status = CalendarContract.Events.STATUS_CANCELED),
        )
        sync.refresh()
        assertThat(titlesOn(day)).containsExactly("Kept")
    }

    @Test fun `all-day events stay on their date in every time zone`() = runTest {
        FakeCalendarProvider.calendars = listOf(calendar(1, "Holidays"))
        FakeCalendarProvider.instances = listOf(
            instance(10, 1, "Holiday", t("2026-10-22T00:00:00Z"), t("2026-10-23T00:00:00Z"), allDay = true, zone = "UTC"),
            instance(11, 1, "Trip", t("2026-10-22T00:00:00Z"), t("2026-10-25T00:00:00Z"), allDay = true, zone = "UTC"),
        )
        for (zone in listOf("America/Los_Angeles", "Pacific/Auckland", "Europe/Berlin")) {
            clock.zoneId = ZoneId.of(zone)
            sync.refresh()
            val z = ZoneId.of(zone)
            assertThat(titlesOn(LocalDate.parse("2026-10-21"), z)).isEmpty()
            assertThat(titlesOn(day, z)).containsExactly("Holiday", "Trip")
            assertThat(titlesOn(LocalDate.parse("2026-10-24"), z)).containsExactly("Trip")
            assertThat(titlesOn(LocalDate.parse("2026-10-25"), z)).isEmpty() // the end is exclusive
        }
    }

    @Test fun `an event without a title or a time zone is still shown`() = runTest {
        FakeCalendarProvider.calendars = listOf(calendar(1, "Work"))
        FakeCalendarProvider.instances = listOf(instance(10, 1, "", t("2026-10-22T07:00:00Z"), t("2026-10-22T08:00:00Z"), zone = null))
        sync.refresh()
        val shown = events.occurrences(day, day, berlin).first().single()
        assertThat(shown.title).isEqualTo("(No title)")
        assertThat(shown.start).isEqualTo(Instant.parse("2026-10-22T07:00:00Z"))
    }

    @Test fun `calendars that are not synced to the phone are skipped`() = runTest {
        FakeCalendarProvider.calendars = listOf(calendar(1, "Work"), calendar(2, "Not synced", sync = false))
        FakeCalendarProvider.instances = emptyList()
        sync.refresh()
        assertThat(db.calendars().byKind(SourceKind.DEVICE).map { it.name }).containsExactly("Work")
    }

    @Test fun `only the window around today is read`() = runTest {
        FakeCalendarProvider.calendars = listOf(calendar(1, "Work"))
        FakeCalendarProvider.instances = listOf(
            instance(10, 1, "Ancient", t("2025-01-01T07:00:00Z"), t("2025-01-01T08:00:00Z")),
            instance(11, 1, "Soon", t("2026-10-22T07:00:00Z"), t("2026-10-22T08:00:00Z")),
            instance(12, 1, "Far future", t("2029-01-01T07:00:00Z"), t("2029-01-01T08:00:00Z")),
        )
        sync.refresh()
        val segments = FakeCalendarProvider.lastInstancesUri!!.pathSegments.takeLast(2).map { it.toLong() }
        assertThat(segments).containsExactly(
            clock.now().minus(Duration.ofDays(DeviceCalendarSync.PAST_DAYS)).toEpochMilli(),
            clock.now().plus(Duration.ofDays(DeviceCalendarSync.FUTURE_DAYS)).toEpochMilli(),
        ).inOrder()
        assertThat(db.events().deviceEvents().map { it.title }).containsExactly("Soon")
    }

    @Test fun `choices made in Daysemble survive a refresh, and a calendar removed on the phone goes with its events`() = runTest {
        FakeCalendarProvider.calendars = listOf(calendar(1, "Work", color = 0xFF112233.toInt()), calendar(2, "Family"))
        FakeCalendarProvider.instances = listOf(
            instance(10, 1, "Standup", t("2026-10-22T07:00:00Z"), t("2026-10-22T07:15:00Z")),
            instance(11, 2, "Dinner", t("2026-10-22T17:00:00Z"), t("2026-10-22T19:00:00Z")),
        )
        sync.refresh()
        events.setCalendarVisible("device:1", false)
        events.setCalendarColor("device:1", 0xFFAA0000.toInt())

        FakeCalendarProvider.calendars = listOf(calendar(1, "Work (renamed)", color = 0xFF112233.toInt()), calendar(2, "Family"))
        sync.refresh()
        val work = db.calendars().get("device:1")!!
        assertThat(work.name).isEqualTo("Work (renamed)")
        assertThat(work.isVisible).isFalse()
        assertThat(work.colorArgb).isEqualTo(0xFFAA0000.toInt())

        FakeCalendarProvider.calendars = listOf(calendar(1, "Work (renamed)"))
        FakeCalendarProvider.instances = listOf(instance(10, 1, "Standup", t("2026-10-22T07:00:00Z"), t("2026-10-22T07:15:00Z")))
        sync.refresh()
        assertThat(db.calendars().get("device:2")).isNull()
        assertThat(db.events().deviceEvents().map { it.title }).containsExactly("Standup")
    }

    @Test fun `no permission mirrors nothing, asks nothing and removes an earlier copy`() = runTest {
        FakeCalendarProvider.calendars = listOf(calendar(1, "Work"))
        FakeCalendarProvider.instances = listOf(instance(10, 1, "Standup", t("2026-10-22T07:00:00Z"), t("2026-10-22T07:15:00Z")))
        sync.refresh()
        assertThat(deviceEventCount()).isEqualTo(1)

        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        FakeCalendarProvider.queries = 0
        assertThat(sync.refresh()).isEqualTo(DeviceCalendarSync.Result.NO_PERMISSION)

        assertThat(FakeCalendarProvider.queries).isEqualTo(0)
        assertThat(db.calendars().byKind(SourceKind.DEVICE)).isEmpty()
        assertThat(deviceEventCount()).isEqualTo(0)
        assertThat(titlesOn(day)).isEmpty()
        // Granting it later brings everything back.
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        assertThat(sync.refresh()).isEqualTo(DeviceCalendarSync.Result.SYNCED)
        assertThat(titlesOn(day)).containsExactly("Standup")
    }

    @Test fun `a permission revoked while reading removes the copy`() = runTest {
        FakeCalendarProvider.calendars = listOf(calendar(1, "Work"))
        FakeCalendarProvider.instances = listOf(instance(10, 1, "Standup", t("2026-10-22T07:00:00Z"), t("2026-10-22T07:15:00Z")))
        sync.refresh()
        FakeCalendarProvider.failure = SecurityException("revoked")
        assertThat(sync.refresh()).isEqualTo(DeviceCalendarSync.Result.NO_PERMISSION)
        assertThat(deviceEventCount()).isEqualTo(0)
    }

    @Test fun `a provider failure keeps what was mirrored, reports it, and recovers`() = runTest {
        FakeCalendarProvider.calendars = listOf(calendar(1, "Work"))
        FakeCalendarProvider.instances = listOf(instance(10, 1, "Standup", t("2026-10-22T07:00:00Z"), t("2026-10-22T07:15:00Z")))
        sync.refresh()

        FakeCalendarProvider.failure = IllegalStateException("provider crashed")
        assertThat(sync.refresh()).isEqualTo(DeviceCalendarSync.Result.UNAVAILABLE)
        assertThat(sync.failed.value).isTrue()
        assertThat(titlesOn(day)).containsExactly("Standup")

        FakeCalendarProvider.failure = null
        FakeCalendarProvider.instances = null // no provider answers at all
        assertThat(sync.refresh()).isEqualTo(DeviceCalendarSync.Result.UNAVAILABLE)
        assertThat(titlesOn(day)).containsExactly("Standup")

        FakeCalendarProvider.instances = listOf(instance(10, 1, "Standup", t("2026-10-22T07:00:00Z"), t("2026-10-22T07:15:00Z")))
        assertThat(sync.refresh()).isEqualTo(DeviceCalendarSync.Result.SYNCED)
        assertThat(sync.failed.value).isFalse()
    }

    @Test fun `phone events never touch or duplicate the app's own events and tasks`() = runTest {
        val tasks = TaskRepository(db, clock, ChangeListener.None)
        val calendarId = events.ensureDefaultCalendar("Personal")
        val listId = tasks.ensureDefaultList("My tasks")
        val own = events.create(EventDraft(title = "Own event", startDate = day, startTime = LocalTime.of(9, 0), endTime = LocalTime.of(10, 0), zone = berlin), calendarId)
        val task = tasks.create(TaskDraft(title = "Own task", dueDate = day), listId)
        FakeCalendarProvider.calendars = listOf(calendar(1, "Work"))
        FakeCalendarProvider.instances = listOf(instance(10, 1, "Phone event", t("2026-10-22T07:00:00Z"), t("2026-10-22T08:00:00Z")))

        repeat(3) { sync.refresh() }

        assertThat(titlesOn(day)).containsExactly("Own event", "Phone event")
        assertThat(events.get(own)).isNotNull()
        assertThat(tasks.openTasksInRange(day, day).first().map { it.title }).containsExactly("Own task")
        assertThat(tasks.allTasks.first().map { it.id }).containsExactly(task)
        // The app's own calendar stays editable, the mirrored one doesn't offer itself for new events.
        assertThat(events.calendarList.first().filter { it.isWritable }.map { it.id }).containsExactly(calendarId)
    }
}
