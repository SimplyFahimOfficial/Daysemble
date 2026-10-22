package app.daysemble.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Injectable clock so time-dependent logic (overdue, countdowns, reminders) is testable. */
interface AppClock {
    fun now(): Instant
    fun zone(): ZoneId
    fun today(): LocalDate = now().atZone(zone()).toLocalDate()
    fun zonedNow(): ZonedDateTime = now().atZone(zone())
}

object SystemAppClock : AppClock {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

class FixedAppClock(var instant: Instant, var zoneId: ZoneId) : AppClock {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId
}
