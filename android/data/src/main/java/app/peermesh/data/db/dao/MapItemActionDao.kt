package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.MapItemPersistenceMutationValidator
import app.peermesh.data.db.MapItemPersistenceRecordValidator
import app.peermesh.data.db.MapItemRejectionReasonContract
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.PersistenceWriteResult
import app.peermesh.data.db.failure
import app.peermesh.data.db.entity.MapItemActionEntity

@Dao
abstract class MapItemActionDao {
    @Query("""
        SELECT * FROM map_item_actions
        WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId
          AND materialization_state = 'pending_target'
        ORDER BY action_t ASC, action_lt ASC, actor_did ASC, event_id ASC
    """)
    abstract fun findPendingTarget(gid: String, itemType: String, itemId: String): List<MapItemActionEntity>

    @Query("""
        SELECT * FROM map_item_actions
        WHERE materialization_state = 'pending_target' AND gid = :gid
          AND (:cursorT IS NULL OR action_t > :cursorT
            OR (action_t = :cursorT AND action_lt > :cursorLt)
            OR (action_t = :cursorT AND action_lt = :cursorLt AND actor_did > :cursorActor)
            OR (action_t = :cursorT AND action_lt = :cursorLt AND actor_did = :cursorActor AND event_id > :cursorEvent))
        ORDER BY action_t ASC, action_lt ASC, actor_did ASC, event_id ASC LIMIT :limit
    """)
    abstract fun scanPending(gid: String, cursorT: Long?, cursorLt: Long?, cursorActor: String?, cursorEvent: String?, limit: Int): List<MapItemActionEntity>

    @Query("""SELECT * FROM map_item_actions
        WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId AND materialization_state = 'applied'
        ORDER BY action_t DESC, action_lt DESC, actor_did DESC, event_id DESC LIMIT 1""")
    abstract fun findLatestApplied(gid: String, itemType: String, itemId: String): MapItemActionEntity?

    @Query("""SELECT * FROM map_item_actions
        WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId
          AND materialization_state = 'applied' AND action_type = :actionType
        ORDER BY action_t DESC, action_lt DESC, actor_did DESC, event_id DESC LIMIT 1""")
    abstract fun findLatestAppliedOfType(gid: String, itemType: String, itemId: String, actionType: String): MapItemActionEntity?

    @Query("""SELECT * FROM map_item_actions
        WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId
          AND materialization_state = 'applied' AND action_type = :actionType
        ORDER BY action_t ASC, action_lt ASC, actor_did ASC, event_id ASC LIMIT 1""")
    abstract fun findEarliestAppliedOfType(gid: String, itemType: String, itemId: String, actionType: String): MapItemActionEntity?

    @Query("""SELECT * FROM map_item_actions
        WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId AND materialization_state = 'applied'
          AND (action_type = 'update_status' OR (action_type = 'add_update' AND update_disposition = 'situation_changed'))
        ORDER BY action_t DESC, action_lt DESC, actor_did DESC, event_id DESC LIMIT 1""")
    abstract fun findLatestMaterialChange(gid: String, itemType: String, itemId: String): MapItemActionEntity?

    @Query("""SELECT COUNT(DISTINCT actor_did) FROM map_item_actions
        WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId
          AND materialization_state = 'applied' AND action_type = 'confirm'""")
    abstract fun countConfirmationActors(gid: String, itemType: String, itemId: String): Int

    @Query("""SELECT COUNT(DISTINCT actor_did) FROM map_item_actions
        WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId
          AND materialization_state = 'applied' AND action_type = 'confirm'
          AND (action_t > :t OR (action_t = :t AND action_lt > :lt)
            OR (action_t = :t AND action_lt = :lt AND actor_did > :actor)
            OR (action_t = :t AND action_lt = :lt AND actor_did = :actor AND event_id > :eventId))""")
    abstract fun countConfirmationActorsAfter(gid: String, itemType: String, itemId: String, t: Long, lt: Long, actor: String, eventId: String): Int

    @Query("""SELECT COUNT(DISTINCT actor_did) FROM map_item_actions
        WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId
          AND materialization_state = 'applied' AND action_type = 'acknowledge'""")
    abstract fun countAcknowledgementActors(gid: String, itemType: String, itemId: String): Int

    @Query("""SELECT * FROM map_item_actions
        WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId AND materialization_state = 'applied'
          AND (:cursorT IS NULL OR action_t < :cursorT
            OR (action_t = :cursorT AND action_lt < :cursorLt)
            OR (action_t = :cursorT AND action_lt = :cursorLt AND actor_did < :cursorActor)
            OR (action_t = :cursorT AND action_lt = :cursorLt AND actor_did = :cursorActor AND event_id < :cursorEvent))
        ORDER BY action_t DESC, action_lt DESC, actor_did DESC, event_id DESC LIMIT :limit""")
    abstract fun findAppliedHistory(gid: String, itemType: String, itemId: String, cursorT: Long?, cursorLt: Long?, cursorActor: String?, cursorEvent: String?, limit: Int): List<MapItemActionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertRaw(entity: MapItemActionEntity): Long

    fun insertIgnore(entity: MapItemActionEntity): PersistenceWriteResult<Long> =
        when (val validation = MapItemPersistenceRecordValidator.validate(entity)) {
            is PersistenceParseResult.Success -> PersistenceWriteResult.Success(insertRaw(entity))
            else -> PersistenceWriteResult.Rejected(validation.failure())
        }

    @Query("SELECT * FROM map_item_actions WHERE gid = :gid AND action_id = :actionId")
    abstract fun findByActionId(gid: String, actionId: String): MapItemActionEntity?

    @Query("SELECT * FROM map_item_actions WHERE gid = :gid AND event_id = :eventId")
    abstract fun findByEventId(gid: String, eventId: String): MapItemActionEntity?

    fun transitionPendingToApplied(gid: String, actionId: String): PersistenceWriteResult<Int> =
        when (val validation = MapItemPersistenceMutationValidator.actionIdentity(gid, actionId)) {
            is PersistenceParseResult.Success -> PersistenceWriteResult.Success(transitionPendingToAppliedRaw(gid, actionId))
            else -> PersistenceWriteResult.Rejected(validation.failure())
        }

    fun transitionPendingToRejected(
        gid: String,
        actionId: String,
        rejectionReason: String,
    ): PersistenceWriteResult<Int> {
        val identity = MapItemPersistenceMutationValidator.actionIdentity(gid, actionId)
        if (identity !is PersistenceParseResult.Success) return PersistenceWriteResult.Rejected(identity.failure())
        return when (val reason = MapItemRejectionReasonContract.validate(rejectionReason)) {
            is PersistenceParseResult.Success -> PersistenceWriteResult.Success(
                transitionPendingToRejectedRaw(gid, actionId, reason.value),
            )
            else -> PersistenceWriteResult.Rejected(reason.failure())
        }
    }

    @Query("""
        UPDATE map_item_actions
        SET materialization_state = 'applied', rejection_reason = NULL
        WHERE gid = :gid AND action_id = :actionId
          AND materialization_state = 'pending_target'
    """)
    protected abstract fun transitionPendingToAppliedRaw(gid: String, actionId: String): Int

    @Query("""
        UPDATE map_item_actions
        SET materialization_state = 'rejected', rejection_reason = :rejectionReason
        WHERE gid = :gid AND action_id = :actionId
          AND materialization_state = 'pending_target'
          AND trim(:rejectionReason) != ''
          AND :rejectionReason NOT GLOB '*[^a-z0-9_]*'
    """)
    protected abstract fun transitionPendingToRejectedRaw(gid: String, actionId: String, rejectionReason: String): Int
}
