package app.peermesh.map.application.submission

import app.peermesh.data.map.local.LocalMapWriteOutcome
import app.peermesh.data.map.processing.MapEventRejectionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapInteractionOutcomeMapperM3A3Test {
    private val mapper = DefaultMapInteractionOutcomeMapper()

    @Test fun `all local write outcomes remain distinct and non-new outcomes are idempotent`() {
        assertEquals(MapInteractionSubmissionResult.Accepted("new"), mapper.mapWriteOutcome(
            LocalMapWriteOutcome.AcceptedNew("new"),
        ))
        assertEquals(MapInteractionSubmissionResult.AlreadyRecorded("no-change"), mapper.mapWriteOutcome(
            LocalMapWriteOutcome.AcceptedNoChange("no-change"),
        ))
        assertEquals(MapInteractionSubmissionResult.AlreadyRecorded("duplicate"), mapper.mapWriteOutcome(
            LocalMapWriteOutcome.ExactDuplicate("duplicate"),
        ))
        assertEquals(
            MapInteractionSubmissionFailure.CONFLICTING_DUPLICATE,
            (mapper.mapWriteOutcome(LocalMapWriteOutcome.ConflictingDuplicate("conflict")) as
                MapInteractionSubmissionResult.Failed).reason,
        )
        assertEquals(
            MapInteractionSubmissionFailure.INVALID_PERSISTED_STATE,
            (mapper.mapWriteOutcome(LocalMapWriteOutcome.InvalidPersistedState("bad", "private")) as
                MapInteractionSubmissionResult.Failed).reason,
        )
        assertEquals(
            MapInteractionSubmissionFailure.PERSISTENCE_FAILURE,
            (mapper.mapWriteOutcome(LocalMapWriteOutcome.PersistenceFailure("failed", IllegalStateException())) as
                MapInteractionSubmissionResult.Failed).reason,
        )
    }

    @Test fun `stable M2 semantic reasons map explicitly`() {
        val expected = mapOf(
            MapEventRejectionReason.ACTOR_NOT_MEMBER to MapInteractionRejection.NOT_CURRENT_MEMBER,
            MapEventRejectionReason.LOCAL_MEMBER_REMOVED to MapInteractionRejection.NOT_CURRENT_MEMBER,
            MapEventRejectionReason.TARGET_NOT_FOUND to MapInteractionRejection.TARGET_MISSING,
            MapEventRejectionReason.TARGET_TYPE_CONFLICT to MapInteractionRejection.TARGET_TYPE_CONFLICT,
            MapEventRejectionReason.TARGET_DELETED to MapInteractionRejection.TARGET_DELETED,
            MapEventRejectionReason.TARGET_CATEGORY_UNRESOLVED to MapInteractionRejection.CATEGORY_UNRESOLVED,
            MapEventRejectionReason.ACTION_NOT_ALLOWED_FOR_CATEGORY to MapInteractionRejection.ACTION_INCOMPATIBLE,
            MapEventRejectionReason.TARGET_EXPIRED to MapInteractionRejection.TARGET_EXPIRED,
            MapEventRejectionReason.INTERACTION_WINDOW_CLOSED to MapInteractionRejection.INTERACTION_WINDOW_CLOSED,
            MapEventRejectionReason.TARGET_RESOLVED to MapInteractionRejection.TARGET_RESOLVED,
            MapEventRejectionReason.LIFECYCLE_REACTIVATION to MapInteractionRejection.LIFECYCLE_REACTIVATION_FORBIDDEN,
            MapEventRejectionReason.INVALID_ACTION_PAYLOAD to MapInteractionRejection.INVALID_ACTION,
        )
        expected.forEach { (reason, applicationReason) ->
            val result = mapper.mapWriteOutcome(LocalMapWriteOutcome.SemanticRejected("event", reason)) as
                MapInteractionSubmissionResult.Rejected
            assertEquals(reason.name, applicationReason, result.reason)
        }
    }

    @Test fun `all remaining semantic reasons map safely and persisted invariants become failures`() {
        val persistedInvariantReasons = setOf(
            MapEventRejectionReason.ACTION_PERSISTENCE_INCONSISTENT,
            MapEventRejectionReason.INVALID_LEGACY_CONTRIBUTION,
            MapEventRejectionReason.INVALID_PERSISTED_STATE,
        )
        MapEventRejectionReason.entries.forEach { reason ->
            val result = mapper.mapWriteOutcome(LocalMapWriteOutcome.SemanticRejected(null, reason))
            if (reason in persistedInvariantReasons) {
                assertEquals(
                    reason.name,
                    MapInteractionSubmissionFailure.INVALID_PERSISTED_STATE,
                    (result as MapInteractionSubmissionResult.Failed).reason,
                )
            } else {
                assertTrue(reason.name, result is MapInteractionSubmissionResult.Rejected)
            }
        }
    }

    @Test fun `refresh is data only and recommended for stale state`() {
        val stale = setOf(
            MapEventRejectionReason.ACTOR_NOT_MEMBER,
            MapEventRejectionReason.TARGET_NOT_FOUND,
            MapEventRejectionReason.TARGET_DELETED,
            MapEventRejectionReason.TARGET_EXPIRED,
            MapEventRejectionReason.INTERACTION_WINDOW_CLOSED,
            MapEventRejectionReason.TARGET_RESOLVED,
        )
        stale.forEach { reason ->
            assertTrue((mapper.mapWriteOutcome(LocalMapWriteOutcome.SemanticRejected("e", reason)) as
                MapInteractionSubmissionResult.Rejected).refreshRecommended)
        }
        assertFalse((mapper.mapWriteOutcome(LocalMapWriteOutcome.SemanticRejected(
            "e", MapEventRejectionReason.INVALID_ACTION_PAYLOAD,
        )) as MapInteractionSubmissionResult.Rejected).refreshRecommended)
    }

    @Test fun `transport warnings merge without changing local acceptance`() {
        val accepted = MapInteractionSubmissionResult.Accepted("event")
        assertNull(mapper.mapAcceptedDelivery(accepted, MapInteractionPostCommitResult.Enqueued).deliveryWarning)
        assertEquals(MapInteractionDeliveryWarning.MISSING_GROUP_KEY, mapper.mapAcceptedDelivery(
            accepted, MapInteractionPostCommitResult.MissingGroupKey,
        ).deliveryWarning)
        assertEquals(MapInteractionDeliveryWarning.ENQUEUE_UNAVAILABLE, mapper.mapAcceptedDelivery(
            accepted, MapInteractionPostCommitResult.EnqueueUnavailable,
        ).deliveryWarning)
        assertEquals(MapInteractionDeliveryWarning.ENQUEUE_FAILED, mapper.mapAcceptedDelivery(
            accepted, MapInteractionPostCommitResult.EnqueueFailed,
        ).deliveryWarning)
    }
}
