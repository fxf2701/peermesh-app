package app.peermesh.data.map.compatibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies M2D3 introduces no migration or schema version change.
 *
 */
@RunWith(AndroidJUnit4::class)
class RoomSchema53M2D3ContractTest {
    @Test
    fun appDatabaseUsesCurrentVersion() {
        M2D3RoomHarness().use { assertEquals(54, it.db.openHelper.readableDatabase.version) }
    }

    @Test
    fun noM2D3SchemaOrMigrationIsRequired() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m")); h.process(h.confirm("c", "m")); assertEquals(54, h.db.openHelper.writableDatabase.version) }
    }

}
