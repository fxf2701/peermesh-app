package app.peermesh.data.reducer

import app.peermesh.data.map.processing.DedicatedMapActionContext
import app.peermesh.data.map.processing.MapEventRejectionReason

/**
 * Dedicated-action reducer boundary.
 *
 * RoomMapEventProcessor owns the transaction and common DB-aware checks. M2C2A expands this
 * boundary to immutable action insertion and duplicate classification only. M2C2B owns projection.
 */
internal interface DedicatedMapActionReducer {
    fun apply(context: DedicatedMapActionContext): DedicatedMapActionReductionResult
}

internal sealed interface DedicatedMapActionReductionResult {
    /** Accepted immutable action insertion winner. */
    data object AppliedInserted : DedicatedMapActionReductionResult

    /** Historically valid applied action that must not replace newer projection. */
    data object HistoricalAppliedInserted : DedicatedMapActionReductionResult

    data object ExactDuplicate : DedicatedMapActionReductionResult
    data object ConflictingDuplicate : DedicatedMapActionReductionResult

    data class Rejected(
        val reason: MapEventRejectionReason,
    ) : DedicatedMapActionReductionResult

    data class InvalidPersistedState(
        val detail: String,
    ) : DedicatedMapActionReductionResult

    /** Temporary compatibility outcome until M2C2A wiring is implemented. */
    data object DeferredToM2C2 : DedicatedMapActionReductionResult
}
