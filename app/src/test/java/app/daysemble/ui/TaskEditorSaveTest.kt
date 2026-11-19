package app.daysemble.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.repo.TaskRepository
import app.daysemble.data.sync.ChangeListener
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.Task
import app.daysemble.domain.model.TaskDraft
import app.daysemble.ui.task.EditorEvent
import app.daysemble.ui.task.TaskEditorUiState
import app.daysemble.ui.task.TaskEditorViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
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
import java.time.ZoneId

/** One Save of a task edit that also moves it to another list is all-or-nothing, and can be retried. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskEditorSaveTest {
    private lateinit var db: DaysembleDatabase
    private lateinit var repo: TaskRepository
    private val notified = mutableListOf<String>()
    private lateinit var home: String
    private lateinit var work: String
    private lateinit var id: String

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DaysembleDatabase::class.java)
            .allowMainThreadQueries().build()
        val listener = object : ChangeListener { override fun onTaskChanged(taskId: String) { notified += taskId } }
        repo = TaskRepository(db, FixedAppClock(Instant.parse("2026-10-22T10:00:00Z"), ZoneId.of("Europe/Berlin")), listener)
        runBlocking {
            home = repo.ensureDefaultList("Home")
            work = repo.createList("Work")
            id = repo.create(TaskDraft(title = "Pay rent", dueDate = LocalDate.of(2026, 10, 23), reminderMinutesBefore = listOf(10)), home)
        }
        notified.clear()
    }

    @After fun tearDown() { scope.coroutineContext[Job]?.cancel(); db.close() }

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var ui: TaskEditorUiState? = null

    private fun settle() = repeat(40) { Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle() }

    private fun failReminderWrites(on: Boolean) = db.openHelper.writableDatabase.execSQL(
        if (on) "CREATE TRIGGER boom BEFORE INSERT ON reminder BEGIN SELECT RAISE(ABORT, 'boom'); END" else "DROP TRIGGER boom",
    )

    @Test fun `a failed save leaves list and fields alone, and a retry applies both once`() {
        val vm = TaskEditorViewModel(repo, { home }, id, null, null, SavedStateHandle())
        scope.launch { vm.state.collect { ui = it } }
        settle()
        vm.update { it.copy(title = "Pay rent (March)", listId = work) }

        failReminderWrites(true)
        vm.save(); settle()
        runBlocking { repo.get(id)!! }.let {
            assertThat(it.listId).isEqualTo(home)
            assertThat(it.title).isEqualTo("Pay rent")
        }
        assertThat(vm.events.value).isNull()
        assertThat(ui!!.saveError).isTrue()
        assertThat(ui!!.form.title).isEqualTo("Pay rent (March)") // the draft is kept
        assertThat(notified).isEmpty()

        failReminderWrites(false)
        vm.save(); settle()
        runBlocking { repo.get(id)!! }.let {
            assertThat(it.listId).isEqualTo(work)
            assertThat(it.title).isEqualTo("Pay rent (March)")
        }
        assertThat(ui!!.saveError).isFalse()
        assertThat(vm.events.value).isEqualTo(EditorEvent.Saved)
        assertThat(notified).containsExactly(id) // once, after the commit
    }

    @Test fun `high priority from Quick Add survives More options and a rotation, and saves like a direct save`() {
        val direct = runBlocking { repo.create(TaskDraft(title = "Call", priority = Priority.HIGH), home) }

        val handle = SavedStateHandle()
        fun open() = TaskEditorViewModel(repo, { home }, null, null, null, handle, initialTitle = "Call", initialPriority = Priority.HIGH.level)
        open(); settle()
        val rotated = open() // the same route arguments and saved state, as after a rotation
        settle()
        rotated.save(); settle()

        val tasks = runBlocking { repo.allTasks.first() }
        val viaEditor = tasks.single { it.id != direct && it.id != id }
        fun Task.shape() = listOf(title, notes, listId, dueDate, dueTime, priority, durationMinutes, countdownEnabled)
        assertThat(viaEditor.priority).isEqualTo(Priority.HIGH)
        assertThat(viaEditor.shape()).isEqualTo(tasks.single { it.id == direct }.shape())
    }
}
