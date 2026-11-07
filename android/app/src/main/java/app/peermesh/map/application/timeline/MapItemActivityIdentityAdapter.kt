package app.peermesh.map.application.timeline

import app.peermesh.map.application.detail.MapItemIdentity

/** Exact adapter retaining group scope at the M3 boundary. */
internal object MapItemActivityIdentityAdapter {
    fun toDataIdentity(identity: MapItemIdentity): app.peermesh.data.map.MapItemIdentity =
        app.peermesh.data.map.MapItemIdentity(identity.itemType, identity.itemId)
}
