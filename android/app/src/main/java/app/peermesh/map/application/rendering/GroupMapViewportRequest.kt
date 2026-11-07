package app.peermesh.map.application.rendering

import app.peermesh.map.application.detail.MapItemIdentity

data class GroupMapViewportRequest(
    val groupId: String,
    val identities: Set<MapItemIdentity>,
    val environment: MapRenderEnvironment,
)
