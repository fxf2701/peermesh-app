package app.peermesh

sealed interface NearbyTransportHealth {
    data object Blocked : NearbyTransportHealth
    data object Starting : NearbyTransportHealth
    data object Healthy : NearbyTransportHealth
    data object Stalled : NearbyTransportHealth
}

data class NearbyTransportSnapshot(
    val systemReady: Boolean,
    val runtimeRunning: Boolean,
    val unresolvedStall: Boolean,
)

object NearbyTransportHealthEvaluator {
    fun evaluate(snapshot: NearbyTransportSnapshot): NearbyTransportHealth = when {
        !snapshot.systemReady -> NearbyTransportHealth.Blocked
        snapshot.unresolvedStall -> NearbyTransportHealth.Stalled
        !snapshot.runtimeRunning -> NearbyTransportHealth.Starting
        else -> NearbyTransportHealth.Healthy
    }
}

sealed interface OnlinePathState {
    data object Unavailable : OnlinePathState
    data object AvailableUnverified : OnlinePathState
    data object Healthy : OnlinePathState
}

data class OnlinePathSnapshot(
    val capabilityAvailable: Boolean,
    val syncEnabled: Boolean,
    val internetValidated: Boolean,
    val pathConfigured: Boolean,
    val hasRecentSuccess: Boolean,
)

object OnlinePathEvaluator {
    fun evaluate(snapshot: OnlinePathSnapshot): OnlinePathState = when {
        !snapshot.capabilityAvailable || !snapshot.syncEnabled ||
            !snapshot.internetValidated || !snapshot.pathConfigured ->
            OnlinePathState.Unavailable
        snapshot.hasRecentSuccess -> OnlinePathState.Healthy
        else -> OnlinePathState.AvailableUnverified
    }
}

sealed interface GroupChatConnectivityState {
    data class OnlineAndNearby(
        val nearbyPeerCount: Int,
        val onlinePathVerified: Boolean,
    ) : GroupChatConnectivityState

    data class OnlineOnly(
        val onlinePathVerified: Boolean,
        val nearbyHealth: NearbyTransportHealth,
    ) : GroupChatConnectivityState

    data class NearbyOnly(
        val nearbyPeerCount: Int,
    ) : GroupChatConnectivityState

    data object NoActivePath : GroupChatConnectivityState
    data class Degraded(val reason: GroupChatConnectivityReason) : GroupChatConnectivityState
}

enum class GroupChatConnectivityReason {
    NearbyStarting,
    NearbyStalled,
}

object GroupChatConnectivityEvaluator {
    fun evaluate(
        onlinePath: OnlinePathState,
        nearbyHealth: NearbyTransportHealth,
        nearbyPeerCount: Int,
    ): GroupChatConnectivityState {
        val onlineAvailable = onlinePath != OnlinePathState.Unavailable
        return when {
            onlineAvailable && nearbyHealth == NearbyTransportHealth.Healthy && nearbyPeerCount > 0 ->
                GroupChatConnectivityState.OnlineAndNearby(
                    nearbyPeerCount = nearbyPeerCount,
                    onlinePathVerified = onlinePath == OnlinePathState.Healthy,
                )
            onlineAvailable && nearbyHealth == NearbyTransportHealth.Stalled ->
                GroupChatConnectivityState.Degraded(GroupChatConnectivityReason.NearbyStalled)
            onlineAvailable -> GroupChatConnectivityState.OnlineOnly(
                onlinePathVerified = onlinePath == OnlinePathState.Healthy,
                nearbyHealth = nearbyHealth,
            )
            nearbyHealth == NearbyTransportHealth.Healthy ->
                GroupChatConnectivityState.NearbyOnly(nearbyPeerCount.coerceAtLeast(0))
            nearbyHealth == NearbyTransportHealth.Stalled ->
                GroupChatConnectivityState.Degraded(GroupChatConnectivityReason.NearbyStalled)
            nearbyHealth == NearbyTransportHealth.Starting ->
                GroupChatConnectivityState.Degraded(GroupChatConnectivityReason.NearbyStarting)
            else -> GroupChatConnectivityState.NoActivePath
        }
    }
}
