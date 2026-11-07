package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.RemovedMemberEntity

@Dao
interface RemovedMemberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: RemovedMemberEntity)

    @Query(
        """
        SELECT * FROM removed_members
        WHERE groupId = :groupId AND peerId = :peerId
        LIMIT 1
        """
    )
    fun find(groupId: String, peerId: String): RemovedMemberEntity?

    @Query(
        """
        SELECT * FROM removed_members
        WHERE groupId = :groupId
        ORDER BY removedAtSeconds DESC, peerId ASC
        """
    )
    fun listByGroup(groupId: String): List<RemovedMemberEntity>

    @Query(
        """
        SELECT COUNT(*) FROM removed_members
        WHERE groupId = :groupId AND peerId = :peerId
        """
    )
    fun count(groupId: String, peerId: String): Int

    @Query("DELETE FROM removed_members WHERE groupId = :groupId")
    fun deleteByGroup(groupId: String): Int
}
