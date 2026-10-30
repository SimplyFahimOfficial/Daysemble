package app.daysemble.core.time

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One manually recorded sleep period. [bedtime] comes from "I'm going to sleep", [wake] from
 * "I just woke up"; either may be missing. Only a record with both has a [duration]. This is time
 * keeping, not sleep measurement: nothing here knows whether the user actually slept.
 */
data class SleepRecord(
    val id: String,
    val bedtime: Instant?,
    val wake: Instant?,
    val bedZone: ZoneId? = null,
    val wakeZone: ZoneId? = null,
) {
    /** Bedtime recorded, wake-up not yet. */
    val isOpen: Boolean get() = bedtime != null && wake == null
    val isComplete: Boolean get() = bedtime != null && wake != null
    val duration: Duration? get() = if (bedtime != null && wake != null) Duration.between(bedtime, wake) else null

    /** The later of the two times (what the record is sorted by). */
    val latest: Instant? get() = wake ?: bedtime

    /**
     * The date the record belongs to: the local date of the wake-up (the morning it ended), or for a
     * record with only a bedtime, the morning after it (a bedtime before noon counts for that day).
     */
    fun nightOf(zone: ZoneId): LocalDate? {
        wake?.let { return it.atZone(wakeZone ?: zone).toLocalDate() }
        val b = bedtime?.atZone(bedZone ?: zone) ?: return null
        return if (b.hour >= 12) b.toLocalDate().plusDays(1) else b.toLocalDate()
    }
}

/**
 * The rules behind the two sleep buttons, as pure decisions (the repository applies them in one
 * transaction, so repeated taps can't create two records).
 */
object SleepLog {
    /** An open bedtime older than this is treated as forgotten: a wake-up no longer closes it. */
    val MAX_SESSION: Duration = Duration.ofHours(20)

    /** A second "I just woke up" within this time of the last wake-up is a repeat, not a new record. */
    val REPEAT_WINDOW: Duration = Duration.ofHours(3)

    /** Longest sleep period an edit accepts. */
    val MAX_EDIT: Duration = Duration.ofHours(24)

    sealed interface SleepDecision {
        /** Start a new record with bedtime now. */
        data object Start : SleepDecision
        /** A bedtime is already open: confirm (update it to now) or edit instead of starting another. */
        data class AlreadyAsleep(val open: SleepRecord) : SleepDecision
    }

    sealed interface WakeDecision {
        /** Close [open] with the wake-up now. */
        data class Close(val open: SleepRecord) : WakeDecision
        /** No usable bedtime: record the wake-up alone (no duration is invented). */
        data object WakeOnly : WakeDecision
        /** Woke up was just recorded: confirm (move it to now) or edit instead of adding another. */
        data class AlreadyAwake(val last: SleepRecord) : WakeDecision
    }

    /** The open record that a wake-up at [now] would close, if any (not stale, not in the future). */
    fun openSession(records: List<SleepRecord>, now: Instant): SleepRecord? =
        records.filter { it.isOpen && !it.bedtime!!.isAfter(now) && Duration.between(it.bedtime, now) <= MAX_SESSION }
            .maxByOrNull { it.bedtime!! }

    fun onGoingToSleep(records: List<SleepRecord>, now: Instant): SleepDecision =
        openSession(records, now)?.let { SleepDecision.AlreadyAsleep(it) } ?: SleepDecision.Start

    fun onWokeUp(records: List<SleepRecord>, now: Instant): WakeDecision {
        openSession(records, now)?.let { return WakeDecision.Close(it) }
        val last = records.filter { it.wake != null && !it.wake.isAfter(now) }.maxByOrNull { it.wake!! }
        if (last != null && Duration.between(last.wake, now) < REPEAT_WINDOW) return WakeDecision.AlreadyAwake(last)
        return WakeDecision.WakeOnly
    }

    enum class EditError { WAKE_NOT_AFTER_BEDTIME, TOO_LONG, IN_FUTURE, OVERLAPS, EMPTY }

    /**
     * Checks an edited record: at least one time; wake after bedtime and at most [MAX_EDIT] after it;
     * nothing more than a minute in the future; no overlap with another complete record.
     */
    fun validate(edited: SleepRecord, others: List<SleepRecord>, now: Instant): EditError? {
        val b = edited.bedtime
        val w = edited.wake
        if (b == null && w == null) return EditError.EMPTY
        val limit = now.plusSeconds(60)
        if (listOfNotNull(b, w).any { it.isAfter(limit) }) return EditError.IN_FUTURE
        if (b != null && w != null) {
            if (!w.isAfter(b)) return EditError.WAKE_NOT_AFTER_BEDTIME
            if (Duration.between(b, w) > MAX_EDIT) return EditError.TOO_LONG
            val clash = others.any { o ->
                o.id != edited.id && o.isComplete && o.bedtime!!.isBefore(w) && b.isBefore(o.wake!!)
            }
            if (clash) return EditError.OVERLAPS
        }
        return null
    }

    /** The newest complete record whose wake-up is on [date] (the night before that day). */
    fun lastNight(records: List<SleepRecord>, date: LocalDate, zone: ZoneId): SleepRecord? =
        records.filter { it.isComplete && it.nightOf(zone) == date }.maxByOrNull { it.wake!! }

    /** The newest record of any kind belonging to [date] (see [SleepRecord.nightOf]). */
    fun forDate(records: List<SleepRecord>, date: LocalDate, zone: ZoneId): SleepRecord? =
        records.filter { it.nightOf(zone) == date }.maxByOrNull { it.latest!! }
}
