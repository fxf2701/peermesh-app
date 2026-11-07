package app.peermesh.data.map.canonical

import app.peermesh.data.map.MapItemIdentity

/**
 * True batch canonical-read boundary for map-scene loading.
 *
 * Implementations must not delegate to repeated single-item [MapItemCanonicalReadRepository.find]
 * calls. Results are keyed by the exact data-layer identity (type + item ID).
 */
interface MapItemCanonicalBatchReadRepository {
    fun findBatch(
        groupId: String,
        identities: Collection<MapItemIdentity>,
    ): MapItemCanonicalBatchLookupResult
}

data class MapItemCanonicalBatchLookupResult(
    val found: Map<MapItemIdentity, MapItemCanonicalRead>,
    val missing: Set<MapItemIdentity>,
    val invalid: Set<MapItemIdentity>,
    val readFailure: Boolean = false,
)
