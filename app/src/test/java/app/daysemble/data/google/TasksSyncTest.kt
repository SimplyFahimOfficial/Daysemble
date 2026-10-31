package app.daysemble.data.google

import app.daysemble.data.db.SourceKind
import app.daysemble.data.db.SyncState
import app.daysemble.data.google.tasks.TasksApi
import app.daysemble.data.google.tasks.TasksSyncer
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.TaskDraft
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TasksSyncTest : SyncTestBase() {
    private val acct = "me@example.com"
    private fun syncer() = TasksSyncer(db, TasksApi(http, base("/tasks/v1/")), clock)
    private fun lists() = json(body = """{"items":[{"id":"L1","title":"My Tasks"}]}""")
    private val t1 = """{"id":"T1","title":"Pay rent","notes":"","status":"needsAction","due":"2026-10-25T00:00:00.000Z","updated":"2026-10-20T10:00:00.000Z","position":"001"}"""
    private val sub = """{"id":"T2","title":"Find receipt","status":"completed","completed":"2026-10-21T09:00:00.000Z","parent":"T1","updated":"2026-10-21T09:00:00.000Z"}"""

    private suspend fun listId() = db.taskLists().all().single { it.kind == SourceKind.GOOGLE }.id

    @Test fun `full pull maps due dates without zone shift, subtasks and completion`() = runTest {
        router.on("GET", "/tasks/v1/users/@me/lists") { lists() }
        router.on("GET", "/tasks/v1/lists/L1/tasks") { r ->
            if (r.url.queryParameter("pageToken") == null) json(body = """{"items":[$sub],"nextPageToken":"p2"}""") // child first
            else json(body = """{"items":[$t1]}""")
        }
        syncer().sync(acct)
        val l = listId()
        val parent = db.tasks().byRemoteId(l, "T1")!!
        val child = db.tasks().byRemoteId(l, "T2")!!
        assertThat(LocalDate.ofEpochDay(parent.dueDate!!)).isEqualTo(LocalDate.parse("2026-10-25"))
        assertThat(child.parentId).isEqualTo(parent.id)
        assertThat(child.isCompleted).isTrue()
        assertThat(db.taskLists().get(l)!!.lastPulledAt).isEqualTo(clock.now().toEpochMilli())
    }

    @Test fun `incremental pull uses updatedMin with overlap and keeps local-only priority and time`() = runTest {
        router.on("GET", "/tasks/v1/users/@me/lists") { lists() }
        router.on("GET", "/tasks/v1/lists/L1/tasks") { json(body = """{"items":[$t1]}""") }
        syncer().sync(acct)
        val l = listId()
        val local = db.tasks().byRemoteId(l, "T1")!!
        tasks.setPriority(local.id, Priority.HIGH)
        db.tasks().upsert(db.tasks().get(local.id)!!.copy(dueTimeMinutes = 18 * 60)) // local-only time

        clock.instant = clock.instant.plusSeconds(3600)
        router.on("GET", "/tasks/v1/lists/L1/tasks") { r ->
            assertThat(r.url.queryParameter("updatedMin")).isEqualTo("2026-10-22T07:55:00Z")
            json(body = """{"items":[${t1.replace("Pay rent", "Pay rent (Oct)").replace("2026-10-20T10", "2026-10-22T08")}]}""")
        }
        syncer().sync(acct)
        val after = db.tasks().get(local.id)!!
        assertThat(after.title).isEqualTo("Pay rent (Oct)")
        assertThat(after.priority).isEqualTo(Priority.HIGH.level)
        assertThat(after.dueTimeMinutes).isEqualTo(18 * 60)
        assertThat(db.tasks().allIn(l)).hasSize(1)
    }

    @Test fun `create pushes parent before child and sends explicit nulls to clear fields`() = runTest {
        router.on("GET", "/tasks/v1/users/@me/lists") { lists() }
        syncer().refreshLists(acct)
        val l = listId()
        val parent = tasks.create(TaskDraft(title = "Trip", dueDate = LocalDate.parse("2026-11-01"), dueTime = LocalTime.of(9, 0)), l)
        val child = tasks.create(TaskDraft(title = "Passport", parentId = parent), l)
        var n = 0
        router.on("POST", "/tasks/v1/lists/L1/tasks") { r ->
            n++
            val body = r.body!!.utf8()
            if (n == 1) {
                assertThat(body).contains("\"title\":\"Trip\"")
                assertThat(body).contains("\"due\":\"2026-11-01T00:00:00.000Z\"") // date only; time stays local
                json(body = """{"id":"P","title":"Trip","updated":"2026-10-22T08:00:01.000Z"}""")
            } else {
                assertThat(r.url.queryParameter("parent")).isEqualTo("P")
                assertThat(body).contains("\"due\":null")
                assertThat(body).contains("\"completed\":null")
                json(body = """{"id":"C","title":"Passport","parent":"P","updated":"2026-10-22T08:00:02.000Z"}""")
            }
        }
        router.on("GET", "/tasks/v1/lists/L1/tasks") { json(body = """{"items":[]}""") }
        syncer().sync(acct)
        assertThat(db.tasks().get(parent)!!.remoteId).isEqualTo("P")
        assertThat(db.tasks().get(child)!!.remoteId).isEqualTo("C")
        assertThat(db.sync().outbox()).isEmpty()
    }

    @Test fun `a create whose response was lost is adopted instead of duplicated`() = runTest {
        router.on("GET", "/tasks/v1/users/@me/lists") { lists() }
        syncer().refreshLists(acct)
        val l = listId()
        val id = tasks.create(TaskDraft(title = "Call bank", dueDate = LocalDate.parse("2026-10-23")), l)
        // First attempt: server times out after (possibly) creating.
        router.on("POST", "/tasks/v1/lists/L1/tasks") { json(503, "{}") }
        runCatching { syncer().pushOutbox() }
        assertThat(db.sync().outbox().single().attempts).isEqualTo(1)

        router.on("GET", "/tasks/v1/lists/L1/tasks") {
            json(body = """{"items":[{"id":"X","title":"Call bank","notes":"","due":"2026-10-23T00:00:00.000Z","status":"needsAction","updated":"2026-10-22T08:00:00.500Z"}]}""")
        }
        syncer().sync(acct)
        assertThat(router.count("POST", "/tasks/v1/lists/L1/tasks")).isEqualTo(1) // no second POST
        assertThat(db.tasks().get(id)!!.remoteId).isEqualTo("X")
        assertThat(db.tasks().allIn(l)).hasSize(1)
    }

    @Test fun `completing and deleting are pushed`() = runTest {
        router.on("GET", "/tasks/v1/users/@me/lists") { lists() }
        router.on("GET", "/tasks/v1/lists/L1/tasks") { json(body = """{"items":[$t1]}""") }
        syncer().sync(acct)
        val l = listId()
        val local = db.tasks().byRemoteId(l, "T1")!!
        tasks.setCompleted(local.id, true)
        router.on("GET", "/tasks/v1/lists/L1/tasks/T1") { json(body = t1) }
        router.on("PATCH", "/tasks/v1/lists/L1/tasks/T1") { r ->
            assertThat(r.body!!.utf8()).contains("\"status\":\"completed\"")
            json(body = t1.replace("needsAction", "completed").replace("2026-10-20T10", "2026-10-22T08"))
        }
        syncer().sync(acct)
        assertThat(db.tasks().get(local.id)!!.syncState).isEqualTo(SyncState.SYNCED)

        tasks.delete(local.id)
        router.on("DELETE", "/tasks/v1/lists/L1/tasks/T1") { MockResponseNoContent() }
        syncer().sync(acct)
        assertThat(db.tasks().get(local.id)).isNull()
        assertThat(router.count("DELETE", "/tasks/v1/lists/L1/tasks/T1")).isEqualTo(1)
    }

    @Test fun `newer remote edit beats an older pending local edit and is logged`() = runTest {
        router.on("GET", "/tasks/v1/users/@me/lists") { lists() }
        router.on("GET", "/tasks/v1/lists/L1/tasks") { json(body = """{"items":[$t1]}""") }
        syncer().sync(acct)
        val l = listId()
        val local = db.tasks().byRemoteId(l, "T1")!!
        clock.instant = java.time.Instant.parse("2026-10-21T00:00:00Z")
        tasks.update(local.id, TaskDraft(title = "Mine", dueDate = LocalDate.parse("2026-10-25")))
        clock.instant = java.time.Instant.parse("2026-10-22T08:00:00Z")
        router.on("GET", "/tasks/v1/lists/L1/tasks/T1") { json(body = t1.replace("Pay rent", "Theirs").replace("2026-10-20T10", "2026-10-21T12")) }
        val r = syncer().sync(acct)
        assertThat(r.conflicts).isAtLeast(1)
        assertThat(db.tasks().get(local.id)!!.title).isEqualTo("Theirs")
        assertThat(router.count("PATCH", "/tasks/v1/lists/L1/tasks/T1")).isEqualTo(0)
    }

    @Test fun `changing a local-only field doesn't make an older pending edit win over Google`() = runTest {
        router.on("GET", "/tasks/v1/users/@me/lists") { lists() }
        router.on("GET", "/tasks/v1/lists/L1/tasks") { json(body = """{"items":[$t1]}""") }
        syncer().sync(acct)
        val l = listId()
        val local = db.tasks().byRemoteId(l, "T1")!!
        clock.instant = java.time.Instant.parse("2026-10-21T00:00:00Z")
        tasks.update(local.id, TaskDraft(title = "Mine", dueDate = LocalDate.parse("2026-10-25")))
        val editedAt = db.tasks().get(local.id)!!.updatedAt
        // After Google's edit (21 Oct 12:00), the user only changes the priority and the countdown.
        clock.instant = java.time.Instant.parse("2026-10-22T08:00:00Z")
        tasks.setPriority(local.id, Priority.HIGH)
        tasks.setCountdown(local.id, true)
        assertThat(db.tasks().get(local.id)!!.updatedAt).isEqualTo(editedAt)

        router.on("GET", "/tasks/v1/lists/L1/tasks/T1") { json(body = t1.replace("Pay rent", "Theirs").replace("2026-10-20T10", "2026-10-21T12")) }
        syncer().sync(acct)
        val after = db.tasks().get(local.id)!!
        assertThat(after.title).isEqualTo("Theirs")
        assertThat(after.priority).isEqualTo(Priority.HIGH.level) // local-only fields survive
        assertThat(after.countdownEnabled).isTrue()
        assertThat(router.count("PATCH", "/tasks/v1/lists/L1/tasks/T1")).isEqualTo(0)
    }

    @Test fun `an edit made while Google's version is fetched is kept and pushed`() = runTest {
        router.on("GET", "/tasks/v1/users/@me/lists") { lists() }
        router.on("GET", "/tasks/v1/lists/L1/tasks") { json(body = """{"items":[$t1]}""") }
        syncer().sync(acct)
        val l = listId()
        val local = db.tasks().byRemoteId(l, "T1")!!
        clock.instant = java.time.Instant.parse("2026-10-21T00:00:00Z")
        tasks.update(local.id, TaskDraft(title = "Mine", dueDate = LocalDate.parse("2026-10-25")))
        clock.instant = java.time.Instant.parse("2026-10-22T08:00:00Z")
        router.on("GET", "/tasks/v1/lists/L1/tasks/T1") {
            // The user edits again while the sync is fetching Google's version.
            kotlinx.coroutines.runBlocking { tasks.update(local.id, TaskDraft(title = "Mine, edited during sync", dueDate = LocalDate.parse("2026-10-25"))) }
            json(body = t1.replace("Pay rent", "Theirs").replace("2026-10-20T10", "2026-10-21T12"))
        }
        router.on("PATCH", "/tasks/v1/lists/L1/tasks/T1") { r ->
            json(body = t1.replace("Pay rent", "Mine, edited during sync").replace("2026-10-20T10", "2026-10-22T08"))
        }
        syncer().sync(acct)
        assertThat(db.tasks().get(local.id)!!.title).isEqualTo("Mine, edited during sync")
        val patch = router.requests.single { it.method == "PATCH" }.body!!.utf8()
        assertThat(patch).contains("Mine, edited during sync")
    }

    @Test fun `a task deleted while Google's version is fetched stays deleted`() = runTest {
        router.on("GET", "/tasks/v1/users/@me/lists") { lists() }
        router.on("GET", "/tasks/v1/lists/L1/tasks") { json(body = """{"items":[$t1]}""") }
        syncer().sync(acct)
        val l = listId()
        val local = db.tasks().byRemoteId(l, "T1")!!
        clock.instant = java.time.Instant.parse("2026-10-21T00:00:00Z")
        tasks.update(local.id, TaskDraft(title = "Mine", dueDate = LocalDate.parse("2026-10-25")))
        clock.instant = java.time.Instant.parse("2026-10-22T08:00:00Z")
        router.on("GET", "/tasks/v1/lists/L1/tasks/T1") {
            kotlinx.coroutines.runBlocking { tasks.delete(local.id) }
            json(body = t1.replace("Pay rent", "Theirs").replace("2026-10-20T10", "2026-10-21T12"))
        }
        syncer().pushOutbox()
        val row = db.tasks().get(local.id)!!
        assertThat(row.isDeleted).isTrue()
        assertThat(db.sync().outbox().map { it.op }).containsExactly(app.daysemble.data.db.OutboxOp.DELETE)
        assertThat(router.count("PATCH", "/tasks/v1/lists/L1/tasks/T1")).isEqualTo(0)
    }

    @Test fun `a task moved to another list while Google's version is fetched is not patched there`() = runTest {
        router.on("GET", "/tasks/v1/users/@me/lists") { lists() }
        router.on("GET", "/tasks/v1/lists/L1/tasks") { json(body = """{"items":[$t1]}""") }
        syncer().sync(acct)
        val l = listId()
        db.taskLists().upsert(app.daysemble.data.db.TaskListEntity(id = "local", kind = SourceKind.LOCAL, title = "On this phone"))
        val local = db.tasks().byRemoteId(l, "T1")!!
        clock.instant = java.time.Instant.parse("2026-10-21T00:00:00Z")
        tasks.update(local.id, TaskDraft(title = "Mine", dueDate = LocalDate.parse("2026-10-25")))
        clock.instant = java.time.Instant.parse("2026-10-22T08:00:00Z")
        router.on("GET", "/tasks/v1/lists/L1/tasks/T1") {
            kotlinx.coroutines.runBlocking { tasks.moveToList(local.id, "local") }
            json(body = t1)
        }
        syncer().pushOutbox()
        assertThat(router.count("PATCH", "/tasks/v1/lists/L1/tasks/T1")).isEqualTo(0)
        val row = db.tasks().get(local.id)!!
        assertThat(row.listId).isEqualTo("local")
        assertThat(row.remoteId).isNull()
        assertThat(row.syncState).isEqualTo(SyncState.LOCAL)
        // The old Google copy is still queued for deletion.
        assertThat(db.sync().outbox().single().snapshotRemoteId).isEqualTo("T1")
    }

    @Test fun `remote deletion with unpushed local edits keeps the task and re-creates it`() = runTest {
        router.on("GET", "/tasks/v1/users/@me/lists") { lists() }
        router.on("GET", "/tasks/v1/lists/L1/tasks") { json(body = """{"items":[$t1]}""") }
        syncer().sync(acct)
        val l = listId()
        val local = db.tasks().byRemoteId(l, "T1")!!
        tasks.update(local.id, TaskDraft(title = "Keep", dueDate = LocalDate.parse("2026-10-25")))
        router.on("GET", "/tasks/v1/lists/L1/tasks/T1") { json(body = """{"id":"T1","deleted":true}""") }
        router.on("POST", "/tasks/v1/lists/L1/tasks") { json(body = """{"id":"T9","title":"Keep","updated":"2026-10-22T08:00:05.000Z"}""") }
        syncer().sync(acct)
        syncer().sync(acct)
        assertThat(db.tasks().get(local.id)!!.title).isEqualTo("Keep")
        assertThat(db.tasks().get(local.id)!!.remoteId).isEqualTo("T9")
    }
}

