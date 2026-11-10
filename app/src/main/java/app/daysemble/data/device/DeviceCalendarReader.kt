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
    val allDay: Boolean,
) {
    // Android Calendar Provider stores all-day dates at midnight UTC.
    fun localDate(zone: ZoneId): LocalDate = startsAt.atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()
}

/** No persistence, write permission, dependency or Google OAuth. Queries expanded occurrences. */
object DeviceCalendarReader {
    private val projection = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.ALL_DAY,
    )

    suspend fun upcoming(context: Context): List<DeviceCalendarEvent> = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return@withContext emptyList()
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val begin = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(14).atStartOfDay(zone).toInstant().toEpochMilli()
        try {
            CalendarContract.Instances.query(context.contentResolver, projection, begin, end)?.use { cursor ->
                buildList {
                    while (size < 10 && cursor.moveToNext()) {
                        add(
                            DeviceCalendarEvent(
                                eventId = cursor.getLong(0),
                                title = cursor.getString(1).orEmpty().ifBlank { context.getString(R.string.untitled_event) },
                                startsAt = Instant.ofEpochMilli(cursor.getLong(2)),
                                allDay = cursor.getInt(3) != 0,
                            ),
                        )
                    }
                }
            }.orEmpty()
        } catch (_: SecurityException) {
            // Permission may have been revoked while the query was starting.
            emptyList()
        }
    }
}
