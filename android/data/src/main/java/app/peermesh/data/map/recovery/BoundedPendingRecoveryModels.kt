package app.peermesh.data.map.recovery

import app.peermesh.data.db.MapActionOrderKey

/**
 * Complete keyset cursor for the group-scoped pending scan.
 *
 * The cursor is intentionally the accepted M2B2 action-order tuple. Partial cursors are not
 * representable through this API.
 */
internal data class PendingRecoveryCursor(
    val order: MapActionOrderKey,
)

internal data class BoundedPendingRecoveryRequest(
    val gid: String,
    val limit: Int,
    val after: PendingRecoveryCursor? = null,
)

/** Stable target-scoped failure vocabulary. No Room entities or localized text cross this seam. */
internal enum class PendingRecoveryFailureReason {
    INVALID_PENDING_IDENTITY,
    INVALID_PERSISTED_TARGET_HISTORY,
    PERSISTENCE_INVARIANT_FAILURE,
    INTERNAL_PERSISTENCE_FAILURE,
}

internal data class PendingRecoveryTargetFailure(
    val itemType: String,
    val itemId: String,
    val reason: PendingRecoveryFailureReason,
)

internal sealed interface BoundedPendingRecoveryResult {
    data class Completed(
        val scannedRowCount: Int,
        val discoveredTargetCount: Int,
        val completedTargetCount: Int,
        val skippedMissingTargetCount: Int,
        val failedTargetCount: Int,
        val nextCursor: PendingRecoveryCursor?,
        val failures: List<PendingRecoveryTargetFailure>,
    ) : BoundedPendingRecoveryResult

    data class InvalidRequest(
        val reason: String,
    ) : BoundedPendingRecoveryResult
}
