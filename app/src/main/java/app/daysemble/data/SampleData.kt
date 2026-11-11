package app.daysemble.data

import app.daysemble.AppContainer
import app.daysemble.core.recurrence.RRule
import app.daysemble.domain.model.EventDraft
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.TaskDraft
import java.time.LocalTime

/**
 * Debug-only sample content for demos and screenshots. Reachable only from
 * Settings › About in debug builds. Items are created in the on-device calendar/list and can be
 * removed with "Delete local data".
 */
object SampleData {
    suspend fun load(c: AppContainer) {
        val d = c.defaults()
        val zone = c.clock.zone()
        val today = c.clock.today()
        fun ev(title: String, s: LocalTime, e: LocalTime, loc: String = "", day: Long = 0, rule: String? = null, rem: List<Int> = emptyList()) =
            EventDraft(
                title = title, startDate = today.plusDays(day), startTime = s, endDate = today.plusDays(day), endTime = e,
                zone = zone, location = loc, rrule = rule?.let(RRule::parse), reminderMinutesBefore = rem,
            )
        c.events.create(EventDraft(title = "Maya’s birthday", isAllDay = true, startDate = today, endDate = today, zone = zone), d.calendarId)
        c.events.create(ev("Team standup", LocalTime.of(9, 15), LocalTime.of(9, 30), rule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", day = -2), d.calendarId)
        c.events.create(ev("Design review", LocalTime.of(10, 30), LocalTime.of(11, 30), "Studio 3", rem = listOf(10)), d.calendarId)
        c.events.create(ev("Planning sync", LocalTime.of(11, 0), LocalTime.of(11, 45)), d.calendarId)
        c.events.create(ev("Lunch with Sam", LocalTime.of(12, 30), LocalTime.of(13, 30), "Café Lumen"), d.calendarId)
        c.events.create(ev("Yoga", LocalTime.of(18, 0), LocalTime.of(19, 0), "Riverside studio", day = 1, rule = "FREQ=WEEKLY"), d.calendarId)
        c.events.create(ev("Dentist", LocalTime.of(15, 0), LocalTime.of(15, 30), "Main St 12", day = 3, rem = listOf(60)), d.calendarId)
        c.tasks.create(TaskDraft(title = "Send invoice to Northwind", dueDate = today.minusDays(1), priority = Priority.HIGH), d.taskListId)
        c.tasks.create(TaskDraft(title = "Buy groceries", notes = "Oat milk, lemons, basil", dueDate = today), d.taskListId)
        val trip = c.tasks.create(
            TaskDraft(title = "Book train tickets", dueDate = today, dueTime = LocalTime.of(16, 0), priority = Priority.MEDIUM, countdown = true),
            d.taskListId,
        )
        c.tasks.create(TaskDraft(title = "Check seat reservations", parentId = trip), d.taskListId)
        val passport = c.tasks.create(TaskDraft(title = "Find passport", parentId = trip), d.taskListId)
        c.tasks.setCompleted(passport, true)
        c.tasks.create(TaskDraft(title = "Call the plumber", dueDate = today.plusDays(2)), d.taskListId)
        val reviewDay = today.plusDays(3)
        val review = c.tasks.create(
            TaskDraft(
                title = "Weekly review", notes = "Inbox, calendar, next week’s plan", dueDate = reviewDay, dueTime = LocalTime.of(17, 0),
                repeat = RRule.parse("FREQ=WEEKLY;BYDAY=" + reviewDay.dayOfWeek.name.take(2)), countdown = true,
            ),
            d.taskListId,
        )
        c.tasks.create(TaskDraft(title = "Clear the inbox", parentId = review), d.taskListId)
        c.tasks.create(TaskDraft(title = "Plan next week", parentId = review), d.taskListId)
        c.tasks.create(TaskDraft(title = "Read chapter 4"), d.taskListId)
        val done = c.tasks.create(TaskDraft(title = "Water the plants", dueDate = today), d.taskListId)
        c.tasks.setCompleted(done, true)
    }
}
