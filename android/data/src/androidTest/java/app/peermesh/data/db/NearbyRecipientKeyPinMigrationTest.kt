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
class NearbyRecipientKeyPinMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "nearby-recipient-key-pin-migration-test"

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
    fun migrates53To54PreservingParticipantWithNullPinTuple() {
        helper.createDatabase(databaseName, 53).use(::seedParticipant)

        helper.runMigrationsAndValidate(
            databaseName,
            54,
            true,
            DatabaseMigrations.MIGRATION_53_54,
        ).use { db ->
            db.query(
                """
                SELECT room_id, device_id, display_name, avatar_id, profile_updated_at_ms,
                       joined_at_ms, last_seen_at_ms, role, status, invitation_blocked,
                       recipient_encryption_algorithm,
                       recipient_encryption_key_id,
                       recipient_encryption_public_key
                FROM nearby_room_participants
                WHERE room_id = 'room-1' AND device_id = 'device-1'
                """.trimIndent()
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("room-1", cursor.getString(0))
                assertEquals("device-1", cursor.getString(1))
                assertEquals("Alice", cursor.getString(2))
                assertEquals("pet_cat", cursor.getString(3))
                assertEquals(123L, cursor.getLong(4))
                assertEquals(100L, cursor.getLong(5))
                assertEquals(200L, cursor.getLong(6))
                assertEquals("PARTICIPANT", cursor.getString(7))
                assertEquals("ACTIVE", cursor.getString(8))
                assertEquals(0, cursor.getInt(9))
                assertNull(if (cursor.isNull(10)) null else cursor.getString(10))
                assertNull(if (cursor.isNull(11)) null else cursor.getString(11))
                assertNull(if (cursor.isNull(12)) null else cursor.getBlob(12))
            }
            assertCompositeParticipantPrimaryKey(db)
        }
    }

    private fun assertCompositeParticipantPrimaryKey(db: SupportSQLiteDatabase) {
        val primaryKeyPositions = mutableMapOf<String, Int>()
        db.query("PRAGMA table_info(nearby_room_participants)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                val position = cursor.getInt(primaryKeyIndex)
                if (position > 0) primaryKeyPositions[cursor.getString(nameIndex)] = position
            }
        }
        assertEquals(mapOf("room_id" to 1, "device_id" to 2), primaryKeyPositions)
    }

    private fun seedParticipant(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO nearby_room_participants(
                room_id, device_id, display_name, avatar_id, profile_updated_at_ms,
                joined_at_ms, last_seen_at_ms, role, status, invitation_blocked
            ) VALUES ('room-1', 'device-1', 'Alice', 'pet_cat', 123, 100, 200, 'PARTICIPANT', 'ACTIVE', 0)
            """.trimIndent()
        )
    }
}
