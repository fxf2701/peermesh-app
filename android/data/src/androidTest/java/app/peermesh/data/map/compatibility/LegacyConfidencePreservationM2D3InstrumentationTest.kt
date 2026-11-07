package app.peermesh.data.map.compatibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves live legacy ingest preserves confidence while creator source stays unspecified.
 *
 */
@RunWith(AndroidJUnit4::class)
class LegacyConfidencePreservationM2D3InstrumentationTest {
    @Test
    fun legacyMarkerConfidenceAndOwnerConfidenceRemainUnchanged() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m", confidence = 2)); val row = h.db.mapDao().findMarker("g", "m")!!; assertEquals(2, row.confidence); assertEquals("owner", row.actor_did) }
    }

    @Test
    fun legacyAreaConfidenceAndOwnerConfidenceRemainUnchanged() {
        M2D3RoomHarness().use { h -> h.process(h.area("a", confidence = 0)); val row = h.db.mapDao().findArea("g", "a")!!; assertEquals(0, row.confidence); assertEquals("owner", row.actor_did) }
    }

    @Test
    fun legacyConfidenceIsNotConvertedIntoInformationSource() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m", confidence = 2)); assertNull(h.db.mapItemDomainStateDao().find("g", "marker", "m")!!.creator_information_source) }
    }

}
