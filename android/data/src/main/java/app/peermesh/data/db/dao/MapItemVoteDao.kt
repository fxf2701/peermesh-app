package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.MapItemVoteEntity

@Dao
interface MapItemVoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(vote: MapItemVoteEntity)

    @Query("SELECT * FROM map_item_votes WHERE gid = :gid AND item_id = :itemId AND actor_did = :actorDid")
    fun find(gid: String, itemId: String, actorDid: String): MapItemVoteEntity?

    @Query("DELETE FROM map_item_votes WHERE gid = :gid AND item_id = :itemId AND actor_did = :actorDid")
    fun delete(gid: String, itemId: String, actorDid: String)

    @Query(
        "SELECT item_id, confidence, COUNT(DISTINCT actor_did) as cnt " +
            "FROM map_item_votes " +
            "WHERE gid = :gid AND item_type = :itemType AND item_id IN (:itemIds) " +
            "GROUP BY item_id, confidence"
    )
    fun countByItemIds(
        gid: String,
        itemType: String,
        itemIds: List<String>
    ): List<ItemVoteCount>

    @Query(
        "SELECT * FROM map_item_votes " +
            "WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId " +
            "ORDER BY updated_t DESC " +
            "LIMIT :limit OFFSET :offset"
    )
    fun listVotesForItem(
        gid: String,
        itemType: String,
        itemId: String,
        limit: Int,
        offset: Int
    ): List<MapItemVoteEntity>

    @Query("DELETE FROM map_item_votes WHERE gid = :gid")
    fun deleteByGid(gid: String): Int
}

data class ItemVoteCount(
    val item_id: String,
    val confidence: Int,
    val cnt: Int
)
