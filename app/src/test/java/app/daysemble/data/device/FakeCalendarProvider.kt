package app.daysemble.data.device

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import org.robolectric.Robolectric

/** A row of the fake provider, by column name. */
typealias Row = Map<String, Any?>

/**
 * Stands in for the system Calendar Provider in tests. It serves the two tables Daysemble reads:
 * the calendars table and the time-windowed instances view (the real provider expands repeats and
 * exceptions itself, so tests hand it the expanded rows). Like the real one, the instances view
 * only returns rows that overlap the requested window, and it honours no `visible` filter unless
 * a selection asks for it (so a test notices if the reader depends on the provider's own filter).
 */
class FakeCalendarProvider : ContentProvider() {
    companion object {
        var calendars: List<Row> = emptyList()
        var instances: List<Row>? = emptyList() // null: the provider answers with no cursor at all
        var failure: RuntimeException? = null
        var queries = 0
        var lastInstancesUri: Uri? = null

        fun reset() {
            calendars = emptyList(); instances = emptyList(); failure = null; queries = 0; lastInstancesUri = null
        }

        fun install() {
            reset()
            Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
        }

        fun calendar(id: Long, name: String, account: String = "me@example.com", visible: Boolean = true, sync: Boolean = true, color: Int = 0xFF3366CC.toInt()): Row =
            mapOf(
                CalendarContract.Calendars._ID to id, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME to name,
                CalendarContract.Calendars.ACCOUNT_NAME to account, CalendarContract.Calendars.CALENDAR_COLOR to color,
                CalendarContract.Calendars.VISIBLE to if (visible) 1 else 0, CalendarContract.Calendars.SYNC_EVENTS to if (sync) 1 else 0,
            )

        fun instance(
            eventId: Long, calendarId: Long, title: String, begin: Long, end: Long, allDay: Boolean = false,
            zone: String? = "Europe/Berlin", status: Int = CalendarContract.Events.STATUS_CONFIRMED,
            description: String = "", location: String = "",
        ): Row = mapOf(
            CalendarContract.Instances.EVENT_ID to eventId, CalendarContract.Instances.CALENDAR_ID to calendarId,
            CalendarContract.Instances.TITLE to title, CalendarContract.Instances.DESCRIPTION to description,
            CalendarContract.Instances.EVENT_LOCATION to location, CalendarContract.Instances.BEGIN to begin,
            CalendarContract.Instances.END to end, CalendarContract.Instances.ALL_DAY to if (allDay) 1 else 0,
            CalendarContract.Instances.EVENT_TIMEZONE to zone, CalendarContract.Instances.STATUS to status,
        )
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? {
        queries++
        failure?.let { throw it }
        val cols = projection ?: error("projection required")
        val path = uri.pathSegments
        return when {
            path.firstOrNull() == "calendars" -> cursor(cols, calendars)
            path.firstOrNull() == "instances" && path.getOrNull(1) == "when" -> {
                lastInstancesUri = uri
                val begin = path[2].toLong(); val end = path[3].toLong()
                val rows = instances ?: return null
                val inWindow = rows.filter { (it[CalendarContract.Instances.BEGIN] as Long) < end && (it[CalendarContract.Instances.END] as Long) >= begin }
                    .sortedBy { it[CalendarContract.Instances.BEGIN] as Long }
                // The convenience Instances.query() would pass "visible=?"; the reader must not depend on it.
                val visibleOnly = selection?.contains("visible") == true
                val visible = calendars.filter { (it[CalendarContract.Calendars.VISIBLE] as Int) == 1 }.map { it[CalendarContract.Calendars._ID] }
                cursor(cols, if (visibleOnly) inWindow.filter { it[CalendarContract.Instances.CALENDAR_ID] in visible } else inWindow)
            }
            else -> error("unexpected $uri")
        }
    }

    private fun cursor(cols: Array<String>, rows: List<Row>): Cursor =
        MatrixCursor(cols).apply { rows.forEach { r -> addRow(cols.map { c -> r[c] }.toTypedArray()) } }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = throw UnsupportedOperationException()
}
