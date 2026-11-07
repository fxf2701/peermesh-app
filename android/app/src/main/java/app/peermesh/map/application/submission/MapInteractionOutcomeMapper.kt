package app.peermesh.map.application.submission

import app.peermesh.data.map.local.LocalMapWriteOutcome
import app.peermesh.data.map.processing.MapEventRejectionReason

interface MapInteractionOutcomeMapper {
    fun mapWriteOutcome(outcome: LocalMapWriteOutcome): MapInteractionSubmissionResult

    fun mapAcceptedDelivery(
        accepted: MapInteractionSubmissionResult.Accepted,
        transport: MapInteractionPostCommitResult,
    ): MapInteractionSubmissionResult.Accepted
}

class DefaultMapInteractionOutcomeMapper : MapInteractionOutcomeMapper {
    override fun mapWriteOutcome(outcome: LocalMapWriteOutcome): MapInteractionSubmissionResult = when (outcome) {
        is LocalMapWriteOutcome.AcceptedNew -> MapInteractionSubmissionResult.Accepted(outcome.eventId)
        is LocalMapWriteOutcome.AcceptedNoChange -> MapInteractionSubmissionResult.AlreadyRecorded(outcome.eventId)
        is LocalMapWriteOutcome.ExactDuplicate -> MapInteractionSubmissionResult.AlreadyRecorded(outcome.eventId)
        is LocalMapWriteOutcome.ConflictingDuplicate -> MapInteractionSubmissionResult.Failed(
            outcome.eventId,
            MapInteractionSubmissionFailure.CONFLICTING_DUPLICATE,
        )
        is LocalMapWriteOutcome.SemanticRejected -> mapRejection(outcome)
        is LocalMapWriteOutcome.InvalidPersistedState -> MapInteractionSubmissionResult.Failed(
            outcome.eventId,
            MapInteractionSubmissionFailure.INVALID_PERSISTED_STATE,
        )
        is LocalMapWriteOutcome.PersistenceFailure -> MapInteractionSubmissionResult.Failed(
            outcome.eventId,
            MapInteractionSubmissionFailure.PERSISTENCE_FAILURE,
        )
    }

    override fun mapAcceptedDelivery(
        accepted: MapInteractionSubmissionResult.Accepted,
        transport: MapInteractionPostCommitResult,
    ): MapInteractionSubmissionResult.Accepted = accepted.copy(
        deliveryWarning = when (transport) {
            MapInteractionPostCommitResult.NotEligible,
            MapInteractionPostCommitResult.Enqueued,
            -> null
            MapInteractionPostCommitResult.MissingGroupKey -> MapInteractionDeliveryWarning.MISSING_GROUP_KEY
            MapInteractionPostCommitResult.EnqueueUnavailable -> MapInteractionDeliveryWarning.ENQUEUE_UNAVAILABLE
            MapInteractionPostCommitResult.EnqueueFailed -> MapInteractionDeliveryWarning.ENQUEUE_FAILED
        },
    )

    private fun mapRejection(
        outcome: LocalMapWriteOutcome.SemanticRejected,
    ): MapInteractionSubmissionResult = when (outcome.reason) {
        MapEventRejectionReason.ACTION_PERSISTENCE_INCONSISTENT,
        MapEventRejectionReason.INVALID_LEGACY_CONTRIBUTION,
        MapEventRejectionReason.INVALID_PERSISTED_STATE,
        -> MapInteractionSubmissionResult.Failed(
            outcome.eventId,
            MapInteractionSubmissionFailure.INVALID_PERSISTED_STATE,
        )
        else -> MapInteractionSubmissionResult.Rejected(
            eventId = outcome.eventId,
            reason = rejectionFor(outcome.reason),
            refreshRecommended = refreshFor(outcome.reason),
        )
    }

    private fun rejectionFor(reason: MapEventRejectionReason): MapInteractionRejection = when (reason) {
        MapEventRejectionReason.ACTOR_NOT_MEMBER,
        MapEventRejectionReason.LOCAL_MEMBER_REMOVED,
        -> MapInteractionRejection.NOT_CURRENT_MEMBER
        MapEventRejectionReason.TARGET_NOT_FOUND -> MapInteractionRejection.TARGET_MISSING
        MapEventRejectionReason.TARGET_TYPE_CONFLICT -> MapInteractionRejection.TARGET_TYPE_CONFLICT
        MapEventRejectionReason.TARGET_DELETED -> MapInteractionRejection.TARGET_DELETED
        MapEventRejectionReason.TARGET_CATEGORY_UNRESOLVED -> MapInteractionRejection.CATEGORY_UNRESOLVED
        MapEventRejectionReason.ACTION_NOT_ALLOWED_FOR_CATEGORY -> MapInteractionRejection.ACTION_INCOMPATIBLE
        MapEventRejectionReason.TARGET_EXPIRED -> MapInteractionRejection.TARGET_EXPIRED
        MapEventRejectionReason.INTERACTION_WINDOW_CLOSED -> MapInteractionRejection.INTERACTION_WINDOW_CLOSED
        MapEventRejectionReason.TARGET_RESOLVED -> MapInteractionRejection.TARGET_RESOLVED
        MapEventRejectionReason.LIFECYCLE_REACTIVATION -> MapInteractionRejection.LIFECYCLE_REACTIVATION_FORBIDDEN
        MapEventRejectionReason.INVALID_ACTION_PAYLOAD -> MapInteractionRejection.INVALID_ACTION
        else -> MapInteractionRejection.OTHER
    }

    private fun refreshFor(reason: MapEventRejectionReason): Boolean = when (reason) {
        MapEventRejectionReason.ACTOR_NOT_MEMBER,
        MapEventRejectionReason.LOCAL_MEMBER_REMOVED,
        MapEventRejectionReason.TARGET_NOT_FOUND,
        MapEventRejectionReason.TARGET_TYPE_CONFLICT,
        MapEventRejectionReason.TARGET_DELETED,
        MapEventRejectionReason.TARGET_CATEGORY_UNRESOLVED,
        MapEventRejectionReason.ACTION_NOT_ALLOWED_FOR_CATEGORY,
        MapEventRejectionReason.TARGET_EXPIRED,
        MapEventRejectionReason.INTERACTION_WINDOW_CLOSED,
        MapEventRejectionReason.TARGET_RESOLVED,
        MapEventRejectionReason.LIFECYCLE_REACTIVATION,
        MapEventRejectionReason.STALE_CANONICAL_EVENT,
        MapEventRejectionReason.CONFLICTING_ADD,
        MapEventRejectionReason.OWNER_REQUIRED,
        MapEventRejectionReason.OWNER_EDIT_WINDOW_CLOSED,
        -> true
        else -> false
    }
}
