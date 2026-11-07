package app.peermesh.map.application.timeline

import app.peermesh.core.map.MapItemType
import app.peermesh.map.application.detail.MapItemIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMapItemActivityMergePolicyM3B2Test {
    private val policy = DefaultMapItemActivityMergePolicy()

    @Test fun refreshPrependsUniqueHeadPreservesOlderOrderAndSuppressesIdenticalOverlap() {
        val existing = listOf(entry("e3", 300), entry("e2", 200), entry("e1", 100))
        val refreshed = listOf(entry("e5", 50), entry("e4", 900), existing[0], existing[1])

        val merged = policy.mergeRefreshedHead(existing, refreshed) as MapItemActivityMergeResult.Merged

        assertEquals(listOf("e5", "e4", "e3", "e2", "e1"), merged.entries.map { it.entryId })
        assertEquals(listOf("e3", "e2", "e1"), existing.map { it.entryId })
        assertEquals(listOf("e5", "e4", "e3", "e2"), refreshed.map { it.entryId })
        assertNotSame(existing, merged.entries)
    }

    @Test fun appendKeepsCurrentFirstAddsUniqueOlderRowsAndNeverSortsByTimestamp() {
        val existing = listOf(entry("new", 10), entry("middle", 900))
        val older = listOf(existing[1], entry("old-a", 800), entry("old-b", 1_000))

        val merged = policy.mergeOlderPage(existing, older) as MapItemActivityMergeResult.Merged

        assertEquals(listOf("new", "middle", "old-a", "old-b"), merged.entries.map { it.entryId })
        assertEquals(listOf("middle", "old-a", "old-b"), older.map { it.entryId })
    }

    @Test fun identicalDuplicatesWithinOnePageAreIdempotent() {
        val duplicate = entry("same", 100)
        val refresh = policy.mergeRefreshedHead(emptyList(), listOf(duplicate, duplicate))
            as MapItemActivityMergeResult.Merged
        val append = policy.mergeOlderPage(emptyList(), listOf(duplicate, duplicate))
            as MapItemActivityMergeResult.Merged

        assertEquals(listOf(duplicate), refresh.entries)
        assertEquals(listOf(duplicate), append.entries)
    }

    @Test fun conflictingIdIsRejectedAcrossAndWithinPages() {
        val original = entry("same", 100)
        val conflict = entry("same", 101)

        listOf(
            policy.mergeRefreshedHead(listOf(original), listOf(conflict)),
            policy.mergeOlderPage(listOf(original), listOf(conflict)),
            policy.mergeRefreshedHead(emptyList(), listOf(original, conflict)),
            policy.mergeOlderPage(emptyList(), listOf(original, conflict)),
        ).forEach { result ->
            assertTrue(result is MapItemActivityMergeResult.ConflictingEntry)
            assertEquals("same", (result as MapItemActivityMergeResult.ConflictingEntry).entryId)
        }
    }

    private fun entry(id: String, timestamp: Long) = MapItemActivityEntry(
        entryId = id,
        itemIdentity = IDENTITY,
        type = MapItemActivityType.SITUATION_CONFIRMED,
        actor = MapItemActivityActor("did:$id", "did:$id"),
        actionTimestampSeconds = timestamp,
        informationSource = null,
        note = null,
        updateDisposition = null,
        targetLifecycle = null,
    )

    private companion object {
        val IDENTITY = MapItemIdentity("group", MapItemType.MARKER, "item")
    }
}
