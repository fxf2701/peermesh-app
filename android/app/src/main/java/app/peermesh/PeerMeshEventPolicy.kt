package app.peermesh

import app.peermesh.core.types.EventTypes

object PeerMeshEventPolicy {
    fun canGenerateLocal(type: String): Boolean {
        return when {
            EventTypes.isChat(type) -> true
            EventTypes.isSys(type) -> true
            EventTypes.isMap(type) -> PeerMeshCapabilities.canUseMap
            EventTypes.isLiveTrail(type) -> PeerMeshCapabilities.canUseSharedLiveTrail
            else -> PeerMeshCapabilities.hasPremiumFeatureAccess
        }
    }

    fun canSendOutbound(type: String): Boolean = canGenerateLocal(type)

    fun canPushViaRelay(type: String): Boolean {
        return PeerMeshCapabilities.canUseRelaySync && canGenerateLocal(type)
    }

    fun deniedReason(type: String): String {
        return when {
            EventTypes.isMap(type) -> "map_requires_pro"
            EventTypes.isLiveTrail(type) -> "live_trail_requires_pro"
            !EventTypes.isChat(type) && !EventTypes.isSys(type) -> "event_type_requires_pro"
            else -> "allowed"
        }
    }
}
