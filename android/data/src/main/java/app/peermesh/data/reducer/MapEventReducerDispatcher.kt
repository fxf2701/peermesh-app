package app.peermesh.data.reducer

import app.peermesh.core.model.EventCore
import app.peermesh.core.types.EventTypes
import app.peermesh.data.map.processing.CanonicalMapEventPolicyDecision
import app.peermesh.data.map.processing.DedicatedMapActionContext

/** Responsibility-only dispatcher; it owns no transaction and performs no persistence itself. */
internal class MapEventReducerDispatcher(
    private val canonicalReducer: CanonicalMapEventReducer,
    private val actionReducer: DedicatedMapActionReducer,
) {
    fun route(
        gid: String,
        event: EventCore,
        canonicalDecision: CanonicalMapEventPolicyDecision?,
        actionContext: DedicatedMapActionContext? = null,
    ): MapEventReductionDispatchResult = when (event.type) {
        EventTypes.MAP_MARKER_ADD,
        EventTypes.MAP_MARKER_UPD,
        EventTypes.MAP_MARKER_DEL,
        EventTypes.MAP_AREA_ADD,
        EventTypes.MAP_AREA_UPD,
        EventTypes.MAP_AREA_DEL,
        -> {
            val decision = requireNotNull(canonicalDecision) {
                "Canonical map events require a policy decision"
            }
            MapEventReductionDispatchResult.Canonical(canonicalReducer.apply(gid, event, decision))
        }
        EventTypes.MAP_ITEM_CONFIRM,
        EventTypes.MAP_ITEM_SITUATION_UPDATE,
        EventTypes.MAP_ITEM_SITUATION_STATUS,
        EventTypes.MAP_ITEM_ACKNOWLEDGE,
        EventTypes.MAP_ITEM_NOTE,
        -> MapEventReductionDispatchResult.DedicatedAction(actionReducer.apply(requireNotNull(actionContext)))
        else -> MapEventReductionDispatchResult.Unsupported
    }
}

internal sealed interface MapEventReductionDispatchResult {
    data class Canonical(val result: CanonicalMapReductionResult) : MapEventReductionDispatchResult
    data class DedicatedAction(val result: DedicatedMapActionReductionResult) : MapEventReductionDispatchResult
    data object Unsupported : MapEventReductionDispatchResult
}
