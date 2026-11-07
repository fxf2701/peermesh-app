package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.BlockedPeerEntity

@Dao
interface BlockedPeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: BlockedPeerEntity): Long

    @Query(
        """
        SELECT * FROM blocked_peers
        WHERE blockedPeerId = :blockedPeerId
          AND scope = :scope
          AND groupId = :groupId
          AND (expiresAtMs IS NULL OR expiresAtMs > :nowMs)
        LIMIT 1
        """
    )
    fun findScoped(
        blockedPeerId: String,
        groupId: String,
        nowMs: Long,
        scope: String = "group_local",
    ): BlockedPeerEntity?

    @Query(
        """
        SELECT * FROM blocked_peers
        WHERE blockedPeerId = :blockedPeerId
          AND scope = :scope
          AND groupId IS NULL
          AND (expiresAtMs IS NULL OR expiresAtMs > :nowMs)
        LIMIT 1
        """
    )
    fun findGlobal(
        blockedPeerId: String,
        nowMs: Long,
        scope: String = "global_local",
    ): BlockedPeerEntity?

    @Query(
        """
        SELECT * FROM blocked_peers
        WHERE groupId = :groupId
          AND scope = :scope
          AND (expiresAtMs IS NULL OR expiresAtMs > :nowMs)
        ORDER BY updatedAtMs DESC, id DESC
        """
    )
    fun listScoped(
        groupId: String,
        nowMs: Long,
        scope: String = "group_local",
    ): List<BlockedPeerEntity>

    @Query(
        """
        SELECT * FROM blocked_peers
        WHERE groupId IS NULL
          AND scope = :scope
          AND (expiresAtMs IS NULL OR expiresAtMs > :nowMs)
        ORDER BY updatedAtMs DESC, id DESC
        """
    )
    fun listGlobal(
        nowMs: Long,
        scope: String = "global_local",
    ): List<BlockedPeerEntity>

    @Query(
        """
        SELECT * FROM blocked_peers
        WHERE (expiresAtMs IS NULL OR expiresAtMs > :nowMs)
        ORDER BY updatedAtMs DESC, id DESC
        """
    )
    fun listAllActive(nowMs: Long): List<BlockedPeerEntity>

    @Query(
        """
        SELECT blockedPeerId FROM blocked_peers
        WHERE groupId = :groupId
          AND scope = :scope
          AND hideMessages = 1
          AND (expiresAtMs IS NULL OR expiresAtMs > :nowMs)
        """
    )
    fun listMessageHiddenPeerIds(
        groupId: String,
        nowMs: Long,
        scope: String = "group_local",
    ): List<String>

    @Query(
        """
        SELECT blockedPeerId FROM blocked_peers
        WHERE groupId IS NULL
          AND scope = :scope
          AND hideMessages = 1
          AND (expiresAtMs IS NULL OR expiresAtMs > :nowMs)
        """
    )
    fun listGlobalMessageHiddenPeerIds(
        nowMs: Long,
        scope: String = "global_local",
    ): List<String>

    @Query(
        """
        SELECT blockedPeerId FROM blocked_peers
        WHERE groupId = :groupId
          AND scope = :scope
          AND (hideLocation = 1 OR hideTrail = 1)
          AND (expiresAtMs IS NULL OR expiresAtMs > :nowMs)
        """
    )
    fun listLocationHiddenPeerIds(
        groupId: String,
        nowMs: Long,
        scope: String = "group_local",
    ): List<String>

    @Query(
        """
        SELECT blockedPeerId FROM blocked_peers
        WHERE groupId IS NULL
          AND scope = :scope
          AND (hideLocation = 1 OR hideTrail = 1)
          AND (expiresAtMs IS NULL OR expiresAtMs > :nowMs)
        """
    )
    fun listGlobalLocationHiddenPeerIds(
        nowMs: Long,
        scope: String = "global_local",
    ): List<String>

    @Query(
        """
        DELETE FROM blocked_peers
        WHERE blockedPeerId = :blockedPeerId
          AND groupId = :groupId
          AND scope = :scope
        """
    )
    fun deleteScoped(
        blockedPeerId: String,
        groupId: String,
        scope: String = "group_local",
    ): Int

    @Query(
        """
        DELETE FROM blocked_peers
        WHERE blockedPeerId = :blockedPeerId
          AND scope = :scope
          AND groupId IS NULL
        """
    )
    fun deleteGlobal(
        blockedPeerId: String,
        scope: String = "global_local",
    ): Int

    @Query("DELETE FROM blocked_peers WHERE blockedPeerId = :blockedPeerId")
    fun deleteAllScopes(blockedPeerId: String): Int
}
