package app.peermesh.data.map.recovery

import app.peermesh.data.db.MapActionOrderKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedPendingRecoveryOrderCursorTest {
    @Test fun completeComparatorResolvesEveryTieLevel() {
        val unordered = listOf(
            key(2, 0, "a", "a"),
            key(1, 2, "a", "a"),
            key(1, 1, "b", "a"),
            key(1, 1, "a", "b"),
            key(1, 1, "a", "a"),
        )
        assertEquals(listOf(
            key(1, 1, "a", "a"),
            key(1, 1, "a", "b"),
            key(1, 1, "b", "a"),
            key(1, 2, "a", "a"),
            key(2, 0, "a", "a"),
        ), unordered.sortedWith(MapActionOrderKey.ASCENDING))
    }

    @Test fun completeKeysetPagesHaveNoDuplicateOrSkippedRows() {
        val ordered = listOf(
            key(1, 0, "a", "a"), key(1, 0, "a", "b"),
            key(1, 0, "b", "a"), key(1, 1, "a", "a"), key(2, 0, "a", "a"),
        )
        var cursor: MapActionOrderKey? = null
        val scanned = mutableListOf<MapActionOrderKey>()
        do {
            val after = cursor
            val page = ordered.filter { after == null || it > after }.take(2)
            scanned += page
            cursor = page.lastOrNull()
        } while (page.isNotEmpty())
        assertEquals(ordered, scanned)
        assertEquals(scanned.size, scanned.toSet().size)
    }

    @Test fun recoveryCursorContainsCompleteAcceptedOrder() {
        val order = key(10, 20, "did-peer", "event-id")
        assertEquals(order, PendingRecoveryCursor(order).order)
        assertTrue(order.actorDid.isNotBlank() && order.eventId.isNotBlank())
    }

    private fun key(t: Long, lt: Long, actor: String, event: String) =
        MapActionOrderKey(t, lt, actor, event)
}