@Suppress("FunctionName")
private fun MockResponseNoContent() = mockwebserver3.MockResponse.Builder().code(204).build()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskMoveTest : SyncTestBase() {
    private val t1 = """{"id":"T1","title":"Pay rent","status":"needsAction","updated":"2026-10-20T10:00:00.000Z"}"""

    @Test fun `moving a google task to another list deletes the old copy and creates it in the new list`() = runTest {
        router.on("GET", "/tasks/v1/users/@me/lists") { json(body = """{"items":[{"id":"L1","title":"A"},{"id":"L2","title":"B"}]}""") }
        router.on("GET", "/tasks/v1/lists/L1/tasks") { json(body = """{"items":[$t1]}""") }
        router.on("GET", "/tasks/v1/lists/L2/tasks") { json(body = """{"items":[]}""") }
        val syncer = TasksSyncer(db, TasksApi(http, base("/tasks/v1/")), clock)
        syncer.sync("me@example.com")
        val lists = db.taskLists().all().associateBy { it.remoteId }
        val id = db.tasks().byRemoteId(lists.getValue("L1").id, "T1")!!.id
        tasks.setPriority(id, Priority.HIGH)

        tasks.moveToList(id, lists.getValue("L2").id)
        router.on("DELETE", "/tasks/v1/lists/L1/tasks/T1") { MockResponseNoContent() }
        router.on("POST", "/tasks/v1/lists/L2/tasks") { json(body = """{"id":"N1","title":"Pay rent","updated":"2026-10-22T08:00:01.000Z"}""") }
        router.on("GET", "/tasks/v1/lists/L1/tasks") { json(body = """{"items":[{"id":"T1","deleted":true}]}""") }
        syncer.sync("me@example.com")

        assertThat(router.count("DELETE", "/tasks/v1/lists/L1/tasks/T1")).isEqualTo(1)
        val moved = db.tasks().get(id)!!
        assertThat(moved.listId).isEqualTo(lists.getValue("L2").id)
        assertThat(moved.remoteId).isEqualTo("N1")
        assertThat(moved.priority).isEqualTo(Priority.HIGH.level) // local-only fields survive
        assertThat(db.tasks().allIn(lists.getValue("L1").id)).isEmpty() // no resurrected duplicate
    }

    @Test fun `deleting a moved task before sync still removes the old remote copy`() = runTest {
        router.on("GET", "/tasks/v1/users/@me/lists") { json(body = """{"items":[{"id":"L1","title":"A"}]}""") }
        router.on("GET", "/tasks/v1/lists/L1/tasks") { json(body = """{"items":[$t1]}""") }
        val syncer = TasksSyncer(db, TasksApi(http, base("/tasks/v1/")), clock)
        syncer.sync("me@example.com")
        val google = db.taskLists().all().single()
        val local = tasks.ensureDefaultList("Mine")
        val id = db.tasks().byRemoteId(google.id, "T1")!!.id
        tasks.moveToList(id, local)
        tasks.delete(id)
        router.on("DELETE", "/tasks/v1/lists/L1/tasks/T1") { MockResponseNoContent() }
        syncer.pushOutbox()
        assertThat(router.count("DELETE", "/tasks/v1/lists/L1/tasks/T1")).isEqualTo(1)
    }
}
