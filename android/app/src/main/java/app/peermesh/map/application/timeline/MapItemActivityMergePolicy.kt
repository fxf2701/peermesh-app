package app.peermesh.map.application.timeline

/**
 * Pure collection policy for M3B2 timeline paging.
 *
 * The loader already returns deterministic newest-first pages. Implementations must preserve that
 * order, must not globally sort, and may deduplicate only by [MapItemActivityEntry.entryId].
 */
interface MapItemActivityMergePolicy {
    /** Prepends a refreshed first page while retaining already loaded older entries. */
    fun mergeRefreshedHead(
        existing: List<MapItemActivityEntry>,
        refreshedPage: List<MapItemActivityEntry>,
    ): MapItemActivityMergeResult

    /** Appends an older keyset page after the current visible collection. */
    fun mergeOlderPage(
        existing: List<MapItemActivityEntry>,
        olderPage: List<MapItemActivityEntry>,
    ): MapItemActivityMergeResult
}

sealed interface MapItemActivityMergeResult {
    data class Merged(val entries: List<MapItemActivityEntry>) : MapItemActivityMergeResult

    /** The same stable entry identity was observed with conflicting projected content. */
    data class ConflictingEntry(val entryId: String) : MapItemActivityMergeResult
}

class DefaultMapItemActivityMergePolicy : MapItemActivityMergePolicy {
    override fun mergeRefreshedHead(
        existing: List<MapItemActivityEntry>,
        refreshedPage: List<MapItemActivityEntry>,
    ): MapItemActivityMergeResult = merge(primary = refreshedPage, secondary = existing)

    override fun mergeOlderPage(
        existing: List<MapItemActivityEntry>,
        olderPage: List<MapItemActivityEntry>,
    ): MapItemActivityMergeResult = merge(primary = existing, secondary = olderPage)

    private fun merge(
        primary: List<MapItemActivityEntry>,
        secondary: List<MapItemActivityEntry>,
    ): MapItemActivityMergeResult {
        val entriesById = LinkedHashMap<String, MapItemActivityEntry>(primary.size + secondary.size)
        for (entry in primary) {
            val conflict = entriesById.addOrValidate(entry)
            if (conflict != null) return conflict
        }
        for (entry in secondary) {
            val conflict = entriesById.addOrValidate(entry)
            if (conflict != null) return conflict
        }
        return MapItemActivityMergeResult.Merged(entriesById.values.toList())
    }

    private fun MutableMap<String, MapItemActivityEntry>.addOrValidate(
        entry: MapItemActivityEntry,
    ): MapItemActivityMergeResult.ConflictingEntry? {
        val existing = this[entry.entryId]
        if (existing == null) {
            this[entry.entryId] = entry
            return null
        }
        return if (existing == entry) null else MapItemActivityMergeResult.ConflictingEntry(entry.entryId)
    }
}
