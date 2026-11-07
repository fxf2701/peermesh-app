package app.peermesh.data.db

import android.content.Context
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

@RunWith(AndroidJUnit4::class)
class NearbyGroupInvitationMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "nearby-group-invitation-migration-test"

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
    fun migrates54To55AdditivelyAndPreservesMessageKeysAndIndices() {
        helper.createDatabase(databaseName, 54).use { db ->
            db.execSQL(
                """
                INSERT INTO nearby_room_messages(
                    room_id, message_id, reply_to, owner_device_id, sender_device_id,
                    sender_display_name, body, created_at_ms, received_at_ms, ttl_expires_at_ms,
                    delivery_state, edited_at_ms, deleted_at_ms
                ) VALUES (
                    'room-1', 'message-1', 'parent-1', 'owner-1', 'sender-1',
                    'Alice', 'unchanged body', 100, 110, 1000, 'RECEIVED', 120, NULL
                )
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            55,
            true,
            DatabaseMigrations.MIGRATION_54_55,
        ).use { db ->
            db.query("SELECT * FROM nearby_room_messages WHERE room_id='room-1' AND message_id='message-1'")
                .use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("unchanged body", cursor.getString(cursor.getColumnIndexOrThrow("body")))
                    assertNull(
                        cursor.getColumnIndexOrThrow("local_render_source_body").let { index ->
                            if (cursor.isNull(index)) null else cursor.getString(index)
                        }
                    )
                    assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("is_hidden_locally")))
                }
            assertEquals(
                mapOf("room_id" to 1, "message_id" to 2),
                primaryKeyPositions(db),
            )
            assertEquals(
                setOf(
                    "index_nearby_room_messages_room_id_created_at_ms",
                    "index_nearby_room_messages_room_id_ttl_expires_at_ms",
                ),
                indexNames(db),
            )
        }
    }

    private fun primaryKeyPositions(db: SupportSQLiteDatabase): Map<String, Int> = buildMap {
        db.query("PRAGMA table_info(nearby_room_messages)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            val pk = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                val position = cursor.getInt(pk)
                if (position > 0) put(cursor.getString(name), position)
            }
        }
    }

    private fun indexNames(db: SupportSQLiteDatabase): Set<String> = buildSet {
        db.query("PRAGMA index_list(nearby_room_messages)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                cursor.getString(name).takeUnless { it.startsWith("sqlite_autoindex") }?.let(::add)
            }
        }
    }
}
