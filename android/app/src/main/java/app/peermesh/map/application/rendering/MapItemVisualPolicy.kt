package app.peermesh.map.application.rendering

import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.scene.MapSceneItem

fun interface MapItemVisualPolicy {
    fun evaluate(
        item: MapSceneItem,
        emphasis: MapItemEmphasis,
        environment: MapRenderEnvironment,
        itemOutcome: MapSceneItemOutcome,
    ): MapItemVisualPolicyResult
}

sealed interface MapItemVisualPolicyResult {
    data class Renderable(
        val spec: MapItemRenderSpec,
    ) : MapItemVisualPolicyResult

    data class Unsupported(
        val identity: MapItemIdentity,
        val reason: MapVisualPolicyFailure,
    ) : MapItemVisualPolicyResult
}

enum class MapVisualPolicyFailure {
    IDENTITY_GEOMETRY_MISMATCH,
    INVALID_CATEGORY_STATE,
    INVALID_SITUATION_LIFECYCLE,
    COORDINATION_WITH_SITUATION_LIFECYCLE,
    INVALID_VERIFICATION_COMBINATION,
    INVALID_PARTICIPATION_COMBINATION,
    INVALID_TEMPORAL_COMBINATION,
    INVALID_ITEM_OUTCOME,
}
