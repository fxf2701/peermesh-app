package app.peermesh.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyMapItemWindowBackfillTest {
    @Test
    fun `no expiry preserves all-null windows`() {
        val result = LegacyMapItemWindowBackfill.derive(createdAtSeconds = 1_000L, expiresAtSeconds = null)
        assertNull(result.windows.expiresAtSeconds)
        assertNull(result.windows.ownerEditUntilSeconds)
        assertNull(result.windows.interactionUntilSeconds)
        assertEquals(MapItemPersistenceValues.TemporalOrigin.LEGACY_NO_EXPIRY, result.temporalOrigin)
    }

    @Test
    fun `finite expiry derives owner edit and interaction windows`() {
        val result = LegacyMapItemWindowBackfill.derive(createdAtSeconds = 1_000L, expiresAtSeconds = 5_000L)
        assertEquals(5_000L, result.windows.expiresAtSeconds)
        assertEquals(3_000L, result.windows.ownerEditUntilSeconds)
        assertEquals(5_000L, result.windows.interactionUntilSeconds)
        assertEquals(MapItemPersistenceValues.TemporalOrigin.LEGACY_TTL_DERIVED, result.temporalOrigin)
    }

    @Test
    fun `minimum owner edit duration is ten minutes`() {
        val result = LegacyMapItemWindowBackfill.derive(1_000L, 1_100L)
        assertEquals(1_600L, result.windows.ownerEditUntilSeconds)
    }

    @Test
    fun `maximum owner edit duration is six hours`() {
        val result = LegacyMapItemWindowBackfill.derive(1_000L, 101_000L)
        assertEquals(22_600L, result.windows.ownerEditUntilSeconds)
    }

    @Test
    fun `expiry equal to creation is unresolved`() {
        assertUnresolved(LegacyMapItemWindowBackfill.derive(1_000L, 1_000L))
    }

    @Test
    fun `expiry before creation is unresolved`() {
        assertUnresolved(LegacyMapItemWindowBackfill.derive(1_000L, 999L))
    }

    @Test
    fun `non-positive creation is unresolved`() {
        assertUnresolved(LegacyMapItemWindowBackfill.derive(0L, 1_000L))
        assertUnresolved(LegacyMapItemWindowBackfill.derive(-1L, 1_000L))
    }

    @Test
    fun `non-positive expiry is unresolved`() {
        assertUnresolved(LegacyMapItemWindowBackfill.derive(1_000L, 0L))
        assertUnresolved(LegacyMapItemWindowBackfill.derive(1_000L, -1L))
    }

    @Test
    fun `owner edit addition overflow is unresolved rather than wrapped`() {
        assertUnresolved(LegacyMapItemWindowBackfill.derive(Long.MAX_VALUE - 1L, Long.MAX_VALUE))
    }

    @Test
    fun `repeated derivation is deterministic`() {
        val first = LegacyMapItemWindowBackfill.derive(10_000L, 20_000L)
        assertEquals(first, LegacyMapItemWindowBackfill.derive(10_000L, 20_000L))
    }

    @Test
    fun `malformed finite expiry becomes unresolved without preserving invalid values`() {
        val result = LegacyMapItemWindowBackfill.derive(createdAtSeconds = 1_000L, expiresAtSeconds = 999L)
        assertEquals(MapItemPersistenceValues.TemporalOrigin.LEGACY_UNRESOLVED, result.temporalOrigin)
        assertNull(result.windows.expiresAtSeconds)
        assertNull(result.windows.ownerEditUntilSeconds)
        assertNull(result.windows.interactionUntilSeconds)
    }


    private fun assertUnresolved(result: LegacyMapItemWindowBackfill.Result) {
        assertEquals(MapItemPersistenceValues.TemporalOrigin.LEGACY_UNRESOLVED, result.temporalOrigin)
        assertNull(result.windows.expiresAtSeconds)
        assertNull(result.windows.ownerEditUntilSeconds)
        assertNull(result.windows.interactionUntilSeconds)
    }
}
