package app.peermesh.nearby

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyRoomReceiverPolicyTest {
    @Test
    fun matchingJoiningOrActiveRoomProcessesAllNearbyFamilies() {
        listOf(
            NearbyRoomTransportEnvelope.KIND_JOIN_READY_V1,
            NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1,
            NearbyRoomTransportEnvelope.KIND_SELF_INFO_ACK_V1,
            NearbyRoomTransportEnvelope.KIND_MESSAGE_V2,
            NearbyRoomTransportEnvelope.KIND_MESSAGE_OFFER_V1,
            NearbyRoomTransportEnvelope.KIND_MESSAGE_FETCH_V1,
            NearbyRoomTransportEnvelope.KIND_MESSAGE_DATA_V1,
        ).forEach { kind ->
            assertTrue(NearbyRoomReceiverPolicy.shouldProcess(kind, matchingRuntimeRoom = true))
        }
    }

    @Test
    fun fourJoinTransitionKindsBypassRoomProjectionGate() {
        listOf(
            NearbyRoomTransportEnvelope.KIND_KEY_REQUEST_V1,
            NearbyRoomTransportEnvelope.KIND_KEY_BOOTSTRAP_V1,
            NearbyRoomTransportEnvelope.KIND_METADATA_REQUEST_V1,
            NearbyRoomTransportEnvelope.KIND_METADATA_RESPONSE_V1,
        ).forEach { kind ->
            assertTrue(NearbyRoomReceiverPolicy.shouldProcess(kind, matchingRuntimeRoom = false))
        }
    }

    @Test
    fun wrongRoomChatIsNotAppliedOrPublishedAndRemainsRetryableAtReceiverBoundary() {
        assertFalse(
            NearbyRoomReceiverPolicy.shouldProcess(
                NearbyRoomTransportEnvelope.KIND_MESSAGE_V2,
                matchingRuntimeRoom = false,
            )
        )
    }
}
