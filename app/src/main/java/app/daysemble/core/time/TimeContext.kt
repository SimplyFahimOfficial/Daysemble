package app.daysemble.core.time

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The user's current time context. Held in a StateFlow in AppContainer and updated by the UI root
 * whenever the device zone or the first-day-of-week preference changes, so ViewModels never keep
 * a stale zone captured at creation time.
 */
data class TimeContext(
    val zone: ZoneId = ZoneId.systemDefault(),
    val firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
)
