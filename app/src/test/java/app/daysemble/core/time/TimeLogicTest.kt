package app.daysemble.core.time

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class TimeLogicTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant()

    @Test
    fun `countdown rounds up and switches to ends-in`() {
        val s = at("2026-10-22T10:00"); val e = at("2026-10-22T11:00")
        assertThat(RelativeTime.of(s, e, false, at("2026-10-22T09:34:30"), zone)).isEqualTo(Relative.StartsIn(26))
        assertThat(RelativeTime.of(s, e, false, at("2026-10-22T09:58:30"), zone)).isEqualTo(Relative.StartsIn(2))
        assertThat(RelativeTime.of(s, e, false, at("2026-10-22T09:59:30"), zone)).isEqualTo(Relative.Now)
        assertThat(RelativeTime.of(s, e, false, at("2026-10-22T10:00:30"), zone)).isEqualTo(Relative.Now)
        assertThat(RelativeTime.of(s, e, false, at("2026-10-22T10:50"), zone)).isEqualTo(Relative.EndsIn(10))
        assertThat(RelativeTime.of(s, e, false, at("2026-10-22T11:00"), zone)).isEqualTo(Relative.Ended)
    }

    @Test
    fun `later days use calendar days, not 24h blocks`() {
        val s = at("2026-10-24T08:00"); val e = at("2026-10-24T09:00")
        assertThat(RelativeTime.of(s, e, false, at("2026-10-22T23:00"), zone)).isEqualTo(Relative.StartsInDays(2))
        // Tomorrow morning but < 12 h away: still in hours/minutes.
        val s2 = at("2026-10-23T07:00")
        assertThat(RelativeTime.of(s2, s2.plusSeconds(3600), false, at("2026-10-22T22:00"), zone)).isEqualTo(Relative.StartsIn(540))
    }

    @Test
    fun `countdown across the DST change is in real minutes`() {
        // UK clocks go back at 02:00 BST on 25 Oct 2026 -> 01:00 GMT.
        val now = at("2026-10-25T00:30")
        val start = at("2026-10-25T02:30") // three real hours later
        assertThat(RelativeTime.of(start, start.plusSeconds(1800), false, now, zone)).isEqualTo(Relative.StartsIn(180))
    }

    @Test
    fun `all-day relative states`() {
        val s = java.time.LocalDate.parse("2026-10-22").atStartOfDay(zone).toInstant()
        val e = java.time.LocalDate.parse("2026-10-23").atStartOfDay(zone).toInstant()
        assertThat(RelativeTime.of(s, e, true, at("2026-10-22T23:59"), zone)).isEqualTo(Relative.AllDayToday)
        assertThat(RelativeTime.of(s, e, true, at("2026-10-23T00:00"), zone)).isEqualTo(Relative.Ended)
        assertThat(RelativeTime.of(s, e, true, at("2026-10-21T12:00"), zone)).isEqualTo(Relative.StartsInDays(1))
    }

    @Test
    fun `next tick aligns to the minute`() {
        assertThat(RelativeTime.nextTickDelayMs(Instant.ofEpochMilli(60_000L * 5 + 1_500))).isEqualTo(58_500)
    }

    @Test
    fun `overlap layout`() {
        val placed = DayLayout.layout(
            listOf(
                DayLayout.Item("a", 540, 600), // 9-10
                DayLayout.Item("b", 570, 630), // 9:30-10:30
                DayLayout.Item("c", 600, 660), // 10-11 (fits under a)
                DayLayout.Item("d", 720, 780), // separate cluster
            ),
        ).associateBy { it.key }
        assertThat(placed.getValue("a").columns).isEqualTo(2)
        assertThat(placed.getValue("a").column).isEqualTo(0)
        assertThat(placed.getValue("b").column).isEqualTo(1)
        assertThat(placed.getValue("c").column).isEqualTo(0)
        assertThat(placed.getValue("d").columns).isEqualTo(1)
        assertThat(placed.getValue("d").widthFraction).isEqualTo(1f)
    }

    @Test
    fun `free columns to the right are used`() {
        val placed = DayLayout.layout(
            listOf(
                DayLayout.Item("long", 540, 720),
                DayLayout.Item("x", 540, 570),
                DayLayout.Item("y", 540, 570),
                DayLayout.Item("z", 600, 660),
            ),
        ).associateBy { it.key }
        assertThat(placed.getValue("long").columns).isEqualTo(3)
        // z sits in column 1 and can extend into column 2 (free 10:00-11:00).
        assertThat(placed.getValue("z").column).isEqualTo(1)
        assertThat(placed.getValue("z").span).isEqualTo(2)
    }

    @Test
    fun `zero length events get a minimum height`() {
        val p = DayLayout.layout(listOf(DayLayout.Item("r", 600, 600))).single()
        assertThat(p.endMin - p.startMin).isEqualTo(DayLayout.MIN_DURATION_MIN)
    }
}
