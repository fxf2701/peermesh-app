package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.peermesh.data.db.entity.OutboundBatchEntity
import app.peermesh.data.db.entity.OutboundBatchEventEntity
import app.peermesh.data.db.entity.OutboundBatchPeerDeliveryEntity
import app.peermesh.data.db.entity.OutboundBatchPeerDeliveryState
import app.peermesh.data.db.entity.OutboundBatchStatus

@Dao
interface OutboundBatchDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertBatch(batch: OutboundBatchEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertBatchEvents(events: List<OutboundBatchEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertPeerDelivery(delivery: OutboundBatchPeerDeliveryEntity)

    @Transaction
    fun insertBatchGraph(batch: OutboundBatchEntity, events: List<OutboundBatchEventEntity>) {
        insertBatch(batch)
        insertBatchEvents(events)
    }

    @Transaction
    fun replaceBatchGraph(
        oldBatchId: String,
        batch: OutboundBatchEntity,
        events: List<OutboundBatchEventEntity>,
    ) {
        insertBatch(batch)
        insertBatchEvents(events)
        deleteBatch(oldBatchId)
    }

    @Query(
        """
        SELECT * FROM outbound_batch
        WHERE gid = :gid
          AND source = :source
          AND priority = :priority
          AND status = :status
          AND lane = :lane
        ORDER BY created_at DESC, batch_id DESC
        LIMIT 1
        """
    )
    fun findLatestAppendCandidate(
        gid: String,
        source: String,
        priority: Int,
        status: String = OutboundBatchStatus.QUEUED,
        lane: String,
    ): OutboundBatchEntity?

    @Query(
        """
        SELECT * FROM outbound_batch
        WHERE status IN (:statuses)
          AND lane = :lane
        ORDER BY CASE status
            WHEN 'FAILED_RETRYABLE' THEN 0
            WHEN 'QUEUED' THEN 1
            ELSE 2
        END,
        priority ASC,
        created_at DESC,
        batch_id DESC
        LIMIT :limit
        """
    )
    fun selectDispatchCandidatesByLane(statuses: List<String>, lane: String, limit: Int): List<OutboundBatchEntity>

    @Query(
        """
        SELECT * FROM outbound_batch
        WHERE gid = :gid
          AND status IN (:statuses)
        ORDER BY priority ASC, created_at ASC, batch_id ASC
        LIMIT :limit
        """
    )
    fun selectDtnCarrierCandidates(gid: String, statuses: List<String>, limit: Int): List<OutboundBatchEntity>

    @Query(
        """
        SELECT COUNT(*) FROM outbound_batch
        WHERE status IN (:statuses)
          AND last_event_created_t >= :createdAfterInclusive
        """
    )
    fun countActiveBatchesCreatedAfter(statuses: List<String>, createdAfterInclusive: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM outbound_batch
        WHERE status = :status
          AND priority = :priority
        """
    )
    fun countBatchesByStatusAndPriority(status: String, priority: Int): Int

    @Query(
        """
        SELECT COUNT(*) FROM outbound_batch
        WHERE status = :status
          AND priority != :priority
        """
    )
    fun countBatchesByStatusExcludingPriority(status: String, priority: Int): Int

    @Query(
        """
        UPDATE outbound_batch
        SET status = :status,
            locked_at = :lockedAt,
            updated_at = :updatedAt
        WHERE batch_id IN (:batchIds)
        """
    )
    fun updateStatusForBatches(batchIds: List<String>, status: String, lockedAt: Long?, updatedAt: Long)

    @Query(
        """
        UPDATE outbound_batch
        SET status = :status,
            locked_at = NULL,
            updated_at = :updatedAt
        WHERE batch_id IN (:batchIds)
        """
    )
    fun releaseBatches(batchIds: List<String>, status: String, updatedAt: Long)

    @Query("SELECT * FROM outbound_batch WHERE batch_id = :batchId LIMIT 1")
    fun findBatch(batchId: String): OutboundBatchEntity?

    @Query(
        """
        SELECT * FROM outbound_batch_peer_delivery
        WHERE batch_id = :batchId AND device_id = :deviceId
        LIMIT 1
        """
    )
    fun findPeerDelivery(batchId: String, deviceId: String): OutboundBatchPeerDeliveryEntity?

    @Query(
        """
        SELECT * FROM outbound_batch_peer_delivery
        WHERE batch_id = :batchId
        ORDER BY device_id ASC
        """
    )
    fun listPeerDeliveries(batchId: String): List<OutboundBatchPeerDeliveryEntity>

    @Query(
        """
        SELECT device_id FROM outbound_batch_peer_delivery
        WHERE batch_id = :batchId
          AND state = :state
        ORDER BY updated_at ASC, device_id ASC
        """
    )
    fun listPeerDeviceIdsByState(batchId: String, state: String): List<String>

    @Query(
        """
        SELECT device_id FROM outbound_batch_peer_delivery
        WHERE batch_id = :batchId
          AND state = :state
          AND (next_retry_at IS NULL OR next_retry_at <= :now)
        ORDER BY updated_at ASC, device_id ASC
        """
    )
    fun listPeerDeviceIdsByStateDue(batchId: String, state: String, now: Long): List<String>

    @Query(
        """
        SELECT COUNT(*) FROM outbound_batch_peer_delivery
        WHERE batch_id = :batchId
          AND state = :state
        """
    )
    fun countPeerDeliveriesByState(batchId: String, state: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM outbound_batch_peer_delivery
        WHERE batch_id = :batchId
          AND state IN (:states)
        """
    )
    fun countPeerDeliveriesByStates(batchId: String, states: List<String>): Int

    @Query(
        """
        SELECT MIN(next_retry_at) FROM outbound_batch_peer_delivery
        WHERE state = :state
          AND next_retry_at IS NOT NULL
        """
    )
    fun findEarliestPeerRetryAt(state: String = OutboundBatchPeerDeliveryState.FAILED_RETRYABLE): Long?

    @Query(
        """
        SELECT * FROM outbound_batch_event
        WHERE batch_id = :batchId
        ORDER BY ordinal_in_batch ASC
        """
    )
    fun listBatchEvents(batchId: String): List<OutboundBatchEventEntity>

    @Query(
        """
        SELECT ob.batch_id FROM outbound_batch ob
        WHERE ob.source = :batchSource
          AND ob.status = :status
          AND NOT EXISTS (
            SELECT 1
            FROM outbound_batch_event obe
            JOIN events e
              ON e.event_id = obe.event_id
             AND e.gid = ob.gid
            WHERE obe.batch_id = ob.batch_id
              AND e.invalid_at IS NULL
              AND e.source != :excludedEventSource
              AND (
                e.inserted_at > :lastPushAt
                OR (e.inserted_at = :lastPushAt AND e.event_id > :lastPushEventId)
              )
          )
        ORDER BY ob.created_at ASC, ob.batch_id ASC
        """
    )
    fun findCoveredBatchIdsByPushCursor(
        batchSource: String,
        status: String,
        excludedEventSource: String,
        lastPushAt: Long,
        lastPushEventId: String,
    ): List<String>

    @Query(
        """
        SELECT ob.batch_id FROM outbound_batch ob
        WHERE ob.source = :batchSource
          AND ob.status = :status
          AND NOT EXISTS (
            SELECT 1
            FROM outbound_batch_event obe
            JOIN events e
              ON e.event_id = obe.event_id
             AND e.gid = ob.gid
            WHERE obe.batch_id = ob.batch_id
              AND e.invalid_at IS NULL
              AND e.source != :excludedEventSource
              AND e.id > :lastPushRowId
          )
        ORDER BY ob.created_at ASC, ob.batch_id ASC
        """
    )
    fun findCoveredBatchIdsByPushRowId(
        batchSource: String,
        status: String,
        excludedEventSource: String,
        lastPushRowId: Long,
    ): List<String>

    @Query(
        """
        UPDATE outbound_batch
        SET status = :status,
            lane = :lane,
            updated_at = :updatedAt,
            locked_at = NULL,
            last_error = :lastError,
            attempt_count = :attemptCount
        WHERE batch_id = :batchId
        """
    )
    fun markRetryable(batchId: String, status: String, lane: String, updatedAt: Long, lastError: String?, attemptCount: Int)

    @Query(
        """
        UPDATE outbound_batch
        SET status = :status,
            updated_at = :updatedAt,
            locked_at = NULL,
            sent_at = :sentAt,
            last_error = NULL
        WHERE batch_id = :batchId
        """
    )
    fun markSent(batchId: String, status: String, updatedAt: Long, sentAt: Long)

    @Query(
        """
        UPDATE outbound_batch
        SET status = :status,
            updated_at = :updatedAt,
            locked_at = NULL,
            last_error = :lastError,
            attempt_count = :attemptCount
        WHERE batch_id = :batchId
        """
    )
    fun markFinal(batchId: String, status: String, updatedAt: Long, lastError: String?, attemptCount: Int)

    @Query("DELETE FROM outbound_batch WHERE batch_id = :batchId")
    fun deleteBatch(batchId: String)

    @Query("DELETE FROM outbound_batch_peer_delivery WHERE batch_id = :batchId AND device_id = :deviceId")
    fun deletePeerDelivery(batchId: String, deviceId: String)

    @Query("DELETE FROM outbound_batch_peer_delivery WHERE batch_id IN (SELECT batch_id FROM outbound_batch WHERE gid = :gid)")
    fun deletePeerDeliveriesByGid(gid: String): Int

    @Query("DELETE FROM outbound_batch_event WHERE batch_id IN (SELECT batch_id FROM outbound_batch WHERE gid = :gid)")
    fun deleteBatchEventsByGid(gid: String): Int

    @Query("DELETE FROM outbound_batch WHERE gid = :gid")
    fun deleteBatchesByGid(gid: String): Int

    @Query(
        """
        SELECT * FROM outbound_batch
        WHERE status = :status AND locked_at IS NOT NULL AND locked_at < :lockedBefore
        ORDER BY locked_at ASC
        """
    )
    fun findStaleProcessing(status: String = OutboundBatchStatus.PROCESSING, lockedBefore: Long): List<OutboundBatchEntity>

    @Transaction
    fun markPeerQueued(batchId: String, deviceId: String, now: Long) {
        val existing = findPeerDelivery(batchId, deviceId)
        if (existing?.state == OutboundBatchPeerDeliveryState.DELIVERED ||
            existing?.state == OutboundBatchPeerDeliveryState.FAILED_FINAL
        ) {
            return
        }
        val attemptCount = when {
            existing == null -> 1
            existing.state == OutboundBatchPeerDeliveryState.IN_FLIGHT -> existing.attempt_count
            else -> existing.attempt_count + 1
        }
        upsertPeerDelivery(
            OutboundBatchPeerDeliveryEntity(
                batch_id = batchId,
                device_id = deviceId,
                state = OutboundBatchPeerDeliveryState.IN_FLIGHT,
                attempt_count = attemptCount,
                last_error = null,
                last_attempt_at = now,
                next_retry_at = null,
                delivered_at = existing?.delivered_at,
                updated_at = now,
            ),
        )
    }

    @Transaction
    fun markPeerPending(batchId: String, deviceId: String, now: Long) {
        val existing = findPeerDelivery(batchId, deviceId)
        if (existing != null) {
            if (existing.state == OutboundBatchPeerDeliveryState.DELIVERED ||
                existing.state == OutboundBatchPeerDeliveryState.FAILED_FINAL ||
                existing.state == OutboundBatchPeerDeliveryState.FAILED_RETRYABLE ||
                existing.state == OutboundBatchPeerDeliveryState.IN_FLIGHT
            ) {
                return
            }
        }
        upsertPeerDelivery(
            OutboundBatchPeerDeliveryEntity(
                batch_id = batchId,
                device_id = deviceId,
                state = OutboundBatchPeerDeliveryState.PENDING,
                attempt_count = existing?.attempt_count ?: 0,
                last_error = existing?.last_error,
                last_attempt_at = existing?.last_attempt_at,
                next_retry_at = existing?.next_retry_at,
                delivered_at = existing?.delivered_at,
                updated_at = now,
            ),
        )
    }

    @Transaction
    fun markPeerDelivered(batchId: String, deviceId: String, now: Long) {
        val existing = findPeerDelivery(batchId, deviceId)
        upsertPeerDelivery(
            OutboundBatchPeerDeliveryEntity(
                batch_id = batchId,
                device_id = deviceId,
                state = OutboundBatchPeerDeliveryState.DELIVERED,
                attempt_count = existing?.attempt_count ?: 1,
                last_error = null,
                last_attempt_at = existing?.last_attempt_at ?: now,
                next_retry_at = null,
                delivered_at = now,
                updated_at = now,
            ),
        )
    }

    @Transaction
    fun markPeerFailed(
        batchId: String,
        deviceId: String,
        now: Long,
        reason: String,
        maxAttempts: Int,
        retryDelayMs: Long,
    ) {
        val existing = findPeerDelivery(batchId, deviceId)
        val attemptCount = existing?.attempt_count ?: 1
        val state = if (attemptCount >= maxAttempts) {
            OutboundBatchPeerDeliveryState.FAILED_FINAL
        } else {
            OutboundBatchPeerDeliveryState.FAILED_RETRYABLE
        }
        upsertPeerDelivery(
            OutboundBatchPeerDeliveryEntity(
                batch_id = batchId,
                device_id = deviceId,
                state = state,
                attempt_count = attemptCount,
                last_error = reason,
                last_attempt_at = existing?.last_attempt_at ?: now,
                next_retry_at = if (state == OutboundBatchPeerDeliveryState.FAILED_RETRYABLE) now + retryDelayMs else null,
                delivered_at = existing?.delivered_at,
                updated_at = now,
            ),
        )
    }

    @Transaction
    fun markInflightPeersStale(
        batchId: String,
        now: Long,
        reason: String,
        maxAttempts: Int,
        retryDelayMs: Long,
    ) {
        listPeerDeliveries(batchId)
            .filter { it.state == OutboundBatchPeerDeliveryState.IN_FLIGHT }
            .forEach { delivery ->
                val state = if (delivery.attempt_count >= maxAttempts) {
                    OutboundBatchPeerDeliveryState.FAILED_FINAL
                } else {
                    OutboundBatchPeerDeliveryState.FAILED_RETRYABLE
                }
                upsertPeerDelivery(
                    delivery.copy(
                        state = state,
                        last_error = reason,
                        next_retry_at = if (state == OutboundBatchPeerDeliveryState.FAILED_RETRYABLE) now + retryDelayMs else null,
                        updated_at = now,
                    ),
                )
            }
    }
}
