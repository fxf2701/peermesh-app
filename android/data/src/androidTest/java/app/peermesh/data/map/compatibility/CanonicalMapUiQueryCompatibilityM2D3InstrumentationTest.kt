package app.peermesh.data.map.compatibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies existing canonical DAO/UI-facing queries remain stable without implementing M3 UI.
 *
 */
@RunWith(AndroidJUnit4::class)
class CanonicalMapUiQueryCompatibilityM2D3InstrumentationTest {
    @Test
    fun legacyMarkerRemainsVisibleThroughExistingCanonicalQuery() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m")); assertEquals(listOf("m"), h.db.mapDao().listActiveMarkers("g", 101, 10).map { it.marker_id }) }
    }

    @Test
    fun legacyAreaRemainsVisibleThroughExistingCanonicalQuery() {
        M2D3RoomHarness().use { h -> h.process(h.area("a")); assertEquals(listOf("a"), h.db.mapDao().listActiveAreas("g", 101, 10).map { it.area_id }) }
    }

    @Test
    fun dedicatedHistoryDoesNotChangeCanonicalQueryContract() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m")); val before = h.db.mapDao().listActiveMarkers("g", 101, 10); h.process(h.confirm("c", "m")); assertEquals(before, h.db.mapDao().listActiveMarkers("g", 101, 10)) }
    }

}
