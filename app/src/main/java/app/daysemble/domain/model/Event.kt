package app.daysemble.domain.model

import androidx.compose.runtime.Immutable
import app.daysemble.core.recurrence.RRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Immutable
data class CalendarInfo(
    val id: String,
    val name: String,
    val colorArgb: Int,
    /** A read-only mirror of a calendar on the phone (see data/device/DeviceCalendarSync). */
    val isDevice: Boolean,
    val isVisible: Boolean,
    val isWritable: Boolean,
)

/** One concrete appearance of an event on the calendar (a single event, or one instance of a series). */
@Immutable
data class EventOccurrence(
    /** Id of the event row (series master for recurring events). */
    val eventId: String,
    /** Stable key of this instance within its series; equal to the start key for single events. */
    val occurrenceKey: String,
    /** Id of the override row when this instance was individually modified. */
    val overrideId: String?,
    val calendarId: String,
    val title: String,
    val description: String,
    val location: String,
    val isAllDay: Boolean,
    /** For all-day events: start of [startDate] in the device zone (layout convenience). */
    val start: Instant,
    val end: Instant,
    val startDate: LocalDate,
    /** Exclusive. */
    val endDate: LocalDate,
    val timeZone: ZoneId,
    val colorArgb: Int,
    val isRecurring: Boolean,
    val isDevice: Boolean,
    val isReadOnly: Boolean,
) {
    val stableKey: String get() = "$eventId|$occurrenceKey"
}

enum class RepeatEnd { NEVER, COUNT, UNTIL }

/** Editable fields of an event. Times are wall-clock in [zone]. */
data class EventDraft(
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val calendarId: String? = null,
    val isAllDay: Boolean = false,
    val startDate: LocalDate,
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endDate: LocalDate = startDate,
    val endTime: LocalTime = LocalTime.of(10, 0),
    val zone: ZoneId,
    val rrule: RRule? = null,
    val reminderMinutesBefore: List<Int> = emptyList(),
    val colorArgb: Int? = null,
) {
    fun startInstant(): Instant = startDate.atTime(startTime).atZone(zone).toInstant()
    fun endInstant(): Instant = endDate.atTime(endTime).atZone(zone).toInstant()

    val validationError: EventValidation?
        get() = when {
            title.isBlank() -> EventValidation.TITLE_REQUIRED
            isAllDay && endDate.isBefore(startDate) -> EventValidation.END_BEFORE_START
            !isAllDay && endInstant().isBefore(startInstant()) -> EventValidation.END_BEFORE_START
            rrule != null && !rrule.isSupported -> null // kept as-is, not editable
            else -> null
        }
}

enum class EventValidation { TITLE_REQUIRED, END_BEFORE_START }
