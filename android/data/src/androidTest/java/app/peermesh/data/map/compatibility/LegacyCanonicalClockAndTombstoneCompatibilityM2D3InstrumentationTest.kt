package app.peermesh.data.map.compatibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.factory.EventFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves compatibility ingest preserves canonical clocks and tombstone semantics.
 *
 */
@RunWith(AndroidJUnit4::class)
class LegacyCanonicalClockAndTombstoneCompatibilityM2D3InstrumentationTest {
    @Test
    fun legacyMarkerClocksAndTombstoneRemainCanonical() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m")); h.process(EventFactory.mapMarkerDelete("del", "owner", 120, 9, "m")); val row = h.db.mapDao().findMarker("g", "m")!!; assertEquals(120, row.last_event_t); assertEquals(9, row.last_event_lt); assertEquals("deleted", row.status) }
    }

    @Test
    fun legacyAreaClocksAndTombstoneRemainCanonical() {
        M2D3RoomHarness().use { h -> h.process(h.area("a")); h.process(EventFactory.mapAreaDelete("del", "owner", 120, 9, "a")); val row = h.db.mapDao().findArea("g", "a")!!; assertEquals(120, row.last_event_t); assertEquals(9, row.last_event_lt); assertEquals("deleted", row.status) }
    }

    @Test
    fun legacyTombstoneIsNotReinterpretedAsSituationLifecycle() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m")); h.process(EventFactory.mapMarkerDelete("del", "owner", 120, 9, "m")); assertEquals("active", h.db.mapItemDomainStateDao().find("g", "marker", "m")!!.situation_lifecycle) }
    }

}
