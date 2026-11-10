package app.daysemble.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.OutboxOp
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.SourceKind
import app.daysemble.data.db.SyncState
import app.daysemble.data.db.TaskListEntity
import app.daysemble.data.sync.ChangeListener
import app.daysemble.data.sync.Outbox
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.TaskDraft
import app.cash.turbine.test
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
class TaskRepositoryTest {

    private lateinit var db: DaysembleDatabase
    private lateinit var repo: TaskRepository
    private val clock = FixedAppClock(Instant.parse("2026-10-22T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val changed = mutableListOf<String>()
    private var pendingSyncCalls = 0
    private lateinit var localList: String

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DaysembleDatabase::class.java)
            .allowMainThreadQueries().build()
        val listener = object : ChangeListener {
            override fun onTaskChanged(taskId: String) { changed += taskId }
            override fun onPendingSync() { pendingSyncCalls++ }
        }
        repo = TaskRepository(db, Outbox(db.sync()) { clock.now().toEpochMilli() }, clock, listener)
        localList = repo.ensureDefaultList("My tasks")
    }

    @After fun tearDown() = db.close()

    @Test
    fun `default list is created once`() = runTest {
        assertThat(repo.ensureDefaultList("My tasks")).isEqualTo(localList)
        assertThat(db.taskLists().all()).hasSize(1)
    }

    @Test
    fun `create, edit, complete and persist a local task`() = runTest {
        val id = repo.create(
            TaskDraft(title = "  Book flights  ", dueDate = LocalDate.of(2026, 10, 23), dueTime = LocalTime.of(9, 30),
                priority = Priority.HIGH, reminderMinutesBefore = listOf(15, 15, 60)),
            localList,
        )
        var t = repo.get(id)!!
        assertThat(t.title).isEqualTo("Book flights")
        assertThat(t.dueTime).isEqualTo(LocalTime.of(9, 30))
        assertThat(t.priority).isEqualTo(Priority.HIGH)
        assertThat(t.syncState).isEqualTo(SyncState.LOCAL)
        assertThat(repo.reminders(id)).containsExactly(15, 60).inOrder()

        repo.update(id, TaskDraft(title = "Book train", dueDate = null))
        t = repo.get(id)!!
        assertThat(t.title).isEqualTo("Book train")
        assertThat(t.dueDate).isNull()
        assertThat(repo.reminders(id)).isEmpty() // reminders need a due date

        repo.setCompleted(id, true)
        assertThat(repo.get(id)!!.isCompleted).isTrue()
        assertThat(repo.get(id)!!.completedAt).isEqualTo(clock.now())
        repo.setCompleted(id, false)
        assertThat(repo.get(id)!!.completedAt).isNull()

        assertThat(changed).contains(id)
        assertThat(pendingSyncCalls).isEqualTo(0)
        assertThat(db.sync().outbox()).isEmpty()
    }

    @Test
    fun `blank titles are rejected`() = runTest {
        val r = runCatching { repo.create(TaskDraft(title = "   "), localList) }
        assertThat(r.isFailure).isTrue()
    }

    @Test
    fun `subtasks are one level deep and counted on the parent`() = runTest {
        val parent = repo.create(TaskDraft(title = "Trip"), localList)
        val child = repo.create(TaskDraft(title = "Passport", parentId = parent), localList)
        val grandChild = repo.create(TaskDraft(title = "Photos", parentId = child), localList)
        assertThat(repo.get(grandChild)!!.parentId).isEqualTo(parent)
        repo.setCompleted(child, true)
        val p = repo.allTasks.first().first { it.id == parent }
        assertThat(p.subtaskCount).isEqualTo(2)
        assertThat(p.subtasksDone).isEqualTo(1)
    }

    @Test
    fun `date range matches the full task view including subtask progress and visibility`() = runTest {
        val from = LocalDate.of(2026, 10, 22)
        val to = from.plusDays(6)
        val first = repo.create(TaskDraft(title = "First day", dueDate = from), localList)
        repo.create(TaskDraft(title = "Last day", dueDate = to), localList)
        val doneChild = repo.create(TaskDraft(title = "Done child", parentId = first), localList)
        repo.setCompleted(doneChild, true)
        repo.create(TaskDraft(title = "Future child", parentId = first, dueDate = to.plusDays(1)), localList)
        val deletedChild = repo.create(TaskDraft(title = "Deleted child", parentId = first), localList)
        repo.delete(deletedChild)
        val outside = repo.create(TaskDraft(title = "Earlier parent", dueDate = from.minusDays(1)), localList)
        repo.create(TaskDraft(title = "Child in range", parentId = outside, dueDate = from), localList)
        repo.create(TaskDraft(title = "Later parent", dueDate = to.plusDays(1)), localList)
        repo.create(TaskDraft(title = "Undated"), localList)
        val completed = repo.create(TaskDraft(title = "Completed", dueDate = from), localList)
        repo.setCompleted(completed, true)
        val deleted = repo.create(TaskDraft(title = "Deleted", dueDate = from), localList)
        repo.delete(deleted)
        db.taskLists().upsert(TaskListEntity(id = "g", kind = SourceKind.GOOGLE, remoteId = "r1", accountEmail = "a@example.com", title = "G"))
        repo.create(TaskDraft(title = "Google", dueDate = from), "g")

        for (enabled in listOf(true, false)) {
            db.taskLists().setSyncEnabled("g", enabled)
            val expected = repo.allTasks.first().filter {
                it.parentId == null && !it.isCompleted && it.dueDate?.let { date -> date in from..to } == true
            }
            val actual = repo.openTasksInRange(from, to).first()
            assertThat(actual).containsExactlyElementsIn(expected)
            val parent = actual.single { it.id == first }
            assertThat(parent.subtaskCount).isEqualTo(2)
            assertThat(parent.subtasksDone).isEqualTo(1)
        }
        assertThat(repo.openTasksInRange(to.plusDays(2), to.plusDays(3)).first()).isEmpty()
    }

