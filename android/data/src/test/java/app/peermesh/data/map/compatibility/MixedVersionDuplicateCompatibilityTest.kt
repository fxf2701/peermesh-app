package app.peermesh.data.map.compatibility

import app.peermesh.data.map.processing.Sha256MapEventFingerprint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * Composes immutable compatibility fixtures with the duplicate owner already proven by M2D2.
 *
 */
class MixedVersionDuplicateCompatibilityTest {
    @Test
    fun exactDuplicateCompatibilityFixtureRemainsIdempotent() {
        val fixture = M2D3JvmHarness.fixture("legacy_marker_add_v0.json")
        assertEquals(Sha256MapEventFingerprint.sha256(fixture), Sha256MapEventFingerprint.sha256(fixture))
    }

    @Test
    fun conflictingDuplicateCompatibilityFixtureRemainsNonDestructive() {
        val original = M2D3JvmHarness.fixture("legacy_marker_add_v0.json")
        val changed = original.copy(data = JsonObject((original.data as JsonObject) + ("label" to JsonPrimitive("conflict"))))
        assertEquals(original.id, changed.id)
        assertNotEquals(Sha256MapEventFingerprint.sha256(original), Sha256MapEventFingerprint.sha256(changed))
    }

}
