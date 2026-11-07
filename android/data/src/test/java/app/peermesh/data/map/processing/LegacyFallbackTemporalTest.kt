package app.peermesh.data.map.processing

import app.peermesh.data.db.LegacyMapItemWindowBackfill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyFallbackTemporalTest {
    @Test fun markerReplayUsesCanonicalCreationNotIncomingTimestamp() {
        val first = LegacyMapItemWindowBackfill.derive(1_000L, 5_000L)
        val replayedUpd = LegacyMapItemWindowBackfill.derive(1_000L, 5_000L)
        assertEquals(first, replayedUpd)
        assertEquals(3_000L, first.windows.ownerEditUntilSeconds)
    }

    @Test fun areaReplayUsesCanonicalCreationNotIncomingTimestamp() {
        val first = LegacyMapItemWindowBackfill.derive(10_000L, 20_000L)
        val replayedDel = LegacyMapItemWindowBackfill.derive(10_000L, 20_000L)
        assertEquals(first.windows, replayedDel.windows)
        assertEquals(20_000L, replayedDel.windows.interactionUntilSeconds)
    }

    @Test fun noExpiryLegacyFallbackKeepsAllWindowsNull() {
        val result = LegacyMapItemWindowBackfill.derive(10_000L, null)
        assertNull(result.windows.expiresAtSeconds)
        assertNull(result.windows.ownerEditUntilSeconds)
        assertNull(result.windows.interactionUntilSeconds)
    }
}
