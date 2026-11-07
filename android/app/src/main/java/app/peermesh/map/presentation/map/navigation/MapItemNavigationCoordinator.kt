package app.peermesh.map.presentation.map.navigation

import app.peermesh.map.application.detail.MapItemIdentity

/**
 * Narrow Activity-scoped owner for duplicate-launch and detail-return refresh orchestration.
 *
 * Locked M3D4 boundaries:
 * - [app.peermesh.map.application.rendering.GroupMapController] remains selection authority.
 * - Creation-draft state remains owned by MapCreationController.
 * - Android navigation remains owned by GroupMapActivity.
 * - This coordinator performs no Room, repository, processor, reducer, or SDK work.
 * - Calls are expected to be main-thread confined by the Activity integration.
 */
interface MapItemNavigationCoordinator : AutoCloseable {
    fun currentState(): MapItemNavigationState

    /**
     * Validates and reserves an exact identity before the Activity mutates persisted selection.
     * A [MapItemNavigationDecision.Reserve] result owns the identity until selection either
     * succeeds, fails, or the coordinator closes.
     */
    fun beginItemClick(
        identity: MapItemIdentity,
        currentGroupId: String,
        creationDraftActive: Boolean,
        activityActive: Boolean,
    ): MapItemNavigationDecision

    /**
     * Completes an owned selection reservation. Only [MapItemNavigationDecision.Launch] permits
     * the Activity to cross the Android detail-launch boundary.
     */
    fun markSelectionAccepted(identity: MapItemIdentity): MapItemNavigationDecision

    /** Releases an owned selection reservation without launching detail. */
    fun markSelectionFailed(identity: MapItemIdentity)

    /** Called only after Android accepted the detail launch request. */
    fun markLaunchSucceeded(identity: MapItemIdentity)

    /** Called after an ordinary Android launch [Exception]. Fatal [Error] must propagate. */
    fun markLaunchFailed(identity: MapItemIdentity)

    /**
     * Returns true once after a successful detail launch has returned to the map.
     * A true result requests a persisted-authority semantic refresh; it carries no detail state.
     */
    fun consumeReturnRefreshRequest(): Boolean

    override fun close()
}

/** Composition boundary. The implementation is intentionally deferred to the M3D4 Codex task. */
fun interface MapItemNavigationCoordinatorFactory {
    fun create(): MapItemNavigationCoordinator
}
