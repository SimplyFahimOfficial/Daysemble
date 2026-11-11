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

/** Undo of event deletes: [EventRepository.delete] / [EventRepository.deleteOccurrence] then [EventRepository.restore]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventUndoTest {
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

    private fun draft(calendarId: String? = null, rrule: RRule? = null) = EventDraft(
        title = "Dentist", startDate = day, startTime = LocalTime.of(10, 0), endDate = day, endTime = LocalTime.of(11, 0), zone = berlin,
        calendarId = calendarId, rrule = rrule, reminderMinutesBefore = listOf(10, 60),
    )

    private suspend fun titlesOn(d: LocalDate) = repo.occurrences(d, d, berlin).first().map { it.title }

    @Test fun `an on-device event comes back with its reminders`() = runTest {
        val id = repo.create(draft(), local)
        val deleted = repo.delete(id)
        assertThat(titlesOn(day)).isEmpty()
        repo.restore(deleted!!)
        assertThat(titlesOn(day)).containsExactly("Dentist")
        assertThat(repo.reminders(id)).containsExactly(10, 60)
    }

    @Test fun `a deleted occurrence of an on-device series comes back`() = runTest {
        val id = repo.create(draft(rrule = RRule.parse("FREQ=DAILY")), local)
        val deleted = repo.deleteOccurrence(id, "2026-10-23T10:00")
        assertThat(titlesOn(day.plusDays(1))).isEmpty()
        repo.restore(deleted!!)
        assertThat(titlesOn(day.plusDays(1))).containsExactly("Dentist")
        assertThat(repo.get(id)!!.exdates).isEmpty()
    }

    /** A changed occurrence of [seriesId] with a reminder of its own. */
    private suspend fun addOverride(seriesId: String): String {
        val master = repo.get(seriesId)!!
        val ov = master.copy(
            id = "ov-$seriesId", rrule = null, exdates = "", recurringParentId = seriesId, originalStartKey = "2026-10-23T10:00",
            title = "Dentist (moved)",
        )
        db.events().upsert(ov)
        db.reminders().replace(OwnerType.EVENT, ov.id, listOf(app.daysemble.data.db.ReminderEntity.of(OwnerType.EVENT, ov.id, 5)))
        return ov.id
    }

    @Test fun `deleting a series removes its changed occurrences' reminders, and undo brings them back`() = runTest {
        val id = repo.create(draft(rrule = RRule.parse("FREQ=DAILY")), local)
        val ov = addOverride(id)
        val deleted = repo.delete(id)!!
        assertThat(db.reminders().forOwner(OwnerType.EVENT, ov)).isEmpty()
        repo.restore(deleted)
        assertThat(repo.reminders(ov)).containsExactly(5)
        assertThat(repo.reminders(id)).containsExactly(10, 60)
    }

}
