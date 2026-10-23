package app.daysemble.data.sync

import app.daysemble.data.db.OutboxEntity
import app.daysemble.data.db.OutboxOp
import app.daysemble.data.db.OwnerType
import app.daysemble.data.db.SyncDao
import app.daysemble.data.db.SyncState

/**
 * Records local changes to Google-backed items so the sync engine can push them.
 * Pushers always send the *current* row, so one pending op per entity is enough and
 * operations are coalesced:
 *
 *   CREATE + UPDATE           -> CREATE
 *   CREATE + DELETE (never attempted) -> nothing to push; caller hard-deletes
 *   CREATE + DELETE (attempted, outcome unknown) -> DELETE (remote may exist)
 *   UPDATE + UPDATE           -> UPDATE
 *   UPDATE + DELETE           -> DELETE
 */
class Outbox(private val dao: SyncDao, private val now: () -> Long) {

    enum class DeleteResult { HARD_DELETE_LOCALLY, PUSH_DELETE }

    suspend fun recordCreate(type: OwnerType, id: String) {
        dao.clearFor(type, id)
        dao.enqueue(OutboxEntity(entityType = type, entityId = id, op = OutboxOp.CREATE, createdAt = now()))
    }

    /** Returns the sync state the entity should be stored with after an edit. */
    suspend fun recordUpdate(type: OwnerType, id: String, current: SyncState): SyncState {
        val existing = dao.outbox(type).filter { it.entityId == id }
        return when {
            current == SyncState.PENDING_CREATE || existing.any { it.op == OutboxOp.CREATE } -> {
                if (existing.none { it.op == OutboxOp.CREATE }) recordCreate(type, id)
                SyncState.PENDING_CREATE
            }
            existing.any { it.op == OutboxOp.UPDATE } -> SyncState.PENDING_UPDATE
            else -> {
                dao.enqueue(OutboxEntity(entityType = type, entityId = id, op = OutboxOp.UPDATE, createdAt = now()))
                SyncState.PENDING_UPDATE
            }
        }
    }

    suspend fun recordDelete(type: OwnerType, id: String, hasRemoteId: Boolean): DeleteResult {
        val existing = dao.outbox(type).filter { it.entityId == id }
        val create = existing.firstOrNull { it.op == OutboxOp.CREATE }
        if (!hasRemoteId && (create == null || create.attempts == 0)) {
            dao.clearFor(type, id)
            return DeleteResult.HARD_DELETE_LOCALLY
        }
        dao.clearFor(type, id)
        dao.enqueue(OutboxEntity(entityType = type, entityId = id, op = OutboxOp.DELETE, createdAt = now()))
        return DeleteResult.PUSH_DELETE
    }

    /**
     * The record left its remote container (moved list): delete the old remote copy by snapshot,
     * then, if it now lives in a Google container, create it there.
     */
    suspend fun recordMove(type: OwnerType, id: String, oldRemoteId: String?, oldRemoteContainer: String?, nowGoogle: Boolean) {
        dao.clearFor(type, id)
        if (oldRemoteId != null && oldRemoteContainer != null) {
            dao.enqueue(OutboxEntity(entityType = type, entityId = id, op = OutboxOp.DELETE, createdAt = now(),
                snapshotRemoteId = oldRemoteId, snapshotContainerId = oldRemoteContainer))
        }
        // A create with an unknown outcome in the old list can't be addressed without an id; the old
        // list's next full pull would show it as an unmapped task (documented limitation).
        if (nowGoogle) dao.enqueue(OutboxEntity(entityType = type, entityId = id, op = OutboxOp.CREATE, createdAt = now()))
    }

    /** Undo of a delete that was queued but not yet pushed. */
    suspend fun cancelDelete(type: OwnerType, id: String, hasRemoteId: Boolean) {
        dao.clearFor(type, id)
        if (hasRemoteId) dao.enqueue(OutboxEntity(entityType = type, entityId = id, op = OutboxOp.UPDATE, createdAt = now()))
        else dao.enqueue(OutboxEntity(entityType = type, entityId = id, op = OutboxOp.CREATE, createdAt = now()))
    }
}

/** Notified after any local data change, so reminders and sync can react. */
interface ChangeListener {
    fun onTaskChanged(taskId: String) {}
    fun onEventChanged(eventId: String) {}
    /** A Google-backed item changed locally and should be pushed soon. */
    fun onPendingSync() {}

    object None : ChangeListener
}

/** Fans out to several listeners; lets AppContainer register reminders and sync independently. */
class CompositeChangeListener : ChangeListener {
    private val listeners = mutableListOf<ChangeListener>()
    fun add(l: ChangeListener) = synchronized(listeners) { listeners += l }
    private fun each(f: (ChangeListener) -> Unit) = synchronized(listeners) { listeners.toList() }.forEach(f)
    override fun onTaskChanged(taskId: String) = each { it.onTaskChanged(taskId) }
    override fun onEventChanged(eventId: String) = each { it.onEventChanged(eventId) }
    override fun onPendingSync() = each { it.onPendingSync() }
}
