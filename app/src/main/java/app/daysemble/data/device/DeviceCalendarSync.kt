package app.daysemble.data.device

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.room.withTransaction
import app.daysemble.R
import app.daysemble.core.time.AppClock
import app.daysemble.data.db.CalendarSourceEntity
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.EventEntity
import app.daysemble.data.db.SourceKind
import app.daysemble.data.repo.EventRepository
import app.daysemble.data.repo.toArgbInt
import app.daysemble.ui.theme.EventSwatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.ZoneId

/**
 * Mirrors the phone's Calendar Provider (whatever calendar apps and account sync put there, such as
 * a Google account's calendars) into Room as read-only [SourceKind.DEVICE] calendars and events, so
 * Today, Calendar, search and the calendar list show them like any other event.
 *
 * - Read-only: Daysemble never writes to the provider (no WRITE_CALENDAR). Mirrored events can't be
 *   edited, and nothing is uploaded.
 * - One row per provider instance, with the id `device:<eventId>:<begin>`: a refresh rewrites only
 *   rows that differ, so repeated refreshes can't duplicate anything. The provider expands repeats
 *   and exceptions itself.
 * - A failed read (provider missing, crashed) keeps what was mirrored before. Losing the permission
 *   removes the mirror, so no copy stays after access is withdrawn.
 * - Per-calendar choices made in Daysemble (shown or hidden, colour) survive refreshes.
 * - This is calendar data only. Google Tasks are not in the Calendar Provider and are not synced.
 *
 * shortcut: only [PAST_DAYS] back and [FUTURE_DAYS] ahead are mirrored, so events outside that window
 * are not shown. Upgrade to loading the visible range on demand if that matters.
 */
