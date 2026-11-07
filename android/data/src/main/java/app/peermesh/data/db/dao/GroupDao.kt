package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.peermesh.data.db.entity.GroupEntity

@Dao
interface GroupDao {
    @Query("SELECT gid FROM groups ORDER BY updated_at DESC")
    fun listGids(): List<String>

    @Query("SELECT * FROM groups ORDER BY updated_at DESC")
    fun listGroups(): List<GroupEntity>

    @Query(
        """
        SELECT g.* FROM groups g
        INNER JOIN user_profiles p ON g.gid = p.gid
        WHERE p.actor_did = :actorDid
        ORDER BY g.updated_at DESC
        """
    )
    fun listGroupsForActor(actorDid: String): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE gid = :gid")
    fun findGroup(gid: String): GroupEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    fun upsert(entity: GroupEntity)

    @Query("DELETE FROM groups WHERE gid = :gid")
    fun delete(gid: String): Int
}
