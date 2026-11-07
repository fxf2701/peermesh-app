package app.peermesh.map.presentation.map

import app.peermesh.core.map.MapSituationLifecycleStatus

/** Mirrors the canonical writer constraints that apply to owner DEL events. */
internal object MapOwnerDeleteAvailabilityPolicy {
    data class State(
        val ownerDid: String,
        val lifecycleWireValue: String?,
        val expiresAtSeconds: Long?,
        val ownerEditUntilSeconds: Long?,
    )

    fun canDelete(
        state: State?,
        currentActorDid: String,
        nowSeconds: Long,
        currentActorRemoved: Boolean,
    ): Boolean {
        if (state == null || currentActorRemoved || state.ownerDid != currentActorDid) return false
        if (state.expiresAtSeconds?.let { nowSeconds >= it } == true) return false
        if (state.ownerEditUntilSeconds?.let { nowSeconds >= it } == true) return false
        return when (MapSituationLifecycleStatus.fromWireValue(state.lifecycleWireValue)) {
            MapSituationLifecycleStatus.NO_LONGER_ACTIVE -> false
            MapSituationLifecycleStatus.ACTIVE, null -> state.lifecycleWireValue == null ||
                state.lifecycleWireValue == MapSituationLifecycleStatus.ACTIVE.wireValue
        }
    }
}
