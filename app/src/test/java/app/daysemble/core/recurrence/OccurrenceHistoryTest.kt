package app.daysemble.core.recurrence

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class OccurrenceHistoryTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun d(s: String) = LocalDate.parse(s)
    private fun at(s: String, h: Int = 12) = d(s).atTime(h, 0).atZone(zone).toInstant()
    private val daily = RRule.parse("FREQ=DAILY")!!

    private fun done(date: String) = OccurrenceRow("s:$date", d(date), d(date), null, true, at(date, 18))
    private fun open(date: String, time: LocalTime? = null) = OccurrenceRow("s:$date", d(date), d(date), time, false, null)

    private fun build(rule: RRule?, anchor: String, rows: List<OccurrenceRow>, now: Instant, skips: Set<LocalDate> = emptySet()) =
        OccurrenceHistoryCalc.build(rule, d(anchor), rows, skips, now, zone, horizonEnd = now.atZone(zone).toLocalDate().plusDays(6))

    @Test fun newSeries_hasNoHistory_onlyTheActiveOneAndUpcoming() {
        val h = build(daily, "2026-10-27", listOf(open("2026-10-27")), at("2026-10-27", 9))
        assertThat(h.isEmpty).isTrue()
        assertThat(h.completed).isEqualTo(0)
        assertThat(h.missed).isEqualTo(0)
        assertThat(h.active!!.status).isEqualTo(OccurrenceStatus.DUE)
        assertThat(h.upcoming).isEqualTo(7) // today (active) + 6 days
        assertThat(h.nextAfterActive).isEqualTo(d("2026-10-28"))
    }

    @Test fun datesTheSeriesPassedOver_areMissed_completedOnesCompleted() {
        // Done 20, 21, then nothing until 25 was completed (22–24 were passed over), now 27 is open.
        val rows = listOf(done("2026-10-20"), done("2026-10-21"), done("2026-10-25"), open("2026-10-27"))
        val h = build(daily, "2026-10-20", rows, at("2026-10-27", 9))
        val byDate = h.cells.associate { it.date to it.status }
        assertThat(byDate[d("2026-10-22")]).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(byDate[d("2026-10-26")]).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(byDate[d("2026-10-25")]).isEqualTo(OccurrenceStatus.COMPLETED)
        assertThat(h.completed).isEqualTo(3)
        assertThat(h.missed).isEqualTo(4) // 22, 23, 24, 26
        assertThat(h.streak).isEqualTo(0) // 26 was missed, today's is still open
    }

    @Test fun activeOccurrencePastDue_isOverdueNotMissed() {
        val h = build(daily, "2026-10-26", listOf(done("2026-10-26"), open("2026-10-27", LocalTime.of(9, 0))), at("2026-10-27", 10))
        assertThat(h.active!!.status).isEqualTo(OccurrenceStatus.OVERDUE)
        assertThat(h.missed).isEqualTo(0)
        assertThat(h.streak).isEqualTo(1) // an overdue active one doesn't break it
    }

    @Test fun datesBehindAnOverdueActiveOne_areMissed_becauseTheyCanNoLongerBeDone() {
        // The 24th is still open on the 27th: completing it moves the series to the 27th or later.
        val h = build(daily, "2026-10-24", listOf(open("2026-10-24")), at("2026-10-27", 9))
        val byDate = h.cells.associate { it.date to it.status }
        assertThat(byDate[d("2026-10-24")]).isEqualTo(OccurrenceStatus.OVERDUE)
        assertThat(byDate[d("2026-10-25")]).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(byDate[d("2026-10-26")]).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(byDate[d("2026-10-27")]).isEqualTo(OccurrenceStatus.UPCOMING)
    }

    @Test fun skippedDates_areSkipped_notMissed_andDontBreakTheStreak() {
        val rows = listOf(done("2026-10-24"), done("2026-10-26"), open("2026-10-27"))
        val h = build(daily, "2026-10-24", rows, at("2026-10-27", 9), skips = setOf(d("2026-10-25")))
        assertThat(h.cells.single { it.date == d("2026-10-25") }.status).isEqualTo(OccurrenceStatus.SKIPPED)
        assertThat(h.missed).isEqualTo(0)
        assertThat(h.skipped).isEqualTo(1)
        assertThat(h.streak).isEqualTo(2)
    }

    @Test fun weeklyRule_onlyScheduledDaysExist() {
        val tueThu = RRule.parse("FREQ=WEEKLY;BYDAY=TU,TH")!!
        val rows = listOf(done("2026-10-20"), open("2026-10-27")) // Thu 22 was passed over
        val h = build(tueThu, "2026-10-20", rows, at("2026-10-27", 9))
        assertThat(h.cells.map { it.date.toString() to it.status }).containsExactly(
            "2026-10-20" to OccurrenceStatus.COMPLETED,
            "2026-10-22" to OccurrenceStatus.MISSED,
            "2026-10-27" to OccurrenceStatus.DUE,
            "2026-10-29" to OccurrenceStatus.UPCOMING,
        ).inOrder()
    }

    @Test fun countLimit_reportsWhatIsLeft() {
        val three = RRule.parse("FREQ=DAILY;COUNT=3")!!
        val h = build(three, "2026-10-26", listOf(done("2026-10-26"), open("2026-10-27")), at("2026-10-27", 9))
        assertThat(h.endsWithinHorizon).isTrue()
        assertThat(h.upcoming).isEqualTo(2) // 27 (active) and 28; nothing after
    }

    @Test fun endedSeries_showsOnlyStoredRows() {
        val h = build(null, "2026-10-20", listOf(done("2026-10-20"), done("2026-10-23")), at("2026-10-27", 9))
        assertThat(h.cells.map { it.status }).containsExactly(OccurrenceStatus.COMPLETED, OccurrenceStatus.COMPLETED)
        assertThat(h.missed).isEqualTo(0)
        assertThat(h.upcoming).isEqualTo(0)
    }

    @Test fun completedRowMovedOffTheSchedule_staysInTheHistory() {
        val moved = OccurrenceRow("x", null, d("2026-10-10"), null, true, at("2026-10-10"))
        val h = build(daily, "2026-10-26", listOf(moved, open("2026-10-27")), at("2026-10-27", 9))
        assertThat(h.cells.first().date).isEqualTo(d("2026-10-10"))
        assertThat(h.completed).isEqualTo(1)
    }

    @Test fun monthlyOnThe31st_clampsLikeTheRepository() {
        val monthly = RRule.parse("FREQ=MONTHLY")!!
        val rows = listOf(done("2027-01-31"), open("2027-02-28"))
        val h = OccurrenceHistoryCalc.build(monthly, d("2027-01-31"), rows, emptySet(), at("2027-02-10"), zone, d("2027-05-01"))
        assertThat(h.cells.map { it.date.toString() }).containsExactly("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30").inOrder()
    }
}
