package app.peermesh.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    @Suppress("unused")
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS keys (
                    gid TEXT NOT NULL PRIMARY KEY,
                    kid TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS map_marker_field_clock (
                    gid TEXT NOT NULL,
                    marker_id TEXT NOT NULL,
                    field TEXT NOT NULL,
                    last_event_t INTEGER NOT NULL,
                    last_event_lt INTEGER NOT NULL,
                    last_event_actor TEXT NOT NULL,
                    PRIMARY KEY (gid, marker_id, field)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_map_marker_field_clock_gid_marker_id
                ON map_marker_field_clock(gid, marker_id)
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS relay_cursors (
                    relay_url TEXT NOT NULL,
                    gid TEXT NOT NULL,
                    cursor TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (relay_url, gid)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_relay_cursors_gid
                ON relay_cursors(gid)
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE events ADD COLUMN source TEXT NOT NULL DEFAULT 'mesh'
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_events_gid_source
                ON events(gid, source)
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS relay_push_state (
                    relay_url TEXT NOT NULL PRIMARY KEY,
                    last_push_at INTEGER NOT NULL,
                    last_push_event_id TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chat_messages_new (
                    gid TEXT NOT NULL,
                    message_id TEXT NOT NULL,
                    text TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_t INTEGER NOT NULL,
                    updated_t INTEGER NOT NULL,
                    actor_did TEXT NOT NULL,
                    reply_to TEXT,
                    expires_at INTEGER,
                    last_event_id TEXT NOT NULL,
                    last_event_t INTEGER NOT NULL,
                    last_event_lt INTEGER NOT NULL,
                    last_event_actor TEXT NOT NULL,
                    PRIMARY KEY (gid, message_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO chat_messages_new (
                    gid,
                    message_id,
                    text,
                    status,
                    created_t,
                    updated_t,
                    actor_did,
                    reply_to,
                    expires_at,
                    last_event_id,
                    last_event_t,
                    last_event_lt,
                    last_event_actor
                )
                SELECT
                    gid,
                    message_id,
                    text,
                    status,
                    created_t,
                    updated_t,
                    actor_did,
                    reply_to,
                    expires_at,
                    last_event_id,
                    last_event_t,
                    last_event_lt,
                    last_event_actor
                FROM chat_messages
                """.trimIndent()
            )
            db.execSQL("DROP TABLE chat_messages")
            db.execSQL("ALTER TABLE chat_messages_new RENAME TO chat_messages")
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_chat_messages_gid_created_t
                ON chat_messages(gid, created_t)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_chat_messages_gid_status
                ON chat_messages(gid, status)
                """.trimIndent()
            )
            db.execSQL("DROP TABLE IF EXISTS chat_threads")
        }
    }

    @Suppress("unused")
    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS user_profiles (
                    gid TEXT NOT NULL,
                    actor_did TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    last_event_id TEXT NOT NULL,
                    last_event_t INTEGER NOT NULL,
                    last_event_lt INTEGER NOT NULL,
                    last_event_actor TEXT NOT NULL,
                    PRIMARY KEY (gid, actor_did)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_user_profiles_gid_updated_at
                ON user_profiles(gid, updated_at)
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_8_9: Migration = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE user_profiles ADD COLUMN avatar_id TEXT
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE groups ADD COLUMN owner_did TEXT
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_10_11: Migration = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE chat_messages ADD COLUMN received_ack_count INTEGER NOT NULL DEFAULT 0
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE chat_messages ADD COLUMN received_group_ack_count INTEGER NOT NULL DEFAULT 0
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE chat_messages ADD COLUMN received_ack_by TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE chat_messages ADD COLUMN received_group_ack_by TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE chat_messages ADD COLUMN seen_by TEXT
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_11_12: Migration = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE chat_messages ADD COLUMN delivery_status TEXT NOT NULL DEFAULT 'sent'
                """.trimIndent()
            )
        }
    }

    /**
     * Add confidence for map markers & areas.
     * confidence: 2=observed, 1=reported (default), 0=uncertain
     */
    @Suppress("unused")
    val MIGRATION_12_13: Migration = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE map_markers ADD COLUMN confidence TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE map_areas ADD COLUMN confidence TEXT
                """.trimIndent()
            )
        }
    }

    /**
     * Modify confidence for map markers & areas, change type to integer.
     * confidence: 2=observed, 1=reported (default), 0=uncertain
     */
    @Suppress("unused")
    val MIGRATION_13_14: Migration = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS map_markers_new (
                    gid TEXT NOT NULL,
                    marker_id TEXT NOT NULL,
                    layer_id TEXT NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    radius_m REAL,
                    note TEXT,
                    kind TEXT,
                    confidence INTEGER NOT NULL DEFAULT 1,
                    status TEXT NOT NULL,
                    created_t INTEGER NOT NULL,
                    updated_t INTEGER NOT NULL,
                    actor_did TEXT NOT NULL,
                    expires_at INTEGER,
                    last_event_id TEXT NOT NULL,
                    last_event_t INTEGER NOT NULL,
                    last_event_lt INTEGER NOT NULL,
                    last_event_actor TEXT NOT NULL,
                    PRIMARY KEY (gid, marker_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO map_markers_new (
                    gid,
                    marker_id,
                    layer_id,
                    lat,
                    lon,
                    radius_m,
                    note,
                    kind,
                    confidence,
                    status,
                    created_t,
                    updated_t,
                    actor_did,
                    expires_at,
                    last_event_id,
                    last_event_t,
                    last_event_lt,
                    last_event_actor
                )
                SELECT
                    gid,
                    marker_id,
                    layer_id,
                    lat,
                    lon,
                    radius_m,
                    note,
                    kind,
                    CAST(confidence AS INTEGER),
                    status,
                    created_t,
                    updated_t,
                    actor_did,
                    expires_at,
                    last_event_id,
                    last_event_t,
                    last_event_lt,
                    last_event_actor
                FROM map_markers
                """.trimIndent()
            )
            db.execSQL("DROP TABLE map_markers")
            db.execSQL("ALTER TABLE map_markers_new RENAME TO map_markers")
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_map_markers_gid_layer_id_status
                ON map_markers(gid, layer_id, status)
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS map_areas_new (
                    gid TEXT NOT NULL,
                    area_id TEXT NOT NULL,
                    layer_id TEXT NOT NULL,
                    geometry_json TEXT NOT NULL,
                    note TEXT,
                    kind TEXT,
                    confidence INTEGER NOT NULL DEFAULT 1,
                    status TEXT NOT NULL,
                    created_t INTEGER NOT NULL,
                    updated_t INTEGER NOT NULL,
                    actor_did TEXT NOT NULL,
                    expires_at INTEGER,
                    last_event_id TEXT NOT NULL,
                    last_event_t INTEGER NOT NULL,
                    last_event_lt INTEGER NOT NULL,
                    last_event_actor TEXT NOT NULL,
                    PRIMARY KEY (gid, area_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO map_areas_new (
                    gid,
                    area_id,
                    layer_id,
                    geometry_json,
                    note,
                    kind,
                    confidence,
                    status,
                    created_t,
                    updated_t,
                    actor_did,
                    expires_at,
                    last_event_id,
                    last_event_t,
                    last_event_lt,
                    last_event_actor
                )
                SELECT
                    gid,
                    area_id,
                    layer_id,
                    geometry_json,
                    note,
                    kind,
                    CAST(confidence AS INTEGER),
                    status,
                    created_t,
                    updated_t,
                    actor_did,
                    expires_at,
                    last_event_id,
                    last_event_t,
                    last_event_lt,
                    last_event_actor
                FROM map_areas
                """.trimIndent()
            )
            db.execSQL("DROP TABLE map_areas")
            db.execSQL("ALTER TABLE map_areas_new RENAME TO map_areas")
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_map_areas_gid_layer_id_status
                ON map_areas(gid, layer_id, status)
                """.trimIndent()
            )
        }
    }

    /**
     * Add group context profile (urban/outdoor).
     */
    @Suppress("unused")
    val MIGRATION_14_15: Migration = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE groups ADD COLUMN context_profile TEXT NOT NULL DEFAULT 'urban'
                """.trimIndent()
            )
        }
    }

    /**
     * Add trails + trail_points tables.
     */
    @Suppress("unused")
    val MIGRATION_15_16: Migration = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS trails (
                    trail_id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    start_t INTEGER NOT NULL,
                    end_t INTEGER NOT NULL,
                    point_count INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS trail_points (
                    trail_id TEXT NOT NULL,
                    idx INTEGER NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    t INTEGER NOT NULL,
                    acc REAL,
                    speed REAL,
                    PRIMARY KEY (trail_id, idx),
                    FOREIGN KEY (trail_id) REFERENCES trails(trail_id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_trail_points_trail_id
                ON trail_points(trail_id)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_trails_start_t
                ON trails(start_t)
                """.trimIndent()
            )
        }
    }

    /**
     * Shorten trail_points columns + add motion mode.
     */
    @Suppress("unused")
    val MIGRATION_16_17: Migration = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS trail_points_new (
                    tid TEXT NOT NULL,
                    idx INTEGER NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    t INTEGER NOT NULL,
                    acc REAL,
                    spd REAL,
                    mm INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (tid, idx),
                    FOREIGN KEY (tid) REFERENCES trails(trail_id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO trail_points_new (
                    tid,
                    idx,
                    lat,
                    lon,
                    t,
                    acc,
                    spd,
                    mm
                )
                SELECT
                    trail_id,
                    idx,
                    lat,
                    lon,
                    t,
                    acc,
                    speed,
                    0
                FROM trail_points
                """.trimIndent()
            )
            db.execSQL("DROP TABLE trail_points")
            db.execSQL("ALTER TABLE trail_points_new RENAME TO trail_points")
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_trail_points_tid
                ON trail_points(tid)
                """.trimIndent()
            )
        }
    }

    /**
     * Add dist_m to trails.
     */
    @Suppress("unused")
    val MIGRATION_17_18: Migration = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE trails ADD COLUMN dist_m REAL NOT NULL DEFAULT 0
                """.trimIndent()
            )
        }
    }

    /**
     * Add label to map markers and areas.
     */
    @Suppress("unused")
    val MIGRATION_18_19: Migration = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE map_markers ADD COLUMN label TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE map_areas ADD COLUMN label TEXT
                """.trimIndent()
            )
        }
    }

    /**
     * Add unstable flag and note to trails.
     */
    @Suppress("unused")
    val MIGRATION_19_20: Migration = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE trails ADD COLUMN unstable INTEGER NOT NULL DEFAULT 0
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE trails ADD COLUMN unstable_note TEXT
                """.trimIndent()
            )
        }
    }

    /**
     * Add per-user map item votes.
     */
    @Suppress("unused")
    val MIGRATION_20_21: Migration = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS map_item_votes (
                    gid TEXT NOT NULL,
                    item_id TEXT NOT NULL,
                    item_type TEXT NOT NULL,
                    actor_did TEXT NOT NULL,
                    note TEXT,
                    confidence INTEGER NOT NULL,
                    created_t INTEGER NOT NULL,
                    updated_t INTEGER NOT NULL,
                    PRIMARY KEY (gid, item_id, actor_did)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_map_item_votes_gid_item_id ON map_item_votes(gid, item_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_map_item_votes_gid_actor_did ON map_item_votes(gid, actor_did)")
        }
    }

    /**
     * Rename map_item_notes -> map_item_votes (legacy upgrade).
     */
    @Suppress("unused")
    val MIGRATION_21_22: Migration = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS map_item_votes (
                    gid TEXT NOT NULL,
                    item_id TEXT NOT NULL,
                    item_type TEXT NOT NULL,
                    actor_did TEXT NOT NULL,
                    note TEXT,
                    confidence INTEGER NOT NULL,
                    created_t INTEGER NOT NULL,
                    updated_t INTEGER NOT NULL,
                    PRIMARY KEY (gid, item_id, actor_did)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_map_item_votes_gid_item_id ON map_item_votes(gid, item_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_map_item_votes_gid_actor_did ON map_item_votes(gid, actor_did)")
            val hasNotesTable = db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='map_item_notes'"
            ).use { it.moveToFirst() }
            if (hasNotesTable) {
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO map_item_votes (gid, item_id, item_type, actor_did, note, confidence, created_t, updated_t)
                    SELECT gid, item_id, item_type, actor_did, note, confidence, created_t, updated_t FROM map_item_notes
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS map_item_notes")
            }
        }
    }

    /**
     * Add removal request fields to map items.
     */
    @Suppress("unused")
    val MIGRATION_22_23: Migration = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE map_markers ADD COLUMN removal_requested_t INTEGER")
            db.execSQL("ALTER TABLE map_markers ADD COLUMN removal_requested_actor TEXT")
            db.execSQL("ALTER TABLE map_markers ADD COLUMN removal_reason TEXT")
            db.execSQL("ALTER TABLE map_markers ADD COLUMN removal_cancel_t INTEGER")
            db.execSQL("ALTER TABLE map_areas ADD COLUMN removal_requested_t INTEGER")
            db.execSQL("ALTER TABLE map_areas ADD COLUMN removal_requested_actor TEXT")
            db.execSQL("ALTER TABLE map_areas ADD COLUMN removal_reason TEXT")
            db.execSQL("ALTER TABLE map_areas ADD COLUMN removal_cancel_t INTEGER")
        }
    }

    /**
     * Add live trail sharing tables.
     */
    @Suppress("unused")
    val MIGRATION_23_24: Migration = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS live_trails (
                    trail_id TEXT NOT NULL,
                    gid TEXT NOT NULL,
                    owner_did TEXT NOT NULL,
                    name TEXT,
                    share_scope TEXT NOT NULL,
                    share_to_json TEXT,
                    start_t INTEGER NOT NULL,
                    end_t INTEGER,
                    point_count INTEGER NOT NULL,
                    dist_m REAL NOT NULL,
                    active INTEGER NOT NULL,
                    updated_t INTEGER NOT NULL,
                    last_event_id TEXT NOT NULL,
                    last_event_t INTEGER NOT NULL,
                    last_event_lt INTEGER NOT NULL,
                    last_event_actor TEXT NOT NULL,
                    PRIMARY KEY (trail_id)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_live_trails_gid_updated_t ON live_trails(gid, updated_t)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_live_trails_gid_owner_did ON live_trails(gid, owner_did)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_live_trails_gid_active ON live_trails(gid, active)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS live_trail_points (
                    trail_id TEXT NOT NULL,
                    idx INTEGER NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    t INTEGER NOT NULL,
                    acc REAL,
                    spd REAL,
                    mm INTEGER NOT NULL,
                    PRIMARY KEY (trail_id, idx)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_live_trail_points_trail_id ON live_trail_points(trail_id)")
        }
    }

    /**
     * Add invalid event tracking fields.
     */
    @Suppress("unused")
    val MIGRATION_24_25: Migration = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE events ADD COLUMN invalid_at INTEGER")
            db.execSQL("ALTER TABLE events ADD COLUMN invalid_reason TEXT")
        }
    }

    /**
     * Add live trail acceptance flag.
     */
    @Suppress("unused")
    val MIGRATION_25_26: Migration = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE live_trails ADD COLUMN accepted INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Add pending live trail expiry.
     */
    @Suppress("unused")
    val MIGRATION_26_27: Migration = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE live_trails ADD COLUMN pending_expires_t INTEGER")
        }
    }

    /**
     * Add event dedupe persistence table for hybrid dedupe (memory + DB).
     */
    @Suppress("unused")
    val MIGRATION_27_28: Migration = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS event_dedupe (
                    gid TEXT NOT NULL,
                    event_id TEXT NOT NULL,
                    seen_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    PRIMARY KEY (gid, event_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_event_dedupe_expires_at
                ON event_dedupe(expires_at)
                """.trimIndent()
            )
        }
    }

    /**
     * Add persistent sender outbound batch queue.
     */
    @Suppress("unused")
    val MIGRATION_28_29: Migration = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS outbound_batch (
                    batch_id TEXT NOT NULL,
                    gid TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    priority INTEGER NOT NULL,
                    attempt_count INTEGER NOT NULL,
                    last_error TEXT,
                    locked_at INTEGER,
                    sent_at INTEGER,
                    payload BLOB NOT NULL,
                    event_count INTEGER NOT NULL,
                    first_event_created_t INTEGER NOT NULL,
                    last_event_created_t INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    PRIMARY KEY(batch_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS outbound_batch_event (
                    batch_id TEXT NOT NULL,
                    event_id TEXT NOT NULL,
                    created_t INTEGER NOT NULL,
                    ordinal_in_batch INTEGER NOT NULL,
                    PRIMARY KEY(batch_id, event_id),
                    FOREIGN KEY(batch_id) REFERENCES outbound_batch(batch_id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_batch_status_created_at ON outbound_batch(status, created_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_batch_gid_status_created_at ON outbound_batch(gid, status, created_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_batch_locked_at ON outbound_batch(locked_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_batch_event_batch_id_ordinal_in_batch ON outbound_batch_event(batch_id, ordinal_in_batch)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_batch_event_event_id ON outbound_batch_event(event_id)")
        }
    }

    /**
     * Add outbound batch freshness lanes so old backlog cannot starve fresh local sends.
     */
    @Suppress("unused")
    val MIGRATION_29_30: Migration = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE outbound_batch ADD COLUMN lane TEXT NOT NULL DEFAULT 'BACKLOG'
                """.trimIndent()
            )
            db.execSQL("DROP INDEX IF EXISTS index_outbound_batch_status_created_at")
            db.execSQL("DROP INDEX IF EXISTS index_outbound_batch_gid_status_created_at")
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_outbound_batch_status_lane_created_at
                ON outbound_batch(status, lane, created_at)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_outbound_batch_gid_status_lane_created_at
                ON outbound_batch(gid, status, lane, created_at)
                """.trimIndent()
            )
        }
    }

    /**
     * Track outbound delivery state per peer so retries can target only peers still missing a batch.
     */
    @Suppress("unused")
    val MIGRATION_30_31: Migration = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS outbound_batch_peer_delivery (
                    batch_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    state TEXT NOT NULL,
                    attempt_count INTEGER NOT NULL,
                    last_error TEXT,
                    last_attempt_at INTEGER,
                    next_retry_at INTEGER,
                    delivered_at INTEGER,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(batch_id, device_id),
                    FOREIGN KEY(batch_id) REFERENCES outbound_batch(batch_id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_outbound_batch_peer_delivery_batch_id_state
                ON outbound_batch_peer_delivery(batch_id, state)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_outbound_batch_peer_delivery_device_id_state
                ON outbound_batch_peer_delivery(device_id, state)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_outbound_batch_peer_delivery_state_next_retry_at
                ON outbound_batch_peer_delivery(state, next_retry_at)
                """.trimIndent()
            )
        }
    }

    /**
     * Add DTN store-carry-forward sync state tables.
     */
    @Suppress("unused")
    val MIGRATION_31_32: Migration = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS dtn_peer_state (
                    peer_id TEXT NOT NULL,
                    gid TEXT NOT NULL,
                    last_seen_at INTEGER NOT NULL,
                    last_session_id TEXT,
                    last_sync_at INTEGER,
                    fail_count INTEGER NOT NULL,
                    backoff_until INTEGER,
                    PRIMARY KEY(peer_id, gid)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS dtn_advertised (
                    peer_id TEXT NOT NULL,
                    gid TEXT NOT NULL,
                    event_id TEXT NOT NULL,
                    advertised_at INTEGER NOT NULL,
                    expires_at INTEGER,
                    PRIMARY KEY(peer_id, gid, event_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS dtn_sync_cursor (
                    peer_id TEXT NOT NULL,
                    gid TEXT NOT NULL,
                    last_inserted_at INTEGER NOT NULL,
                    last_event_id TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(peer_id, gid)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS dtn_pending_pull (
                    peer_id TEXT NOT NULL,
                    gid TEXT NOT NULL,
                    event_id TEXT NOT NULL,
                    requested_at INTEGER NOT NULL,
                    next_retry_at INTEGER,
                    attempt_count INTEGER NOT NULL,
                    expires_at INTEGER,
                    PRIMARY KEY(peer_id, gid, event_id)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_peer_state_peer_id_gid_last_seen_at ON dtn_peer_state(peer_id, gid, last_seen_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_peer_state_gid_last_seen_at ON dtn_peer_state(gid, last_seen_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_advertised_gid_event_id ON dtn_advertised(gid, event_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_advertised_peer_id_gid_advertised_at ON dtn_advertised(peer_id, gid, advertised_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_advertised_expires_at ON dtn_advertised(expires_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_sync_cursor_gid_updated_at ON dtn_sync_cursor(gid, updated_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_pending_pull_gid_event_id ON dtn_pending_pull(gid, event_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_pending_pull_peer_id_gid_next_retry_at ON dtn_pending_pull(peer_id, gid, next_retry_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_pending_pull_expires_at ON dtn_pending_pull(expires_at)")
        }
    }

    /**
     * Add opaque DTN carrier tables for non-member store-carry-forward.
     */
    @Suppress("unused")
    val MIGRATION_32_33: Migration = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS opaque_dtn_capsules (
                    capsule_id TEXT NOT NULL,
                    gid TEXT NOT NULL,
                    gid_hash TEXT,
                    event_id TEXT,
                    batch_id TEXT,
                    kind TEXT NOT NULL,
                    payload BLOB NOT NULL,
                    payload_sha256 TEXT NOT NULL,
                    payload_bytes INTEGER NOT NULL,
                    created_t INTEGER NOT NULL,
                    received_at INTEGER NOT NULL,
                    expires_at INTEGER,
                    hop_count INTEGER NOT NULL,
                    max_hops INTEGER NOT NULL,
                    priority INTEGER NOT NULL,
                    source_peer_id TEXT,
                    last_forwarded_at INTEGER,
                    manifest_id TEXT,
                    PRIMARY KEY(capsule_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS dtn_carrier_manifest (
                    manifest_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    sender_peer_id TEXT NOT NULL,
                    receiver_peer_id TEXT,
                    gid_hash TEXT NOT NULL,
                    max_batches INTEGER NOT NULL,
                    max_bytes INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    nonce TEXT NOT NULL,
                    signature BLOB NOT NULL,
                    created_at INTEGER NOT NULL,
                    accepted_at INTEGER,
                    PRIMARY KEY(manifest_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS dtn_carrier_manifest_batch (
                    manifest_id TEXT NOT NULL,
                    batch_id TEXT NOT NULL,
                    payload_sha256 TEXT NOT NULL,
                    payload_bytes INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    expires_at INTEGER NOT NULL,
                    priority INTEGER NOT NULL,
                    PRIMARY KEY(manifest_id, batch_id),
                    FOREIGN KEY(manifest_id) REFERENCES dtn_carrier_manifest(manifest_id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_opaque_dtn_capsules_gid_received_at ON opaque_dtn_capsules(gid, received_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_opaque_dtn_capsules_gid_event_id ON opaque_dtn_capsules(gid, event_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_opaque_dtn_capsules_batch_id ON opaque_dtn_capsules(batch_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_opaque_dtn_capsules_expires_at ON opaque_dtn_capsules(expires_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_opaque_dtn_capsules_priority_received_at ON opaque_dtn_capsules(priority, received_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_opaque_dtn_capsules_manifest_id ON opaque_dtn_capsules(manifest_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_carrier_manifest_session_id ON dtn_carrier_manifest(session_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_carrier_manifest_sender_peer_id_expires_at ON dtn_carrier_manifest(sender_peer_id, expires_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_carrier_manifest_gid_hash_expires_at ON dtn_carrier_manifest(gid_hash, expires_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_carrier_manifest_expires_at ON dtn_carrier_manifest(expires_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_carrier_manifest_batch_batch_id ON dtn_carrier_manifest_batch(batch_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_carrier_manifest_batch_payload_sha256 ON dtn_carrier_manifest_batch(payload_sha256)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_carrier_manifest_batch_expires_at ON dtn_carrier_manifest_batch(expires_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_carrier_manifest_batch_priority_expires_at ON dtn_carrier_manifest_batch(priority, expires_at)")
        }
    }

    /**
     * Add peer manifest key registry for DTN carrier manifest verification.
     */
    @Suppress("unused")
    val MIGRATION_33_34: Migration = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS dtn_peer_manifest_keys (
                    peer_id TEXT NOT NULL,
                    algorithm TEXT NOT NULL,
                    key_bytes BLOB NOT NULL,
                    first_seen_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(peer_id)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dtn_peer_manifest_keys_algorithm_updated_at ON dtn_peer_manifest_keys(algorithm, updated_at)")
        }
    }

    /**
     * Add per-peer delivery state for opaque DTN carrier capsules.
     */
    @Suppress("unused")
    val MIGRATION_34_35: Migration = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS opaque_dtn_capsule_delivery (
                    capsule_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    state TEXT NOT NULL,
                    attempt_count INTEGER NOT NULL,
                    last_error TEXT,
                    last_attempt_at INTEGER,
                    next_retry_at INTEGER,
                    delivered_at INTEGER,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(capsule_id, device_id),
                    FOREIGN KEY(capsule_id) REFERENCES opaque_dtn_capsules(capsule_id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_opaque_dtn_capsule_delivery_capsule_id_state ON opaque_dtn_capsule_delivery(capsule_id, state)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_opaque_dtn_capsule_delivery_device_id_state ON opaque_dtn_capsule_delivery(device_id, state)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_opaque_dtn_capsule_delivery_state_next_retry_at ON opaque_dtn_capsule_delivery(state, next_retry_at)")
            db.execSQL(
                """
                UPDATE opaque_dtn_capsules
                SET expires_at = received_at + 86400
                WHERE expires_at IS NULL OR expires_at > received_at + 86400
                """.trimIndent()
            )
            // Do not backfill opaque_dtn_capsule_delivery from last_forwarded_at here:
            // legacy rows only know that they were forwarded at least once, but not which
            // device received them. Runtime cleanup/listForwardable guards suppress or
            // retire those legacy forwarded rows without inventing a target device id.
        }
    }

    /**
     * Add monotonic relay push row-id cursor to avoid same-second event ordering gaps.
     */
    @Suppress("unused")
    val MIGRATION_35_36: Migration = object : Migration(35, 36) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE relay_push_state ADD COLUMN last_push_row_id INTEGER NOT NULL DEFAULT 0
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_36_37: Migration = object : Migration(36, 37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS deleted_events (
                    gid TEXT NOT NULL,
                    target_event_id TEXT NOT NULL,
                    delete_event_id TEXT NOT NULL,
                    actor_did TEXT NOT NULL,
                    deleted_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    target_created_t INTEGER,
                    delete_created_t INTEGER NOT NULL,
                    last_event_t INTEGER NOT NULL,
                    last_event_lt INTEGER NOT NULL,
                    last_event_actor TEXT NOT NULL,
                    PRIMARY KEY (gid, target_event_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_deleted_events_gid_expires_at
                ON deleted_events(gid, expires_at)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_deleted_events_gid_delete_event_id
                ON deleted_events(gid, delete_event_id)
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_37_38: Migration = object : Migration(37, 38) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS nearby_rooms (
                    room_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    owner_device_id TEXT NOT NULL,
                    owner_display_name TEXT,
                    created_at_ms INTEGER NOT NULL,
                    expires_at_ms INTEGER NOT NULL,
                    closed_at_ms INTEGER,
                    state TEXT NOT NULL,
                    participant_count INTEGER,
                    is_local_owner INTEGER NOT NULL,
                    last_seen_at_ms INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    PRIMARY KEY(room_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS nearby_room_participants (
                    room_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    display_name TEXT,
                    joined_at_ms INTEGER NOT NULL,
                    last_seen_at_ms INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    status TEXT NOT NULL,
                    invitation_blocked INTEGER NOT NULL,
                    PRIMARY KEY(room_id, device_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS nearby_room_messages (
                    room_id TEXT NOT NULL,
                    message_id TEXT NOT NULL,
                    sender_device_id TEXT NOT NULL,
                    sender_display_name TEXT,
                    body TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    received_at_ms INTEGER NOT NULL,
                    ttl_expires_at_ms INTEGER NOT NULL,
                    delivery_state TEXT NOT NULL,
                    PRIMARY KEY(room_id, message_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS nearby_room_invite_quotas (
                    room_id TEXT NOT NULL,
                    sender_device_id TEXT NOT NULL,
                    target_device_id TEXT NOT NULL,
                    sent_count INTEGER NOT NULL,
                    last_sent_at_ms INTEGER NOT NULL,
                    blocked_after_decline INTEGER NOT NULL,
                    PRIMARY KEY(room_id, sender_device_id, target_device_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_nearby_rooms_state_expires_at_ms
                ON nearby_rooms(state, expires_at_ms)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_nearby_rooms_last_seen_at_ms
                ON nearby_rooms(last_seen_at_ms)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_nearby_room_participants_room_id_status
                ON nearby_room_participants(room_id, status)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_nearby_room_messages_room_id_created_at_ms
                ON nearby_room_messages(room_id, created_at_ms)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_nearby_room_messages_room_id_ttl_expires_at_ms
                ON nearby_room_messages(room_id, ttl_expires_at_ms)
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_38_39: Migration = object : Migration(38, 39) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE nearby_rooms
                ADD COLUMN metadata_status TEXT NOT NULL DEFAULT 'RESOLVED'
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE nearby_rooms
                ADD COLUMN metadata_last_fetched_at INTEGER
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE nearby_rooms
                ADD COLUMN metadata_source_device_id TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE nearby_rooms
                SET metadata_last_fetched_at = created_at_ms,
                    metadata_source_device_id = owner_device_id
                WHERE is_local_owner = 1
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_39_40: Migration = object : Migration(39, 40) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE nearby_room_messages
                ADD COLUMN owner_device_id TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE nearby_room_messages
                SET owner_device_id = sender_device_id
                WHERE owner_device_id IS NULL
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS nearby_room_relay_offers (
                    room_id TEXT NOT NULL,
                    message_compact_id TEXT NOT NULL,
                    target_device_id TEXT NOT NULL,
                    state TEXT NOT NULL,
                    offered_at_ms INTEGER NOT NULL,
                    expires_at_ms INTEGER NOT NULL,
                    PRIMARY KEY(room_id, message_compact_id, target_device_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS nearby_room_relay_wants (
                    room_id TEXT NOT NULL,
                    message_compact_id TEXT NOT NULL,
                    state TEXT NOT NULL,
                    candidate_source_device_ids TEXT NOT NULL,
                    message_owner_device_id TEXT,
                    full_message_id TEXT,
                    attempt_count INTEGER NOT NULL,
                    last_requested_at_ms INTEGER,
                    next_source_index INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    expires_at_ms INTEGER NOT NULL,
                    PRIMARY KEY(room_id, message_compact_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_nearby_room_relay_offers_state_offered_at_ms
                ON nearby_room_relay_offers(state, offered_at_ms)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_nearby_room_relay_offers_expires_at_ms
                ON nearby_room_relay_offers(expires_at_ms)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_nearby_room_relay_wants_state_updated_at_ms
                ON nearby_room_relay_wants(state, updated_at_ms)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_nearby_room_relay_wants_expires_at_ms
                ON nearby_room_relay_wants(expires_at_ms)
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_40_41: Migration = object : Migration(40, 41) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS blocked_peers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    blockedPeerId TEXT NOT NULL,
                    scope TEXT NOT NULL,
                    groupId TEXT,
                    hideMessages INTEGER NOT NULL,
                    hideLocation INTEGER NOT NULL,
                    hideTrail INTEGER NOT NULL,
                    reasonCode TEXT,
                    createdAtMs INTEGER NOT NULL,
                    updatedAtMs INTEGER NOT NULL,
                    expiresAtMs INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS index_blocked_peers_blockedPeerId_groupId_scope
                ON blocked_peers(blockedPeerId, groupId, scope)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_blocked_peers_groupId_scope
                ON blocked_peers(groupId, scope)
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS moderation_reports (
                    reportId TEXT NOT NULL PRIMARY KEY,
                    groupId TEXT NOT NULL,
                    reporterPeerId TEXT NOT NULL,
                    reportedPeerId TEXT NOT NULL,
                    targetType TEXT NOT NULL,
                    messageId TEXT,
                    eventId TEXT,
                    reasonCode TEXT NOT NULL,
                    userNote TEXT,
                    includePlaintext INTEGER NOT NULL,
                    plaintextExcerpt TEXT,
                    ciphertextRef TEXT,
                    deliveryState TEXT NOT NULL,
                    attemptCount INTEGER NOT NULL,
                    createdAtMs INTEGER NOT NULL,
                    lastAttemptAtMs INTEGER,
                    deliveredAtMs INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_moderation_reports_deliveryState_createdAtMs
                ON moderation_reports(deliveryState, createdAtMs)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_moderation_reports_groupId_reportedPeerId
                ON moderation_reports(groupId, reportedPeerId)
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS moderation_actions (
                    actionId TEXT NOT NULL PRIMARY KEY,
                    groupId TEXT,
                    actorPeerId TEXT NOT NULL,
                    targetPeerId TEXT NOT NULL,
                    actionType TEXT NOT NULL,
                    createdAtMs INTEGER NOT NULL,
                    metadataJson TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_moderation_actions_groupId_createdAtMs
                ON moderation_actions(groupId, createdAtMs)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_moderation_actions_targetPeerId_actionType
                ON moderation_actions(targetPeerId, actionType)
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_41_42: Migration = object : Migration(41, 42) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS removed_members (
                    groupId TEXT NOT NULL,
                    peerId TEXT NOT NULL,
                    removedByPeerId TEXT NOT NULL,
                    removalEventId TEXT NOT NULL,
                    removedAtSeconds INTEGER NOT NULL,
                    lastEventT INTEGER NOT NULL,
                    lastEventLt INTEGER NOT NULL,
                    lastEventActor TEXT NOT NULL,
                    PRIMARY KEY(groupId, peerId)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_removed_members_groupId_removedAtSeconds
                ON removed_members(groupId, removedAtSeconds)
                """.trimIndent()
            )
        }
    }

    @Suppress("unused")
    val MIGRATION_42_43: Migration = object : Migration(42, 43) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_peer_routes (
                    gid TEXT NOT NULL,
                    actor_did TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    first_seen_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(gid, actor_did, device_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_group_peer_routes_gid_actor_did_updated_at
                ON group_peer_routes(gid, actor_did, updated_at)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_group_peer_routes_gid_device_id_updated_at
                ON group_peer_routes(gid, device_id, updated_at)
                """.trimIndent()
            )
        }
    }

    val MIGRATION_43_44: Migration = object : Migration(43, 44) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN edited_at INTEGER")
        }
    }

    val MIGRATION_44_45: Migration = object : Migration(44, 45) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE nearby_room_messages ADD COLUMN edited_at_ms INTEGER")
        }
    }

    val MIGRATION_45_46: Migration = object : Migration(45, 46) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE nearby_room_messages ADD COLUMN reply_to TEXT")
        }
    }

    val MIGRATION_46_47: Migration = object : Migration(46, 47) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE nearby_room_messages ADD COLUMN deleted_at_ms INTEGER")
        }
    }

    val MIGRATION_47_48: Migration = object : Migration(47, 48) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS pending_feedback (
                    feedbackId TEXT NOT NULL PRIMARY KEY,
                    category TEXT NOT NULL,
                    message TEXT NOT NULL,
                    createdAtMs INTEGER NOT NULL,
                    appVersion TEXT,
                    buildNumber TEXT,
                    androidVersion TEXT,
                    deviceModel TEXT,
                    localDeviceId TEXT,
                    source TEXT NOT NULL,
                    deliveryState TEXT NOT NULL,
                    attemptCount INTEGER NOT NULL,
                    lastAttemptAtMs INTEGER,
                    deliveredAtMs INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_pending_feedback_deliveryState_createdAtMs
                ON pending_feedback(deliveryState, createdAtMs)
                """.trimIndent()
            )
        }
    }

    val MIGRATION_48_49: Migration = object : Migration(48, 49) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE pending_feedback
                ADD COLUMN feedbackType TEXT NOT NULL DEFAULT 'user_feedback'
                """.trimIndent()
            )
        }
    }

    val MIGRATION_49_50: Migration = object : Migration(49, 50) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS nearby_room_mute_states (
                    room_id TEXT NOT NULL,
                    target_device_id TEXT NOT NULL,
                    actor_device_id TEXT NOT NULL,
                    is_muted INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    expires_at_ms INTEGER NOT NULL,
                    PRIMARY KEY(room_id, target_device_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_nearby_room_mute_states_room_id_is_muted
                ON nearby_room_mute_states(room_id, is_muted)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_nearby_room_mute_states_expires_at_ms
                ON nearby_room_mute_states(expires_at_ms)
                """.trimIndent()
            )
        }
    }

    val MIGRATION_50_51: Migration = object : Migration(50, 51) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE nearby_room_participants
                ADD COLUMN avatar_id TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE nearby_room_participants
                ADD COLUMN profile_updated_at_ms INTEGER
                """.trimIndent()
            )
        }
    }

    val MIGRATION_51_52: Migration = object : Migration(51, 52) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MapItemM2b1Migration.migrate(db)
        }
    }

    val MIGRATION_52_53: Migration = object : Migration(52, 53) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS pending_canonical_map_events (
                    gid TEXT NOT NULL,
                    event_id TEXT NOT NULL,
                    item_type TEXT NOT NULL,
                    item_id TEXT NOT NULL,
                    root_event_id TEXT,
                    event_t INTEGER NOT NULL,
                    event_lt INTEGER NOT NULL,
                    actor_did TEXT NOT NULL,
                    event_fingerprint TEXT NOT NULL,
                    materialization_state TEXT NOT NULL,
                    rejection_reason TEXT,
                    inserted_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    replay_attempt_count INTEGER NOT NULL,
                    PRIMARY KEY(gid, event_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                index_pending_canonical_map_events_gid_item_type_item_id_materialization_state
                ON pending_canonical_map_events(gid, item_type, item_id, materialization_state)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                index_pending_canonical_map_events_root_event_id
                ON pending_canonical_map_events(root_event_id)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                index_pending_canonical_map_events_expires_at
                ON pending_canonical_map_events(expires_at)
                """.trimIndent()
            )
        }
    }

    val MIGRATION_53_54: Migration = object : Migration(53, 54) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE nearby_room_participants
                ADD COLUMN recipient_encryption_algorithm TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE nearby_room_participants
                ADD COLUMN recipient_encryption_key_id TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE nearby_room_participants
                ADD COLUMN recipient_encryption_public_key BLOB
                """.trimIndent()
            )
        }
    }

    val MIGRATION_54_55: Migration = object : Migration(54, 55) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE nearby_room_messages ADD COLUMN local_render_source_body TEXT")
            db.execSQL(
                """
                ALTER TABLE nearby_room_messages
                ADD COLUMN is_hidden_locally INTEGER NOT NULL DEFAULT 0
                """.trimIndent()
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
        MIGRATION_22_23,
        MIGRATION_23_24,
        MIGRATION_24_25,
        MIGRATION_25_26,
        MIGRATION_26_27,
        MIGRATION_27_28,
        MIGRATION_28_29,
        MIGRATION_29_30,
        MIGRATION_30_31,
        MIGRATION_31_32,
        MIGRATION_32_33,
        MIGRATION_33_34,
        MIGRATION_34_35,
        MIGRATION_35_36,
        MIGRATION_36_37,
        MIGRATION_37_38,
        MIGRATION_38_39,
        MIGRATION_39_40,
        MIGRATION_40_41,
        MIGRATION_41_42,
        MIGRATION_42_43,
        MIGRATION_43_44,
        MIGRATION_44_45,
        MIGRATION_45_46,
        MIGRATION_46_47,
        MIGRATION_47_48,
        MIGRATION_48_49,
        MIGRATION_49_50,
        MIGRATION_50_51,
        MIGRATION_51_52,
        MIGRATION_52_53,
        MIGRATION_53_54,
        MIGRATION_54_55,
    )
}
