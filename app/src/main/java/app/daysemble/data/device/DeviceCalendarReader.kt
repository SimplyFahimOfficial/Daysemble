package app.daysemble.data.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import app.daysemble.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class DeviceCalendarEvent(
    val eventId: Long,
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val allDay: Boolean,
) {
    // Android Calendar Provider stores all-day dates at midnight UTC, with an exclusive end.
    fun localDate(zone: ZoneId): LocalDate = startsAt.atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()

    fun isOver(now: Instant, zone: ZoneId): Boolean =
        if (allDay) !endsAt.atZone(ZoneOffset.UTC).toLocalDate().isAfter(now.atZone(zone).toLocalDate())
        else !endsAt.isAfter(now)
}

/** No persistence, write permission, dependency or Google OAuth. Queries expanded occurrences. */
object DeviceCalendarReader {
    private const val LIMIT = 10
    private const val WINDOW_DAYS = 14L
    private val projection = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
    )

    /** Up to [LIMIT] not-yet-ended instances from visible calendars over [WINDOW_DAYS] days, earliest first. */
    suspend fun upcoming(context: Context, zone: ZoneId, now: Instant = Instant.now()): Result<List<DeviceCalendarEvent>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                    throw SecurityException("READ_CALENDAR not granted")
                }
                val today = now.atZone(zone).toLocalDate()
                // From local midnight so today's all-day events (UTC midnight) overlap; ended instances are dropped below.
                val begin = today.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = today.plusDays(WINDOW_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
                // Sorted by begin and limited to visible calendars by the provider; null when no provider exists.
                val cursor = CalendarContract.Instances.query(context.contentResolver, projection, begin, end)
                    ?: error("Calendar Provider unavailable")
                cursor.use {
                    fun col(name: String) = it.getColumnIndexOrThrow(name)
                    buildList {
                        while (size < LIMIT && it.moveToNext()) {
                            val event = DeviceCalendarEvent(
                                eventId = it.getLong(col(CalendarContract.Instances.EVENT_ID)),
                                title = it.getString(col(CalendarContract.Instances.TITLE)).orEmpty()
                                    .ifBlank { context.getString(R.string.untitled_event) },
                                startsAt = Instant.ofEpochMilli(it.getLong(col(CalendarContract.Instances.BEGIN))),
                                endsAt = Instant.ofEpochMilli(it.getLong(col(CalendarContract.Instances.END))),
                                allDay = it.getInt(col(CalendarContract.Instances.ALL_DAY)) != 0,
                            )
                            if (!event.isOver(now, zone)) add(event)
                        }
                    }
                }
            }
        }
}
