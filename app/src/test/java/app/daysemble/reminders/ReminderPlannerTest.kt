package app.daysemble.reminders

import app.daysemble.data.db.EventEntity
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.ReminderEntity
import app.daysemble.data.db.SyncState
import app.daysemble.data.db.TaskEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderPlannerTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant()
    private fun r(min: Int, owner: String = "e", type: OwnerType = OwnerType.EVENT) = ReminderEntity("r$min", type, owner, min)

    private fun event(start: String, end: String, rrule: String? = null, exdates: String = "", allDay: Boolean = false, date: String = "2026-10-22") =
        EventEntity(
            id = "e", calendarId = "c", title = "T", isAllDay = allDay,
            startUtc = if (allDay) 0 else at(start).toEpochMilli(), endUtc = if (allDay) 0 else at(end).toEpochMilli(),
            startDate = if (allDay) LocalDate.parse(date).toEpochDay() else 0,
            endDateExclusive = if (allDay) LocalDate.parse(date).plusDays(1).toEpochDay() else 0,
            timeZone = zone.id, rrule = rrule, exdates = exdates, rangeStartUtc = 0, rangeEndUtc = Long.MAX_VALUE,
            createdAt = 0, updatedAt = 0, syncState = SyncState.LOCAL,
        )

    private fun task(due: String?, minutes: Int? = null, done: Boolean = false) = TaskEntity(
        id = "t", listId = "l", title = "T", dueDate = due?.let { LocalDate.parse(it).toEpochDay() }, dueTimeMinutes = minutes,
        isCompleted = done, sortKey = 0, createdAt = 0, updatedAt = 0, syncState = SyncState.LOCAL,
    )

    @Test
    fun `single event reminders before start, past ones dropped`() {
        val e = event("2026-10-22T10:00", "2026-10-22T11:00")
        val plan = ReminderPlanner.forEvent(e, listOf(r(10), r(60), r(0)), emptyList(), at("2026-10-22T09:30"), zone, 540)
        assertThat(plan.map { it.triggerAt }).containsExactly(at("2026-10-22T09:50"), at("2026-10-22T10:00"))
    }

    @Test
    fun `recurring events plan each occurrence inside the horizon and skip exdates`() {
        val e = event("2026-10-19T09:00", "2026-10-19T09:30", rrule = "RRULE:FREQ=DAILY", exdates = "2026-10-23T09:00")
        val plan = ReminderPlanner.forEvent(e, listOf(r(15)), emptyList(), at("2026-10-22T12:00"), zone, 540)
        val keys = plan.map { it.occurrenceKey }
        assertThat(keys).doesNotContain("2026-10-22T09:00") // already past
        assertThat(keys).doesNotContain("2026-10-23T09:00") // exdate
        assertThat(keys.first()).isEqualTo("2026-10-24T09:00")
        assertThat(plan.size).isAtMost(8)
        // DST ends 25 Oct: still 08:45 local.
        assertThat(plan.map { it.triggerAt.atZone(zone).toLocalTime().toString() }.toSet()).containsExactly("08:45")
    }

    @Test
    fun `moved override uses its new start, cancelled override has none`() {
        val e = event("2026-10-22T09:00", "2026-10-22T09:30", rrule = "RRULE:FREQ=DAILY;COUNT=3")
        val moved = e.copy(id = "o1", rrule = null, recurringParentId = "e", originalStartKey = "2026-10-23T09:00",
            startUtc = at("2026-10-23T14:00").toEpochMilli(), endUtc = at("2026-10-23T15:00").toEpochMilli())
        val cancelled = e.copy(id = "o2", rrule = null, recurringParentId = "e", originalStartKey = "2026-10-24T09:00", isCancelled = true)
        val plan = ReminderPlanner.forEvent(e, listOf(r(10)), listOf(moved, cancelled), at("2026-10-22T10:00"), zone, 540)
        assertThat(plan.map { it.triggerAt }).containsExactly(at("2026-10-23T13:50"))
    }

    @Test
    fun `all-day events remind at the default time`() {
        val e = event("", "", allDay = true, date = "2026-10-23")
        val plan = ReminderPlanner.forEvent(e, listOf(r(0), r(1440)), emptyList(), at("2026-10-22T08:00"), zone, 9 * 60)
        assertThat(plan.map { it.triggerAt }).containsExactly(at("2026-10-22T09:00"), at("2026-10-23T09:00"))
    }

    @Test
    fun `tasks use due time or default time and stop when completed`() {
        val now = at("2026-10-22T08:00")
        assertThat(ReminderPlanner.forTask(task("2026-10-22", 14 * 60), listOf(r(30, "t", OwnerType.TASK)), now, zone, 540).single().triggerAt)
            .isEqualTo(at("2026-10-22T13:30"))
        assertThat(ReminderPlanner.forTask(task("2026-10-22"), listOf(r(0, "t", OwnerType.TASK)), now, zone, 540).single().triggerAt)
            .isEqualTo(at("2026-10-22T09:00"))
        assertThat(ReminderPlanner.forTask(task("2026-10-22", done = true), listOf(r(0, "t", OwnerType.TASK)), now, zone, 540)).isEmpty()
        assertThat(ReminderPlanner.forTask(task(null), listOf(r(0, "t", OwnerType.TASK)), now, zone, 540)).isEmpty()
    }

    @Test
    fun `beyond the horizon is not planned yet`() {
        val e = event("2026-11-20T10:00", "2026-11-20T11:00")
        assertThat(ReminderPlanner.forEvent(e, listOf(r(10)), emptyList(), Instant.parse("2026-10-22T00:00:00Z"), zone, 540)).isEmpty()
    }

    @Test
    fun `reminder in a DST gap moves forward`() {
        // 29 Mar 2026 02:30 doesn't exist in Berlin; java.time shifts to 03:30.
        val t = ReminderPlanner.atMinute(LocalDate.parse("2026-03-29"), 150, zone)
        assertThat(t.atZone(zone).toLocalTime().toString()).isEqualTo("03:30")
    }
}
