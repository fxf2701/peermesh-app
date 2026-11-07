package app.peermesh.map.presentation.map.navigation

import app.peermesh.map.application.detail.MapItemIdentity

/**
 * Activity-scoped navigation lifecycle for persisted map-item detail.
 *
 * This state is intentionally UI-safe. It must never contain Room entities,
 * semantic snapshots, runtime map artifacts, detail state, timeline state, or
 * creation-draft content.
 */
sealed interface MapItemNavigationState {
    data object Ready : MapItemNavigationState

    data class Launching(
        val identity: MapItemIdentity,
    ) : MapItemNavigationState

    data class AwaitingReturn(
        val identity: MapItemIdentity,
    ) : MapItemNavigationState

    data object Closed : MapItemNavigationState
}