    @Test
    fun `date range updates when a task is completed reopened or moved`() = runTest {
        val date = LocalDate.of(2026, 10, 22)
        val id = repo.create(TaskDraft(title = "Task", dueDate = date), localList)
        repo.openTasksInRange(date, date).test {
            assertThat(awaitItem().map { it.id }).containsExactly(id)
            repo.setCompleted(id, true)
            assertThat(awaitItem()).isEmpty()
            repo.setCompleted(id, false)
            assertThat(awaitItem().map { it.id }).containsExactly(id)
            repo.update(id, TaskDraft(title = "Task", dueDate = date.plusDays(1)))
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `delete then restore brings back the task and its subtasks`() = runTest {
        val parent = repo.create(TaskDraft(title = "Trip"), localList)
        val child = repo.create(TaskDraft(title = "Passport", parentId = parent), localList)
        repo.delete(parent)
        assertThat(repo.allTasks.first()).isEmpty()
        repo.restore(parent)
        assertThat(repo.allTasks.first().map { it.id }).containsExactly(parent, child)
    }

    @Test
    fun `tombstones are purged only after the undo window`() = runTest {
        val id = repo.create(TaskDraft(title = "Temp", dueDate = LocalDate.of(2026, 10, 23), reminderMinutesBefore = listOf(15)), localList)
        repo.delete(id)
        repo.purgeExpiredTombstones(10_000)
        assertThat(db.tasks().get(id)).isNotNull()
        assertThat(db.reminders().forOwner(OwnerType.TASK, id)).hasSize(1) // kept for Undo
        clock.instant = clock.instant.plusSeconds(11)
        repo.purgeExpiredTombstones(10_000)
        assertThat(db.tasks().get(id)).isNull()
        // Reminder rows have no foreign key; the purge removes them with their task.
        assertThat(db.reminders().forOwner(OwnerType.TASK, id)).isEmpty()
    }

    @Test
    fun `google-backed tasks are queued and coalesced in the outbox`() = runTest {
        db.taskLists().upsert(TaskListEntity(id = "g", kind = SourceKind.GOOGLE, remoteId = "r1", accountEmail = "a@example.com", title = "G"))
        val id = repo.create(TaskDraft(title = "Remote"), "g")
        repo.update(id, TaskDraft(title = "Remote 2"))
        repo.setCompleted(id, true)
        val ops = db.sync().outbox()
        assertThat(ops.map { it.op }).containsExactly(OutboxOp.CREATE)
        assertThat(repo.get(id)!!.syncState).isEqualTo(SyncState.PENDING_CREATE)
        assertThat(pendingSyncCalls).isAtLeast(1)

        // Never pushed -> deleting needs no remote call.
        repo.delete(id)
        assertThat(db.sync().outbox()).isEmpty()
    }

    @Test
    fun `deleting a synced google task queues a remote delete, undo re-queues an update`() = runTest {
        db.taskLists().upsert(TaskListEntity(id = "g", kind = SourceKind.GOOGLE, remoteId = "r1", accountEmail = "a@example.com", title = "G"))
        val id = repo.create(TaskDraft(title = "Remote"), "g")
        // Simulate a successful push.
        db.sync().clearFor(OwnerType.TASK, id)
        db.tasks().upsert(db.tasks().get(id)!!.copy(remoteId = "t1", syncState = SyncState.SYNCED))

        repo.delete(id)
        assertThat(db.sync().outbox().map { it.op }).containsExactly(OutboxOp.DELETE)
        assertThat(db.tasks().get(id)!!.syncState).isEqualTo(SyncState.PENDING_DELETE)

        repo.restore(id)
        assertThat(db.sync().outbox().map { it.op }).containsExactly(OutboxOp.UPDATE)
        assertThat(repo.get(id)!!.syncState).isEqualTo(SyncState.PENDING_UPDATE)
    }

    @Test
    fun `priority changes on google tasks stay local`() = runTest {
        db.taskLists().upsert(TaskListEntity(id = "g", kind = SourceKind.GOOGLE, remoteId = "r1", accountEmail = "a@example.com", title = "G"))
        val id = repo.create(TaskDraft(title = "Remote"), "g")
        db.sync().clearFor(OwnerType.TASK, id)
        db.tasks().upsert(db.tasks().get(id)!!.copy(remoteId = "t1", syncState = SyncState.SYNCED))
        repo.setPriority(id, Priority.HIGH)
        assertThat(db.sync().outbox()).isEmpty()
        assertThat(repo.get(id)!!.priority).isEqualTo(Priority.HIGH)
    }
}
