package app.peermesh.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import app.peermesh.core.map.MapItemKindCatalog
import app.peermesh.data.reducer.ReducerConstants

/**
 * Non-destructive schema 51 -> 52 migration for Phase M2B1.
 *
 * Creates generic domain-state and action-history tables, then deterministically backfills only
 * safe legacy state from canonical marker and area rows. It does not replay the event log or
 * synthesize dedicated action rows.
 */
internal object MapItemM2b1Migration {
    fun migrate(db: SupportSQLiteDatabase) {
        createDomainStateTable(db)
        createActionTable(db)
        backfillDomainState(db)
        createSecondaryIndexes(db)
    }

    private fun createDomainStateTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS map_item_domain_state (
                gid TEXT NOT NULL,
                item_type TEXT NOT NULL,
                item_id TEXT NOT NULL,
                category TEXT,
                category_origin TEXT NOT NULL,
                creator_information_source TEXT,
                domain_version INTEGER,
                state_origin TEXT NOT NULL,
                owner_did TEXT NOT NULL,
                canonical_add_event_id TEXT,
                canonical_add_t INTEGER,
                canonical_add_lt INTEGER,
                canonical_add_actor TEXT,
                situation_lifecycle TEXT,
                expires_at INTEGER,
                owner_edit_until INTEGER,
                interaction_until INTEGER,
                temporal_origin TEXT NOT NULL,
                confirmation_actor_count INTEGER NOT NULL DEFAULT 0,
                acknowledgement_actor_count INTEGER NOT NULL DEFAULT 0,
                latest_confirmation_event_id TEXT,
                latest_confirmation_t INTEGER,
                latest_confirmation_lt INTEGER,
                latest_confirmation_actor TEXT,
                latest_update_event_id TEXT,
                latest_update_t INTEGER,
                latest_update_lt INTEGER,
                latest_update_actor TEXT,
                latest_lifecycle_event_id TEXT,
                latest_lifecycle_t INTEGER,
                latest_lifecycle_lt INTEGER,
                latest_lifecycle_actor TEXT,
                latest_material_event_id TEXT,
                latest_material_t INTEGER,
                latest_material_lt INTEGER,
                latest_material_actor TEXT,
                PRIMARY KEY (gid, item_type, item_id)
            )
            """.trimIndent(),
        )
    }

    private fun createActionTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS map_item_actions (
                gid TEXT NOT NULL,
                action_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                map_domain_v INTEGER NOT NULL,
                item_type TEXT NOT NULL,
                item_id TEXT NOT NULL,
                action_type TEXT NOT NULL,
                actor_did TEXT NOT NULL,
                action_t INTEGER NOT NULL,
                action_lt INTEGER NOT NULL,
                information_source TEXT,
                action_note TEXT,
                update_disposition TEXT,
                target_lifecycle TEXT,
                event_fingerprint TEXT NOT NULL,
                materialization_state TEXT NOT NULL,
                rejection_reason TEXT,
                PRIMARY KEY (gid, action_id)
            )
            """.trimIndent(),
        )
    }

    private fun backfillDomainState(db: SupportSQLiteDatabase) {
        val insert = db.compileStatement(
            """
            INSERT OR IGNORE INTO map_item_domain_state (
                gid, item_type, item_id, category, category_origin,
                creator_information_source, domain_version, state_origin, owner_did,
                canonical_add_event_id, canonical_add_t, canonical_add_lt, canonical_add_actor,
                situation_lifecycle, expires_at, owner_edit_until, interaction_until, temporal_origin
            ) VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?, NULL, NULL, NULL, NULL, ?, ?, ?, ?, ?)
            """.trimIndent(),
        )
        try {
            backfillTable(db, insert, "map_markers", "marker_id", MapItemPersistenceValues.ItemType.MARKER)
            backfillTable(db, insert, "map_areas", "area_id", MapItemPersistenceValues.ItemType.AREA)
        } finally {
            insert.close()
        }
    }

    private fun backfillTable(
        db: SupportSQLiteDatabase,
        insert: SupportSQLiteStatement,
        table: String,
        idColumn: String,
        itemType: String,
    ) {
        db.query("SELECT gid, $idColumn, actor_did, kind, status, created_t, expires_at FROM $table").use { cursor ->
            val gidIndex = cursor.getColumnIndexOrThrow("gid")
            val idIndex = cursor.getColumnIndexOrThrow(idColumn)
            val ownerIndex = cursor.getColumnIndexOrThrow("actor_did")
            val kindIndex = cursor.getColumnIndexOrThrow("kind")
            val statusIndex = cursor.getColumnIndexOrThrow("status")
            val createdIndex = cursor.getColumnIndexOrThrow("created_t")
            val expiresIndex = cursor.getColumnIndexOrThrow("expires_at")
            while (cursor.moveToNext()) {
                val category = deriveCategory(cursor.getStringOrNull(kindIndex), itemType)
                val windows = LegacyMapItemWindowBackfill.derive(
                    createdAtSeconds = cursor.getLong(createdIndex),
                    expiresAtSeconds = cursor.getLongOrNull(expiresIndex),
                )
                insert.clearBindings()
                insert.bindString(1, cursor.getString(gidIndex))
                insert.bindString(2, itemType)
                insert.bindString(3, cursor.getString(idIndex))
                insert.bindNullableString(4, category)
                insert.bindString(5, if (category == null) MapItemPersistenceValues.CategoryOrigin.UNRESOLVED else MapItemPersistenceValues.CategoryOrigin.LEGACY_KIND_DERIVED)
                insert.bindString(6, MapItemPersistenceValues.StateOrigin.LEGACY_ROW_BACKFILL)
                insert.bindString(7, cursor.getString(ownerIndex))
                val lifecycle = if (category == MapItemPersistenceValues.Category.SITUATION && cursor.getString(statusIndex) == ReducerConstants.STATUS_ACTIVE) {
                    MapItemPersistenceValues.SituationLifecycle.ACTIVE
                } else null
                insert.bindNullableString(8, lifecycle)
                insert.bindNullableLong(9, windows.windows.expiresAtSeconds)
                insert.bindNullableLong(10, windows.windows.ownerEditUntilSeconds)
                insert.bindNullableLong(11, windows.windows.interactionUntilSeconds)
                insert.bindString(12, windows.temporalOrigin)
                insert.executeInsert()
            }
        }
    }

    private fun deriveCategory(kind: String?, itemType: String): String? {
        val normalized = MapItemKindCatalog.normalize(kind)
        val supported = when (itemType) {
            MapItemPersistenceValues.ItemType.MARKER -> MapItemKindCatalog.supportsMarker(normalized)
            MapItemPersistenceValues.ItemType.AREA -> MapItemKindCatalog.supportsArea(normalized)
            else -> false
        }
        return if (supported) MapItemKindCatalog.categoryFor(normalized)?.wireValue else null
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun android.database.Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun SupportSQLiteStatement.bindNullableString(index: Int, value: String?) = if (value == null) bindNull(index) else bindString(index, value)
    private fun SupportSQLiteStatement.bindNullableLong(index: Int, value: Long?) = if (value == null) bindNull(index) else bindLong(index, value)

    private fun createSecondaryIndexes(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_map_item_domain_state_gid_item_type_category ON map_item_domain_state(gid, item_type, category)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_map_item_actions_gid_event_id ON map_item_actions(gid, event_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_map_item_actions_gid_item_type_item_id_materialization_state_action_t_action_lt_actor_did_event_id ON map_item_actions(gid, item_type, item_id, materialization_state, action_t, action_lt, actor_did, event_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_map_item_actions_gid_item_type_item_id_materialization_state_action_type_actor_did ON map_item_actions(gid, item_type, item_id, materialization_state, action_type, actor_did)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_map_item_actions_materialization_state_gid_item_type_item_id_action_t_action_lt_actor_did_event_id ON map_item_actions(materialization_state, gid, item_type, item_id, action_t, action_lt, actor_did, event_id)")
    }
}
