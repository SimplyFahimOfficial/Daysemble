package app.daysemble.data.repo

import app.daysemble.core.recurrence.OccurrenceRow
import app.daysemble.core.recurrence.RRule
import app.daysemble.data.db.DaysembleDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Read-only views of repeating-task series, for the occurrence history and free-time projection.
 * All writes (completing, skipping, editing a series) stay in [TaskRepository].
 */
class TaskSeriesReader(db: DaysembleDatabase) {
    private val series = db.taskSeries()
    private val skips = db.skips()

    /**
     * Everything the occurrence history of series [seriesId] is built from: its rule and first date,
     * every stored occurrence (deleted ones within the undo window too) and the skipped dates.
     */
    fun observeSeries(seriesId: String): Flow<SeriesData?> =
        combine(series.observe(seriesId), series.observeOccurrences(seriesId), skips.observeForSeries(seriesId)) { s, rows, sk ->
            s ?: return@combine null
            SeriesData(
                id = s.id, rule = s.activeRule(), anchor = LocalDate.ofEpochDay(s.anchorDate), ended = s.endedAt != null,
                rows = rows.filter { it.parentId == null }.map { r ->
                    OccurrenceRow(
                        id = r.id, occurrenceDate = r.occurrenceDate?.let(LocalDate::ofEpochDay), dueDate = r.dueDate?.let(LocalDate::ofEpochDay),
                        dueTime = r.dueTimeMinutes?.let { LocalTime.of(it / 60, it % 60) }, isCompleted = r.isCompleted,
                        completedAt = r.completedAt?.let(Instant::ofEpochMilli), isDeleted = r.isDeleted,
                    )
                },
                skips = sk.map { LocalDate.ofEpochDay(it.occurrenceDate) }.toSet(),
            )
        }

    /** A series and its stored occurrences (see [observeSeries]). [rule] is null once it stopped repeating. */
    data class SeriesData(
        val id: String,
        val rule: RRule?,
        val anchor: LocalDate,
        val ended: Boolean,
        val rows: List<OccurrenceRow>,
        val skips: Set<LocalDate>,
    )

    /**
     * Active series with a due time and a duration: their future occurrences are time blocks on days
     * that have no row yet (only the current occurrence is stored), for the free-time calculation.
     */
    val timedSeries: Flow<List<SeriesTemplate>> = series.observeAll().map { all ->
        all.mapNotNull { s ->
            val rule = s.activeRule() ?: return@mapNotNull null
            val time = s.dueTimeMinutes ?: return@mapNotNull null
            val minutes = s.durationMinutes?.takeIf { it > 0 } ?: return@mapNotNull null
            SeriesTemplate(s.id, s.title, rule, LocalDate.ofEpochDay(s.anchorDate), LocalTime.of(time / 60, time % 60), minutes)
        }
    }

    data class SeriesTemplate(val id: String, val title: String, val rule: RRule, val anchor: LocalDate, val dueTime: LocalTime, val durationMinutes: Int)
}
