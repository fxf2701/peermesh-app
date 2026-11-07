package app.peermesh.map.presentation.map.navigation

import app.peermesh.core.map.MapItemType
import app.peermesh.map.application.detail.MapItemIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMapItemNavigationCoordinatorM3D4Test {
    private val marker = MapItemIdentity("g", MapItemType.MARKER, "same")
    private val area = MapItemIdentity("g", MapItemType.AREA, "same")

    @Test fun initialStateIsReadyAndValidTypedIdentitiesReserve() {
        val subject = coordinator()
        assertEquals(MapItemNavigationState.Ready, subject.currentState())
        assertEquals(MapItemNavigationDecision.Reserve(marker), subject.begin(marker))
        assertEquals(MapItemNavigationState.Launching(marker), subject.currentState())
        subject.markSelectionFailed(marker)
        assertEquals(MapItemNavigationDecision.Reserve(area), subject.begin(area))
        assertFalse(marker == area)
    }

    @Test fun invalidLifecycleAndScopeFactsBlockBeforeReservation() {
        assertBlocked(
            MapItemNavigationBlockReason.INVALID_IDENTITY,
            MapItemIdentity("", MapItemType.MARKER, "x"),
        )
        assertBlocked(
            MapItemNavigationBlockReason.INVALID_IDENTITY,
            MapItemIdentity("g", MapItemType.MARKER, " "),
        )
        assertBlocked(MapItemNavigationBlockReason.WRONG_GROUP, marker, currentGroupId = "other")
        assertBlocked(MapItemNavigationBlockReason.ACTIVITY_NOT_ACTIVE, marker, activityActive = false)
        assertBlocked(
            MapItemNavigationBlockReason.CREATION_DRAFT_ACTIVE,
            marker,
            creationDraftActive = true,
        )
    }

    @Test fun launchingIgnoresEveryDuplicateWithoutReplacingReservedIdentity() {
        val subject = coordinator()
        assertEquals(MapItemNavigationDecision.Reserve(marker), subject.begin(marker))
        assertEquals(MapItemNavigationDecision.IgnoreDuplicate, subject.begin(marker))
        assertEquals(MapItemNavigationDecision.IgnoreDuplicate, subject.begin(area))
        assertEquals(MapItemNavigationState.Launching(marker), subject.currentState())
    }

    @Test fun awaitingReturnIgnoresEveryFurtherClickWithoutReplacingOwner() {
        val subject = coordinator()
        subject.reserveAndLaunch(marker)
        subject.markLaunchSucceeded(marker)
        assertEquals(MapItemNavigationState.AwaitingReturn(marker), subject.currentState())
        assertEquals(MapItemNavigationDecision.IgnoreDuplicate, subject.begin(marker))
        assertEquals(MapItemNavigationDecision.IgnoreDuplicate, subject.begin(area))
        assertEquals(MapItemNavigationState.AwaitingReturn(marker), subject.currentState())
    }

    @Test fun ownedSelectionAcceptancePermitsLaunchAndFailureRestoresReady() {
        val success = coordinator()
        assertEquals(MapItemNavigationDecision.Reserve(marker), success.begin(marker))
        assertEquals(MapItemNavigationDecision.Launch(marker), success.markSelectionAccepted(marker))
        assertEquals(MapItemNavigationState.Launching(marker), success.currentState())

        val failure = coordinator()
        failure.begin(area)
        failure.markSelectionFailed(area)
        assertEquals(MapItemNavigationState.Ready, failure.currentState())
        assertEquals(MapItemNavigationDecision.Reserve(marker), failure.begin(marker))
    }

    @Test fun staleSelectionCallbacksCannotAffectCurrentOwner() {
        val subject = coordinator()
        subject.begin(marker)
        assertEquals(MapItemNavigationDecision.IgnoreDuplicate, subject.markSelectionAccepted(area))
        subject.markSelectionFailed(area)
        assertEquals(MapItemNavigationState.Launching(marker), subject.currentState())
        assertEquals(MapItemNavigationDecision.Launch(marker), subject.markSelectionAccepted(marker))
        subject.markSelectionFailed(area)
        assertEquals(MapItemNavigationState.Launching(marker), subject.currentState())
    }

    @Test fun ownedLaunchCallbacksTransitionAndStaleLaunchCallbacksDoNothing() {
        val success = coordinator()
        success.reserveAndLaunch(marker)
        success.markLaunchSucceeded(area)
        assertEquals(MapItemNavigationState.Launching(marker), success.currentState())
        success.markLaunchSucceeded(marker)
        assertEquals(MapItemNavigationState.AwaitingReturn(marker), success.currentState())

        val failure = coordinator()
        failure.reserveAndLaunch(marker)
        failure.markLaunchFailed(area)
        assertEquals(MapItemNavigationState.Launching(marker), failure.currentState())
        failure.markLaunchFailed(marker)
        assertEquals(MapItemNavigationState.Ready, failure.currentState())
    }

    @Test fun returnRefreshIsTrueExactlyOnce() {
        val subject = coordinator()
        assertFalse(subject.consumeReturnRefreshRequest())
        subject.reserveAndLaunch(area)
        assertFalse(subject.consumeReturnRefreshRequest())
        subject.markLaunchSucceeded(area)
        assertTrue(subject.consumeReturnRefreshRequest())
        assertEquals(MapItemNavigationState.Ready, subject.currentState())
        assertFalse(subject.consumeReturnRefreshRequest())
    }

    @Test fun closeIsIdempotentTerminalAndCannotBeRevivedByCallbacks() {
        val subject = coordinator()
        subject.begin(marker)
        subject.close()
        subject.close()
        assertEquals(MapItemNavigationState.Closed, subject.currentState())
        assertEquals(
            MapItemNavigationDecision.Blocked(MapItemNavigationBlockReason.NAVIGATION_CLOSED),
            subject.begin(marker),
        )
        assertEquals(
            MapItemNavigationDecision.Blocked(MapItemNavigationBlockReason.NAVIGATION_CLOSED),
            subject.markSelectionAccepted(marker),
        )
        subject.markSelectionFailed(marker)
        subject.markLaunchSucceeded(marker)
        subject.markLaunchFailed(marker)
        assertFalse(subject.consumeReturnRefreshRequest())
        assertEquals(MapItemNavigationState.Closed, subject.currentState())
    }

    private fun coordinator() = DefaultMapItemNavigationCoordinator()

    private fun MapItemNavigationCoordinator.begin(
        identity: MapItemIdentity,
        currentGroupId: String = "g",
        creationDraftActive: Boolean = false,
        activityActive: Boolean = true,
    ) = beginItemClick(identity, currentGroupId, creationDraftActive, activityActive)

    private fun MapItemNavigationCoordinator.reserveAndLaunch(identity: MapItemIdentity) {
        assertEquals(MapItemNavigationDecision.Reserve(identity), begin(identity))
        assertEquals(MapItemNavigationDecision.Launch(identity), markSelectionAccepted(identity))
    }

    private fun assertBlocked(
        reason: MapItemNavigationBlockReason,
        identity: MapItemIdentity,
        currentGroupId: String = "g",
        creationDraftActive: Boolean = false,
        activityActive: Boolean = true,
    ) {
        val subject = coordinator()
        assertEquals(
            MapItemNavigationDecision.Blocked(reason),
            subject.begin(identity, currentGroupId, creationDraftActive, activityActive),
        )
        assertEquals(MapItemNavigationState.Ready, subject.currentState())
    }
}
