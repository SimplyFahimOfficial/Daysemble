package app.daysemble.data.repo

import androidx.room.withTransaction
import app.daysemble.core.time.AppClock
import app.daysemble.core.time.SleepLog
import app.daysemble.core.time.SleepRecord
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.db.SleepSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Manual sleep and wake-up records ("I'm going to sleep", "I just woke up"). Separate from the
 * configured schedule in settings: recording a time never changes the schedule. Every decision is
 * made and applied in one database transaction, so repeated or concurrent taps can't add duplicates.
 */
class SleepRepository(private val db: DaysembleDatabase, private val clock: AppClock) {
    private val dao = db.sleep()

    /** The newest records (enough for the summary, the history list and decisions). */
    val recent: Flow<List<SleepRecord>> = dao.observeRecent(RECENT).map { rows -> rows.map { it.toRecord() } }

    sealed interface Result {
        /** A record was created or changed. */
        data class Saved(val record: SleepRecord) : Result
        /** "I'm going to sleep" while a bedtime is open: nothing changed; the UI asks what to do. */
        data class AlreadyAsleep(val open: SleepRecord) : Result
        /** "I just woke up" right after a wake-up was recorded: nothing changed; the UI asks. */
        data class AlreadyAwake(val last: SleepRecord) : Result
    }

    /** "I'm going to sleep" now. */
    suspend fun goToSleep(zone: ZoneId): Result = db.withTransaction {
        val now = clock.now()
        when (val d = SleepLog.onGoingToSleep(all(), now)) {
            is SleepLog.SleepDecision.AlreadyAsleep -> Result.AlreadyAsleep(d.open)
            SleepLog.SleepDecision.Start -> Result.Saved(insert(bedtime = now, wake = null, zone = zone, now = now))
        }
    }

    /** "I just woke up" now: closes the open bedtime, or records the wake-up alone. */
    suspend fun wakeUp(zone: ZoneId): Result = db.withTransaction {
        val now = clock.now()
        when (val d = SleepLog.onWokeUp(all(), now)) {
            is SleepLog.WakeDecision.Close -> {
                val row = dao.get(d.open.id)!!
                val updated = row.copy(endUtc = now.toEpochMilli(), endZone = zone.id, updatedAt = now.toEpochMilli())
                dao.upsert(updated)
                Result.Saved(updated.toRecord())
            }
            is SleepLog.WakeDecision.AlreadyAwake -> Result.AlreadyAwake(d.last)
            SleepLog.WakeDecision.WakeOnly -> Result.Saved(insert(bedtime = null, wake = now, zone = zone, now = now))
        }
    }

    /** Moves the bedtime of record [id] to now (the "already asleep, update to now" answer). */
    suspend fun setBedtimeNow(id: String, zone: ZoneId): SleepLog.EditError? {
        val now = clock.now()
        val r = dao.get(id)?.toRecord() ?: return null
        return update(id, now, r.wake, zone)
    }

    /** Moves the wake-up of record [id] to now (the "already awake, update to now" answer). */
    suspend fun setWakeNow(id: String, zone: ZoneId): SleepLog.EditError? {
        val now = clock.now()
        val r = dao.get(id)?.toRecord() ?: return null
        return update(id, r.bedtime, now, zone)
    }

    /**
     * Saves corrected times for record [id] (null clears that time). Returns why it was refused,
     * or null when saved. A zone is only replaced for a time that changed.
     */
    suspend fun update(id: String, bedtime: Instant?, wake: Instant?, zone: ZoneId): SleepLog.EditError? = db.withTransaction {
        val row = dao.get(id) ?: return@withTransaction null
        val edited = SleepRecord(id, bedtime, wake)
        SleepLog.validate(edited, overlapCandidates(bedtime, wake), clock.now())?.let { return@withTransaction it }
        val b = bedtime?.toEpochMilli()
        val w = wake?.toEpochMilli()
        dao.upsert(
            row.copy(
                startUtc = b, endUtc = w,
                startZone = if (b == row.startUtc) row.startZone else bedtime?.let { zone.id },
                endZone = if (w == row.endUtc) row.endZone else wake?.let { zone.id },
                updatedAt = clock.now().toEpochMilli(),
            ),
        )
        null
    }

    /** Adds a record entered by hand (e.g. a night that wasn't tapped). */
    suspend fun add(bedtime: Instant?, wake: Instant?, zone: ZoneId): SleepLog.EditError? = db.withTransaction {
        val now = clock.now()
        SleepLog.validate(SleepRecord("new", bedtime, wake), overlapCandidates(bedtime, wake), now)?.let { return@withTransaction it }
        insert(bedtime, wake, zone, now)
        null
    }

    suspend fun delete(id: String) = dao.delete(id)

    /** Restores a deleted record exactly (undo). */
    suspend fun restore(record: SleepRecord) {
        val now = clock.now().toEpochMilli()
        dao.upsert(
            SleepSessionEntity(
                id = record.id, startUtc = record.bedtime?.toEpochMilli(), endUtc = record.wake?.toEpochMilli(),
                startZone = record.bedZone?.id, endZone = record.wakeZone?.id, createdAt = now, updatedAt = now,
            ),
        )
    }

    /** Recent records, for the button decisions (only the latest ones can be open or a repeat). */
    private suspend fun all(): List<SleepRecord> = dao.recent(RECENT).map { it.toRecord() }

    /** Every stored record that overlaps [bedtime, wake), however old, for checking an edit. */
    private suspend fun overlapCandidates(bedtime: Instant?, wake: Instant?): List<SleepRecord> =
        if (bedtime == null || wake == null) emptyList()
        else dao.overlapping(bedtime.toEpochMilli(), wake.toEpochMilli()).map { it.toRecord() }

    private suspend fun insert(bedtime: Instant?, wake: Instant?, zone: ZoneId, now: Instant): SleepRecord {
        val row = SleepSessionEntity(
            id = UUID.randomUUID().toString(),
            startUtc = bedtime?.toEpochMilli(), endUtc = wake?.toEpochMilli(),
            startZone = bedtime?.let { zone.id }, endZone = wake?.let { zone.id },
            createdAt = now.toEpochMilli(), updatedAt = now.toEpochMilli(),
        )
        dao.upsert(row)
        return row.toRecord()
    }

    private companion object {
        const val RECENT = 90
    }
}

internal fun SleepSessionEntity.toRecord() = SleepRecord(
    id = id,
    bedtime = startUtc?.let(Instant::ofEpochMilli),
    wake = endUtc?.let(Instant::ofEpochMilli),
    bedZone = startZone?.let { runCatching { ZoneId.of(it) }.getOrNull() },
    wakeZone = endZone?.let { runCatching { ZoneId.of(it) }.getOrNull() },
)
