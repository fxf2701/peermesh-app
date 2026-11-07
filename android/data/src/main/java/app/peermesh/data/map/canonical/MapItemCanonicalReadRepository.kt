package app.peermesh.data.map.canonical

import app.peermesh.data.map.MapItemIdentity

interface MapItemCanonicalReadRepository {
    fun find(groupId: String, identity: MapItemIdentity): MapItemCanonicalLookupResult
}
