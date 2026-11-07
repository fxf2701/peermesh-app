package app.peermesh.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingCanonicalMapEventMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "pending-canonical-map-event-migration-test"

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
    fun migrates52To53AndCreatesEmptyPendingCanonicalInbox() {
        helper.createDatabase(databaseName, 52).close()

        helper.runMigrationsAndValidate(
            databaseName,
            53,
            true,
            DatabaseMigrations.MIGRATION_52_53,
        ).use { db ->
            db.query("SELECT COUNT(*) FROM pending_canonical_map_events").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }
}
