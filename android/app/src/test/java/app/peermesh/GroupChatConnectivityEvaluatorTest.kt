package app.peermesh

import org.junit.Assert.assertTrue
import org.junit.Test

class GroupChatConnectivityEvaluatorTest {
    @Test
    fun `healthy nearby runtime with zero peers remains healthy`() {
        val health = NearbyTransportHealthEvaluator.evaluate(
            NearbyTransportSnapshot(
                systemReady = true,
                runtimeRunning = true,
                unresolvedStall = false,
            ),
        )
        assertTrue(health == NearbyTransportHealth.Healthy)
    }

    @Test
    fun `configured online path without recent success is unverified`() {
        val online = OnlinePathEvaluator.evaluate(
            OnlinePathSnapshot(
                capabilityAvailable = true,
                syncEnabled = true,
                internetValidated = true,
                pathConfigured = true,
                hasRecentSuccess = false,
            ),
        )
        assertTrue(online == OnlinePathState.AvailableUnverified)
    }

    @Test
    fun `configured online path without validated internet is unavailable`() {
        val online = OnlinePathEvaluator.evaluate(
            OnlinePathSnapshot(
                capabilityAvailable = true,
                syncEnabled = true,
                internetValidated = false,
                pathConfigured = true,
                hasRecentSuccess = true,
            ),
        )
        assertTrue(online == OnlinePathState.Unavailable)
    }

    @Test
    fun `online plus nearby peers is online and nearby`() {
        val state = GroupChatConnectivityEvaluator.evaluate(
            onlinePath = OnlinePathState.AvailableUnverified,
            nearbyHealth = NearbyTransportHealth.Healthy,
            nearbyPeerCount = 2,
        )
        assertTrue(state is GroupChatConnectivityState.OnlineAndNearby)
    }

    @Test
    fun `online plus healthy nearby with no peers remains online only`() {
        val state = GroupChatConnectivityEvaluator.evaluate(
            onlinePath = OnlinePathState.Healthy,
            nearbyHealth = NearbyTransportHealth.Healthy,
            nearbyPeerCount = 0,
        )
        assertTrue(state is GroupChatConnectivityState.OnlineOnly)
    }

    @Test
    fun `nearby healthy with no peers is nearby ready not an error`() {
        val state = GroupChatConnectivityEvaluator.evaluate(
            onlinePath = OnlinePathState.Unavailable,
            nearbyHealth = NearbyTransportHealth.Healthy,
            nearbyPeerCount = 0,
        )
        assertTrue(state is GroupChatConnectivityState.NearbyOnly)
    }

    @Test
    fun `blocked nearby and unavailable online pauses connection`() {
        val state = GroupChatConnectivityEvaluator.evaluate(
            onlinePath = OnlinePathState.Unavailable,
            nearbyHealth = NearbyTransportHealth.Blocked,
            nearbyPeerCount = 0,
        )
        assertTrue(state is GroupChatConnectivityState.NoActivePath)
    }
}
