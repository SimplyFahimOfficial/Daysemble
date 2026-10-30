package app.daysemble.data.google

import app.daysemble.data.db.OutboxOp
import app.daysemble.data.db.SourceKind
import app.daysemble.data.db.SyncState
import app.daysemble.data.google.calendar.CalendarApi
import app.daysemble.data.google.calendar.CalendarMapper
import app.daysemble.data.google.calendar.CalendarSyncer
import app.daysemble.domain.model.EventDraft
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarSyncTest : SyncTestBase() {
    private val acct = "me@example.com"
    private fun syncer() = CalendarSyncer(db, CalendarApi(http, base("/calendar/v3/")), clock)

    private fun calendarList(extra: String = "") = json(body = """
        {"items":[{"id":"primary@x","summary":"Me","backgroundColor":"#4285f4","accessRole":"owner","primary":true,"timeZone":"Europe/Berlin",
                   "defaultReminders":[{"method":"popup","minutes":30},{"method":"email","minutes":60}]}$extra]}""")

    private val ev1 = """{"id":"ev1","status":"confirmed","etag":"\"e1\"","summary":"Dentist","location":"Main St",
        "start":{"dateTime":"2026-10-22T10:00:00+02:00","timeZone":"Europe/Berlin"},"end":{"dateTime":"2026-10-22T11:00:00+02:00"},
        "updated":"2026-10-20T08:00:00.000Z","reminders":{"useDefault":true}}"""
    private val series = """{"id":"s1","status":"confirmed","summary":"Standup","start":{"dateTime":"2026-10-19T09:00:00+02:00","timeZone":"Europe/Berlin"},
        "end":{"dateTime":"2026-10-19T09:15:00+02:00","timeZone":"Europe/Berlin"},"recurrence":["RRULE:FREQ=DAILY","EXDATE;TZID=Europe/Berlin:20261021T090000"],
        "updated":"2026-10-01T08:00:00.000Z"}"""
    private val moved = """{"id":"s1_20261023T070000Z","status":"confirmed","summary":"Standup (moved)","recurringEventId":"s1",
        "originalStartTime":{"dateTime":"2026-10-23T09:00:00+02:00","timeZone":"Europe/Berlin"},
        "start":{"dateTime":"2026-10-23T15:00:00+02:00"},"end":{"dateTime":"2026-10-23T15:15:00+02:00"},"updated":"2026-10-02T08:00:00.000Z"}"""
    private val cancelledInstance = """{"id":"s1_20261024T070000Z","status":"cancelled","recurringEventId":"s1",
        "originalStartTime":{"dateTime":"2026-10-24T09:00:00+02:00","timeZone":"Europe/Berlin"}}"""

    @Test fun `full sync pages through results, maps series exceptions and stores the sync token`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") { calendarList() }
        router.on("GET", "/calendar/v3/calendars/primary@x/events") { r ->
            // Exception arrives on page 1 before its master on page 2: must still attach.
            if (r.url.queryParameter("pageToken") == null) json(body = """{"items":[$moved,$ev1],"nextPageToken":"p2"}""")
            else json(body = """{"items":[$series,$cancelledInstance],"nextSyncToken":"tok1"}""")
        }
        syncer().sync(acct)
        val cal = db.calendars().byKind(SourceKind.GOOGLE).single()
        assertThat(cal.syncToken).isEqualTo("tok1")
        assertThat(cal.defaultReminders).isEqualTo("30")

        val week = events.occurrences(LocalDate.parse("2026-10-19"), LocalDate.parse("2026-10-25"), zone).first()
        val standups = week.filter { it.eventId == db.events().byRemoteId(cal.id, "s1")!!.id }
        assertThat(standups.map { it.occurrenceKey }).containsExactly(
            "2026-10-19T09:00", "2026-10-20T09:00", "2026-10-22T09:00", "2026-10-23T09:00", "2026-10-25T09:00",
        ).inOrder() // 21st EXDATE, 24th cancelled
        val movedOcc = standups.single { it.occurrenceKey == "2026-10-23T09:00" }
        assertThat(movedOcc.title).isEqualTo("Standup (moved)")
        assertThat(movedOcc.start.atZone(zone).toLocalTime()).isEqualTo(LocalTime.of(15, 0))
        // useDefault reminders come from the calendar's popup defaults.
        val dentist = db.events().byRemoteId(cal.id, "ev1")!!
        assertThat(events.reminders(dentist.id)).containsExactly(30)
    }

    @Test fun `incremental sync updates in place without duplicates and applies remote deletes`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") { calendarList() }
        router.on("GET", "/calendar/v3/calendars/primary@x/events") { json(body = """{"items":[$ev1],"nextSyncToken":"tok1"}""") }
        syncer().sync(acct)
        val cal = db.calendars().byKind(SourceKind.GOOGLE).single()
        val localId = db.events().byRemoteId(cal.id, "ev1")!!.id

        router.on("GET", "/calendar/v3/calendars/primary@x/events") { r ->
            assertThat(r.url.queryParameter("syncToken")).isEqualTo("tok1")
            json(body = """{"items":[${ev1.replace("Dentist", "Dentist (new time)").replace("2026-10-20T08", "2026-10-21T08")}],"nextSyncToken":"tok2"}""")
        }
        syncer().sync(acct)
        assertThat(db.events().allIn(cal.id)).hasSize(1)
        assertThat(db.events().get(localId)!!.title).isEqualTo("Dentist (new time)")

        router.on("GET", "/calendar/v3/calendars/primary@x/events") { json(body = """{"items":[{"id":"ev1","status":"cancelled"}],"nextSyncToken":"tok3"}""") }
        syncer().sync(acct)
        assertThat(db.events().allIn(cal.id)).isEmpty()
    }

    @Test fun `410 gone drops the token and does a full resync reconciling by remote id`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") { calendarList() }
        router.on("GET", "/calendar/v3/calendars/primary@x/events") { json(body = """{"items":[$ev1,$series],"nextSyncToken":"tok1"}""") }
        syncer().sync(acct)
        val cal = db.calendars().byKind(SourceKind.GOOGLE).single()
        val idBefore = db.events().byRemoteId(cal.id, "ev1")!!.id

        router.on("GET", "/calendar/v3/calendars/primary@x/events") { r ->
            if (r.url.queryParameter("syncToken") != null) json(410, """{"error":{"code":410}}""")
            else json(body = """{"items":[$ev1],"nextSyncToken":"fresh"}""") // series deleted meanwhile
        }
        syncer().sync(acct)
        assertThat(db.calendars().get(cal.id)!!.syncToken).isEqualTo("fresh")
        assertThat(db.events().byRemoteId(cal.id, "ev1")!!.id).isEqualTo(idBefore)
        assertThat(db.events().byRemoteId(cal.id, "s1")).isNull()
    }

    @Test fun `local create is pushed with a client id, a retried create adopts the existing event`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") { calendarList() }
        router.on("GET", "/calendar/v3/calendars/primary@x/events") { json(body = """{"items":[],"nextSyncToken":"t"}""") }
        syncer().refreshCalendars(acct)
        val cal = db.calendars().byKind(SourceKind.GOOGLE).single()
        val id = events.create(EventDraft(title = "Lunch", startDate = LocalDate.parse("2026-10-23"), startTime = LocalTime.NOON,
            endDate = LocalDate.parse("2026-10-23"), endTime = LocalTime.of(13, 0), zone = zone, reminderMinutesBefore = listOf(10)), cal.id)
        val clientId = CalendarMapper.remoteIdFor(id)

        // Simulate: the server already has it (earlier attempt's response was lost) -> 409, then GET.
        router.on("POST", "/calendar/v3/calendars/primary@x/events") { json(409, """{"error":{"errors":[{"reason":"duplicate"}],"code":409}}""") }
        val remote = """{"id":"$clientId","etag":"\"x\"","summary":"Lunch","start":{"dateTime":"2026-10-23T12:00:00+02:00"},"end":{"dateTime":"2026-10-23T13:00:00+02:00"},"updated":"2026-10-22T08:00:01.000Z"}"""
        router.on("GET", "/calendar/v3/calendars/primary@x/events") { json(body = """{"items":[$remote],"nextSyncToken":"t2"}""") }
        router.on("GET", "/calendar/v3/calendars/primary@x/events/$clientId") { json(body = remote) }
        syncer().sync(acct)
        val row = db.events().get(id)!!
        assertThat(row.remoteId).isEqualTo(clientId)
        assertThat(row.syncState).isEqualTo(SyncState.SYNCED)
        assertThat(db.sync().outbox()).isEmpty()
        val post = router.requests.first { it.method == "POST" }.body!!.utf8()
        assertThat(post).contains("\"id\":\"$clientId\"")
        assertThat(post).contains("\"timeZone\":\"Europe/Berlin\"")
        assertThat(post).contains("\"minutes\":10")
        assertThat(post).contains("\"method\":\"popup\"")
        assertThat(post).contains("\"dateTime\":\"2026-10-23T12:00:00+02:00\"")
    }

    @Test fun `412 on update with a newer remote edit lets the remote win and logs the conflict`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") { calendarList() }
        router.on("GET", "/calendar/v3/calendars/primary@x/events") { json(body = """{"items":[$ev1],"nextSyncToken":"tok1"}""") }
        syncer().sync(acct)
        val cal = db.calendars().byKind(SourceKind.GOOGLE).single()
        val localId = db.events().byRemoteId(cal.id, "ev1")!!.id
        // Local edit made "yesterday" (older than the remote edit below).
        clock.instant = java.time.Instant.parse("2026-10-21T00:00:00Z")
        events.update(localId, events.draftFor(localId, zone)!!.copy(title = "Dentist (mine)"))
        clock.instant = java.time.Instant.parse("2026-10-22T08:00:00Z")

        val theirs = ev1.replace("Dentist", "Dentist (theirs)").replace("2026-10-20T08:00:00.000Z", "2026-10-21T12:00:00.000Z")
        router.on("GET", "/calendar/v3/calendars/primary@x/events") { json(body = """{"items":[$theirs],"nextSyncToken":"tok2"}""") }
        router.on("PATCH", "/calendar/v3/calendars/primary@x/events/ev1") { json(412, """{"error":{"code":412}}""") }
        router.on("GET", "/calendar/v3/calendars/primary@x/events/ev1") { json(body = theirs) }
        val r = syncer().sync(acct)
        assertThat(r.conflicts).isEqualTo(1)
        assertThat(db.events().get(localId)!!.title).isEqualTo("Dentist (theirs)")
        assertThat(db.sync().observeConflicts().first().single().resolvedWith).isEqualTo("REMOTE")
        assertThat(db.sync().outbox()).isEmpty()
    }

    @Test fun `remote delete of an item with unpushed local edits keeps the local version`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") { calendarList() }
        router.on("GET", "/calendar/v3/calendars/primary@x/events") { json(body = """{"items":[$ev1],"nextSyncToken":"tok1"}""") }
        syncer().sync(acct)
        val cal = db.calendars().byKind(SourceKind.GOOGLE).single()
        val localId = db.events().byRemoteId(cal.id, "ev1")!!.id
        events.update(localId, events.draftFor(localId, zone)!!.copy(title = "Keep me"))

        router.on("PATCH", "/calendar/v3/calendars/primary@x/events/ev1") { json(404, "{}") }
        var sentId: String? = null
        router.on("POST", "/calendar/v3/calendars/primary@x/events") { r ->
            sentId = Regex("\"id\":\"([a-f0-9]+)\"").find(r.body!!.utf8())!!.groupValues[1]
            // A fresh client id: not the one the deleted copy still holds.
            assertThat(sentId).isNotEqualTo(CalendarMapper.remoteIdFor(localId))
            json(body = """{"id":"$sentId","etag":"\"n\"","summary":"Keep me","updated":"2026-10-22T08:00:02.000Z"}""")
        }
        router.on("GET", "/calendar/v3/calendars/primary@x/events") { json(body = """{"items":[],"nextSyncToken":"tok2"}""") }
        syncer().sync(acct) // PATCH 404 -> marks for re-create
        syncer().sync(acct) // re-create
        val row = db.events().get(localId)!!
        assertThat(row.title).isEqualTo("Keep me")
        assertThat(row.remoteId).isEqualTo(sentId)
        assertThat(row.pendingClientId).isNull()
    }

    @Test fun `a server error keeps the op queued and stops, non-retryable errors are recorded`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") { calendarList() }
        syncer().refreshCalendars(acct)
        val cal = db.calendars().byKind(SourceKind.GOOGLE).single()
        events.create(EventDraft(title = "A", startDate = LocalDate.parse("2026-10-23"), zone = zone), cal.id)
        router.on("POST", "/calendar/v3/calendars/primary@x/events") { json(503, "{}") }
        val err = runCatching { syncer().sync(acct) }.exceptionOrNull()
        assertThat(err).isInstanceOf(ApiError.Server::class.java)
        val op = db.sync().outbox().single()
        assertThat(op.op).isEqualTo(OutboxOp.CREATE)
        assertThat(op.attempts).isEqualTo(1)
        assertThat(op.lastError).isEqualTo("server_503")
    }

    @Test fun `deleting locally deletes remotely, 404 counts as already deleted`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") { calendarList() }
        router.on("GET", "/calendar/v3/calendars/primary@x/events") { json(body = """{"items":[$ev1],"nextSyncToken":"tok1"}""") }
        syncer().sync(acct)
        val cal = db.calendars().byKind(SourceKind.GOOGLE).single()
        events.delete(db.events().byRemoteId(cal.id, "ev1")!!.id)
        router.on("DELETE", "/calendar/v3/calendars/primary@x/events/ev1") { json(404, "{}") }
        router.on("GET", "/calendar/v3/calendars/primary@x/events") { json(body = """{"items":[],"nextSyncToken":"tok2"}""") }
        syncer().sync(acct)
        assertThat(router.count("DELETE", "/calendar/v3/calendars/primary@x/events/ev1")).isEqualTo(1)
        assertThat(db.events().allIn(cal.id)).isEmpty()
        assertThat(db.sync().outbox()).isEmpty()
    }

    @Test fun `401 refreshes the token once and retries`() = runTest {
        var calls = 0
        router.on("GET", "/calendar/v3/users/me/calendarList") { r ->
            calls++
            if (r.headers["Authorization"] == "Bearer t1") json(401, "{}") else calendarList()
        }
        syncer().refreshCalendars(acct)
        assertThat(calls).isEqualTo(2)
        assertThat(tokens.invalidated).containsExactly("t1")
        assertThat(db.calendars().byKind(SourceKind.GOOGLE)).hasSize(1)
    }

    @Test fun `removed calendars disappear locally, read-only calendars are not written to`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") {
            calendarList(""",{"id":"hol","summary":"Holidays","accessRole":"reader"}""")
        }
        syncer().refreshCalendars(acct)
        assertThat(db.calendars().byKind(SourceKind.GOOGLE)).hasSize(2)
        router.on("GET", "/calendar/v3/users/me/calendarList") { calendarList() }
        syncer().refreshCalendars(acct)
        assertThat(db.calendars().byKind(SourceKind.GOOGLE).map { it.remoteId }).containsExactly("primary@x")
    }

    /**
     * A change to an event in a calendar the user can only read can never be pushed. It is a
     * permanent rejection: parked after MAX_ATTEMPTS (then skipped, and offered under "rejected
     * changes" with Retry), not retried and failed on every sync run forever. Nothing is sent.
     */
    @Test fun `a change in a read-only calendar is parked, not retried forever`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") {
            calendarList(""",{"id":"hol","summary":"Holidays","accessRole":"reader"}""")
        }
        syncer().refreshCalendars(acct)
        val hol = db.calendars().byKind(SourceKind.GOOGLE).single { it.remoteId == "hol" }
        val t = clock.now().toEpochMilli()
        db.events().upsert(
            app.daysemble.data.db.EventEntity(
                id = "e1", calendarId = hol.id, remoteId = "r1", title = "Edited", isAllDay = false,
                startUtc = t, endUtc = t + 3_600_000, timeZone = "Europe/Berlin", rangeStartUtc = t, rangeEndUtc = t + 3_600_000,
                createdAt = t, updatedAt = t, syncState = app.daysemble.data.db.SyncState.PENDING_UPDATE,
            ),
        )
        db.sync().enqueue(app.daysemble.data.db.OutboxEntity(entityType = app.daysemble.data.db.OwnerType.EVENT, entityId = "e1", op = app.daysemble.data.db.OutboxOp.UPDATE, createdAt = t))
        val failedPerRun = (1..CalendarSyncer.MAX_ATTEMPTS + 2).map { syncer().pushOutbox().third }
        assertThat(failedPerRun.take(CalendarSyncer.MAX_ATTEMPTS)).containsExactlyElementsIn(List(CalendarSyncer.MAX_ATTEMPTS) { 1 })
        assertThat(failedPerRun.drop(CalendarSyncer.MAX_ATTEMPTS)).containsExactly(0, 0) // parked: skipped
        val op = db.sync().outbox().single()
        assertThat(op.permanentFailures).isEqualTo(CalendarSyncer.MAX_ATTEMPTS)
        assertThat(op.lastError).isEqualTo("read_only")
        assertThat(router.count("PATCH", "/calendar/v3/calendars/hol")).isEqualTo(0)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarInstancesTest : SyncTestBase() {
    @Test fun `series with an unsupported rule shows Google's expanded instances`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") {
            json(body = """{"items":[{"id":"cal","summary":"Me","accessRole":"owner","timeZone":"Europe/Berlin"}]}""")
        }
        val master = """{"id":"m","status":"confirmed","summary":"Last weekday","start":{"dateTime":"2026-10-30T09:00:00+01:00","timeZone":"Europe/Berlin"},
            "end":{"dateTime":"2026-10-30T09:30:00+01:00","timeZone":"Europe/Berlin"},"recurrence":["RRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1"],"updated":"2026-10-01T00:00:00Z"}"""
        router.on("GET", "/calendar/v3/calendars/cal/events") { json(body = """{"items":[$master],"nextSyncToken":"t"}""") }
        fun inst(d: String) = """{"id":"m_$d","summary":"Last weekday","recurringEventId":"m","originalStartTime":{"dateTime":"${d}T09:00:00+01:00"},
            "start":{"dateTime":"${d}T09:00:00+01:00"},"end":{"dateTime":"${d}T09:30:00+01:00"},"updated":"2026-10-01T00:00:00Z"}"""
        router.on("GET", "/calendar/v3/calendars/cal/events/m/instances") {
            json(body = """{"items":[${inst("2026-10-30")},${inst("2026-11-30")},${inst("2026-12-31")}]}""")
        }
        CalendarSyncer(db, CalendarApi(http, base("/calendar/v3/")), clock).sync("me@example.com")
        val occ = events.occurrences(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-31"), zone).first()
        assertThat(occ.map { it.startDate.toString() }).containsExactly("2026-10-30", "2026-11-30", "2026-12-31").inOrder()
        assertThat(occ.all { it.isReadOnly }).isTrue()
    }
}
