package app.daysemble.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.EventEntity
import app.daysemble.data.db.SourceKind
import app.daysemble.data.db.CalendarSourceEntity
import app.daysemble.data.sync.ChangeListener
import app.daysemble.domain.model.EventDraft
import app.daysemble.domain.model.EventValidation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventRepositoryTest {

    private lateinit var db: DaysembleDatabase
    private lateinit var repo: EventRepository
    private val berlin = ZoneId.of("Europe/Berlin")
    private val clock = FixedAppClock(Instant.parse("2026-10-22T08:00:00Z"), berlin)
    private lateinit var cal: String

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DaysembleDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = EventRepository(db, clock, ChangeListener.None)
        cal = repo.ensureDefaultCalendar("Personal")
    }

    @After fun tearDown() = db.close()

    private fun d(s: String) = LocalDate.parse(s)
    private fun draft(title: String = "Standup", date: String = "2026-10-22", start: String = "09:00", end: String = "09:30", zone: ZoneId = berlin) =
        EventDraft(title = title, startDate = d(date), startTime = LocalTime.parse(start), endDate = d(date), endTime = LocalTime.parse(end), zone = zone)

    @Test
    fun `validation`() {
        assertThat(draft(title = " ").validationError).isEqualTo(EventValidation.TITLE_REQUIRED)
        assertThat(draft(start = "10:00", end = "09:00").validationError).isEqualTo(EventValidation.END_BEFORE_START)
        assertThat(draft().validationError).isNull()
    }

    @Test
    fun `single timed event appears only on its day`() = runTest {
        repo.create(draft(), cal)
        assertThat(repo.occurrences(d("2026-10-22"), d("2026-10-22"), berlin).first()).hasSize(1)
        assertThat(repo.occurrences(d("2026-10-23"), d("2026-10-23"), berlin).first()).isEmpty()
        assertThat(repo.occurrences(d("2026-10-21"), d("2026-10-21"), berlin).first()).isEmpty()
    }

    @Test
    fun `event crossing midnight appears on both days, ending at midnight does not`() = runTest {
        repo.create(draft(title = "Late").copy(startTime = LocalTime.of(23, 0), endDate = d("2026-10-23"), endTime = LocalTime.of(1, 0)), cal)
        repo.create(draft(title = "ToMidnight").copy(startTime = LocalTime.of(22, 0), endDate = d("2026-10-23"), endTime = LocalTime.MIDNIGHT), cal)
        val day2 = repo.occurrences(d("2026-10-23"), d("2026-10-23"), berlin).first()
        assertThat(day2.map { it.title }).containsExactly("Late")
        val late = day2.single()
        assertThat(late.startDate).isEqualTo(d("2026-10-22"))
        assertThat(late.endDate).isEqualTo(d("2026-10-24"))
    }

    @Test
    fun `all-day events keep their date in every zone`() = runTest {
        repo.create(draft(title = "Holiday").copy(isAllDay = true), cal)
        for (zone in listOf("Pacific/Kiritimati", "Pacific/Pago_Pago", "UTC", "Asia/Kolkata").map(ZoneId::of)) {
            val occ = repo.occurrences(d("2026-10-22"), d("2026-10-22"), zone).first()
            assertThat(occ.map { it.title }).containsExactly("Holiday")
            assertThat(occ.single().startDate).isEqualTo(d("2026-10-22"))
            assertThat(repo.occurrences(d("2026-10-21"), d("2026-10-21"), zone).first()).isEmpty()
            assertThat(repo.occurrences(d("2026-10-23"), d("2026-10-23"), zone).first()).isEmpty()
        }
    }

    @Test
    fun `timed events shift with the viewing zone`() = runTest {
        repo.create(draft(title = "Call", start = "01:00", end = "02:00"), cal) // 23:00Z on the 21st
        val ny = ZoneId.of("America/New_York")
        assertThat(repo.occurrences(d("2026-10-21"), d("2026-10-21"), ny).first().map { it.title }).containsExactly("Call")
        assertThat(repo.occurrences(d("2026-10-22"), d("2026-10-22"), ny).first()).isEmpty()
    }

    @Test
    fun `recurring series expands, delete one occurrence adds exdate`() = runTest {
        val id = repo.create(draft().copy(rrule = RRule.parse("FREQ=DAILY;COUNT=5")), cal)
        val week = repo.occurrences(d("2026-10-19"), d("2026-10-31"), berlin).first()
        assertThat(week.map { it.occurrenceKey }).containsExactly(
            "2026-10-22T09:00", "2026-10-23T09:00", "2026-10-24T09:00", "2026-10-25T09:00", "2026-10-26T09:00",
        ).inOrder()
        // DST ends on the 25th; still 09:00 local.
        assertThat(week.map { it.start.atZone(berlin).toLocalTime() }.toSet()).containsExactly(LocalTime.of(9, 0))

        repo.deleteOccurrence(id, "2026-10-24T09:00")
        val after = repo.occurrences(d("2026-10-19"), d("2026-10-31"), berlin).first()
        assertThat(after.map { it.occurrenceKey }).doesNotContain("2026-10-24T09:00")
        assertThat(after).hasSize(4)

        // Envelope ends with the COUNT; nothing far in the future.
        assertThat(repo.occurrences(d("2027-01-01"), d("2027-01-31"), berlin).first()).isEmpty()
    }

    @Test
    fun `infinite series is visible far in the future`() = runTest {
        repo.create(draft().copy(rrule = RRule.parse("FREQ=WEEKLY")), cal)
        assertThat(repo.occurrences(d("2030-06-01"), d("2030-06-30"), berlin).first()).isNotEmpty()
    }

    @Test
    fun `update and draft round trip`() = runTest {
        val id = repo.create(draft().copy(location = "Room 4", reminderMinutesBefore = listOf(10, 0)), cal)
        val loaded = repo.draftFor(id, berlin)!!
        assertThat(loaded.location).isEqualTo("Room 4")
        assertThat(loaded.startTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(loaded.reminderMinutesBefore).containsExactly(0, 10).inOrder()
        repo.update(id, loaded.copy(title = "Retro", endTime = LocalTime.of(10, 0)))
        val occ = repo.occurrences(d("2026-10-22"), d("2026-10-22"), berlin).first().single()
        assertThat(occ.title).isEqualTo("Retro")
        assertThat(occ.end).isEqualTo(Instant.parse("2026-10-22T08:00:00Z"))
    }

    @Test
    fun `hidden calendars are excluded`() = runTest {
        repo.create(draft(), cal)
        db.calendars().setVisible(cal, false)
        assertThat(repo.occurrences(d("2026-10-22"), d("2026-10-22"), berlin).first()).isEmpty()
    }

    @Test
    fun `mirrored device events can't be created, changed, moved or deleted`() = runTest {
        db.calendars().upsert(CalendarSourceEntity(id = "device:1", kind = SourceKind.DEVICE, remoteId = "1", name = "Work", colorArgb = 0, accessRole = "reader"))
        val mirrored = EventRepository.withEnvelope(
            EventEntity(
                id = "device:7:1", calendarId = "device:1", title = "Standup", isAllDay = false, timeZone = berlin.id,
                startUtc = Instant.parse("2026-10-22T07:00:00Z").toEpochMilli(), endUtc = Instant.parse("2026-10-22T07:30:00Z").toEpochMilli(),
                rangeStartUtc = 0, rangeEndUtc = 0, createdAt = 0, updatedAt = 0,
            ),
        )
        db.events().upsert(mirrored)

        assertThat(runCatching { repo.create(draft().copy(calendarId = "device:1"), cal) }.isFailure).isTrue()
        assertThat(runCatching { repo.update(mirrored.id, draft(title = "Changed")) }.isFailure).isTrue()
        assertThat(runCatching { repo.moveToCalendar(mirrored.id, cal) }.isFailure).isTrue()
        assertThat(repo.delete(mirrored.id)).isNull()
        assertThat(repo.deleteOccurrence(mirrored.id, "2026-10-22T09:00")).isNull()

        val shown = repo.occurrences(d("2026-10-22"), d("2026-10-22"), berlin).first().single()
        assertThat(shown.title).isEqualTo("Standup")
        assertThat(shown.isDevice).isTrue()
        assertThat(shown.isReadOnly).isTrue()
    }

    @Test
    fun `moving to another calendar and editing are saved together`() = runTest {
        db.calendars().upsert(CalendarSourceEntity(id = "work", kind = SourceKind.LOCAL, name = "Work", colorArgb = 0))
        val id = repo.create(draft(), cal)
        repo.update(id, draft(title = "Planning").copy(calendarId = "work"))
        val row = repo.get(id)!!
        assertThat(row.calendarId).isEqualTo("work")
        assertThat(row.title).isEqualTo("Planning")
    }

    @Test
    fun `saving into a calendar that is gone fails and changes nothing`() = runTest {
        val id = repo.create(draft(), cal)
        val result = runCatching { repo.update(id, draft(title = "Planning").copy(calendarId = "gone")) }
        // The editor reports the failure and stays open with the user's edits.
        assertThat(result.isFailure).isTrue()
        val row = repo.get(id)!!
        assertThat(row.calendarId).isEqualTo(cal)
        assertThat(row.title).isEqualTo("Standup")
    }
}
