package app.daysemble.domain

import app.daysemble.data.db.SyncState
import app.daysemble.data.prefs.TaskSort
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.Task
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TaskRulesTest {
    private val zone = ZoneId.of("America/New_York")

    private fun task(
        id: String = "t",
        due: LocalDate? = null,
        time: LocalTime? = null,
        done: Boolean = false,
        priority: Priority = Priority.NONE,
        created: Long = 0,
        sort: Long = 0,
        completedAt: Instant? = null,
    ) = Task(
        id = id, listId = "l", parentId = null, title = id, notes = "", dueDate = due, dueTime = time,
        priority = priority, isCompleted = done, completedAt = completedAt, createdAt = Instant.ofEpochSecond(created),
        updatedAt = Instant.EPOCH, sortKey = sort, isGoogle = false, syncState = SyncState.LOCAL,
    )

    private fun at(local: String) = java.time.LocalDateTime.parse(local).atZone(zone).toInstant()

    @Test
    fun `date-only task due today is not overdue until the next local midnight`() {
        val t = task(due = LocalDate.of(2026, 10, 22))
        assertThat(TaskRules.isOverdue(t, at("2026-10-22T23:59:59"), zone)).isFalse()
        assertThat(TaskRules.isOverdue(t, at("2026-10-23T00:00:00"), zone)).isTrue()
        assertThat(TaskRules.bucket(t, at("2026-10-22T08:00"), zone)).isEqualTo(TaskBucket.TODAY)
    }

    @Test
    fun `timed task becomes overdue at its due time`() {
        val t = task(due = LocalDate.of(2026, 10, 22), time = LocalTime.of(14, 0))
        assertThat(TaskRules.isOverdue(t, at("2026-10-22T13:59"), zone)).isFalse()
        assertThat(TaskRules.isOverdue(t, at("2026-10-22T14:00"), zone)).isTrue()
        assertThat(TaskRules.bucket(t, at("2026-10-22T14:01"), zone)).isEqualTo(TaskBucket.OVERDUE)
    }

    @Test
    fun `overdue respects the zone, including DST end`() {
        // 2026-11-01 is the US DST fall-back day (25 hours long).
        val t = task(due = LocalDate.of(2026, 11, 1))
        assertThat(TaskRules.isOverdue(t, at("2026-11-01T23:30"), zone)).isFalse()
        assertThat(TaskRules.isOverdue(t, Instant.parse("2026-11-02T05:00:00Z"), zone)).isTrue() // 00:00 EST
    }

    @Test
    fun `completed tasks are never overdue`() {
        val t = task(due = LocalDate.of(2020, 1, 1), done = true)
        assertThat(TaskRules.isOverdue(t, at("2026-10-22T10:00"), zone)).isFalse()
        assertThat(TaskRules.bucket(t, at("2026-10-22T10:00"), zone)).isEqualTo(TaskBucket.COMPLETED)
    }

    @Test
    fun `filters`() {
        val now = at("2026-10-22T10:00")
        val overdue = task("o", due = LocalDate.of(2026, 10, 21))
        val today = task("t", due = LocalDate.of(2026, 10, 22))
        val upcoming = task("u", due = LocalDate.of(2026, 10, 25))
        val none = task("n")
        val done = task("d", done = true)
        val all = listOf(overdue, today, upcoming, none, done)
        fun ids(f: TaskFilter) = all.filter { TaskRules.matches(it, f, now, zone) }.map { it.id }
        assertThat(ids(TaskFilter.ALL)).containsExactly("o", "t", "u", "n")
        assertThat(ids(TaskFilter.TODAY)).containsExactly("o", "t")
        assertThat(ids(TaskFilter.UPCOMING)).containsExactly("u")
        assertThat(ids(TaskFilter.OVERDUE)).containsExactly("o")
        assertThat(ids(TaskFilter.COMPLETED)).containsExactly("d")
    }

    @Test
    fun `sorting by due puts undated last and completed at the bottom`() {
        val a = task("a", due = LocalDate.of(2026, 10, 25))
        val b = task("b", due = LocalDate.of(2026, 10, 23), time = LocalTime.of(9, 0))
        val c = task("c")
        val d = task("d", due = LocalDate.of(2026, 10, 20), done = true, completedAt = Instant.ofEpochSecond(5))
        val e = task("e", due = LocalDate.of(2026, 10, 23), priority = Priority.HIGH)
        val sorted = listOf(a, b, c, d, e).sortedWith(TaskRules.comparator(TaskSort.DUE, zone)).map { it.id }
        assertThat(sorted).containsExactly("b", "e", "a", "c", "d").inOrder()
    }

    @Test
    fun `sorting by priority`() {
        val a = task("a", priority = Priority.LOW)
        val b = task("b", priority = Priority.HIGH)
        val c = task("c", priority = Priority.MEDIUM)
        val sorted = listOf(a, b, c).sortedWith(TaskRules.comparator(TaskSort.PRIORITY, zone)).map { it.id }
        assertThat(sorted).containsExactly("b", "c", "a").inOrder()
    }

    @Test
    fun `query matches title and notes case-insensitively`() {
        val t = task("Groceries").copy(notes = "Oat milk")
        assertThat(TaskRules.matchesQuery(t, "groc")).isTrue()
        assertThat(TaskRules.matchesQuery(t, "MILK")).isTrue()
        assertThat(TaskRules.matchesQuery(t, "bread")).isFalse()
        assertThat(TaskRules.matchesQuery(t, "  ")).isTrue()
    }
}
