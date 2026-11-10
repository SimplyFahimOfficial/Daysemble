package app.daysemble.data.device

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceCalendarReaderTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setUp() {
        FakeCalendarProvider.rows = emptyList()
        FakeCalendarProvider.failure = null
        FakeCalendarProvider.lastUri = null
        Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
    }

    @Test fun allDayEventsKeepTheirUtcDateAcrossTimeZones() {
        val event = DeviceCalendarEvent(1, "Holiday", Instant.parse("2026-11-09T00:00:00Z"), Instant.parse("2026-11-10T00:00:00Z"), true)
        assertThat(event.localDate(ZoneId.of("America/Los_Angeles"))).isEqualTo(LocalDate.of(2026, 11, 9))
        assertThat(event.localDate(ZoneId.of("Pacific/Auckland"))).isEqualTo(LocalDate.of(2026, 11, 9))
    }

    @Test fun timedEventsFollowTheDeviceTimeZone() {
        val event = DeviceCalendarEvent(2, "Meeting", Instant.parse("2026-11-09T01:00:00Z"), Instant.parse("2026-11-09T02:00:00Z"), false)
        assertThat(event.localDate(ZoneId.of("America/Los_Angeles"))).isEqualTo(LocalDate.of(2026, 11, 8))
    }

    @Test fun keepsRecurringOccurrencesAndDropsEndedEventsEastOfUtc() = runTest {
        val tokyo = ZoneId.of("Asia/Tokyo")
        FakeCalendarProvider.rows = listOf(
            row(1, "Yesterday all day", "2026-11-09T00:00:00Z", "2026-11-10T00:00:00Z", allDay = true),
            row(2, "Today all day", "2026-11-10T00:00:00Z", "2026-11-11T00:00:00Z", allDay = true),
            row(3, "Ended this morning", "2026-11-10T01:00:00Z", "2026-11-10T02:00:00Z"),
            row(7, "Standup", "2026-11-10T04:00:00Z", "2026-11-10T04:15:00Z"),
            row(7, "Standup", "2026-11-11T04:00:00Z", "2026-11-11T04:15:00Z"),
        )

        val events = DeviceCalendarReader.upcoming(app, tokyo, Instant.parse("2026-11-10T03:00:00Z")).getOrThrow()

        assertThat(events.map { it.eventId }).containsExactly(2L, 7L, 7L).inOrder()
        assertThat(events[0].localDate(tokyo)).isEqualTo(LocalDate.of(2026, 11, 10))
        assertThat(events.map { it.startsAt }.distinct()).hasSize(3)
        // Window starts at Tokyo midnight and spans 14 days.
        assertThat(FakeCalendarProvider.lastUri!!.pathSegments.takeLast(2))
            .containsExactly(Instant.parse("2026-11-09T15:00:00Z").toEpochMilli().toString(), Instant.parse("2026-11-23T15:00:00Z").toEpochMilli().toString())
            .inOrder()
    }

    @Test fun keepsTodaysAllDayEventLateInTheEveningWestOfUtc() = runTest {
        val la = ZoneId.of("America/Los_Angeles")
        FakeCalendarProvider.rows = listOf(row(1, "Holiday", "2026-11-09T00:00:00Z", "2026-11-10T00:00:00Z", allDay = true))

        // 23:00 on 9 Nov in Los Angeles is already 10 Nov in UTC.
        val events = DeviceCalendarReader.upcoming(app, la, Instant.parse("2026-11-10T07:00:00Z")).getOrThrow()

        assertThat(events.single().localDate(la)).isEqualTo(LocalDate.of(2026, 11, 9))
    }

    @Test fun limitsToTenEvents() = runTest {
        FakeCalendarProvider.rows = (1..12).map {
            row(it.toLong(), "E$it", Instant.parse("2026-11-11T00:00:00Z").plusSeconds(it * 3600L).toString(), "2026-11-12T00:00:00Z")
        }
        val events = DeviceCalendarReader.upcoming(app, ZoneId.of("UTC"), Instant.parse("2026-11-10T00:00:00Z")).getOrThrow()
        assertThat(events).hasSize(10)
    }

    @Test fun deniedPermissionFailsWithoutQuerying() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        val result = DeviceCalendarReader.upcoming(app, ZoneId.of("UTC"))
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        assertThat(FakeCalendarProvider.lastUri).isNull()
    }

    @Test fun revokedDuringQueryIsAFailureNotAnEmptyList() = runTest {
        FakeCalendarProvider.failure = SecurityException("revoked")
        assertThat(DeviceCalendarReader.upcoming(app, ZoneId.of("UTC")).isFailure).isTrue()
    }

    @Test fun missingProviderIsAFailure() = runTest {
        FakeCalendarProvider.rows = null
        assertThat(DeviceCalendarReader.upcoming(app, ZoneId.of("UTC")).isFailure).isTrue()
    }

    private fun row(id: Long, title: String, begin: String, end: String, allDay: Boolean = false): Array<Any?> =
        arrayOf(id, title, Instant.parse(begin).toEpochMilli(), Instant.parse(end).toEpochMilli(), if (allDay) 1 else 0)
}

/** Stands in for the system Calendar Provider; the real one does the RRULE expansion and visibility filter. */
class FakeCalendarProvider : ContentProvider() {
    companion object {
        var rows: List<Array<Any?>>? = emptyList()
        var failure: RuntimeException? = null
        var lastUri: Uri? = null
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? {
        lastUri = uri
        failure?.let { throw it }
        // The convenience query must only ask for visible calendars, sorted by start.
        check(selection == "visible=?" && args?.toList() == listOf("1") && sort == "begin ASC") { "$selection $sort" }
        return rows?.let { r -> MatrixCursor(projection).apply { r.forEach { addRow(it) } } }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = throw UnsupportedOperationException()
}
