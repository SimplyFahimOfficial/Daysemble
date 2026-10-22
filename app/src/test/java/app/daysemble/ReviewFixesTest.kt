package app.daysemble

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.recurrence.RecurrenceExpander
import app.daysemble.data.db.CalendarSourceEntity
import app.daysemble.data.db.EventEntity
import app.daysemble.data.db.OutboxOp
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.ReminderEntity
import app.daysemble.data.db.SourceKind
import app.daysemble.data.db.SyncState
import app.daysemble.data.google.SyncTestBase
import app.daysemble.data.google.calendar.CalendarApi
import app.daysemble.data.google.calendar.CalendarMapper
import app.daysemble.data.google.calendar.CalendarSyncer
import app.daysemble.data.google.json
import app.daysemble.data.prefs.SettingsRepository
import app.daysemble.domain.model.EventDraft
import app.daysemble.domain.model.TaskDraft
import app.daysemble.reminders.AlarmGateway
import app.daysemble.reminders.ReminderPlanner
import app.daysemble.reminders.ReminderScheduler
import app.daysemble.ui.calendar.wallMinutes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Regression tests for the Greptile review findings on PR #1. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewFixesTest : SyncTestBase() {
    @get:Rule val tmp = TemporaryFolder()
    private val acct = "me@example.com"
    private fun syncer() = CalendarSyncer(db, CalendarApi(http, base("/calendar/v3/")), clock)
    private fun calList() = json(body = """{"items":[{"id":"cal","summary":"Me","accessRole":"owner","timeZone":"Europe/Berlin"}]}""")

    private class Alarms : AlarmGateway {
        val set = mutableMapOf<Int, Instant>()
        override fun canScheduleExact() = true
        override fun set(requestCode: Int, triggerAt: Instant, exact: Boolean): Boolean { set[requestCode] = triggerAt; return true }
        override fun cancel(requestCode: Int) { set.remove(requestCode) }
    }
    private fun scheduler(alarms: Alarms) = ReminderScheduler(
        db, SettingsRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { File(tmp.root, "s${System.nanoTime()}.preferences_pb") }),
        alarms, clock,
    ) { true }

    @Test fun `a due alarm row survives rescheduleAll so the receiver can deliver it`() = runTest {
        val list = tasks.ensureDefaultList("My tasks")
        val due = LocalDateTime.ofInstant(clock.now(), zone).plusMinutes(40)
        val id = tasks.create(TaskDraft(title = "Call", dueDate = due.toLocalDate(), dueTime = due.toLocalTime(), reminderMinutesBefore = listOf(30)), list)
        val alarms = Alarms(); val s = scheduler(alarms)
        s.rescheduleFor(OwnerType.TASK, id)
        clock.instant = clock.instant.plusSeconds(11 * 60) // trigger (10 min from start) has passed
        s.rescheduleAll()
        assertThat(db.alarms().forOwner(id)).hasSize(1)
    }

    @Test fun `a due alarm invalidated by an edit is cancelled and not delivered`() = runTest {
        val list = tasks.ensureDefaultList("My tasks")
        val due = LocalDateTime.ofInstant(clock.now(), zone).plusMinutes(40)
        val id = tasks.create(TaskDraft(title = "Call", dueDate = due.toLocalDate(), dueTime = due.toLocalTime(), reminderMinutesBefore = listOf(30)), list)
        val alarms = Alarms(); val s = scheduler(alarms)
        s.rescheduleFor(OwnerType.TASK, id)
        clock.instant = clock.instant.plusSeconds(11 * 60) // reminder is due but (inexact) not yet delivered
        val row = db.alarms().forOwner(id).single()
        assertThat(s.isStillPlanned(row)).isTrue()
        // The user removes the reminder: the due alarm must go too.
        tasks.update(id, TaskDraft(title = "Call", dueDate = due.toLocalDate(), dueTime = due.toLocalTime(), reminderMinutesBefore = emptyList()))
        assertThat(s.isStillPlanned(row)).isFalse()
        s.rescheduleFor(OwnerType.TASK, id)
        assertThat(db.alarms().forOwner(id)).isEmpty()
        assertThat(alarms.set).isEmpty()
    }

    @Test fun `a due alarm survives an unrelated edit and a google pull that rewrite reminders`() = runTest {
        val list = tasks.ensureDefaultList("My tasks")
        val due = LocalDateTime.ofInstant(clock.now(), zone).plusMinutes(40)
        val id = tasks.create(TaskDraft(title = "Call", dueDate = due.toLocalDate(), dueTime = due.toLocalTime(), reminderMinutesBefore = listOf(30)), list)
        val alarms = Alarms(); val s = scheduler(alarms)
        s.rescheduleFor(OwnerType.TASK, id)
        val before = db.reminders().forOwner(OwnerType.TASK, id).single().id
        clock.instant = clock.instant.plusSeconds(11 * 60) // due, not yet delivered
        // Unrelated edit: title changes, same reminder offset and time -> same reminder id.
        tasks.update(id, TaskDraft(title = "Call the bank", dueDate = due.toLocalDate(), dueTime = due.toLocalTime(), reminderMinutesBefore = listOf(30)))
        assertThat(db.reminders().forOwner(OwnerType.TASK, id).single().id).isEqualTo(before)
        s.rescheduleFor(OwnerType.TASK, id)
        val row = db.alarms().forOwner(id).single()
        assertThat(s.isStillPlanned(row)).isTrue()
        // Even if a rewrite did replace ids (legacy rows), validity is judged by time, not id.
        db.reminders().replace(OwnerType.TASK, id, listOf(ReminderEntity("legacy-random-id", OwnerType.TASK, id, 30)))
        assertThat(s.isStillPlanned(row)).isTrue()
        s.rescheduleAll()
        assertThat(db.alarms().forOwner(id)).hasSize(1)
    }

    @Test fun `a due alarm whose task moved later is replaced, not kept`() = runTest {
        val list = tasks.ensureDefaultList("My tasks")
        val due = LocalDateTime.ofInstant(clock.now(), zone).plusMinutes(40)
        val id = tasks.create(TaskDraft(title = "Call", dueDate = due.toLocalDate(), dueTime = due.toLocalTime(), reminderMinutesBefore = listOf(30)), list)
        val alarms = Alarms(); val s = scheduler(alarms)
        s.rescheduleFor(OwnerType.TASK, id)
        clock.instant = clock.instant.plusSeconds(11 * 60)
        val later = due.plusHours(3)
        tasks.update(id, TaskDraft(title = "Call", dueDate = later.toLocalDate(), dueTime = later.toLocalTime(), reminderMinutesBefore = listOf(30)))
        s.rescheduleFor(OwnerType.TASK, id)
        val rows = db.alarms().forOwner(id)
        assertThat(rows).hasSize(1)
        assertThat(Instant.ofEpochMilli(rows.single().triggerAt)).isEqualTo(later.minusMinutes(30).atZone(zone).toInstant())
    }

    @Test fun `snooze survives refresh while pending, and is cancelled when the task is completed`() = runTest {
        val list = tasks.ensureDefaultList("My tasks")
        val id = tasks.create(TaskDraft(title = "Call", dueDate = LocalDate.parse("2026-10-22")), list)
        val alarms = Alarms(); val s = scheduler(alarms)
        s.snooze(OwnerType.TASK, id, "r", "2026-10-22", 10)
        s.rescheduleAll()
        assertThat(db.alarms().forOwner(id).single().occurrenceKey).contains("#snooze")
        tasks.setCompleted(id, true)
        s.rescheduleFor(OwnerType.TASK, id)
        assertThat(db.alarms().forOwner(id)).isEmpty()
        assertThat(alarms.set).isEmpty()
    }

    @Test fun `transient errors don't park changes, permanent rejections do and are reported`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") { calList() }
        router.on("GET", "/calendar/v3/calendars/cal/events") { json(body = """{"items":[],"nextSyncToken":"t"}""") }
        syncer().refreshCalendars(acct)
        val cal = db.calendars().byKind(SourceKind.GOOGLE).single()
        events.create(EventDraft(title = "A", startDate = LocalDate.parse("2026-10-23"), zone = zone), cal.id)
        router.on("POST", "/calendar/v3/calendars/cal/events") { json(503, "{}") }
        repeat(7) { runCatching { syncer().pushOutbox() } }
        assertThat(db.sync().outbox().single().permanentFailures).isEqualTo(0) // still eligible
        router.on("POST", "/calendar/v3/calendars/cal/events") { json(400, """{"error":{"errors":[{"reason":"invalid"}]}}""") }
        val r = syncer().sync(acct)
        assertThat(r.failedOps).isEqualTo(1)
        assertThat(db.sync().outbox().single().permanentFailures).isEqualTo(1)
        db.sync().resetFailures()
        assertThat(db.sync().outbox().single().permanentFailures).isEqualTo(0)
    }

    @Test fun `sync status reports rejected changes instead of success`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") { calList() }
        router.on("GET", "/calendar/v3/calendars/cal/events") { json(body = """{"items":[],"nextSyncToken":"t"}""") }
        val accounts = app.daysemble.data.google.auth.AccountStore(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { File(tmp.root, "acct.preferences_pb") },
        )
        accounts.setConnected(acct); accounts.setTasksEnabled(false)
        val manager = app.daysemble.data.google.sync.SyncManager(
            db, accounts, app.daysemble.data.google.auth.GoogleAuthManager(androidx.test.core.app.ApplicationProvider.getApplicationContext()),
            syncer(), app.daysemble.data.google.tasks.TasksSyncer(db, app.daysemble.data.google.tasks.TasksApi(http, base("/tasks/v1/")), clock),
            clock, onLocalDataChanged = {},
        )
        assertThat(manager.syncNow()).isEqualTo(app.daysemble.data.google.sync.SyncOutcome.OK)
        val cal = db.calendars().byKind(SourceKind.GOOGLE).single()
        events.create(EventDraft(title = "A", startDate = LocalDate.parse("2026-10-23"), zone = zone), cal.id)
        router.on("POST", "/calendar/v3/calendars/cal/events") { json(403, """{"error":{"errors":[{"reason":"forbidden"}]}}""") }
        assertThat(manager.syncNow()).isEqualTo(app.daysemble.data.google.sync.SyncOutcome.FAILED)
        val st = manager.status.first()
        assertThat(st.calendar.lastError).isEqualTo("rejected_changes")
        assertThat(st.hasError).isTrue()
    }

    @Test fun `RDATE instances stored from Google remind even though the RRULE parses`() {
        val now = Instant.parse("2026-10-22T06:00:00Z")
        val master = EventEntity(id = "m", calendarId = "c", title = "T", isAllDay = false,
            startUtc = Instant.parse("2026-10-01T07:00:00Z").toEpochMilli(), endUtc = Instant.parse("2026-10-01T08:00:00Z").toEpochMilli(),
            timeZone = zone.id, rrule = "RRULE:FREQ=MONTHLY;COUNT=3", isReadOnlyRecurrence = true, // had an RDATE
            rangeStartUtc = 0, rangeEndUtc = Long.MAX_VALUE, createdAt = 0, updatedAt = 0, syncState = SyncState.SYNCED)
        val extra = master.copy(id = "rd", rrule = null, isReadOnlyRecurrence = false, recurringParentId = "m", originalStartKey = "2026-10-24T09:00",
            startUtc = Instant.parse("2026-10-24T07:00:00Z").toEpochMilli(), endUtc = Instant.parse("2026-10-24T08:00:00Z").toEpochMilli())
        val plan = ReminderPlanner.forEvent(master, listOf(ReminderEntity("r", OwnerType.EVENT, "m", 10)), listOf(extra), now, zone, 540)
        assertThat(plan.map { it.triggerAt }).containsExactly(Instant.parse("2026-10-24T06:50:00Z"))
    }

    @Test fun `deleting an event whose create response was lost deletes the deterministic remote id`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") { calList() }
        syncer().refreshCalendars(acct)
        val cal = db.calendars().byKind(SourceKind.GOOGLE).single()
        val id = events.create(EventDraft(title = "A", startDate = LocalDate.parse("2026-10-23"), zone = zone), cal.id)
        router.on("POST", "/calendar/v3/calendars/cal/events") { json(503, "{}") } // server may have created it
        runCatching { syncer().pushOutbox() }
        events.delete(id)
        assertThat(db.sync().outbox().single().op).isEqualTo(OutboxOp.DELETE)
        val rid = CalendarMapper.remoteIdFor(id)
        router.on("DELETE", "/calendar/v3/calendars/cal/events/$rid") { json(204, "") }
        syncer().pushOutbox()
        assertThat(router.count("DELETE", "/calendar/v3/calendars/cal/events/$rid")).isEqualTo(1)
    }

    @Test fun `moving an event between calendars deletes the old Google copy and recreates it`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") { calList() }
        val ev = """{"id":"e1","summary":"Lunch","start":{"dateTime":"2026-10-23T12:00:00+02:00","timeZone":"Europe/Berlin"},"end":{"dateTime":"2026-10-23T13:00:00+02:00"},"updated":"2026-10-20T00:00:00Z"}"""
        router.on("GET", "/calendar/v3/calendars/cal/events") { json(body = """{"items":[$ev],"nextSyncToken":"t"}""") }
        syncer().sync(acct)
        val google = db.calendars().byKind(SourceKind.GOOGLE).single()
        val local = events.ensureDefaultCalendar("Personal")
        val id = db.events().byRemoteId(google.id, "e1")!!.id
        events.update(id, events.draftFor(id, zone)!!.copy(calendarId = local))
        assertThat(db.events().get(id)!!.calendarId).isEqualTo(local)
        router.on("DELETE", "/calendar/v3/calendars/cal/events/e1") { json(204, "") }
        router.on("GET", "/calendar/v3/calendars/cal/events") { json(body = """{"items":[{"id":"e1","status":"cancelled"}],"nextSyncToken":"t2"}""") }
        syncer().sync(acct)
        assertThat(router.count("DELETE", "/calendar/v3/calendars/cal/events/e1")).isEqualTo(1)
        assertThat(db.events().get(id)!!.title).isEqualTo("Lunch") // local copy kept
        // And back to Google with a new stable client id. The first create's response is lost (503);
        // the retry sends the SAME id, Google answers 409 and the existing copy is adopted, not duplicated.
        events.update(id, events.draftFor(id, zone)!!.copy(calendarId = google.id))
        val sent = mutableListOf<String>()
        router.on("POST", "/calendar/v3/calendars/cal/events") { r ->
            sent += Regex("\"id\":\"([a-f0-9]+)\"").find(r.body!!.utf8())!!.groupValues[1]
            if (sent.size == 1) json(503, "{}") else json(409, """{"error":{"errors":[{"reason":"duplicate"}],"code":409}}""")
        }
        runCatching { syncer().pushOutbox() }
        val cid = sent.single()
        assertThat(cid).isNotEqualTo(CalendarMapper.remoteIdFor(id))
        router.on("GET", "/calendar/v3/calendars/cal/events/$cid") {
            json(body = """{"id":"$cid","summary":"Lunch","start":{"dateTime":"2026-10-23T12:00:00+02:00"},"end":{"dateTime":"2026-10-23T13:00:00+02:00"},"updated":"2026-10-22T08:00:05Z"}""")
        }
        syncer().pushOutbox()
        assertThat(sent).containsExactly(cid, cid).inOrder()
        assertThat(db.events().get(id)!!.remoteId).isEqualTo(cid)
    }

    @Test fun `an occurrence moved beyond a bounded series still shows`() = runTest {
        val cal = events.ensureDefaultCalendar("Personal")
        val id = events.create(EventDraft(title = "Course", startDate = LocalDate.parse("2026-10-01"), startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(10, 0), zone = zone, rrule = RRule.parse("FREQ=WEEKLY;COUNT=3")), cal)
        val master = db.events().get(id)!!
        val moved = master.copy(id = "ov", rrule = null, recurringParentId = id, originalStartKey = "2026-10-15T09:00",
            startUtc = Instant.parse("2026-11-20T08:00:00Z").toEpochMilli(), endUtc = Instant.parse("2026-11-20T09:00:00Z").toEpochMilli())
        db.events().upsert(app.daysemble.data.repo.EventRepository.withEnvelope(moved))
        val occ = events.occurrences(LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-20"), zone).first()
        assertThat(occ.map { it.occurrenceKey }).containsExactly("2026-10-15T09:00")
    }

    @Test fun `planner reminds for an occurrence moved into the horizon from far away`() {
        val now = Instant.parse("2026-10-22T06:00:00Z")
        val master = EventEntity(id = "m", calendarId = "c", title = "T", isAllDay = false,
            startUtc = Instant.parse("2026-09-01T07:00:00Z").toEpochMilli(), endUtc = Instant.parse("2026-09-01T08:00:00Z").toEpochMilli(),
            timeZone = zone.id, rrule = "RRULE:FREQ=MONTHLY;COUNT=2", rangeStartUtc = 0, rangeEndUtc = Long.MAX_VALUE,
            createdAt = 0, updatedAt = 0, syncState = SyncState.LOCAL)
        val ov = master.copy(id = "o", rrule = null, recurringParentId = "m", originalStartKey = "2026-09-01T09:00",
            startUtc = Instant.parse("2026-10-23T07:00:00Z").toEpochMilli(), endUtc = Instant.parse("2026-10-23T08:00:00Z").toEpochMilli())
        val plan = ReminderPlanner.forEvent(master, listOf(ReminderEntity("r", OwnerType.EVENT, "m", 10)), listOf(ov), now, zone, 540)
        assertThat(plan.map { it.triggerAt }).containsExactly(Instant.parse("2026-10-23T06:50:00Z"))
    }

    @Test fun `unchecked google calendars disappear from views`() = runTest {
        db.calendars().upsert(CalendarSourceEntity(id = "g", kind = SourceKind.GOOGLE, remoteId = "x", accountEmail = acct, name = "G", colorArgb = 0))
        events.create(EventDraft(title = "G event", startDate = LocalDate.parse("2026-10-22"), zone = zone), "g")
        assertThat(events.occurrences(LocalDate.parse("2026-10-22"), LocalDate.parse("2026-10-22"), zone).first()).hasSize(1)
        db.calendars().setSyncEnabled("g", false)
        assertThat(events.occurrences(LocalDate.parse("2026-10-22"), LocalDate.parse("2026-10-22"), zone).first()).isEmpty()
    }

    @Test fun `time grid uses wall-clock minutes on DST days`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val springDay = LocalDate.parse("2026-03-29")
        val nine = springDay.atTime(9, 0).atZone(berlin).toInstant()
        assertThat(wallMinutes(nine, springDay, berlin)).isEqualTo(9 * 60)
        val autumn = LocalDate.parse("2026-10-25")
        assertThat(wallMinutes(autumn.atTime(9, 0).atZone(berlin).toInstant(), autumn, berlin)).isEqualTo(9 * 60)
        assertThat(wallMinutes(autumn.plusDays(1).atStartOfDay(berlin).toInstant(), autumn, berlin)).isEqualTo(24 * 60)
        // An event crossing midnight that ends 30 s into the displayed day occupies minute 0, not the whole day.
        val next = autumn.plusDays(1)
        assertThat(wallMinutes(next.atTime(0, 0, 30).atZone(berlin).toInstant(), next, berlin)).isEqualTo(0)
    }

    @Test fun `recurrence with only empty periods terminates`() {
        val r = RRule.parse("FREQ=MONTHLY;INTERVAL=12;BYMONTHDAY=31")!!
        val got = RecurrenceExpander.dates(r, LocalDate.parse("2026-02-01"), LocalDate.parse("2026-01-01"), LocalDate.parse("2099-12-31"))
        assertThat(got).isEmpty()
    }
}
