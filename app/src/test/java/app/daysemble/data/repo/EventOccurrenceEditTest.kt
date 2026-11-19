package app.daysemble.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.OwnerType
import app.daysemble.data.sync.ChangeListener
import app.daysemble.domain.model.EventDraft
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

/** Editing one occurrence of a repeating event: [EventRepository.updateOccurrence]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventOccurrenceEditTest {
    private lateinit var db: DaysembleDatabase
    private lateinit var repo: EventRepository
    private val berlin = ZoneId.of("Europe/Berlin")
    private val clock = FixedAppClock(Instant.parse("2026-10-22T08:00:00Z"), berlin)
    private lateinit var local: String
    private val day = LocalDate.parse("2026-10-22")

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DaysembleDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = EventRepository(db, clock, ChangeListener.None)
        local = repo.ensureDefaultCalendar("Personal")
    }

    @After fun tearDown() = db.close()

    private fun series(calendarId: String? = null) = EventDraft(
        title = "Standup", startDate = day, startTime = LocalTime.of(9, 0), endDate = day, endTime = LocalTime.of(9, 15), zone = berlin,
        rrule = RRule.parse("FREQ=DAILY"), reminderMinutesBefore = listOf(5), calendarId = calendarId,
    )

    private suspend fun on(d: LocalDate) = repo.occurrences(d, d, berlin).first()

    @Test fun `only the chosen occurrence changes`() = runTest {
        val id = repo.create(series(), local)
        val key = "2026-10-23T09:00"
        val draft = repo.draftForOccurrence(id, key, berlin)!!
        assertThat(draft.rrule).isNull()
        assertThat(draft.startDate).isEqualTo(day.plusDays(1))
        repo.updateOccurrence(id, key, draft.copy(title = "Standup (moved)", startTime = LocalTime.of(14, 0), endTime = LocalTime.of(14, 30)))

        val moved = on(day.plusDays(1)).single()
        assertThat(moved.title).isEqualTo("Standup (moved)")
        assertThat(moved.start.atZone(berlin).toLocalTime()).isEqualTo(LocalTime.of(14, 0))
        assertThat(moved.occurrenceKey).isEqualTo(key) // still the same occurrence of the series
        assertThat(on(day).single().title).isEqualTo("Standup")
        assertThat(on(day.plusDays(2)).single().title).isEqualTo("Standup")
        val master = repo.get(id)!!
        assertThat(master.title).isEqualTo("Standup")
        assertThat(master.rrule).isNotNull()
        assertThat(repo.reminders(id)).containsExactly(5)
    }

    @Test fun `editing the same occurrence again updates it, and the draft shows the change`() = runTest {
        val id = repo.create(series(), local)
        val key = "2026-10-23T09:00"
        repo.updateOccurrence(id, key, repo.draftForOccurrence(id, key, berlin)!!.copy(title = "First"))
        val again = repo.draftForOccurrence(id, key, berlin)!!
        assertThat(again.title).isEqualTo("First")
        repo.updateOccurrence(id, key, again.copy(title = "Second"))
        assertThat(db.events().overridesFor(listOf(id))).hasSize(1)
        assertThat(on(day.plusDays(1)).single().title).isEqualTo("Second")
    }

    @Test fun `a single event or a read-only series can't be edited per occurrence`() = runTest {
        val single = repo.create(series().copy(rrule = null), local)
        assertThat(repo.draftForOccurrence(single, "2026-10-22T09:00", berlin)).isNull()
        val ro = repo.create(series(), local)
        db.events().upsert(repo.get(ro)!!.copy(isReadOnlyRecurrence = true))
        val draft = repo.draftFor(ro, berlin)!!.copy(rrule = null)
        assertThat(runCatching { repo.updateOccurrence(ro, "2026-10-23T09:00", draft) }.isFailure).isTrue()
    }

    @Test fun `editing one occurrence of a series that is gone reports it and writes nothing`() = runTest {
        val id = repo.create(series(), local)
        val key = "2026-10-23T09:00"
        val draft = repo.draftForOccurrence(id, key, berlin)!!
        repo.delete(id)
        assertThat(repo.updateOccurrence(id, key, draft)).isEqualTo(SaveResult.EVENT_GONE)
        assertThat(db.events().overridesFor(listOf(id))).isEmpty()
    }

    @Test fun `editing one occurrence reports saved`() = runTest {
        val id = repo.create(series(), local)
        val key = "2026-10-23T09:00"
        assertThat(repo.updateOccurrence(id, key, repo.draftForOccurrence(id, key, berlin)!!.copy(title = "X"))).isEqualTo(SaveResult.SAVED)
    }

    /** Weekly on Mondays 09:00 from 2026-10-26; the first one is moved to Friday 30th at 14:00. */
    private suspend fun weeklyWithMovedFirst(): Pair<String, String> {
        val monday = LocalDate.parse("2026-10-26")
        val id = repo.create(series().copy(title = "Team sync", startDate = monday, endDate = monday, rrule = RRule.parse("FREQ=WEEKLY")), local)
        val key = "2026-10-26T09:00"
        val friday = LocalDate.parse("2026-10-30")
        repo.updateOccurrence(id, key, repo.draftForOccurrence(id, key, berlin)!!.copy(
            title = "Team sync (Friday)", startDate = friday, endDate = friday, startTime = LocalTime.of(14, 0), endTime = LocalTime.of(14, 30),
        ))
        return id to key
    }

    @Test fun `a moved occurrence opens from its new day with its own date, time and content`() = runTest {
        val (id, key) = weeklyWithMovedFirst()
        val shown = on(LocalDate.parse("2026-10-30")).single()
        assertThat(shown.occurrenceKey).isEqualTo(key)

        // Opening it the way the calendar does: by series id and the original key.
        val opened = repo.observeOccurrence(shown.eventId, shown.occurrenceKey, berlin).first()!!
        assertThat(opened.title).isEqualTo("Team sync (Friday)")
        assertThat(opened.start).isEqualTo(shown.start)
        assertThat(opened.startDate).isEqualTo(LocalDate.parse("2026-10-30"))
        assertThat(opened.overrideId).isEqualTo(shown.overrideId)

        // The next Monday is the series' own, unchanged.
        val next = repo.observeOccurrence(id, "2026-11-02T09:00", berlin).first()!!
        assertThat(next.title).isEqualTo("Team sync")
        assertThat(next.start.atZone(berlin).toLocalTime()).isEqualTo(LocalTime.of(9, 0))
    }

    @Test fun `a moved occurrence can be edited again, and stays one changed occurrence`() = runTest {
        val (id, key) = weeklyWithMovedFirst()
        val again = repo.draftForOccurrence(id, key, berlin)!!
        assertThat(again.title).isEqualTo("Team sync (Friday)")
        assertThat(again.startDate).isEqualTo(LocalDate.parse("2026-10-30"))
        assertThat(again.startTime).isEqualTo(LocalTime.of(14, 0))
        repo.updateOccurrence(id, key, again.copy(title = "Team sync (final)"))

        assertThat(db.events().overridesFor(listOf(id))).hasSize(1)
        assertThat(on(LocalDate.parse("2026-10-30")).single().title).isEqualTo("Team sync (final)")
        assertThat(on(LocalDate.parse("2026-10-26"))).isEmpty()
        assertThat(on(LocalDate.parse("2026-11-02")).single().title).isEqualTo("Team sync")
    }

    @Test fun `a moved occurrence that was deleted or cancelled does not open`() = runTest {
        val (id, key) = weeklyWithMovedFirst()
        val ov = db.events().override(id, key)!!
        db.events().upsert(ov.copy(isCancelled = true))
        assertThat(repo.observeOccurrence(id, key, berlin).first()).isNull()
        assertThat(repo.draftForOccurrence(id, key, berlin)).isNull()

        db.events().upsert(ov.copy(isCancelled = false))
        assertThat(repo.observeOccurrence(id, key, berlin).first()).isNotNull()
        repo.deleteOccurrence(id, key) // excluded: the changed row stays stored but hidden
        assertThat(repo.observeOccurrence(id, key, berlin).first()).isNull()
        assertThat(repo.draftForOccurrence(id, key, berlin)).isNull()
    }
}
