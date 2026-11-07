package app.peermesh.map.application.rendering

import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.scene.MapSceneItem

data class GroupMapState(
    val acceptedRequestId: Long?,
    val activeRequestId: Long?,
    val content: GroupMapContentState,
    val operation: GroupMapOperationState,
    val selection: GroupMapSelectionState,
    val renderSnapshot: MapRenderSnapshot?,
) {
    companion object {
        fun initial(): GroupMapState = GroupMapState(
            acceptedRequestId = null,
            activeRequestId = null,
            content = GroupMapContentState.Empty,
            operation = GroupMapOperationState.Idle,
            selection = GroupMapSelectionState(),
            renderSnapshot = null,
        )
    }
}

sealed interface GroupMapContentState {
    data object Empty : GroupMapContentState

    data class Accepted(
        val groupId: String,
        val items: Map<MapItemIdentity, MapSceneItem>,
        val itemOutcomes: Map<MapItemIdentity, MapSceneItemOutcome>,
    ) : GroupMapContentState
}

enum class MapSceneItemOutcome {
    FOUND,
    CANONICAL_ONLY,
    UNRESOLVED_LEGACY,
    MISSING,
    INVALID_CANONICAL,
    INVALID_DOMAIN,
}

sealed interface GroupMapOperationState {
    data object Idle : GroupMapOperationState

    data class Loading(
        val requestId: Long,
    ) : GroupMapOperationState

    data class Failed(
        val requestId: Long,
        val failure: GroupMapFailure,
        val retainedContent: Boolean,
    ) : GroupMapOperationState
}

data class GroupMapSelectionState(
    val selectedIdentity: MapItemIdentity? = null,
    val focusedIdentity: MapItemIdentity? = null,
)
