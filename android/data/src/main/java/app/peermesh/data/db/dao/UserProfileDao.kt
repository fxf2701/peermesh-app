package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.UserProfileEntity

@Dao
interface UserProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(profile: UserProfileEntity)

    @Query("SELECT * FROM user_profiles WHERE gid = :gid AND actor_did = :actorDid")
    fun find(gid: String, actorDid: String): UserProfileEntity?

    @Query("SELECT * FROM user_profiles WHERE gid = :gid")
    fun listByGid(gid: String): List<UserProfileEntity>

    @Query("DELETE FROM user_profiles WHERE gid = :gid AND actor_did = :actorDid")
    fun delete(gid: String, actorDid: String): Int

    @Query("DELETE FROM user_profiles WHERE gid = :gid")
    fun deleteByGid(gid: String): Int
}
