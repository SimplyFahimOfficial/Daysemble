package app.daysemble.ui

import app.daysemble.ui.components.Zones
import app.daysemble.ui.event.newEventForm
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ZoneAndFormTest {
    @Test fun `zone list contains regions with correct offsets and excludes Etc ids`() {
        val all = Zones.all(Instant.parse("2026-07-01T00:00:00Z"))
        assertThat(all.none { it.id.startsWith("Etc/") }).isTrue()
        assertThat(all.first { it.id == "Europe/Berlin" }.offsetSeconds).isEqualTo(7200)
        assertThat(all.first { it.id == "Asia/Kolkata" }.label).contains("GMT+05:30")
        assertThat(all.map { it.offsetSeconds }).isInOrder()
        assertThat(Zones.matches(all.first { it.id == "America/New_York" }, "new york")).isTrue()
    }

    @Test fun `changing zone keeps wall clock time and moves the instant`() {
        val f = newEventForm(LocalDate.parse("2026-10-22"), 9 * 60, 60, ZoneId.of("Europe/Berlin"), "Call", null)
        val tokyo = f.copy(zone = "Asia/Tokyo")
        assertThat(tokyo.toDraft().startTime).isEqualTo(f.toDraft().startTime)
        assertThat(tokyo.toDraft().startInstant()).isEqualTo(Instant.parse("2026-10-22T00:00:00Z"))
        assertThat(f.toDraft().startInstant()).isEqualTo(Instant.parse("2026-10-22T07:00:00Z"))
    }
}
