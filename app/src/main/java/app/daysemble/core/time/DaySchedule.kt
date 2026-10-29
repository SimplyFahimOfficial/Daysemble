package app.daysemble.core.time

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * One day's intended waking hours: wake-up and bedtime as wall-clock minutes of the day.
 *
 * Bedtime at or before the wake-up time means bedtime is after midnight: wake 07:00 and bed 01:00
 * is awake 07:00 → 01:00 the next morning (18 h) and asleep 01:00 → 07:00 (6 h). Wake and bed can't
 * be equal (the settings refuse it); if they are, the day is treated as 24 h awake and 0 h asleep.
 */
data class SleepSchedule(val wakeMinute: Int, val bedMinute: Int) {
    init {
        require(wakeMinute in 0 until MINUTES_PER_DAY && bedMinute in 0 until MINUTES_PER_DAY)
    }

    val wake: LocalTime get() = LocalTime.of(wakeMinute / 60, wakeMinute % 60)
    val bed: LocalTime get() = LocalTime.of(bedMinute / 60, bedMinute % 60)

    /** Bedtime is on the calendar day after the wake-up. */
    val bedAfterMidnight: Boolean get() = bedMinute <= wakeMinute

    /** Planned sleep on the wall clock (bedtime → next wake-up), e.g. 23:00 → 07:00 = 8 h. */
    val sleepMinutes: Int get() = Math.floorMod(wakeMinute - bedMinute, MINUTES_PER_DAY)

    /** Planned waking hours on the wall clock, e.g. 07:00 → 23:00 = 16 h. */
    val awakeMinutes: Int get() = if (wakeMinute == bedMinute) MINUTES_PER_DAY else MINUTES_PER_DAY - sleepMinutes

    /**
     * The waking window of [date] in [zone]: from [wake] on [date] to [bed] on [date] (or the next day
     * when bedtime is after midnight). Instants, so a daylight-saving change inside the window gives
     * its real length (15 h or 17 h instead of 16 h).
     */
    fun awakeWindow(date: LocalDate, zone: ZoneId): AwakeWindow {
        val start = date.atTime(wake).atZone(zone).toInstant()
        val endDate = if (bedAfterMidnight) date.plusDays(1) else date
        val end = endDate.atTime(bed).atZone(zone).toInstant()
        return AwakeWindow(date, start, if (end.isAfter(start)) end else start.plus(Duration.ofDays(1)))
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        /** Defaults when nothing is configured: up at 07:00, in bed at 23:00 (16 h awake, 8 h asleep). */
        val DEFAULT = SleepSchedule(7 * 60, 23 * 60)
    }
}

/** The span of a day counted for free time. [date] is the day the window belongs to (its wake-up day). */
data class AwakeWindow(val date: LocalDate, val start: Instant, val end: Instant) {
    val duration: Duration get() = Duration.between(start, end)
}

/**
 * The user's waking-hours settings: one [default] schedule and, when [perWeekday] is on, optional
 * [overrides] for single weekdays (a weekday without an override uses the default).
 */
data class DaySchedulePrefs(
    val default: SleepSchedule = SleepSchedule.DEFAULT,
    val perWeekday: Boolean = false,
    val overrides: Map<DayOfWeek, SleepSchedule> = emptyMap(),
) {
    /** The schedule that applies on [date]. */
    fun forDate(date: LocalDate): SleepSchedule = if (perWeekday) overrides[date.dayOfWeek] ?: default else default

    fun awakeWindow(date: LocalDate, zone: ZoneId): AwakeWindow = forDate(date).awakeWindow(date, zone)

    /**
     * The planned sleep that ends on the morning of [date]: from the bedtime of the day before
     * (that day's schedule) to the wake-up of [date] (this day's schedule). With different weekday
     * schedules the two can differ from either schedule's own sleep length. Never negative.
     */
    fun scheduledSleepBefore(date: LocalDate, zone: ZoneId): Duration {
        val bed = awakeWindow(date.minusDays(1), zone).end
        val wake = awakeWindow(date, zone).start
        return if (wake.isAfter(bed)) Duration.between(bed, wake) else Duration.ZERO
    }

    companion object {
        /** Stored form of [overrides]: "1=420-1380;6=540-60" (ISO weekday = wake minute - bed minute). */
        fun encodeOverrides(o: Map<DayOfWeek, SleepSchedule>): String =
            o.entries.sortedBy { it.key.value }.joinToString(";") { (d, s) -> "${d.value}=${s.wakeMinute}-${s.bedMinute}" }

        /** Parses [encodeOverrides]; malformed or equal wake/bed entries are dropped, never thrown. */
        fun decodeOverrides(raw: String?): Map<DayOfWeek, SleepSchedule> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.split(';').mapNotNull { part ->
                val (d, range) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                val (w, b) = range.split('-').takeIf { it.size == 2 } ?: return@mapNotNull null
                val day = d.trim().toIntOrNull()?.takeIf { it in 1..7 } ?: return@mapNotNull null
                val wake = w.trim().toIntOrNull()?.takeIf { it in 0 until SleepSchedule.MINUTES_PER_DAY } ?: return@mapNotNull null
                val bed = b.trim().toIntOrNull()?.takeIf { it in 0 until SleepSchedule.MINUTES_PER_DAY && it != wake } ?: return@mapNotNull null
                DayOfWeek.of(day) to SleepSchedule(wake, bed)
            }.toMap()
        }
    }
}
