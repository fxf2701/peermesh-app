package app.peermesh.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MapItemM2b1MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "m2b1-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    @Throws(IOException::class)
    fun migratesSchema51BackfillsSafeLegacyStateAndPreservesCanonicalRows() {
        helper.createDatabase(databaseName, 51).use { db -> seed(db) }

        helper.runMigrationsAndValidate(
            databaseName,
            52,
            true,
            DatabaseMigrations.MIGRATION_51_52,
        ).use { db ->
            assertEquals(9, scalarLong(db, "SELECT COUNT(*) FROM map_item_domain_state"))
            assertEquals(0, scalarLong(db, "SELECT COUNT(*) FROM map_item_actions"))

            assertState(db, "situation-marker", "situation", "legacy_kind_derived", "active", "owner-a")
            assertState(db, "coordination-marker", "coordination", "legacy_kind_derived", null, "owner-b")
            assertState(db, "situation-area", "situation", "legacy_kind_derived", "active", "owner-c", itemType = "area")
            assertState(db, "marker-only-area", null, "unresolved", null, "owner-d", itemType = "area")
            assertState(db, "alias-marker", "situation", "legacy_kind_derived", "active", "owner-e")
            assertState(db, "unknown-marker", null, "unresolved", null, "owner-f")
            assertState(db, "blank-marker", null, "unresolved", null, "owner-g")
            assertState(db, "deleted-situation", "situation", "legacy_kind_derived", null, "owner-h")
            assertState(db, "null-marker", null, "unresolved", null, "owner-i")

            query(db, "SELECT expires_at, owner_edit_until, interaction_until, temporal_origin FROM map_item_domain_state WHERE item_id='situation-marker'") {
                assertEquals(5_000L, getLong(0)); assertEquals(3_000L, getLong(1)); assertEquals(5_000L, getLong(2)); assertEquals("legacy_ttl_derived", getString(3))
            }
            query(db, "SELECT expires_at, owner_edit_until, interaction_until, temporal_origin FROM map_item_domain_state WHERE item_id='coordination-marker'") {
                assertNull(if (isNull(0)) null else getLong(0))
                assertNull(if (isNull(1)) null else getLong(1))
                assertNull(if (isNull(2)) null else getLong(2))
                assertEquals("legacy_no_expiry", getString(3))
            }
            query(db, "SELECT expires_at, owner_edit_until, interaction_until, temporal_origin FROM map_item_domain_state WHERE item_id='unknown-marker'") {
                assertEquals(true, isNull(0)); assertEquals(true, isNull(1)); assertEquals(true, isNull(2)); assertEquals("legacy_unresolved", getString(3))
            }

            assertMarkerProjection(db)
            assertAreaProjection(db)
            assertAuxiliaryLegacyTables(db)
            assertLegacyConfidenceValues(db)
            assertMapItemVoteProjection(db)
            assertEquals("deleted", scalarString(db, "SELECT status FROM map_markers WHERE marker_id='blank-marker'"))
            assertIndexes(db)
            assertEquals(0, scalarLong(db, "SELECT COUNT(*) FROM map_item_domain_state WHERE latest_confirmation_event_id IS NOT NULL OR latest_confirmation_t IS NOT NULL OR latest_confirmation_lt IS NOT NULL OR latest_confirmation_actor IS NOT NULL OR latest_update_event_id IS NOT NULL OR latest_update_t IS NOT NULL OR latest_update_lt IS NOT NULL OR latest_update_actor IS NOT NULL OR latest_lifecycle_event_id IS NOT NULL OR latest_lifecycle_t IS NOT NULL OR latest_lifecycle_lt IS NOT NULL OR latest_lifecycle_actor IS NOT NULL OR latest_material_event_id IS NOT NULL OR latest_material_t IS NOT NULL OR latest_material_lt IS NOT NULL OR latest_material_actor IS NOT NULL"))
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*DatabaseMigrations.ALL)
            .build()
        try {
            room.openHelper.writableDatabase
        } finally {
            room.close()
        }
    }

    private fun seed(db: SupportSQLiteDatabase) {
        marker(db, "situation-marker", "risk", 1, "active", 1_000, 5_000, "owner-a")
        marker(db, "coordination-marker", "camp", 2, "active", 1_000, null, "owner-b")
        area(db, "situation-area", "risk", 2, "active", 1_000, 5_000, "owner-c")
        area(db, "marker-only-area", "camp", 1, "active", 1_000, null, "owner-d")
        marker(db, "alias-marker", "food_water", 1, "active", 1_000, null, "owner-e")
        marker(db, "unknown-marker", "future", 0, "active", 1_000, 900, "owner-f", "legacy contribution", 77, 88)
        marker(db, "blank-marker", " ", 2, "deleted", 1_000, null, "owner-g")
        marker(db, "deleted-situation", "risk", 1, "deleted", 1_000, null, "owner-h")
        marker(db, "null-marker", null, 1, "active", 1_000, null, "owner-i")
        db.execSQL("INSERT INTO map_item_votes(gid,item_id,item_type,actor_did,note,confidence,created_t,updated_t) VALUES ('g','unknown-marker','marker','voter','vote contribution',2,10,11)")
        db.execSQL("INSERT INTO events(gid,event_id,type,source,created_t,time_lt,actor_did,payload_json,inserted_at) VALUES ('g','durable-event','MAP.MARKER.ADD','local',1,1,'owner-a','{\"kind\":\"risk\"}',99)")
        db.execSQL("INSERT INTO event_dedupe(gid,event_id,seen_at,expires_at) VALUES ('g','dedupe-event',21,99)")
        db.execSQL("INSERT INTO map_marker_field_clock(gid,marker_id,field,last_event_t,last_event_lt,last_event_actor) VALUES ('g','unknown-marker','note',70,8,'clock-actor')")
        db.execSQL("INSERT INTO deleted_events(gid,target_event_id,delete_event_id,actor_did,deleted_at,expires_at,target_created_t,delete_created_t,last_event_t,last_event_lt,last_event_actor) VALUES ('g','target-event','delete-event','deleter',80,180,40,80,80,9,'deleter')")
    }

    private fun marker(db: SupportSQLiteDatabase, id: String, kind: String?, confidence: Int, status: String, created: Long, expires: Long?, owner: String, note: String = "marker-note", request: Long? = null, cancel: Long? = null) {
        db.execSQL("INSERT INTO map_markers(gid,marker_id,layer_id,lat,lon,radius_m,label,note,kind,confidence,status,created_t,updated_t,actor_did,removal_requested_t,removal_requested_actor,removal_reason,removal_cancel_t,expires_at,last_event_id,last_event_t,last_event_lt,last_event_actor) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf("g",id,"layer",12.5,22.5,null,"label",note,kind,confidence,status,created,created,owner,request,if(request==null)null else "requester",if(request==null)null else "reason",cancel,expires,"last-$id",created,1,owner))
    }

    private fun area(db: SupportSQLiteDatabase, id: String, kind: String?, confidence: Int, status: String, created: Long, expires: Long?, owner: String) {
        db.execSQL("INSERT INTO map_areas(gid,area_id,layer_id,geometry_json,label,note,kind,confidence,status,created_t,updated_t,actor_did,removal_requested_t,removal_requested_actor,removal_reason,removal_cancel_t,expires_at,last_event_id,last_event_t,last_event_lt,last_event_actor) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf("g",id,"layer","[[1,2],[3,4]]","area-label","area-note",kind,confidence,status,created,created,owner,null,null,null,null,expires,"last-$id",created,1,owner))
    }

    private fun assertMarkerProjection(db: SupportSQLiteDatabase) {
        query(db, "SELECT gid,marker_id,layer_id,lat,lon,radius_m,label,note,kind,confidence,status,created_t,updated_t,actor_did,expires_at,removal_requested_t,removal_requested_actor,removal_reason,removal_cancel_t,last_event_id,last_event_t,last_event_lt,last_event_actor FROM map_markers WHERE marker_id='unknown-marker'") {
            assertEquals("g", getString(0)); assertEquals("unknown-marker", getString(1)); assertEquals("layer", getString(2))
            assertEquals(12.5, getDouble(3), 0.0); assertEquals(22.5, getDouble(4), 0.0); assertNull(if (isNull(5)) null else getDouble(5))
            assertEquals("label", getString(6)); assertEquals("legacy contribution", getString(7)); assertEquals("future", getString(8)); assertEquals(0, getInt(9)); assertEquals("active", getString(10))
            assertEquals(1_000L, getLong(11)); assertEquals(1_000L, getLong(12)); assertEquals("owner-f", getString(13)); assertEquals(900L, getLong(14))
            assertEquals(77L, getLong(15)); assertEquals("requester", getString(16)); assertEquals("reason", getString(17)); assertEquals(88L, getLong(18))
            assertEquals("last-unknown-marker", getString(19)); assertEquals(1_000L, getLong(20)); assertEquals(1L, getLong(21)); assertEquals("owner-f", getString(22))
        }
    }

    private fun assertAreaProjection(db: SupportSQLiteDatabase) {
        query(db, "SELECT gid,area_id,layer_id,geometry_json,label,note,kind,confidence,status,created_t,updated_t,actor_did,removal_requested_t,removal_requested_actor,removal_reason,removal_cancel_t,expires_at,last_event_id,last_event_t,last_event_lt,last_event_actor FROM map_areas WHERE area_id='situation-area'") {
            assertEquals("g", getString(0)); assertEquals("situation-area", getString(1)); assertEquals("layer", getString(2)); assertEquals("[[1,2],[3,4]]", getString(3)); assertEquals("area-label", getString(4)); assertEquals("area-note", getString(5)); assertEquals("risk", getString(6)); assertEquals(2, getInt(7)); assertEquals("active", getString(8)); assertEquals(1_000L, getLong(9)); assertEquals(1_000L, getLong(10)); assertEquals("owner-c", getString(11)); assertNull(if (isNull(12)) null else getLong(12)); assertNull(if (isNull(13)) null else getString(13)); assertNull(if (isNull(14)) null else getString(14)); assertNull(if (isNull(15)) null else getLong(15)); assertEquals(5_000L, getLong(16)); assertEquals("last-situation-area", getString(17)); assertEquals(1_000L, getLong(18)); assertEquals(1L, getLong(19)); assertEquals("owner-c", getString(20))
        }
    }

    private fun assertAuxiliaryLegacyTables(db: SupportSQLiteDatabase) {
        query(db, "SELECT gid,event_id,seen_at,expires_at FROM event_dedupe WHERE event_id='dedupe-event'") { assertEquals("g", getString(0)); assertEquals("dedupe-event", getString(1)); assertEquals(21L, getLong(2)); assertEquals(99L, getLong(3)) }
        query(db, "SELECT gid,marker_id,field,last_event_t,last_event_lt,last_event_actor FROM map_marker_field_clock WHERE marker_id='unknown-marker'") { assertEquals("g", getString(0)); assertEquals("unknown-marker", getString(1)); assertEquals("note", getString(2)); assertEquals(70L, getLong(3)); assertEquals(8L, getLong(4)); assertEquals("clock-actor", getString(5)) }
        query(db, "SELECT gid,target_event_id,delete_event_id,actor_did,deleted_at,expires_at,target_created_t,delete_created_t,last_event_t,last_event_lt,last_event_actor FROM deleted_events WHERE target_event_id='target-event'") { assertEquals("g", getString(0)); assertEquals("target-event", getString(1)); assertEquals("delete-event", getString(2)); assertEquals("deleter", getString(3)); assertEquals(80L, getLong(4)); assertEquals(180L, getLong(5)); assertEquals(40L, getLong(6)); assertEquals(80L, getLong(7)); assertEquals(80L, getLong(8)); assertEquals(9L, getLong(9)); assertEquals("deleter", getString(10)) }
        query(db, "SELECT gid,event_id,type,source,created_t,time_lt,actor_did,payload_json,inserted_at FROM events WHERE event_id='durable-event'") { assertEquals("g", getString(0)); assertEquals("durable-event", getString(1)); assertEquals("MAP.MARKER.ADD", getString(2)); assertEquals("local", getString(3)); assertEquals(1L, getLong(4)); assertEquals(1L, getLong(5)); assertEquals("owner-a", getString(6)); assertEquals("{\"kind\":\"risk\"}", getString(7)); assertEquals(99L, getLong(8)) }
    }

    private fun assertLegacyConfidenceValues(db: SupportSQLiteDatabase) {
        assertEquals(0, scalarLong(db, "SELECT confidence FROM map_markers WHERE marker_id='unknown-marker'").toInt())
        assertEquals(1, scalarLong(db, "SELECT confidence FROM map_markers WHERE marker_id='situation-marker'").toInt())
        assertEquals(2, scalarLong(db, "SELECT confidence FROM map_markers WHERE marker_id='blank-marker'").toInt())
    }

    private fun assertMapItemVoteProjection(db: SupportSQLiteDatabase) {
        query(db, "SELECT gid,item_id,item_type,actor_did,note,confidence,created_t,updated_t FROM map_item_votes WHERE actor_did='voter'") {
            assertEquals("g", getString(0))
            assertEquals("unknown-marker", getString(1))
            assertEquals("marker", getString(2))
            assertEquals("voter", getString(3))
            assertEquals("vote contribution", getString(4))
            assertEquals(2, getInt(5))
            assertEquals(10L, getLong(6))
            assertEquals(11L, getLong(7))
        }
    }

    private fun assertState(db: SupportSQLiteDatabase, id: String, category: String?, origin: String, lifecycle: String?, owner: String, itemType: String = "marker") {
        query(db, "SELECT category,category_origin,situation_lifecycle,owner_did,creator_information_source,domain_version,canonical_add_event_id,canonical_add_t,canonical_add_lt,canonical_add_actor,confirmation_actor_count,acknowledgement_actor_count,latest_confirmation_event_id,latest_update_event_id,latest_lifecycle_event_id,latest_material_event_id FROM map_item_domain_state WHERE item_type='$itemType' AND item_id='$id'") {
            assertEquals(category, if (isNull(0)) null else getString(0)); assertEquals(origin, getString(1)); assertEquals(lifecycle, if (isNull(2)) null else getString(2)); assertEquals(owner, getString(3))
            for (index in 4..9) assertEquals(true, isNull(index)); assertEquals(0, getInt(10)); assertEquals(0, getInt(11)); for (index in 12..15) assertEquals(true, isNull(index))
        }
    }

    private fun assertIndexes(db: SupportSQLiteDatabase) {
        assertEquals("1:gid,2:item_type,3:category", indexColumns(db, "index_map_item_domain_state_gid_item_type_category"))
        assertEquals("1:gid,2:event_id", indexColumns(db, "index_map_item_actions_gid_event_id"))
        assertEquals(1, indexUnique(db, "map_item_actions", "index_map_item_actions_gid_event_id"))
        assertEquals("1:gid,2:item_type,3:item_id,4:materialization_state,5:action_t,6:action_lt,7:actor_did,8:event_id", indexColumns(db, "index_map_item_actions_gid_item_type_item_id_materialization_state_action_t_action_lt_actor_did_event_id"))
        assertEquals("1:gid,2:item_type,3:item_id,4:materialization_state,5:action_type,6:actor_did", indexColumns(db, "index_map_item_actions_gid_item_type_item_id_materialization_state_action_type_actor_did"))
        assertEquals("1:materialization_state,2:gid,3:item_type,4:item_id,5:action_t,6:action_lt,7:actor_did,8:event_id", indexColumns(db, "index_map_item_actions_materialization_state_gid_item_type_item_id_action_t_action_lt_actor_did_event_id"))
    }

    private fun indexColumns(db: SupportSQLiteDatabase, name: String): String {
        val values = mutableListOf<String>(); db.query("PRAGMA index_info('$name')").use { while (it.moveToNext()) values += "${it.getInt(0)+1}:${it.getString(2)}" }; return values.joinToString(",")
    }
    private fun indexUnique(db: SupportSQLiteDatabase, table: String, name: String): Int = db.query("PRAGMA index_list('$table')").use { cursor ->
        while (cursor.moveToNext()) if (cursor.getString(1) == name) return@use cursor.getInt(2)
        -1
    }
    private fun scalarLong(db: SupportSQLiteDatabase, sql: String) = db.query(sql).use { it.moveToFirst(); it.getLong(0) }
    private fun scalarString(db: SupportSQLiteDatabase, sql: String) = db.query(sql).use { it.moveToFirst(); it.getString(0) }
    private inline fun query(db: SupportSQLiteDatabase, sql: String, assertion: android.database.Cursor.() -> Unit) = db.query(sql).use { assertEquals(true, it.moveToFirst()); it.assertion() }
}
