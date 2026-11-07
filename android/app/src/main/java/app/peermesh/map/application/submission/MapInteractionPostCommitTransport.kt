package app.peermesh.map.application.submission

import app.peermesh.core.model.EventCore
import app.peermesh.data.map.local.LocalMapWriteOutcome

/** Application seam over existing post-commit transport; it owns no persistence semantics. */
fun interface MapInteractionPostCommitTransport {
    fun dispatch(
        groupId: String,
        outcome: LocalMapWriteOutcome,
        core: EventCore,
    ): MapInteractionPostCommitResult
}

sealed interface MapInteractionPostCommitResult {
    data object NotEligible : MapInteractionPostCommitResult
    data object Enqueued : MapInteractionPostCommitResult
    data object MissingGroupKey : MapInteractionPostCommitResult
    data object EnqueueUnavailable : MapInteractionPostCommitResult
    data object EnqueueFailed : MapInteractionPostCommitResult
}
