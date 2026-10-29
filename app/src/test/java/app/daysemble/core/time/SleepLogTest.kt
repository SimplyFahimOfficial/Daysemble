package app.daysemble.core.time

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SleepLogTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(d: String, h: Int, m: Int = 0): Instant = LocalDate.parse(d).atTime(h, m).atZone(zone).toInstant()

    @Test fun bedtimeThenWake_acrossMidnight_givesTheElapsedTime() {
        val open = SleepRecord("a", at("2026-10-26", 23, 10), null)
        val d = SleepLog.onWokeUp(listOf(open), at("2026-10-27", 6, 55))
        assertThat(d).isEqualTo(SleepLog.WakeDecision.Close(open))
        val closed = open.copy(wake = at("2026-10-27", 6, 55))
        assertThat(closed.duration).isEqualTo(Duration.ofMinutes(7 * 60 + 45))
        assertThat(closed.nightOf(zone)).isEqualTo(LocalDate.parse("2026-10-27"))
    }

    @Test fun secondGoingToSleep_whileAsleep_asksInsteadOfStartingAnother() {
        val open = SleepRecord("a", at("2026-10-26", 23, 0), null)
        assertThat(SleepLog.onGoingToSleep(listOf(open), at("2026-10-26", 23, 2))).isEqualTo(SleepLog.SleepDecision.AlreadyAsleep(open))
        assertThat(SleepLog.onGoingToSleep(emptyList(), at("2026-10-26", 23, 2))).isEqualTo(SleepLog.SleepDecision.Start)
    }

    @Test fun wakeWithoutBedtime_isRecordedAlone_noDurationInvented() {
        assertThat(SleepLog.onWokeUp(emptyList(), at("2026-10-27", 7))).isEqualTo(SleepLog.WakeDecision.WakeOnly)
        val r = SleepRecord("w", null, at("2026-10-27", 7))
        assertThat(r.duration).isNull()
        assertThat(r.isComplete).isFalse()
        assertThat(SleepLog.lastNight(listOf(r), LocalDate.parse("2026-10-27"), zone)).isNull()
    }

    @Test fun repeatedWakeTap_isAConfirmation_notASecondRecord() {
        val done = SleepRecord("a", at("2026-10-26", 23), at("2026-10-27", 7))
        assertThat(SleepLog.onWokeUp(listOf(done), at("2026-10-27", 7, 5))).isEqualTo(SleepLog.WakeDecision.AlreadyAwake(done))
        // Hours later (a nap, say) it is a new wake-up again.
        assertThat(SleepLog.onWokeUp(listOf(done), at("2026-10-27", 15))).isEqualTo(SleepLog.WakeDecision.WakeOnly)
    }

    @Test fun forgottenBedtime_isNotClosedDaysLater() {
        val stale = SleepRecord("old", at("2026-10-20", 23), null)
        assertThat(SleepLog.openSession(listOf(stale), at("2026-10-27", 7))).isNull()
        assertThat(SleepLog.onWokeUp(listOf(stale), at("2026-10-27", 7))).isEqualTo(SleepLog.WakeDecision.WakeOnly)
        assertThat(SleepLog.onGoingToSleep(listOf(stale), at("2026-10-27", 23))).isEqualTo(SleepLog.SleepDecision.Start)
    }

    @Test fun validate_rejectsContradictions() {
        val now = at("2026-10-27", 12)
        val other = SleepRecord("o", at("2026-10-25", 23), at("2026-10-26", 7))
        fun v(b: Instant?, w: Instant?) = SleepLog.validate(SleepRecord("x", b, w), listOf(other), now)
        assertThat(v(at("2026-10-27", 7), at("2026-10-26", 23))).isEqualTo(SleepLog.EditError.WAKE_NOT_AFTER_BEDTIME)
        assertThat(v(at("2026-10-25", 6), at("2026-10-26", 8))).isEqualTo(SleepLog.EditError.TOO_LONG)
        assertThat(v(at("2026-10-27", 23), null)).isEqualTo(SleepLog.EditError.IN_FUTURE)
        assertThat(v(at("2026-10-26", 6), at("2026-10-26", 9))).isEqualTo(SleepLog.EditError.OVERLAPS)
        assertThat(v(null, null)).isEqualTo(SleepLog.EditError.EMPTY)
        assertThat(v(at("2026-10-26", 23), at("2026-10-27", 7))).isNull()
    }

    @Test fun bedtimeOnly_belongsToTheNextMorning() {
        assertThat(SleepRecord("b", at("2026-10-26", 23), null).nightOf(zone)).isEqualTo(LocalDate.parse("2026-10-27"))
        assertThat(SleepRecord("b", at("2026-10-27", 1), null).nightOf(zone)).isEqualTo(LocalDate.parse("2026-10-27"))
    }

    @Test fun dstNight_countsRealElapsedTime() {
        // 25 Oct 2026, clocks go back: 23:00 → 07:00 is 9 h of elapsed time.
        val r = SleepRecord("d", at("2026-10-24", 23), at("2026-10-25", 7))
        assertThat(r.duration).isEqualTo(Duration.ofHours(9))
    }
}
