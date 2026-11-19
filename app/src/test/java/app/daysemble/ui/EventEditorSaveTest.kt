package app.daysemble.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.CalendarSourceEntity
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.SourceKind
import app.daysemble.data.repo.EventRepository
import app.daysemble.data.repo.SaveResult
import app.daysemble.data.sync.ChangeListener
import app.daysemble.domain.model.EventDraft
import app.daysemble.ui.event.EventEditorUiState
import app.daysemble.ui.event.EventEditorViewModel
import app.daysemble.ui.event.EventForm
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Save in the event editor closes it only after the write is confirmed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventEditorSaveTest {
    private lateinit var db: DaysembleDatabase
    private lateinit var repo: EventRepository
    private val berlin = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.parse("2026-10-22")
    private lateinit var personal: String
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var ui: EventEditorUiState? = null

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DaysembleDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = EventRepository(db, FixedAppClock(Instant.parse("2026-10-22T08:00:00Z"), berlin), ChangeListener.None)
        personal = runBlocking { repo.ensureDefaultCalendar("Personal") }
    }

    @After fun tearDown() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); db.close() }

    private fun draft() = EventDraft(
        title = "Standup", startDate = day, startTime = LocalTime.of(9, 0), endDate = day, endTime = LocalTime.of(9, 30), zone = berlin,
    )

    private fun settle() = repeat(40) { Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle() }

    private fun editor(eventId: String?): EventEditorViewModel {
        val vm = EventEditorViewModel(repo, { personal }, eventId, { EventForm.from(draft().copy(title = "")) }, berlin, SavedStateHandle())
        scope.launch { vm.state.collect { ui = it } }
        settle()
        return vm
    }

    @Test fun `a normal save closes the editor, and a double tap creates one event`() {
        val vm = editor(null)
        vm.update { it.copy(title = "Planning") }
        vm.save(); vm.save()
        settle()
        assertThat(vm.done.value).isTrue()
        assertThat(runBlocking { repo.occurrences(day, day, berlin).first() }.map { it.title }).containsExactly("Planning")
    }

    @Test fun `saving an event that was deleted meanwhile keeps the draft and shows an error`() {
        val id = runBlocking { repo.create(draft(), personal) }
        val vm = editor(id)
        vm.update { it.copy(title = "Typed changes") }
        runBlocking { repo.delete(id) }
        vm.save()
        settle()
        assertThat(vm.done.value).isFalse()
        assertThat(ui!!.saveError).isEqualTo(SaveResult.EVENT_GONE)
        assertThat(ui!!.form!!.title).isEqualTo("Typed changes")
        assertThat(ui!!.saving).isFalse() // can retry
    }

    @Test fun `saving into a calendar removed meanwhile keeps the draft and writes nothing`() {
        db.calendars().let { runBlocking { it.upsert(CalendarSourceEntity(id = "work", kind = SourceKind.LOCAL, name = "Work", colorArgb = 0)) } }
        val id = runBlocking { repo.create(draft(), personal) }
        val vm = editor(id)
        vm.update { it.copy(title = "Typed changes", calendarId = "work") }
        assertThat(runBlocking { repo.deleteCalendar("work") }).isTrue()
        vm.save()
        settle()
        assertThat(vm.done.value).isFalse()
        assertThat(ui!!.saveError).isEqualTo(SaveResult.CALENDAR_GONE)
        assertThat(ui!!.form!!.title).isEqualTo("Typed changes")
        assertThat(runBlocking { repo.get(id) }!!.title).isEqualTo("Standup")
    }
}
