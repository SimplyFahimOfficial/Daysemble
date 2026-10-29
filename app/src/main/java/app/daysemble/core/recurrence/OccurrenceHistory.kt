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
        val live = rows.filter { !it.isDeleted }
        val deletedDates = rows.filter { it.isDeleted && !it.isCompleted }.mapNotNull { it.date }.toSet()
        val skipped = skips + deletedDates
        val open = live.filter { !it.isCompleted && it.date != null }.sortedBy { it.date }
        val active = open.lastOrNull()
        val completedRows = live.filter { it.isCompleted }
        // How far the series has got: the newest stored row (a done one, or the active one).
        val frontier = live.mapNotNull { it.date }.maxOrNull()

        val cells = sortedMapOf<LocalDate, OccurrenceCell>()
        completedRows.forEach { r ->
            val d = r.date ?: r.completedAt?.atZone(zone)?.toLocalDate() ?: return@forEach
            cells[d] = OccurrenceCell(d, OccurrenceStatus.COMPLETED, r.id, r.completedAt)
        }
        open.forEach { r ->
            val d = r.date!!
            val due = dueMoment(r, zone)
            val status = if (due != null && !now.isBefore(due)) OccurrenceStatus.OVERDUE else OccurrenceStatus.DUE
            if (cells[d]?.status != OccurrenceStatus.COMPLETED) cells[d] = OccurrenceCell(d, status, r.id)
        }

        var endsWithinHorizon = rule == null
        var nextAfterActive: LocalDate? = null
        if (rule != null && anchor != null && TaskRecurrence.supports(rule)) {
            var o = TaskRecurrence.firstOnOrAfter(rule, anchor, anchor)
            var steps = 0
            while (o != null && !o.date.isAfter(horizonEnd) && steps++ < MAX_STEPS) {
                val d = o.date
                if (d !in cells) {
                    val status = when {
                        d in skipped -> OccurrenceStatus.SKIPPED
                        frontier != null && d.isBefore(frontier) -> OccurrenceStatus.MISSED
                        // Behind an active occurrence that is still open: these can't be done any more.
                        d.isBefore(today) && active != null -> OccurrenceStatus.MISSED
                        // Past dates with no row and nothing stored after them yet: the series
                        // hasn't reached them (it was created later on the same anchor, or is new).
                        d.isBefore(today) -> null
                        else -> OccurrenceStatus.UPCOMING
                    }
                    if (status != null) cells[d] = OccurrenceCell(d, status)
                }
                if (active != null && nextAfterActive == null && d.isAfter(active.date!!)) nextAfterActive = d
                o = TaskRecurrence.firstOnOrAfter(rule, anchor, d.plusDays(1))
            }
            endsWithinHorizon = o == null
        }
        // Skips without a scheduled date (rule changed since) still show as skipped.
        skipped.forEach { d -> if (d !in cells && (anchor == null || !d.isBefore(anchor)) && !d.isAfter(today)) cells[d] = OccurrenceCell(d, OccurrenceStatus.SKIPPED) }

        val list = cells.values.toList()
        val streak = run {
            var n = 0
            for (c in list.asReversed()) {
                if (c.date.isAfter(today)) continue
                when (c.status) {
                    OccurrenceStatus.COMPLETED -> n++
                    OccurrenceStatus.SKIPPED, OccurrenceStatus.OVERDUE, OccurrenceStatus.DUE, OccurrenceStatus.UPCOMING -> Unit
                    OccurrenceStatus.MISSED -> break
                }
            }
            n
        }
        return OccurrenceHistory(
            cells = list,
            completed = list.count { it.status == OccurrenceStatus.COMPLETED },
            missed = list.count { it.status == OccurrenceStatus.MISSED },
            skipped = list.count { it.status == OccurrenceStatus.SKIPPED },
            upcoming = list.count { it.status == OccurrenceStatus.UPCOMING || it.status == OccurrenceStatus.DUE },
            streak = streak,
            active = active?.let { cells[it.date!!] },
            nextAfterActive = nextAfterActive,
            endsWithinHorizon = endsWithinHorizon,
        )
    }

    private fun dueMoment(r: OccurrenceRow, zone: ZoneId): Instant? {
        val d = r.dueDate ?: r.occurrenceDate ?: return null
        return if (r.dueTime != null) LocalDateTime.of(d, r.dueTime).atZone(zone).toInstant()
        else d.plusDays(1).atStartOfDay(zone).toInstant()
    }
}
