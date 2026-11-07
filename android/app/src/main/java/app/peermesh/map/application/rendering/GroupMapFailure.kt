package app.peermesh.map.application.rendering

import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.scene.MapSceneInvalidRequestReason
import app.peermesh.map.application.scene.MapSceneLoadFailure

sealed interface GroupMapFailure {
    data class InvalidRequest(
        val reason: MapSceneInvalidRequestReason,
    ) : GroupMapFailure

    data class ReadFailure(
        val reason: MapSceneLoadFailure,
    ) : GroupMapFailure

    data class VisualPolicyFailure(
        val affectedItems: Map<MapItemIdentity, MapVisualPolicyFailure>,
    ) : GroupMapFailure

    data class ExecutionFailure(
        val operation: GroupMapOperation,
    ) : GroupMapFailure
}

enum class GroupMapOperation {
    LOAD_SCENE,
    BUILD_RENDER_SNAPSHOT,
    PUBLISH_STATE,
}
