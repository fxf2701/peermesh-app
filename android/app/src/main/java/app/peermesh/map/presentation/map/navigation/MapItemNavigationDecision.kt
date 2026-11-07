package app.peermesh.map.presentation.map.navigation

import app.peermesh.map.application.detail.MapItemIdentity

/** Result of evaluating one persisted runtime map-item click. */
sealed interface MapItemNavigationDecision {
    /** The coordinator owns this identity; the Activity may now ask the controller to select it. */
    data class Reserve(
        val identity: MapItemIdentity,
    ) : MapItemNavigationDecision

    /** The controller accepted the exact reserved identity; the Activity may launch detail. */
    data class Launch(
        val identity: MapItemIdentity,
    ) : MapItemNavigationDecision

    data class Blocked(
        val reason: MapItemNavigationBlockReason,
    ) : MapItemNavigationDecision

    data object IgnoreDuplicate : MapItemNavigationDecision
}
