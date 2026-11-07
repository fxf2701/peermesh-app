package app.peermesh.data.map.replay.m2d4

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.entity.UserProfileEntity
import app.peermesh.data.map.processing.MapEventProcessor
import app.peermesh.data.map.processing.RoomMapEventProcessorFactory

/** Fresh Room graph plus prerequisite membership authority; no map materialization is seeded. */
internal class M2D4RoomReplayEnvironment(
    groups: Set<String>,
    actors: Set<String>,
) : M2D4ReplayEnvironment {
    internal val db: AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    override val processor: MapEventProcessor = RoomMapEventProcessorFactory.lazy(db)
    override val snapshotReader: MapReplaySnapshotReader = RoomMapReplaySnapshotReader(db)

    init {
        groups.sorted().forEach { gid ->
            actors.sorted().forEach { did ->
                db.userProfileDao().upsert(
                    UserProfileEntity(gid, did, did, null, 1, "m2d4-$gid-$did", 1, 0, did),
                )
            }
        }
    }

    override fun close() = db.close()
}

/** Reads only stable semantic columns and applies explicit comparison order. */
private class RoomMapReplaySnapshotReader(private val db: AppDatabase) : MapReplaySnapshotReader {
    override fun read(gid: String): MapReplaySemanticSnapshot = MapReplaySemanticSnapshot(
        canonicalTargets = readCanonical(gid).sortedWith(compareBy({ it.itemType }, { it.itemId })),
        domainStates = readDomain(gid).sortedWith(compareBy({ it.itemType }, { it.itemId })),
        actions = readActions(gid).sortedWith(
            compareBy<ActionHistorySnapshot>({ it.itemType }, { it.itemId })
                .thenBy { requireNotNull(it.semanticContent["action_t"]).toLong() }
                .thenBy { requireNotNull(it.semanticContent["action_lt"]).toLong() }
                .thenBy { it.semanticContent["actor_did"] }
                .thenBy { it.eventId },
        ),
    )

    private fun readCanonical(gid: String): List<CanonicalTargetSnapshot> = buildList {
        query(
            "SELECT marker_id,layer_id,lat,lon,radius_m,label,note,kind,confidence,status," +
                "created_t,updated_t,actor_did,removal_requested_t,removal_requested_actor," +
                "removal_reason,removal_cancel_t,expires_at,last_event_id,last_event_t,last_event_lt," +
                "last_event_actor FROM map_markers WHERE gid=? ORDER BY marker_id",
            gid,
        ).use { cursor ->
            while (cursor.moveToNext()) add(
                CanonicalTargetSnapshot("marker", cursor.string("marker_id"), cursor.valuesExcept("marker_id")),
            )
        }
        query(
            "SELECT area_id,layer_id,geometry_json,label,note,kind,confidence,status,created_t,updated_t," +
                "actor_did,removal_requested_t,removal_requested_actor,removal_reason,removal_cancel_t," +
                "expires_at,last_event_id,last_event_t,last_event_lt,last_event_actor " +
                "FROM map_areas WHERE gid=? ORDER BY area_id",
            gid,
        ).use { cursor ->
            while (cursor.moveToNext()) add(
                CanonicalTargetSnapshot("area", cursor.string("area_id"), cursor.valuesExcept("area_id")),
            )
        }
    }

    private fun readDomain(gid: String): List<DomainStateSnapshot> {
        val sql = "SELECT item_type,item_id,category,category_origin,creator_information_source," +
            "domain_version,state_origin,owner_did,canonical_add_event_id,canonical_add_t," +
            "canonical_add_lt,canonical_add_actor,situation_lifecycle,expires_at,owner_edit_until," +
            "interaction_until,temporal_origin,confirmation_actor_count,acknowledgement_actor_count," +
            "latest_confirmation_event_id,latest_confirmation_t,latest_confirmation_lt," +
            "latest_confirmation_actor,latest_update_event_id,latest_update_t,latest_update_lt," +
            "latest_update_actor,latest_lifecycle_event_id,latest_lifecycle_t,latest_lifecycle_lt," +
            "latest_lifecycle_actor,latest_material_event_id,latest_material_t,latest_material_lt," +
            "latest_material_actor FROM map_item_domain_state WHERE gid=? ORDER BY item_type,item_id"
        return buildList {
            query(sql, gid).use { cursor ->
                while (cursor.moveToNext()) add(
                    DomainStateSnapshot(
                        cursor.string("item_type"), cursor.string("item_id"),
                        cursor.valuesExcept("item_type", "item_id"),
                    ),
                )
            }
        }
    }

    private fun readActions(gid: String): List<ActionHistorySnapshot> {
        val sql = "SELECT action_id,event_id,event_type,map_domain_v,item_type,item_id,action_type," +
            "actor_did,action_t,action_lt,information_source,action_note,update_disposition," +
            "target_lifecycle,event_fingerprint,materialization_state,rejection_reason " +
            "FROM map_item_actions WHERE gid=? ORDER BY item_type,item_id,action_t,action_lt,actor_did,event_id"
        return buildList {
            query(sql, gid).use { cursor ->
                while (cursor.moveToNext()) add(
                    ActionHistorySnapshot(
                        cursor.string("event_id"), cursor.string("action_id"), cursor.string("item_type"),
                        cursor.string("item_id"),
                        cursor.valuesExcept("event_id", "action_id", "item_type", "item_id"),
                    ),
                )
            }
        }
    }

    private fun query(sql: String, gid: String): Cursor =
        db.openHelper.readableDatabase.query(sql, arrayOf(gid))

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

    private fun Cursor.valuesExcept(vararg excluded: String): Map<String, String?> {
        val excludedSet = excluded.toSet()
        return columnNames.filterNot(excludedSet::contains).associateWith { column ->
            val index = getColumnIndexOrThrow(column)
            when (getType(index)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> getLong(index).toString()
                Cursor.FIELD_TYPE_FLOAT -> getDouble(index).toString()
                Cursor.FIELD_TYPE_BLOB -> getBlob(index).joinToString("") { "%02x".format(it) }
                else -> getString(index)
            }
        }
    }
}
