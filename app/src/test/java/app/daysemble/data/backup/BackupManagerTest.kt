package app.daysemble.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.CalendarSourceEntity
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.EventEntity
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.SleepSessionEntity
import app.daysemble.data.db.SourceKind
import app.daysemble.data.db.TaskListEntity
import app.daysemble.data.repo.EventRepository
import app.daysemble.data.repo.TaskRepository
import app.daysemble.data.sync.ChangeListener
import app.daysemble.domain.model.EventDraft
import app.daysemble.domain.model.TaskDraft
import app.daysemble.domain.model.SeriesScope
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupManagerTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val clock = FixedAppClock(Instant.parse("2026-10-22T08:00:00Z"), zone)
    private val day = LocalDate.parse("2026-10-22")
    private val opened = mutableListOf<DaysembleDatabase>()
    private lateinit var db: DaysembleDatabase
    private var imported = 0

    private fun newDb() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DaysembleDatabase::class.java)
        .allowMainThreadQueries().build().also { opened += it }

    private fun manager(d: DaysembleDatabase) = BackupManager(d, clock) { imported++ }
    private fun events(d: DaysembleDatabase) = EventRepository(d, clock, ChangeListener.None)
    private fun tasks(d: DaysembleDatabase) = TaskRepository(d, clock, ChangeListener.None)

    @Before fun setUp() { db = newDb() }
    @After fun tearDown() = opened.forEach { it.close() }

    /** On-device data of every kind, plus a mirrored phone calendar that must stay out of the file. */
    private suspend fun seed() {
        val ev = events(db)
        val cal = ev.ensureDefaultCalendar("Personal")
        val series = ev.create(
            EventDraft(title = "Standup", startDate = day, startTime = LocalTime.of(9, 0), endDate = day, endTime = LocalTime.of(9, 15), zone = zone,
                rrule = RRule.parse("FREQ=DAILY"), reminderMinutesBefore = listOf(5)),
            cal,
        )
        ev.deleteOccurrence(series, "2026-10-23T09:00")
        ev.create(EventDraft(title = "Birthday", isAllDay = true, startDate = day, endDate = day, zone = zone), cal)

        val ts = tasks(db)
        val list = ts.ensureDefaultList("Tasks")
        val parent = ts.create(TaskDraft(title = "Trip", dueDate = day, dueTime = LocalTime.of(17, 0), reminderMinutesBefore = listOf(30)), list)
        ts.create(TaskDraft(title = "Pack", parentId = parent), list)
        val water = ts.create(TaskDraft(title = "Water plants", dueDate = day, repeat = RRule.parse("FREQ=DAILY")), list)
        ts.delete(water, SeriesScope.THIS) // a skipped date, and the next occurrence
        ts.purgeExpiredTombstones(undoWindowMs = -1)

        db.sleep().upsert(SleepSessionEntity(id = "s1", startUtc = 1, endUtc = 2, startZone = zone.id, endZone = zone.id, createdAt = 1, updatedAt = 2))

        db.calendars().upsert(CalendarSourceEntity(id = "device:1", kind = SourceKind.DEVICE, remoteId = "primary", accountEmail = "a@example.com", name = "Phone", colorArgb = 0, accessRole = "reader"))
        db.events().upsert(
            EventRepository.withEnvelope(
                EventEntity(
                    id = "device:9:1", calendarId = "device:1", title = "From the phone", isAllDay = false, timeZone = zone.id,
                    startUtc = Instant.parse("2026-10-22T10:00:00Z").toEpochMilli(), endUtc = Instant.parse("2026-10-22T11:00:00Z").toEpochMilli(),
                    rangeStartUtc = 0, rangeEndUtc = 0, createdAt = 0, updatedAt = 0,
                ),
            ),
        )
    }

    private suspend fun export(d: DaysembleDatabase): ByteArray = ByteArrayOutputStream().also { manager(d).export(it) }.toByteArray()

    @Test fun `export then import into an empty device restores every on-device row`() = runTest {
        seed()
        val bytes = export(db)
        val before = manager(db).snapshot()
        // The seed really has every kind of row (so the comparisons below aren't of empty lists).
        assertThat(before.events).hasSize(2)
        assertThat(before.tasks.map { it.title }).containsAtLeast("Trip", "Pack", "Water plants")
        assertThat(before.tasks.any { it.parentId != null }).isTrue()
        assertThat(before.series).hasSize(1)
        assertThat(before.skips).hasSize(1)
        assertThat(before.reminders.map { it.ownerType }).containsAtLeast(OwnerType.EVENT, OwnerType.TASK)

        val fresh = newDb()
        val r = manager(fresh).import(ByteArrayInputStream(bytes))
        val after = manager(fresh).snapshot()

        assertThat(after.calendars.map { it.id }).containsExactlyElementsIn(before.calendars.map { it.id })
        assertThat(after.events).containsExactlyElementsIn(before.events)
        assertThat(after.lists.map { it.id }).containsExactlyElementsIn(before.lists.map { it.id })
        assertThat(after.tasks).containsExactlyElementsIn(before.tasks)
        assertThat(after.series).containsExactlyElementsIn(before.series)
        assertThat(after.skips).containsExactlyElementsIn(before.skips)
        assertThat(after.reminders).containsExactlyElementsIn(before.reminders)
        assertThat(after.sleep).containsExactlyElementsIn(before.sleep)
        assertThat(r.added).isEqualTo(BackupCounts(events = before.events.size, tasks = before.tasks.size, sleep = 1))
        assertThat(r.skipped).isEqualTo(0)
        assertThat(imported).isEqualTo(1)
        // The removed occurrence is still removed.
        assertThat(after.events.single { it.title == "Standup" }.exdates).isEqualTo("2026-10-23T09:00")
    }

    @Test fun `mirrored phone calendars and their events are not exported`() = runTest {
        seed()
        val text = export(db).decodeToString()
        assertThat(text).doesNotContain("From the phone")
        assertThat(text).doesNotContain("a@example.com")
        assertThat(text).doesNotContain("primary")
    }

    @Test fun `importing the same file again adds nothing and changes nothing`() = runTest {
        seed()
        val bytes = export(db)
        val before = manager(db).snapshot()
        val r = manager(db).import(ByteArrayInputStream(bytes))
        assertThat(r.added).isEqualTo(BackupCounts(0, 0, 0))
        assertThat(r.skipped).isGreaterThan(0)
        assertThat(imported).isEqualTo(0)
        assertThat(manager(db).snapshot().copy(exportedAt = 0)).isEqualTo(before.copy(exportedAt = 0))
    }

    @Test fun `a file that isn't a backup, or is from a newer version, is refused without writing`() = runTest {
        suspend fun reason(text: String) = runCatching { manager(db).import(ByteArrayInputStream(text.toByteArray())) }
            .exceptionOrNull().let { (it as BackupFormatException).reason }
        assertThat(reason("not json")).isEqualTo(BackupFormatException.Reason.NOT_A_BACKUP)
        assertThat(reason("""{"format":"something-else","version":1}""")).isEqualTo(BackupFormatException.Reason.NOT_A_BACKUP)
        assertThat(reason("""{"format":"daysemble-backup","version":2,"events":[]}""")).isEqualTo(BackupFormatException.Reason.NEWER_VERSION)
        assertThat(db.backup().eventIds()).isEmpty()
        assertThat(imported).isEqualTo(0)
    }

    @Test fun `a hand-edited file can't create device calendars, remote identity or broken references`() = runTest {
        val text = """
            {"format":"daysemble-backup","version":1,
             "calendars":[{"id":"c1","kind":"GOOGLE","remoteId":"x","accountEmail":"evil@example.com","name":"Mine","colorArgb":1,"syncToken":"t"}],
             "events":[
               {"id":"e1","calendarId":"c1","remoteId":"r","etag":"e","title":"Kept","isAllDay":false,"startUtc":1,"endUtc":2,"timeZone":"Europe/Berlin",
                "rangeStartUtc":1,"rangeEndUtc":2,"createdAt":1,"updatedAt":1,"syncState":"PENDING_UPDATE","isDeleted":true},
               {"id":"e2","calendarId":"missing","title":"No calendar","isAllDay":false,"timeZone":"UTC","rangeStartUtc":1,"rangeEndUtc":2,"createdAt":1,"updatedAt":1,"syncState":"LOCAL"},
               {"id":"e3","calendarId":"c1","title":"Bad zone","isAllDay":false,"timeZone":"Mars/Base","rangeStartUtc":1,"rangeEndUtc":2,"createdAt":1,"updatedAt":1,"syncState":"LOCAL"},
               {"id":"e4","calendarId":"c1","title":"Orphan change","isAllDay":false,"timeZone":"UTC","recurringParentId":"nope","rangeStartUtc":1,"rangeEndUtc":2,"createdAt":1,"updatedAt":1,"syncState":"LOCAL"}
             ],
             "taskLists":[{"id":"l1","kind":"LOCAL","title":"L"}],
             "tasks":[
               {"id":"t1","listId":"l1","parentId":"gone","title":"Sub of nothing","sortKey":1,"createdAt":1,"updatedAt":1,"syncState":"SYNCED","remoteId":"z"},
               {"id":"t2","listId":"l1","title":"Unknown series","seriesId":"s9","sortKey":2,"createdAt":1,"updatedAt":1,"syncState":"LOCAL"}
             ],
             "reminders":[
               {"id":"forged","ownerType":"EVENT","ownerId":"e1","minutesBefore":10},
               {"id":"x","ownerType":"EVENT","ownerId":"e1","minutesBefore":-5},
               {"id":"y","ownerType":"TASK","ownerId":"nobody","minutesBefore":5}
             ],
             "futureField":{"ignored":true}}
        """.trimIndent()
        val r = manager(db).import(ByteArrayInputStream(text.toByteArray()))
        assertThat(r.added).isEqualTo(BackupCounts(events = 1, tasks = 1, sleep = 0))
        assertThat(r.skipped).isEqualTo(4) // e2, e3, e4, t2

        val cal = db.calendars().get("c1")!!
        assertThat(cal.kind).isEqualTo(SourceKind.LOCAL)
        assertThat(cal.accountEmail).isNull()
        assertThat(cal.syncToken).isNull()
        val e = db.events().get("e1")!!
                assertThat(e.remoteId).isNull()
        assertThat(e.isDeleted).isFalse()
        val t = db.tasks().get("t1")!!
        assertThat(t.parentId).isNull()
                assertThat(t.remoteId).isNull()
        val reminders = db.reminders().all()
        assertThat(reminders.map { it.id }).containsExactly("EVENT:e1:10")
        assertThat(reminders.single().ownerType).isEqualTo(OwnerType.EVENT)
    }

    private suspend fun importText(text: String) = manager(db).import(ByteArrayInputStream(text.trimIndent().toByteArray()))

    @Test fun `an id that matches a mirrored phone calendar doesn't bring imported rows into it`() = runTest {
        db.calendars().upsert(CalendarSourceEntity(id = "g", kind = SourceKind.DEVICE, remoteId = "primary", accountEmail = "a@example.com", name = "Phone", colorArgb = 0, accessRole = "reader"))
        val r = importText(
            """
            {"format":"daysemble-backup","version":1,
             "calendars":[{"id":"g","kind":"LOCAL","name":"Mine","colorArgb":1}],
             "events":[{"id":"e1","calendarId":"g","title":"Into the phone calendar?","isAllDay":false,"timeZone":"UTC","rangeStartUtc":1,"rangeEndUtc":2,"createdAt":1,"updatedAt":1}],
             "taskLists":[{"id":"l1","kind":"LOCAL","title":"L"}],
             "tasks":[{"id":"t1","listId":"l1","title":"Kept","sortKey":1,"createdAt":1,"updatedAt":1}]}
            """,
        )
        assertThat(db.events().get("e1")).isNull()
        assertThat(db.calendars().get("g")!!.kind).isEqualTo(SourceKind.DEVICE)
        assertThat(r.added).isEqualTo(BackupCounts(events = 0, tasks = 1, sleep = 0))
    }

    @Test fun `a backup from before Google sync was removed still imports`() = runTest {
        // Old files can hold the removed GOOGLE kind and SYNCED state; both become on-device defaults.
        val r = importText(
            """
            {"format":"daysemble-backup","version":1,
             "calendars":[{"id":"c1","kind":"GOOGLE","remoteId":"x","accountEmail":"me@example.com","name":"Old","colorArgb":1,"syncToken":"t"}],
             "events":[{"id":"e1","calendarId":"c1","remoteId":"r","etag":"e","title":"Old event","isAllDay":false,"startUtc":1,"endUtc":2,"timeZone":"UTC","rangeStartUtc":1,"rangeEndUtc":2,"createdAt":1,"updatedAt":1,"syncState":"SYNCED"}],
             "taskLists":[{"id":"l1","kind":"GOOGLE","remoteId":"L","accountEmail":"me@example.com","title":"Old list"}],
             "tasks":[{"id":"t1","listId":"l1","remoteId":"z","title":"Old task","sortKey":1,"createdAt":1,"updatedAt":1,"syncState":"PENDING_UPDATE"}]}
            """,
        )
        assertThat(r.added).isEqualTo(BackupCounts(events = 1, tasks = 1, sleep = 0))
        assertThat(db.calendars().get("c1")!!.kind).isEqualTo(SourceKind.LOCAL)
        assertThat(db.calendars().get("c1")!!.accountEmail).isNull()
        assertThat(db.events().get("e1")!!.remoteId).isNull()
        assertThat(db.taskLists().get("l1")!!.kind).isEqualTo(SourceKind.LOCAL)
        assertThat(db.tasks().get("t1")!!.remoteId).isNull()
    }

    @Test fun `a file that only adds reminders re-plans reminders`() = runTest {
        seed()
        val snap = manager(db).snapshot()
        val trip = snap.tasks.single { it.title == "Trip" }
        val withMore = snap.copy(reminders = snap.reminders + app.daysemble.data.db.ReminderEntity.of(OwnerType.TASK, trip.id, 120))
        val bytes = kotlinx.serialization.json.Json.encodeToString(BackupFile.serializer(), withMore).toByteArray()
        val r = manager(db).import(ByteArrayInputStream(bytes))
        assertThat(r.added).isEqualTo(BackupCounts(0, 0, 0))
        assertThat(r.changed).isTrue()
        assertThat(imported).isEqualTo(1)
        assertThat(db.reminders().forOwner(OwnerType.TASK, trip.id).map { it.minutesBefore }).containsExactly(30, 120)
    }

    @Test fun `out-of-range dates and times, and changes to a single event, are skipped`() = runTest {
        val r = importText(
            """
            {"format":"daysemble-backup","version":1,
             "calendars":[{"id":"c1","kind":"LOCAL","name":"Mine","colorArgb":1}],
             "events":[
               {"id":"single","calendarId":"c1","title":"Single","isAllDay":false,"timeZone":"UTC","rangeStartUtc":1,"rangeEndUtc":2,"createdAt":1,"updatedAt":1,"syncState":"LOCAL"},
               {"id":"change","calendarId":"c1","title":"Change of a single","isAllDay":false,"timeZone":"UTC","recurringParentId":"single","originalStartKey":"x","rangeStartUtc":1,"rangeEndUtc":2,"createdAt":1,"updatedAt":1,"syncState":"LOCAL"},
               {"id":"day","calendarId":"c1","title":"Bad day","isAllDay":true,"startDate":9999999999999,"endDateExclusive":9999999999999,"timeZone":"UTC","rangeStartUtc":1,"rangeEndUtc":2,"createdAt":1,"updatedAt":1,"syncState":"LOCAL"}
             ],
             "taskLists":[{"id":"l1","kind":"LOCAL","title":"L"}],
             "tasks":[
               {"id":"t1","listId":"l1","title":"Bad date","dueDate":9999999999999,"sortKey":1,"createdAt":1,"updatedAt":1,"syncState":"LOCAL"},
               {"id":"t2","listId":"l1","title":"Bad time","dueDate":20000,"dueTimeMinutes":2000,"sortKey":2,"createdAt":1,"updatedAt":1,"syncState":"LOCAL"},
               {"id":"t3","listId":"l1","title":"Fine","dueDate":20000,"dueTimeMinutes":1439,"sortKey":3,"createdAt":1,"updatedAt":1,"syncState":"LOCAL"}
             ]}
            """,
        )
        assertThat(r.added).isEqualTo(BackupCounts(events = 1, tasks = 1, sleep = 0))
        assertThat(r.skipped).isEqualTo(4) // change, day, t1, t2
        // What was stored can be read back.
        assertThat(tasks(db).get("t3")!!.dueTime).isEqualTo(LocalTime.of(23, 59))
    }

    @Test fun `events that could never be shown and backwards sleep are skipped`() = runTest {
        val r = importText(
            """
            {"format":"daysemble-backup","version":1,
             "calendars":[{"id":"c1","kind":"LOCAL","name":"Mine","colorArgb":1}],
             "events":[
               {"id":"empty","calendarId":"c1","title":"Empty range","isAllDay":false,"timeZone":"UTC","rangeStartUtc":5,"rangeEndUtc":5,"createdAt":1,"updatedAt":1,"syncState":"LOCAL"},
               {"id":"back","calendarId":"c1","title":"Ends first","isAllDay":false,"startUtc":10,"endUtc":5,"timeZone":"UTC","rangeStartUtc":1,"rangeEndUtc":20,"createdAt":1,"updatedAt":1,"syncState":"LOCAL"},
               {"id":"ok","calendarId":"c1","title":"Fine","isAllDay":false,"startUtc":5,"endUtc":10,"timeZone":"UTC","rangeStartUtc":5,"rangeEndUtc":10,"createdAt":1,"updatedAt":1,"syncState":"LOCAL"}
             ],
             "sleep":[
               {"id":"s1","startUtc":200,"endUtc":100,"createdAt":1,"updatedAt":1},
               {"id":"s2","startUtc":100,"endUtc":200,"createdAt":1,"updatedAt":1}
             ]}
            """,
        )
        assertThat(r.added).isEqualTo(BackupCounts(events = 1, tasks = 0, sleep = 1))
        assertThat(r.skipped).isEqualTo(3) // empty, back, s1
    }

    @Test fun `a failure while re-planning reminders doesn't fail the import`() = runTest {
        seed()
        val bytes = export(db)
        val fresh = newDb()
        val r = BackupManager(fresh, clock) { error("alarm service unavailable") }.import(ByteArrayInputStream(bytes))
        assertThat(r.added.events).isEqualTo(2)
        assertThat(fresh.backup().eventIds()).hasSize(2)
    }

    @Test fun `a file over the size limit is refused before parsing`() = runTest {
        val huge = object : java.io.InputStream() {
            var left = BackupManager.MAX_FILE_BYTES + 1
            override fun read(): Int = if (left-- > 0) ' '.code else -1
        }
        val e = runCatching { manager(db).import(huge) }.exceptionOrNull()
        assertThat((e as BackupFormatException).reason).isEqualTo(BackupFormatException.Reason.TOO_LARGE)
    }
}
