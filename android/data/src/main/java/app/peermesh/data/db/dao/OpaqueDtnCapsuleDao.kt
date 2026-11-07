package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.peermesh.data.db.entity.OpaqueDtnCapsuleDeliveryEntity
import app.peermesh.data.db.entity.OpaqueDtnCapsuleDeliveryState
import app.peermesh.data.db.entity.OpaqueDtnCapsuleEntity

@Dao
interface OpaqueDtnCapsuleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnore(capsule: OpaqueDtnCapsuleEntity): Long

    @Transaction
    fun upsert(capsule: OpaqueDtnCapsuleEntity) {
        // Do not REPLACE existing capsules: SQLite REPLACE deletes the parent row,
        // which would cascade-delete per-peer delivery rows and make already-delivered
        // capsules eligible for resend to the same device.
        insertIgnore(capsule)
    }

    @Transaction
    fun upsertAll(capsules: List<OpaqueDtnCapsuleEntity>) {
        capsules.forEach { upsert(it) }
    }

    @Query("SELECT * FROM opaque_dtn_capsules WHERE capsule_id = :capsuleId LIMIT 1")
    fun find(capsuleId: String): OpaqueDtnCapsuleEntity?

    @Query(
        """
        SELECT * FROM opaque_dtn_capsules
        WHERE gid = :gid
          AND (expires_at IS NULL OR expires_at > :nowSeconds)
        ORDER BY priority ASC, received_at ASC, capsule_id ASC
        LIMIT :limit
        """
    )
    fun listInventory(gid: String, nowSeconds: Long, limit: Int): List<OpaqueDtnCapsuleEntity>

    @Query(
        """
        SELECT * FROM opaque_dtn_capsules
        WHERE gid = :gid
          AND capsule_id IN (:capsuleIds)
          AND (expires_at IS NULL OR expires_at > :nowSeconds)
        ORDER BY priority ASC, received_at ASC, capsule_id ASC
        """
    )
    fun loadByIds(gid: String, capsuleIds: List<String>, nowSeconds: Long): List<OpaqueDtnCapsuleEntity>

    @Query("SELECT DISTINCT gid FROM opaque_dtn_capsules WHERE expires_at IS NULL OR expires_at > :nowSeconds")
    fun listActiveGids(nowSeconds: Long): List<String>

    @Query(
        """
        SELECT c.* FROM opaque_dtn_capsules c
        LEFT JOIN opaque_dtn_capsule_delivery d
          ON d.capsule_id = c.capsule_id
         AND d.device_id = :deviceId
        WHERE c.expires_at IS NOT NULL
          AND c.expires_at > :nowSeconds
          AND c.hop_count < c.max_hops
          AND (
            (
              d.capsule_id IS NULL
              AND c.last_forwarded_at IS NULL
            )
            OR (
              d.state = :failedRetryableState
              AND (d.next_retry_at IS NULL OR d.next_retry_at <= :nowSeconds)
            )
          )
        ORDER BY
          CASE
            -- 1. New capsule never sent to this target.
            WHEN d.capsule_id IS NULL
             AND c.last_forwarded_at IS NULL
             AND c.received_at >= (:nowSeconds - 300)
            THEN 0

            -- 2. Hot failed/incomplete retry.
            WHEN d.state = :failedRetryableState
             AND (d.next_retry_at IS NULL OR d.next_retry_at <= :nowSeconds)
             AND COALESCE(d.last_attempt_at, d.updated_at, 0) >= (:nowSeconds - 300)
            THEN 1

            -- 3. Older capsule never sent to this target.
            WHEN d.capsule_id IS NULL
             AND c.last_forwarded_at IS NULL
            THEN 2

            -- 4. Normal failed retry.
            WHEN d.state = :failedRetryableState
             AND (d.next_retry_at IS NULL OR d.next_retry_at <= :nowSeconds)
            THEN 3

            -- 5. Legacy/already-forwarded capsule with no per-peer delivery row.
            ELSE 4
          END ASC,

          CASE
            WHEN d.capsule_id IS NULL THEN c.received_at
            ELSE NULL
          END DESC,

          CASE
            WHEN d.capsule_id IS NOT NULL THEN COALESCE(d.last_attempt_at, d.updated_at, 0)
            ELSE NULL
          END ASC,

          CASE
            WHEN d.capsule_id IS NULL AND c.last_forwarded_at IS NOT NULL THEN c.last_forwarded_at
            ELSE NULL
          END ASC,

          c.priority ASC,
          c.capsule_id ASC
        LIMIT :limit
        """
    )
    fun listForwardableForDevice(
        deviceId: String,
        nowSeconds: Long,
        limit: Int,
        failedRetryableState: String = OpaqueDtnCapsuleDeliveryState.FAILED_RETRYABLE,
    ): List<OpaqueDtnCapsuleEntity>

    @Query("SELECT * FROM opaque_dtn_capsule_delivery WHERE capsule_id = :capsuleId AND device_id = :deviceId LIMIT 1")
    fun findDelivery(capsuleId: String, deviceId: String): OpaqueDtnCapsuleDeliveryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertDelivery(delivery: OpaqueDtnCapsuleDeliveryEntity)

    @Query("UPDATE opaque_dtn_capsules SET last_forwarded_at = :forwardedAtSeconds WHERE capsule_id = :capsuleId")
    fun markForwarded(capsuleId: String, forwardedAtSeconds: Long): Int

    @Query("UPDATE opaque_dtn_capsules SET last_forwarded_at = :forwardedAtSeconds WHERE batch_id = :batchId")
    fun markForwardedByBatchId(batchId: String, forwardedAtSeconds: Long): Int

    @Query("SELECT capsule_id FROM opaque_dtn_capsules WHERE batch_id = :batchId")
    fun listCapsuleIdsByBatchId(batchId: String): List<String>

    @Query(
        """
        SELECT d.capsule_id FROM opaque_dtn_capsule_delivery d
        INNER JOIN opaque_dtn_capsules c
          ON c.capsule_id = d.capsule_id
        WHERE c.batch_id = :batchId
          AND d.device_id = :deviceId
          AND d.state = :deliveredState
        """
    )
    fun listDeliveredCapsuleIdsByBatchId(
        batchId: String,
        deviceId: String,
        deliveredState: String = OpaqueDtnCapsuleDeliveryState.DELIVERED,
    ): List<String>

    @Query(
        """
        SELECT capsule_id FROM opaque_dtn_capsule_delivery
        WHERE capsule_id IN (:capsuleIds)
          AND device_id = :deviceId
          AND state = :deliveredState
        """
    )
    fun listDeliveredCapsuleIdsForDevice(
        capsuleIds: List<String>,
        deviceId: String,
        deliveredState: String = OpaqueDtnCapsuleDeliveryState.DELIVERED,
    ): List<String>

    @Query("DELETE FROM opaque_dtn_capsule_delivery WHERE capsule_id NOT IN (SELECT capsule_id FROM opaque_dtn_capsules)")
    fun deleteOrphanDeliveries(): Int

    @Query("SELECT COALESCE(SUM(payload_bytes), 0) FROM opaque_dtn_capsules")
    fun totalPayloadBytes(): Long

    @Query("SELECT COUNT(*) FROM opaque_dtn_capsules WHERE source_peer_id = :peerId AND received_at >= :sinceSeconds")
    fun countFromPeerSince(peerId: String, sinceSeconds: Long): Int

    @Query(
        """
        SELECT capsule_id FROM opaque_dtn_capsules
        ORDER BY priority DESC, received_at ASC, capsule_id ASC
        LIMIT :limit
        """
    )
    fun listPruneCandidates(limit: Int): List<String>

    @Query("DELETE FROM opaque_dtn_capsules WHERE expires_at IS NOT NULL AND expires_at <= :nowSeconds")
    fun deleteExpired(nowSeconds: Long): Int

    @Query("DELETE FROM opaque_dtn_capsules WHERE expires_at IS NULL AND received_at <= :receivedBeforeSeconds")
    fun deleteLegacyNullExpiry(receivedBeforeSeconds: Long): Int

    @Query(
        """
        DELETE FROM opaque_dtn_capsules
        WHERE last_forwarded_at IS NOT NULL
          AND last_forwarded_at <= :forwardedBeforeSeconds
          AND capsule_id NOT IN (
            SELECT DISTINCT capsule_id FROM opaque_dtn_capsule_delivery
          )
        """
    )
    fun deleteLegacyForwardedWithoutDelivery(forwardedBeforeSeconds: Long): Int

    @Query("DELETE FROM opaque_dtn_capsules WHERE gid = :gid")
    fun deleteByGid(gid: String): Int

    @Query("DELETE FROM opaque_dtn_capsules WHERE batch_id = :batchId")
    fun deleteByBatchId(batchId: String): Int

    @Query("DELETE FROM opaque_dtn_capsules WHERE capsule_id IN (:capsuleIds)")
    fun deleteCapsuleIds(capsuleIds: List<String>): Int

    @Query("DELETE FROM opaque_dtn_capsules WHERE capsule_id = :capsuleId")
    fun delete(capsuleId: String): Int
    @Transaction
    fun markDeliveryQueued(capsuleId: String, deviceId: String, nowSeconds: Long) {
        val existing = findDelivery(capsuleId, deviceId)
        if (existing?.state == OpaqueDtnCapsuleDeliveryState.DELIVERED ||
            existing?.state == OpaqueDtnCapsuleDeliveryState.FAILED_FINAL
        ) {
            return
        }
        val attemptCount = when {
            existing == null -> 1
            existing.state == OpaqueDtnCapsuleDeliveryState.IN_FLIGHT -> existing.attempt_count
            else -> existing.attempt_count + 1
        }
        upsertDelivery(
            OpaqueDtnCapsuleDeliveryEntity(
                capsule_id = capsuleId,
                device_id = deviceId,
                state = OpaqueDtnCapsuleDeliveryState.IN_FLIGHT,
                attempt_count = attemptCount,
                last_error = null,
                last_attempt_at = nowSeconds,
                next_retry_at = null,
                delivered_at = existing?.delivered_at,
                updated_at = nowSeconds,
            ),
        )
    }

    @Transaction
    fun markDeliveryDelivered(capsuleId: String, deviceId: String, nowSeconds: Long): Int {
        if (find(capsuleId) == null) return 0
        val existing = findDelivery(capsuleId, deviceId)
        if (existing?.state == OpaqueDtnCapsuleDeliveryState.DELIVERED) return 0
        if (existing?.state == OpaqueDtnCapsuleDeliveryState.FAILED_FINAL) return 0
        upsertDelivery(
            OpaqueDtnCapsuleDeliveryEntity(
                capsule_id = capsuleId,
                device_id = deviceId,
                state = OpaqueDtnCapsuleDeliveryState.DELIVERED,
                attempt_count = existing?.attempt_count ?: 1,
                last_error = null,
                last_attempt_at = existing?.last_attempt_at ?: nowSeconds,
                next_retry_at = null,
                delivered_at = nowSeconds,
                updated_at = nowSeconds,
            ),
        )
        markForwarded(capsuleId, nowSeconds)
        return 1
    }

    @Transaction
    fun markDeliveryFailed(
        capsuleId: String,
        deviceId: String,
        nowSeconds: Long,
        reason: String,
        maxAttempts: Int,
        retryDelaySeconds: Long,
    ): Int {
        if (find(capsuleId) == null) return 0
        val existing = findDelivery(capsuleId, deviceId)
        if (existing?.state == OpaqueDtnCapsuleDeliveryState.DELIVERED ||
            existing?.state == OpaqueDtnCapsuleDeliveryState.FAILED_FINAL
        ) {
            return 0
        }
        val attemptCount = existing?.attempt_count ?: 1
        val state = if (attemptCount >= maxAttempts) {
            OpaqueDtnCapsuleDeliveryState.FAILED_FINAL
        } else {
            OpaqueDtnCapsuleDeliveryState.FAILED_RETRYABLE
        }
        upsertDelivery(
            OpaqueDtnCapsuleDeliveryEntity(
                capsule_id = capsuleId,
                device_id = deviceId,
                state = state,
                attempt_count = attemptCount,
                last_error = reason,
                last_attempt_at = existing?.last_attempt_at ?: nowSeconds,
                next_retry_at = if (state == OpaqueDtnCapsuleDeliveryState.FAILED_RETRYABLE) {
                    nowSeconds + retryDelaySeconds
                } else {
                    null
                },
                delivered_at = existing?.delivered_at,
                updated_at = nowSeconds,
            ),
        )
        return 1
    }

    @Transaction
    fun markBatchDeliveredForDevice(batchId: String, deviceId: String, nowSeconds: Long): Int {
        val capsuleIds = listCapsuleIdsByBatchId(batchId)
        return capsuleIds.sumOf { capsuleId -> markDeliveryDelivered(capsuleId, deviceId, nowSeconds) }
    }

    @Transaction
    fun markCapsulesDeliveredForDevice(capsuleIds: List<String>, deviceId: String, nowSeconds: Long): Int {
        return capsuleIds.distinct().sumOf { capsuleId ->
            markDeliveryDelivered(capsuleId, deviceId, nowSeconds)
        }
    }

    @Transaction
    fun markBatchFailedForDevice(
        batchId: String,
        deviceId: String,
        nowSeconds: Long,
        reason: String,
        maxAttempts: Int,
        retryDelaySeconds: Long,
    ): Int {
        val capsuleIds = listCapsuleIdsByBatchId(batchId)
        return markCapsulesFailedForDevice(
            capsuleIds = capsuleIds,
            deviceId = deviceId,
            nowSeconds = nowSeconds,
            reason = reason,
            maxAttempts = maxAttempts,
            retryDelaySeconds = retryDelaySeconds,
        )
    }

    @Transaction
    fun markCapsulesFailedForDevice(
        capsuleIds: List<String>,
        deviceId: String,
        nowSeconds: Long,
        reason: String,
        maxAttempts: Int,
        retryDelaySeconds: Long,
    ): Int {
        return capsuleIds.distinct().sumOf { capsuleId ->
            markDeliveryFailed(
                capsuleId = capsuleId,
                deviceId = deviceId,
                nowSeconds = nowSeconds,
                reason = reason,
                maxAttempts = maxAttempts,
                retryDelaySeconds = retryDelaySeconds,
            )
        }
    }

}
