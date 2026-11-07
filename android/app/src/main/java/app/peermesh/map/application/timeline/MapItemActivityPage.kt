package app.peermesh.map.application.timeline

/** Public cursor marker. M2 keyset fields remain hidden behind an internal implementation. */
interface MapItemActivityCursor

data class MapItemActivityPage(
    val entries: List<MapItemActivityEntry>,
    val nextCursor: MapItemActivityCursor?,
    val requestedLimit: Int,
) {
    init {
        require(requestedLimit > 0) { "requestedLimit must be positive" }
    }

    /** Advisory only. M3B2 owns final end-of-history state. */
    val mayHaveMore: Boolean
        get() = entries.size == requestedLimit && nextCursor != null
}
