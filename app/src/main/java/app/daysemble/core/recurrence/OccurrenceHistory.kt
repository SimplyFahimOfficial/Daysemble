package app.daysemble.core.recurrence

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The state of each scheduled occurrence of a repeating task, built only from stored rows.
 *
 * Policy (also shown in the app's history legend):
 * - A series has one active occurrence at a time: the open row. While it is open it is [DUE], and
 *   once its due moment (due time, or the end of its due date) has passed it is [OVERDUE]: still
 *   active and still completable, never "missed".
 * - Completing (or skipping) the active occurrence creates the next one on the first scheduled
 *   date that is today or later (TaskRepository.setCompleted). Scheduled dates before that are
 *   passed over and can't be done any more: those without a row are [MISSED]. The same holds for
 *   scheduled dates before today that come after an overdue active occurrence: when it is
 *   completed the series moves on to today or later, so they can no longer be done either.
 * - A date whose occurrence was deleted on its own ("skip") is [SKIPPED], not missed.
 * - Scheduled dates from today on, after the active occurrence, are [UPCOMING].
 * - Completed rows are always shown as [COMPLETED] on their date, even if a later change of the
 *   rule moved them off the schedule. Nothing before the series' first date (its anchor) exists.
 * - No rule (the repeat ended): only the stored rows, nothing is projected.
 */
enum class OccurrenceStatus { COMPLETED, MISSED, SKIPPED, OVERDUE, DUE, UPCOMING }

data class OccurrenceCell(
    val date: LocalDate,
    val status: OccurrenceStatus,
    /** The task row of this date, if there is one (completed, or the active occurrence). */
    val taskId: String? = null,
    val completedAt: Instant? = null,
)

/** A stored occurrence row, as the history needs it. */
data class OccurrenceRow(
    val id: String,
    /** The scheduled date (identity in the series); null for rows moved off the schedule. */
    val occurrenceDate: LocalDate?,
    val dueDate: LocalDate?,
    val dueTime: LocalTime?,
    val isCompleted: Boolean,
    val completedAt: Instant?,
    val isDeleted: Boolean = false,
) {
    val date: LocalDate? get() = occurrenceDate ?: dueDate
}

data class OccurrenceHistory(
    /** Every known cell, oldest first: past scheduled dates, completed rows, the active one, upcoming. */
    val cells: List<OccurrenceCell>,
    val completed: Int,
    val missed: Int,
    val skipped: Int,
    /** The active occurrence if it isn't overdue, plus scheduled dates after it up to the horizon. */
    val upcoming: Int,
    /** Completed occurrences in a row, back from the latest past one (skipped and an overdue active one don't break it). */
    val streak: Int,
    /** The active (open) occurrence, if the series has one. */
    val active: OccurrenceCell?,
    /** The first scheduled date after the active one (where completing it would lead), if any. */
    val nextAfterActive: LocalDate?,
    /** The schedule ends on or before the horizon, so [upcoming] is everything that is left. */
    val endsWithinHorizon: Boolean,
) {
    val isEmpty: Boolean get() = completed == 0 && missed == 0 && skipped == 0
}

object OccurrenceHistoryCalc {
    /** Upper bound of scheduled dates walked (a daily series over ~10 years). */
    private const val MAX_STEPS = 4_000

    fun build(
        rule: RRule?,
        anchor: LocalDate?,
        rows: List<OccurrenceRow>,
        skips: Set<LocalDate>,
        now: Instant,
        zone: ZoneId,
        horizonEnd: LocalDate,
    ): OccurrenceHistory {
        val today = now.atZone(zone).toLocalDate()
        val stored = Stored.of(rows, skips)
        val cells = storedCells(stored, now, zone)
        val projection = if (rule != null && anchor != null && TaskRecurrence.supports(rule)) {
            project(rule, anchor, stored, cells, today, horizonEnd)
        } else Projection(nextAfterActive = null, endsWithinHorizon = rule == null)
        // Skips without a scheduled date (rule changed since) still show as skipped.
        stored.skipped.forEach { d ->
            if (d !in cells && (anchor == null || !d.isBefore(anchor)) && !d.isAfter(today)) cells[d] = OccurrenceCell(d, OccurrenceStatus.SKIPPED)
        }
        val list = cells.values.toList()
        return OccurrenceHistory(
            cells = list,
            completed = list.count { it.status == OccurrenceStatus.COMPLETED },
            missed = list.count { it.status == OccurrenceStatus.MISSED },
            skipped = list.count { it.status == OccurrenceStatus.SKIPPED },
            upcoming = list.count { it.status == OccurrenceStatus.UPCOMING || it.status == OccurrenceStatus.DUE },
            streak = streak(list, today),
            active = stored.active?.let { cells[it.date!!] },
            nextAfterActive = projection.nextAfterActive,
            endsWithinHorizon = projection.endsWithinHorizon,
        )
    }

    /** The stored rows, sorted into what the classification needs. */
    private class Stored(
        val completed: List<OccurrenceRow>,
        val open: List<OccurrenceRow>,
        val skipped: Set<LocalDate>,
        /** How far the series has got: the newest stored row (a done one, or the active one). */
        val frontier: LocalDate?,
    ) {
        val active: OccurrenceRow? get() = open.lastOrNull()

        companion object {
            fun of(rows: List<OccurrenceRow>, skips: Set<LocalDate>): Stored {
                val live = rows.filter { !it.isDeleted }
                val liveDates = live.mapNotNull { it.date }.toSet()
                // A deleted occurrence (open or done) is skipped, the same as after its row is purged
                // and only its skip record is left: never missed, since the user removed it on purpose.
                val deleted = rows.filter { it.isDeleted }.mapNotNull { it.date }.toSet() - liveDates
                return Stored(
                    completed = live.filter { it.isCompleted },
                    open = live.filter { !it.isCompleted && it.date != null }.sortedBy { it.date },
                    skipped = skips + deleted,
                    frontier = liveDates.maxOrNull(),
                )
            }
        }
    }

    /** Completed rows on their date, then the open (active) one as due or overdue. */
    private fun storedCells(st: Stored, now: Instant, zone: ZoneId): java.util.SortedMap<LocalDate, OccurrenceCell> {
        val cells = sortedMapOf<LocalDate, OccurrenceCell>()
        st.completed.forEach { r ->
            val d = r.date ?: r.completedAt?.atZone(zone)?.toLocalDate() ?: return@forEach
            cells[d] = OccurrenceCell(d, OccurrenceStatus.COMPLETED, r.id, r.completedAt)
        }
        st.open.forEach { r ->
            val d = r.date!!
            val due = dueMoment(r, zone)
            val status = if (due != null && !now.isBefore(due)) OccurrenceStatus.OVERDUE else OccurrenceStatus.DUE
            if (cells[d]?.status != OccurrenceStatus.COMPLETED) cells[d] = OccurrenceCell(d, status, r.id)
        }
        return cells
    }

    private class Projection(val nextAfterActive: LocalDate?, val endsWithinHorizon: Boolean)

    /**
     * Walks the scheduled dates up to [horizonEnd] and fills those without a row (see the policy on
     * [OccurrenceStatus]). The walk starts at most [MAX_STEPS] days before the horizon, so a very old
     * series still reaches today; anything older than that is only shown through its stored rows.
     */
    private fun project(
        rule: RRule, anchor: LocalDate, st: Stored, cells: MutableMap<LocalDate, OccurrenceCell>,
        today: LocalDate, horizonEnd: LocalDate,
    ): Projection {
        val active = st.active?.date
        var nextAfterActive: LocalDate? = null
        var o = TaskRecurrence.firstOnOrAfter(rule, anchor, maxOf(anchor, horizonEnd.minusDays(MAX_STEPS - 1L)))
        var steps = 0
        while (o != null && !o.date.isAfter(horizonEnd) && steps++ < MAX_STEPS) {
            val d = o.date
            if (d !in cells) classify(d, st, today)?.let { cells[d] = OccurrenceCell(d, it) }
            if (active != null && nextAfterActive == null && d.isAfter(active)) nextAfterActive = d
            o = TaskRecurrence.firstOnOrAfter(rule, anchor, d.plusDays(1))
        }
        return Projection(nextAfterActive, endsWithinHorizon = o == null)
    }

    /** The state of a scheduled date that has no stored row, or null if the series never reached it. */
    private fun classify(d: LocalDate, st: Stored, today: LocalDate): OccurrenceStatus? = when {
        d in st.skipped -> OccurrenceStatus.SKIPPED
        st.frontier != null && d.isBefore(st.frontier) -> OccurrenceStatus.MISSED
        // Behind an active occurrence that is still open: these can't be done any more.
        d.isBefore(today) && st.active != null -> OccurrenceStatus.MISSED
        // Past dates with no row and nothing stored after them yet: the series hasn't reached them
        // (it was created later on the same anchor, or is new).
        d.isBefore(today) -> null
        else -> OccurrenceStatus.UPCOMING
    }

    /** Completed occurrences in a row back from today; skipped and an active one don't break it. */
    private fun streak(cells: List<OccurrenceCell>, today: LocalDate): Int {
        var n = 0
        for (c in cells.asReversed()) {
            if (c.date.isAfter(today)) continue
            when (c.status) {
                OccurrenceStatus.COMPLETED -> n++
                OccurrenceStatus.MISSED -> return n
                OccurrenceStatus.SKIPPED, OccurrenceStatus.OVERDUE, OccurrenceStatus.DUE, OccurrenceStatus.UPCOMING -> Unit
            }
        }
        return n
    }

    private fun dueMoment(r: OccurrenceRow, zone: ZoneId): Instant? {
        val d = r.dueDate ?: r.occurrenceDate ?: return null
        return if (r.dueTime != null) LocalDateTime.of(d, r.dueTime).atZone(zone).toInstant()
        else d.plusDays(1).atStartOfDay(zone).toInstant()
    }
}
