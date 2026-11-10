package app.daysemble.data.device

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DeviceCalendarReaderTest {
    @Test fun allDayEventsKeepTheirUtcDateAcrossTimeZones() {
        val event = DeviceCalendarEvent(1, "Holiday", Instant.parse("2026-11-09T00:00:00Z"), true)
        assertThat(event.localDate(ZoneId.of("America/Los_Angeles"))).isEqualTo(LocalDate.of(2026, 11, 9))
    }

    @Test fun timedEventsFollowTheDeviceTimeZone() {
        val event = DeviceCalendarEvent(2, "Meeting", Instant.parse("2026-11-09T01:00:00Z"), false)
        assertThat(event.localDate(ZoneId.of("America/Los_Angeles"))).isEqualTo(LocalDate.of(2026, 11, 8))
    }
}
