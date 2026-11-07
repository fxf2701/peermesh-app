package app.peermesh.map.presentation.map.navigation

import app.peermesh.map.application.detail.MapItemIdentity

/**
 * Main-thread-confined Activity-scoped navigation owner.
 *
 * It retains only the exact identity that owns an Android launch boundary. Persisted selection,
 * detail state, and refresh execution remain with their existing owners.
 */
class DefaultMapItemNavigationCoordinator : MapItemNavigationCoordinator {
    private var state: MapItemNavigationState = MapItemNavigationState.Ready

    override fun currentState(): MapItemNavigationState = state

    override fun beginItemClick(
        identity: MapItemIdentity,
        currentGroupId: String,
        creationDraftActive: Boolean,
        activityActive: Boolean,
    ): MapItemNavigationDecision {
        when (state) {
            MapItemNavigationState.Closed ->
                return MapItemNavigationDecision.Blocked(
                    MapItemNavigationBlockReason.NAVIGATION_CLOSED,
                )
            is MapItemNavigationState.Launching,
            is MapItemNavigationState.AwaitingReturn,
            -> return MapItemNavigationDecision.IgnoreDuplicate
            MapItemNavigationState.Ready -> Unit
        }

        val blockReason = when {
            identity.groupId.isBlank() || identity.itemId.isBlank() ->
                MapItemNavigationBlockReason.INVALID_IDENTITY
            identity.groupId != currentGroupId ->
                MapItemNavigationBlockReason.WRONG_GROUP
            !activityActive ->
                MapItemNavigationBlockReason.ACTIVITY_NOT_ACTIVE
            creationDraftActive ->
                MapItemNavigationBlockReason.CREATION_DRAFT_ACTIVE
            else -> null
        }
        if (blockReason != null) return MapItemNavigationDecision.Blocked(blockReason)

        state = MapItemNavigationState.Launching(identity)
        return MapItemNavigationDecision.Reserve(identity)
    }

    override fun markSelectionAccepted(identity: MapItemIdentity): MapItemNavigationDecision {
        return when (val current = state) {
            MapItemNavigationState.Closed -> MapItemNavigationDecision.Blocked(
                MapItemNavigationBlockReason.NAVIGATION_CLOSED,
            )
            is MapItemNavigationState.Launching -> {
                if (current.identity == identity) {
                    MapItemNavigationDecision.Launch(identity)
                } else {
                    MapItemNavigationDecision.IgnoreDuplicate
                }
            }
            MapItemNavigationState.Ready,
            is MapItemNavigationState.AwaitingReturn,
            -> MapItemNavigationDecision.IgnoreDuplicate
        }
    }

    override fun markSelectionFailed(identity: MapItemIdentity) {
        val launching = state as? MapItemNavigationState.Launching ?: return
        if (launching.identity == identity) {
            state = MapItemNavigationState.Ready
        }
    }

    override fun markLaunchSucceeded(identity: MapItemIdentity) {
        val launching = state as? MapItemNavigationState.Launching ?: return
        if (launching.identity == identity) {
            state = MapItemNavigationState.AwaitingReturn(identity)
        }
    }

    override fun markLaunchFailed(identity: MapItemIdentity) {
        val launching = state as? MapItemNavigationState.Launching ?: return
        if (launching.identity == identity) {
            state = MapItemNavigationState.Ready
        }
    }

    override fun consumeReturnRefreshRequest(): Boolean {
        if (state !is MapItemNavigationState.AwaitingReturn) return false
        state = MapItemNavigationState.Ready
        return true
    }

    override fun close() {
        state = MapItemNavigationState.Closed
    }
}

object DefaultMapItemNavigationCoordinatorFactory : MapItemNavigationCoordinatorFactory {
    override fun create(): MapItemNavigationCoordinator = DefaultMapItemNavigationCoordinator()
}
