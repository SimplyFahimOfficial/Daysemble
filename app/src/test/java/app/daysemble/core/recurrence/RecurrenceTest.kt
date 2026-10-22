package app.daysemble.core.recurrence

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RecurrenceTest {

    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `parse and format round trip`() {
        val r = RRule.parse("RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=10")!!
        assertThat(r.isSupported).isTrue()
        assertThat(r.freq).isEqualTo(Freq.WEEKLY)
        assertThat(r.interval).isEqualTo(2)
        assertThat(r.byDay.map { it.day }).containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        assertThat(RRule.parse(r.format())).isEqualTo(r)
    }

    @Test
    fun `unsupported parts are preserved raw and flagged`() {
        val r = RRule.parse("RRULE:FREQ=MONTHLY;BYSETPOS=-1;BYDAY=MO,TU,WE,TH,FR")!!
        assertThat(r.isSupported).isFalse()
        assertThat(r.format()).isEqualTo("RRULE:FREQ=MONTHLY;BYSETPOS=-1;BYDAY=MO,TU,WE,TH,FR")
        assertThat(RRule.parse("RRULE:FREQ=HOURLY")!!.isSupported).isFalse()
        assertThat(RRule.parse("RRULE:FREQ=DAILY;COUNT=abc")!!.isSupported).isFalse()
    }

    @Test
    fun `daily with count`() {
        val r = RRule.parse("FREQ=DAILY;COUNT=3")!!
        assertThat(RecurrenceExpander.dates(r, d("2026-10-30"), d("2026-01-01"), d("2027-01-01")))
            .containsExactly(d("2026-10-30"), d("2026-10-31"), d("2026-11-01")).inOrder()
    }

    @Test
    fun `count includes occurrences before the window`() {
        val r = RRule.parse("FREQ=DAILY;COUNT=5")!!
        assertThat(RecurrenceExpander.dates(r, d("2026-10-01"), d("2026-10-04"), d("2026-12-31")))
            .containsExactly(d("2026-10-04"), d("2026-10-05")).inOrder()
    }

    @Test
    fun `weekly byday skips days before dtstart and respects interval`() {
        // Starts Wednesday; Mondays/Wednesdays every 2 weeks.
        val r = RRule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE")!!
        val got = RecurrenceExpander.dates(r, d("2026-10-21"), d("2026-10-01"), d("2026-11-20"))
        assertThat(got).containsExactly(d("2026-10-21"), d("2026-11-02"), d("2026-11-04"), d("2026-11-16"), d("2026-11-18")).inOrder()
    }

    @Test
    fun `monthly on the 31st skips short months`() {
        val r = RRule.parse("FREQ=MONTHLY")!!
        val got = RecurrenceExpander.dates(r, d("2026-01-31"), d("2026-01-01"), d("2026-06-30"))
        assertThat(got).containsExactly(d("2026-01-31"), d("2026-03-31"), d("2026-05-31")).inOrder()
    }

    @Test
    fun `monthly last day and nth weekday`() {
        val last = RRule.parse("FREQ=MONTHLY;BYMONTHDAY=-1")!!
        assertThat(RecurrenceExpander.dates(last, d("2026-01-31"), d("2026-01-01"), d("2026-03-31")))
            .containsExactly(d("2026-01-31"), d("2026-02-28"), d("2026-03-31")).inOrder()
        val secondTue = RRule.parse("FREQ=MONTHLY;BYDAY=2TU")!!
        assertThat(RecurrenceExpander.dates(secondTue, d("2026-10-13"), d("2026-10-01"), d("2026-12-31")))
            .containsExactly(d("2026-10-13"), d("2026-11-10"), d("2026-12-08")).inOrder()
        val lastFri = RRule.parse("FREQ=MONTHLY;BYDAY=-1FR")!!
        assertThat(RecurrenceExpander.dates(lastFri, d("2026-10-30"), d("2026-10-01"), d("2026-12-31")))
            .containsExactly(d("2026-10-30"), d("2026-11-27"), d("2026-12-25")).inOrder()
    }

    @Test
    fun `yearly on 29 February only occurs in leap years`() {
        val r = RRule.parse("FREQ=YEARLY")!!
        assertThat(RecurrenceExpander.dates(r, d("2024-02-29"), d("2024-01-01"), d("2032-12-31")))
            .containsExactly(d("2024-02-29"), d("2028-02-29"), d("2032-02-29")).inOrder()
    }

    @Test
    fun `until is inclusive`() {
        val r = RRule.parse("FREQ=DAILY;UNTIL=20261024")!!
        assertThat(RecurrenceExpander.dates(r, d("2026-10-22"), d("2026-10-01"), d("2026-12-31"))).hasSize(3)
    }

    @Test
    fun `timed weekly series keeps local wall time across DST`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val start = LocalDateTime.parse("2026-10-19T09:00").atZone(berlin).toInstant() // Monday, CEST
        val r = RRule.parse("FREQ=WEEKLY")!!
        val occ = RecurrenceExpander.timed(
            r, start, Duration.ofHours(1), berlin,
            Instant.parse("2026-10-18T00:00:00Z"), Instant.parse("2026-11-03T00:00:00Z"),
        )
        assertThat(occ.map { it.start.atZone(berlin).toLocalTime().toString() }).containsExactly("09:00", "09:00", "09:00")
        // DST ends 25 Oct 2026: UTC offset moves from +2 to +1.
        assertThat(occ[0].start).isEqualTo(Instant.parse("2026-10-19T07:00:00Z"))
        assertThat(occ[1].start).isEqualTo(Instant.parse("2026-10-26T08:00:00Z"))
        assertThat(occ.map { it.key }).containsExactly("2026-10-19T09:00", "2026-10-26T09:00", "2026-11-02T09:00")
    }

    @Test
    fun `timed until in UTC is compared as an instant`() {
        val ny = ZoneId.of("America/New_York")
        val start = LocalDateTime.parse("2026-10-20T20:00").atZone(ny).toInstant()
        // 2026-10-22T00:00Z == 2026-10-21T20:00 EDT, inclusive.
        val r = RRule.parse("FREQ=DAILY;UNTIL=20261022T000000Z")!!
        val occ = RecurrenceExpander.timed(r, start, Duration.ofMinutes(30), ny, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"))
        assertThat(occ).hasSize(2)
    }

    @Test
    fun `occurrence overlapping the window start is included`() {
        val zone = ZoneId.of("UTC")
        val start = Instant.parse("2026-10-20T23:00:00Z")
        val occ = RecurrenceExpander.timed(RRule.parse("FREQ=DAILY")!!, start, Duration.ofHours(3), zone,
            Instant.parse("2026-10-22T00:00:00Z"), Instant.parse("2026-10-23T00:00:00Z"))
        assertThat(occ.map { it.key }).containsExactly("2026-10-21T23:00", "2026-10-22T23:00").inOrder()
    }

    @Test
    fun `all-day multi-day series`() {
        val r = RRule.parse("FREQ=WEEKLY")!!
        val occ = RecurrenceExpander.allDay(r, d("2026-10-17"), 2, d("2026-10-19"), d("2026-10-25"))
        // Sat-Sun weekends; the first one ends (exclusive) on the 19th so it's excluded.
        assertThat(occ.map { it.key }).containsExactly("2026-10-24")
    }

    @Test
    fun `recurrence block with exdates`() {
        val zone = ZoneId.of("Europe/Berlin")
        val rec = Recurrence.parse(
            listOf("RRULE:FREQ=DAILY", "EXDATE;TZID=Europe/Berlin:20261023T090000,20261024T090000"),
            isAllDay = false, zone = zone,
        )
        assertThat(rec.isSupported).isTrue()
        assertThat(rec.exdateKeys).containsExactly("2026-10-23T09:00", "2026-10-24T09:00")
        val utc = Recurrence.parse(listOf("RRULE:FREQ=DAILY", "EXDATE:20261023T070000Z"), false, zone)
        assertThat(utc.exdateKeys).containsExactly("2026-10-23T09:00")
        val allDay = Recurrence.parse(listOf("RRULE:FREQ=DAILY", "EXDATE;VALUE=DATE:20261023"), true, zone)
        assertThat(allDay.exdateKeys).containsExactly("2026-10-23")
        assertThat(Recurrence.parse(listOf("RRULE:FREQ=DAILY", "RDATE:20261030T090000Z"), false, zone).isSupported).isFalse()
    }

    @Test
    fun `pathological rules terminate`() {
        val r = RRule.parse("FREQ=DAILY")!!
        val got = RecurrenceExpander.dates(r, d("1900-01-01"), d("2026-01-01"), d("2100-01-01"))
        assertThat(got.size).isAtMost(RecurrenceExpander.MAX_RESULTS)
    }

    @Test
    fun `last date of bounded rules`() {
        assertThat(RecurrenceExpander.lastDate(RRule.parse("FREQ=DAILY;COUNT=3")!!, d("2026-10-22"))).isEqualTo(d("2026-10-24"))
        assertThat(RecurrenceExpander.lastDate(RRule.parse("FREQ=DAILY")!!, d("2026-10-22"))).isNull()
    }
}
