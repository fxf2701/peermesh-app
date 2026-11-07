package app.peermesh.map.application.scene

import app.peermesh.map.application.detail.MapItemIdentity

fun interface LoadMapSceneItems {
    fun load(
        groupId: String,
        identities: Collection<MapItemIdentity>,
    ): MapSceneLoadResult
}