class DeviceCalendarSync(
    private val context: Context,
    private val db: DaysembleDatabase,
    private val clock: AppClock,
    scope: CoroutineScope,
) {
    enum class Result { SYNCED, NO_PERMISSION, UNAVAILABLE }

    private val mutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val _failed = MutableStateFlow(false)

    /** The last refresh couldn't read the Calendar Provider (the mirror is kept as it was). */
    val failed: StateFlow<Boolean> = _failed

    private var observing = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = requestRefresh()
    }

    init {
        scope.launch {
            requests.receiveAsFlow().collect {
                delay(DEBOUNCE_MS)
                refresh()
            }
        }
    }

    /** Refreshes soon, off the caller's thread. Bursts (an account sync writes many rows) become one refresh. */
    fun requestRefresh() { requests.trySend(Unit) }

    /** Refreshes when the provider reports a change, while the app is on screen. */
    fun startObserving() {
        if (observing) return
        observing = runCatching { context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer) }.isSuccess
    }

    fun stopObserving() {
        if (!observing) return
        observing = false
        context.contentResolver.unregisterContentObserver(observer)
    }

    suspend fun refresh(): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!granted()) return@withContext purge(Result.NO_PERMISSION)
            val snapshot = try {
                read()
            } catch (_: SecurityException) { // revoked while reading
                return@withContext purge(Result.NO_PERMISSION)
            } catch (_: RuntimeException) { // no provider, or it failed: keep the last mirror
                _failed.value = true
                return@withContext Result.UNAVAILABLE
            }
            apply(snapshot)
            _failed.value = false
            Result.SYNCED
        }
    }

    private fun granted() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private suspend fun purge(result: Result): Result {
        db.calendars().deleteAllDevice() // the events go with their calendars
        _failed.value = false
        return result
    }

    private class Snapshot(val calendars: List<CalendarSourceEntity>, val events: List<EventEntity>)

    private fun read(): Snapshot {
        val resolver = context.contentResolver
        val calendars = readCalendars(resolver)
        val ids = calendars.mapTo(HashSet()) { it.id }
        return Snapshot(calendars, readEvents(resolver).filter { it.calendarId in ids })
    }

    private fun readCalendars(resolver: ContentResolver): List<CalendarSourceEntity> {
        val projection = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME,
            Calendars.CALENDAR_COLOR, Calendars.VISIBLE, Calendars.SYNC_EVENTS,
        )
        val cursor = resolver.query(Calendars.CONTENT_URI, projection, null, null, null) ?: error("Calendar Provider unavailable")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    // A calendar that isn't synced to the device has no events here.
                    if (it.int(Calendars.SYNC_EVENTS) != 1) continue
                    val account = it.string(Calendars.ACCOUNT_NAME)
                    add(
                        CalendarSourceEntity(
                            id = calendarId(it.long(Calendars._ID)), kind = SourceKind.DEVICE, remoteId = it.long(Calendars._ID).toString(),
                            accountEmail = account.ifBlank { null },
                            name = it.string(Calendars.CALENDAR_DISPLAY_NAME).ifBlank { account }
                                .ifBlank { context.getString(R.string.device_calendar_unnamed) },
                            colorArgb = it.int(Calendars.CALENDAR_COLOR).let { color -> if (color == 0) DEFAULT_COLOR else color or OPAQUE },
                            isVisible = it.int(Calendars.VISIBLE) == 1,
                            accessRole = READ_ONLY_ROLE,
                        ),
                    )
                }
            }
        }
    }

    private fun readEvents(resolver: ContentResolver): List<EventEntity> {
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.DESCRIPTION, Instances.EVENT_LOCATION,
            Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.EVENT_TIMEZONE, Instances.STATUS,
        )
        val now = clock.now()
        val uri = Instances.CONTENT_URI.buildUpon()
            .also {
                ContentUris.appendId(it, now.minus(Duration.ofDays(PAST_DAYS)).toEpochMilli())
                ContentUris.appendId(it, now.plus(Duration.ofDays(FUTURE_DAYS)).toEpochMilli())
            }.build()
        // Not Instances.query(): that helper adds "visible = 1", but hidden calendars are mirrored too,
        // so they can be switched on in Lists & calendars.
        val cursor = resolver.query(uri, projection, null, null, "${Instances.BEGIN} ASC")
            ?: error("Calendar Provider unavailable")
        val zone = clock.zone()
        val untitled = context.getString(R.string.untitled_event)
        return cursor.use {
            val rows = LinkedHashMap<String, EventEntity>()
            while (it.moveToNext()) {
                if (it.int(Instances.STATUS) == Events.STATUS_CANCELED) continue
                val event = toEntity(it, zone, untitled)
                rows[event.id] = event
            }
            rows.values.toList()
        }
    }

    private fun toEntity(c: Cursor, zone: ZoneId, untitled: String): EventEntity {
        val begin = c.long(Instances.BEGIN)
        val end = maxOf(c.long(Instances.END), begin)
        val allDay = c.int(Instances.ALL_DAY) != 0
        val first = Math.floorDiv(begin, DAY_MS)
        val eventZone = c.string(Instances.EVENT_TIMEZONE).takeIf { id -> runCatching { ZoneId.of(id) }.isSuccess } ?: zone.id
        return EventRepository.withEnvelope(
            EventEntity(
                id = "device:${c.long(Instances.EVENT_ID)}:$begin", calendarId = calendarId(c.long(Instances.CALENDAR_ID)),
                title = c.string(Instances.TITLE).ifBlank { untitled }, description = c.string(Instances.DESCRIPTION),
                location = c.string(Instances.EVENT_LOCATION),
                isAllDay = allDay,
                // All-day instances are UTC midnights (end exclusive, or the last millisecond of the day).
                startUtc = if (allDay) 0 else begin, endUtc = if (allDay) 0 else end,
                startDate = if (allDay) first else 0,
                endDateExclusive = if (allDay) maxOf(Math.floorDiv(end + DAY_MS - 1, DAY_MS), first + 1) else 0,
                timeZone = if (allDay) zone.id else eventZone,
                rangeStartUtc = 0, rangeEndUtc = 0, createdAt = 0, updatedAt = 0,
            ),
        )
    }

    private suspend fun apply(snapshot: Snapshot) = db.withTransaction {
        val calendars = db.calendars()
        val events = db.events()
        val oldCalendars = calendars.byKind(SourceKind.DEVICE).associateBy { it.id }
        for (c in snapshot.calendars) {
            val old = oldCalendars[c.id]
            // A calendar already here keeps the visibility and colour chosen in Daysemble.
            if (old == null) calendars.upsert(c)
            else if (old.name != c.name || old.accountEmail != c.accountEmail) calendars.updateDevice(c.id, c.name, c.accountEmail)
        }
        val current = snapshot.calendars.mapTo(HashSet()) { it.id }
        oldCalendars.keys.filter { it !in current }.forEach { calendars.delete(it) } // cascades to their events

        val oldEvents = events.deviceEvents().associateBy { it.id }
        events.upsertAll(snapshot.events.filter { oldEvents[it.id] != it })
        val kept = snapshot.events.mapTo(HashSet()) { it.id }
        oldEvents.keys.filter { it !in kept }.chunked(DELETE_CHUNK).forEach { events.deleteAll(it) }
    }

    private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column)).orEmpty()
    private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))

    companion object {
        const val PAST_DAYS = 60L
        const val FUTURE_DAYS = 400L
        const val READ_ONLY_ROLE = "reader"
        private const val DEBOUNCE_MS = 400L
        private const val DAY_MS = 86_400_000L
        private const val OPAQUE = 0xFF000000.toInt()
        private const val DELETE_CHUNK = 500
        private val DEFAULT_COLOR = EventSwatch.VIOLET.light.toArgbInt()

        fun calendarId(providerId: Long) = "device:$providerId"
    }
}
