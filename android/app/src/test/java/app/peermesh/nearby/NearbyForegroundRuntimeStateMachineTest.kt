package app.peermesh.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyForegroundRuntimeStateMachineTest {
    @Test
    fun normalAndDiscoveryAllowNonNearbyWork() {
        val machine = NearbyForegroundRuntimeStateMachine()
        assertTrue(machine.allowsNonNearbyWork())
        machine.enterDiscovery()
        assertTrue(machine.allowsNonNearbyWork())
    }

    @Test
    fun joiningAndActiveRoomBlockNonNearbyButAcceptTheirRoomProtocol() {
        val machine = NearbyForegroundRuntimeStateMachine()
        machine.beginJoining("room-a", "attempt-a")
        assertFalse(machine.allowsNonNearbyWork())
        assertTrue(machine.acceptsRoomProtocol("room-a"))
        assertFalse(machine.acceptsRoomProtocol("room-b"))

        machine.activateRoom("room-a", "attempt-a", "session-a")
        assertFalse(machine.allowsNonNearbyWork())
        assertTrue(machine.acceptsRoomProtocol("room-a"))
    }

    @Test
    fun joinFailureCancelOrTimeoutReleasesMatchingAttempt() {
        listOf("failure", "cancel", "timeout").forEach { suffix ->
            val machine = NearbyForegroundRuntimeStateMachine()
            machine.beginJoining("room", "attempt-$suffix")
            machine.releaseJoining("attempt-$suffix")
            assertEquals(NearbyForegroundRuntimeStateMachine.State.Normal, machine.state)
            assertTrue(machine.allowsNonNearbyWork())
        }
    }

    @Test
    fun leavingRoomReleasesMatchingSession() {
        val machine = NearbyForegroundRuntimeStateMachine()
        machine.activateRoom("room", attemptId = null, sessionId = "session")
        machine.releaseRoom("session")
        assertEquals(NearbyForegroundRuntimeStateMachine.State.Normal, machine.state)
    }

    @Test
    fun staleJoinReleaseCannotReleaseNewAttempt() {
        val machine = NearbyForegroundRuntimeStateMachine()
        machine.beginJoining("room-a", "old")
        machine.releaseJoining("old")
        machine.beginJoining("room-b", "new")
        val transition = machine.releaseJoining("old")
        assertTrue(transition.staleIdentityIgnored)
        assertEquals(
            NearbyForegroundRuntimeStateMachine.State.Joining("room-b", "new"),
            machine.state,
        )
    }

    @Test
    fun staleRoomReleaseCannotReleaseNewSession() {
        val machine = NearbyForegroundRuntimeStateMachine()
        machine.activateRoom("room-a", null, "old")
        machine.releaseRoom("old")
        machine.beginJoining("room-b", "attempt-b")
        machine.activateRoom("room-b", "attempt-b", "new")
        val transition = machine.releaseRoom("old")
        assertTrue(transition.staleIdentityIgnored)
        assertEquals(
            NearbyForegroundRuntimeStateMachine.State.ActiveRoom("room-b", "new"),
            machine.state,
        )
    }

    @Test
    fun staleJoinPromotionCannotReplaceNewJoin() {
        val machine = NearbyForegroundRuntimeStateMachine()
        machine.beginJoining("room-a", "old")
        machine.releaseJoining("old")
        machine.beginJoining("room-b", "new")
        val transition = machine.activateRoom("room-a", "old", "old-session")
        assertTrue(transition.staleIdentityIgnored)
        assertEquals(
            NearbyForegroundRuntimeStateMachine.State.Joining("room-b", "new"),
            machine.state,
        )
    }

    @Test
    fun secondJoinCannotReplaceCurrentJoinWithoutExplicitRelease() {
        val machine = NearbyForegroundRuntimeStateMachine()
        machine.beginJoining("room-a", "attempt-a")
        val transition = machine.beginJoining("room-b", "attempt-b")

        assertTrue(transition.staleIdentityIgnored)
        assertEquals(NearbyForegroundRuntimeStateMachine.State.Joining("room-a", "attempt-a"), machine.state)
    }

    @Test
    fun duplicateSameJoinIsIdempotentAndActiveRoomCannotBeReplaced() {
        val machine = NearbyForegroundRuntimeStateMachine()
        machine.beginJoining("room-a", "attempt-a")
        assertFalse(machine.beginJoining("room-a", "attempt-a").changed)
        machine.activateRoom("room-a", "attempt-a", "session-a")

        val lateAutoOpen = machine.beginJoining("room-b", "attempt-b")
        assertTrue(lateAutoOpen.staleIdentityIgnored)
        assertEquals(NearbyForegroundRuntimeStateMachine.State.ActiveRoom("room-a", "session-a"), machine.state)
    }

    @Test
    fun releaseThenNewJoinWorksAndLatePromotionCannotTakeOwnership() {
        val machine = NearbyForegroundRuntimeStateMachine()
        machine.beginJoining("room-a", "attempt-a")
        machine.releaseJoining("attempt-a")
        machine.beginJoining("room-b", "attempt-b")

        assertTrue(machine.activateRoom("room-a", "attempt-a", "session-a").staleIdentityIgnored)
        assertEquals(NearbyForegroundRuntimeStateMachine.State.Joining("room-b", "attempt-b"), machine.state)
    }

    @Test
    fun discoveryLifecycleCannotOverrideForegroundIdentity() {
        val machine = NearbyForegroundRuntimeStateMachine()
        machine.beginJoining("room", "attempt")
        machine.leaveDiscovery()
        machine.enterDiscovery()
        assertEquals(
            NearbyForegroundRuntimeStateMachine.State.Joining("room", "attempt"),
            machine.state,
        )
    }
}
