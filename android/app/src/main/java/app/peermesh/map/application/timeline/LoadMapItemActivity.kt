package app.peermesh.map.application.timeline

import app.peermesh.map.application.detail.MapItemIdentity

interface LoadMapItemActivity {
    fun load(
        identity: MapItemIdentity,
        after: MapItemActivityCursor? = null,
        limit: Int,
    ): MapItemActivityLoadResult
}
