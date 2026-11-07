package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.NearbyRoomParticipantEntity

@Dao
interface NearbyRoomParticipantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(participant: NearbyRoomParticipantEntity)

    @Query(
        """
        SELECT * FROM nearby_room_participants
        WHERE room_id = :roomId AND status = :activeStatus
        ORDER BY
            CASE role
                WHEN :ownerRole THEN 0
                ELSE 1
            END,
            joined_at_ms ASC,
            device_id ASC
        """
    )
    fun listActiveParticipants(
        roomId: String,
        activeStatus: String = NearbyRoomParticipantEntity.STATUS_ACTIVE,
        ownerRole: String = NearbyRoomParticipantEntity.ROLE_OWNER,
    ): List<NearbyRoomParticipantEntity>

    @Query("SELECT * FROM nearby_room_participants WHERE room_id = :roomId AND device_id = :deviceId")
    fun findParticipant(roomId: String, deviceId: String): NearbyRoomParticipantEntity?

    @Query(
        """
        UPDATE nearby_room_participants
        SET recipient_encryption_algorithm = :algorithm,
            recipient_encryption_key_id = :keyId,
            recipient_encryption_public_key = :publicKey
        WHERE room_id = :roomId
          AND device_id = :deviceId
          AND recipient_encryption_algorithm IS NULL
          AND recipient_encryption_key_id IS NULL
          AND recipient_encryption_public_key IS NULL
        """
    )
    fun pinRecipientEncryptionIdentityIfAbsent(
        roomId: String,
        deviceId: String,
        algorithm: String,
        keyId: String,
        publicKey: ByteArray,
    ): Int

    @Query(
        """
        UPDATE nearby_room_participants
        SET role = :role
        WHERE room_id = :roomId AND device_id = :deviceId
        """
    )
    fun updateRole(
        roomId: String,
        deviceId: String,
        role: String,
    ): Int

    @Query(
        """
        UPDATE nearby_room_participants
        SET status = :leftStatus,
            last_seen_at_ms = :atMs
        WHERE room_id = :roomId AND device_id = :deviceId
        """
    )
    fun markLeft(
        roomId: String,
        deviceId: String,
        atMs: Long,
        leftStatus: String = NearbyRoomParticipantEntity.STATUS_LEFT,
    ): Int

    @Query(
        """
        UPDATE nearby_room_participants
        SET status = :kickedStatus,
            last_seen_at_ms = :atMs
        WHERE room_id = :roomId AND device_id = :deviceId
        """
    )
    fun markKicked(
        roomId: String,
        deviceId: String,
        atMs: Long,
        kickedStatus: String = NearbyRoomParticipantEntity.STATUS_KICKED,
    ): Int

    @Query("DELETE FROM nearby_room_participants WHERE room_id = :roomId")
    fun deleteByRoom(roomId: String): Int
}
