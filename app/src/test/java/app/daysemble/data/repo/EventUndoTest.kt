package app.daysemble.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.recurrence.RRule
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.CalendarSourceEntity
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.OutboxOp
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.SourceKind
import app.daysemble.data.db.SyncState
import app.daysemble.data.sync.ChangeListener
import app.daysemble.data.sync.Outbox
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
        repo = EventRepository(db, Outbox(db.sync()) { clock.now().toEpochMilli() }, clock, ChangeListener.None)
        local = repo.ensureDefaultCalendar("Personal")
        db.calendars().upsert(
            CalendarSourceEntity(id = "g", kind = SourceKind.GOOGLE, remoteId = "primary", accountEmail = "a@example.com", name = "G", colorArgb = 0, accessRole = "owner"),
        )
    }

    @After fun tearDown() = db.close()

    private fun draft(calendarId: String? = null, rrule: RRule? = null) = EventDraft(
        title = "Dentist", startDate = day, startTime = LocalTime.of(10, 0), endDate = day, endTime = LocalTime.of(11, 0), zone = berlin,
        calendarId = calendarId, rrule = rrule, reminderMinutesBefore = listOf(10, 60),
    )

    private suspend fun titlesOn(d: LocalDate) = repo.occurrences(d, d, berlin).first().map { it.title }

    /** As if a sync had pushed the event: it has a Google id and nothing is queued. */
    private suspend fun markSynced(id: String) {
        db.events().upsert(repo.get(id)!!.copy(remoteId = "r-$id", etag = "\"e\"", syncState = SyncState.SYNCED))
        db.sync().clearFor(OwnerType.EVENT, id)
    }

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

    @Test fun `a google delete that wasn't pushed yet is cancelled`() = runTest {
        val id = repo.create(draft(calendarId = "g"), local)
        markSynced(id)
        repo.restore(repo.delete(id)!!)
        val row = repo.get(id)!!
        assertThat(row.isDeleted).isFalse()
        assertThat(row.remoteId).isEqualTo("r-$id")
        assertThat(row.syncState).isEqualTo(SyncState.PENDING_UPDATE)
        assertThat(db.sync().outbox().map { it.op }).containsExactly(OutboxOp.UPDATE)
        assertThat(repo.reminders(id)).containsExactly(10, 60)
    }

    @Test fun `a google event already deleted on Google is created again`() = runTest {
        val id = repo.create(draft(calendarId = "g"), local)
        markSynced(id)
        val deleted = repo.delete(id)!!
        // The sync pushed the delete meanwhile and removed the row.
        db.events().hardDelete(id); db.sync().clearFor(OwnerType.EVENT, id)
        repo.restore(deleted)
        val row = repo.get(id)!!
        assertThat(row.remoteId).isNull()
        assertThat(row.syncState).isEqualTo(SyncState.PENDING_CREATE)
        assertThat(row.pendingClientId).isNotNull()
        assertThat(db.sync().outbox().map { it.op }).containsExactly(OutboxOp.CREATE)
        assertThat(titlesOn(day)).containsExactly("Dentist")
    }

    @Test fun `a google event that was never pushed is queued for creation again`() = runTest {
        val id = repo.create(draft(calendarId = "g"), local)
        repo.restore(repo.delete(id)!!)
        assertThat(repo.get(id)!!.syncState).isEqualTo(SyncState.PENDING_CREATE)
        assertThat(db.sync().outbox().map { it.op }).containsExactly(OutboxOp.CREATE)
    }

    /** A changed occurrence of [seriesId] with a reminder of its own, as a pull from Google would store it. */
    private suspend fun addOverride(seriesId: String, remoteId: String? = null): String {
        val master = repo.get(seriesId)!!
        val ov = master.copy(
            id = "ov-$seriesId", rrule = null, exdates = "", recurringParentId = seriesId, originalStartKey = "2026-10-23T10:00",
            title = "Dentist (moved)", remoteId = remoteId,
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

    @Test fun `undo of a google series delete brings back its changed occurrences' reminders`() = runTest {
        val id = repo.create(draft(calendarId = "g", rrule = RRule.parse("FREQ=DAILY")), local)
        markSynced(id)
        val ov = addOverride(id, remoteId = "r-$id-1")
        val deleted = repo.delete(id)!!
        // The delete isn't pushed yet: the occurrence row is still there, its reminders are not.
        assertThat(db.events().get(ov)).isNotNull()
        assertThat(db.reminders().forOwner(OwnerType.EVENT, ov)).isEmpty()
        repo.restore(deleted)
        assertThat(repo.reminders(ov)).containsExactly(5)
    }

    @Test fun `a deleted occurrence of a google series comes back and is pushed`() = runTest {
        val id = repo.create(draft(calendarId = "g", rrule = RRule.parse("FREQ=DAILY")), local)
        markSynced(id)
        repo.restore(repo.deleteOccurrence(id, "2026-10-23T10:00")!!)
        val row = repo.get(id)!!
        assertThat(row.exdates).isEmpty()
        assertThat(row.syncState).isEqualTo(SyncState.PENDING_UPDATE)
        assertThat(db.sync().outbox().map { it.op }).containsExactly(OutboxOp.UPDATE)
    }
}
