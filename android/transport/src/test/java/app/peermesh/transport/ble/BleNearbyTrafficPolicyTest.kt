package app.peermesh.transport.ble

import app.peermesh.transport.adapter.MeshTrafficHint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleNearbyTrafficPolicyTest {
    @Test
    fun bypassesQuietForNearbyRoomControlWork() {
        assertTrue(BleNearbyTrafficPolicy.shouldBypassDeviceQuiet(hasNearbyRoomControlWork = true))
        assertFalse(BleNearbyTrafficPolicy.shouldBypassDeviceQuiet(hasNearbyRoomControlWork = false))
    }

    @Test
    fun bypassesCooldownOnlyForNearbyRoomControlWork() {
        assertTrue(BleNearbyTrafficPolicy.shouldBypassDeviceCooldown(hasNearbyRoomControlWork = true))
        assertFalse(BleNearbyTrafficPolicy.shouldBypassDeviceCooldown(hasNearbyRoomControlWork = false))
    }

    @Test
    fun prioritizesNearbyRoomPriorityHintsBeforeMeshBatch() {
        assertTrue(BleNearbyTrafficPolicy.shouldPrioritizeBeforeMeshBatch(MeshTrafficHint.NEARBY_ROOM_CONTROL))
        assertTrue(BleNearbyTrafficPolicy.shouldPrioritizeBeforeMeshBatch(MeshTrafficHint.NEARBY_ROOM_KEY_BOOTSTRAP))
        assertTrue(BleNearbyTrafficPolicy.shouldPrioritizeBeforeMeshBatch(MeshTrafficHint.NEARBY_ROOM_DATA))
        assertFalse(BleNearbyTrafficPolicy.shouldPrioritizeBeforeMeshBatch(MeshTrafficHint.DEFAULT))
    }

    @Test
    fun foregroundAdmissionAllowsNearbyFamiliesAndTransportAckOnly() {
        assertTrue(BleNearbyTrafficPolicy.allowsAdmission(true, MeshTrafficHint.NEARBY_ROOM_KEY_BOOTSTRAP, false))
        assertTrue(BleNearbyTrafficPolicy.allowsAdmission(true, MeshTrafficHint.NEARBY_ROOM_CONTROL, false))
        assertTrue(BleNearbyTrafficPolicy.allowsAdmission(true, MeshTrafficHint.NEARBY_ROOM_DATA, false))
        assertTrue(BleNearbyTrafficPolicy.allowsAdmission(true, MeshTrafficHint.DEFAULT, true))
        assertFalse(BleNearbyTrafficPolicy.allowsAdmission(true, MeshTrafficHint.DEFAULT, false))
    }

    @Test
    fun normalAdmissionAndDequeueRemainUnchanged() {
        MeshTrafficHint.entries.forEach { hint ->
            assertTrue(BleNearbyTrafficPolicy.allowsAdmission(false, hint, false))
            assertTrue(BleNearbyTrafficPolicy.allowsNewItemDequeue(false, hint, false))
        }
    }

    @Test
    fun nullBatchEquivalentDefaultHintDoesNotBecomeNearby() {
        assertFalse(BleNearbyTrafficPolicy.isNearbyRoomTraffic(MeshTrafficHint.DEFAULT))
        assertTrue(BleNearbyTrafficPolicy.isNearbyRoomTraffic(MeshTrafficHint.NEARBY_ROOM_DATA))
    }

    @Test
    fun foregroundDrainFinishesCurrentAtomicItemButBlocksTheNextOne() {
        assertTrue(
            BleNearbyTrafficPolicy.allowsPacketDequeue(
                foregroundMode = true,
                hint = MeshTrafficHint.DEFAULT,
                isTransportAck = false,
                activeItemContinuation = true,
            )
        )
        assertFalse(
            BleNearbyTrafficPolicy.allowsPacketDequeue(
                foregroundMode = true,
                hint = MeshTrafficHint.DEFAULT,
                isTransportAck = false,
                activeItemContinuation = false,
            )
        )
    }

    @Test
    fun foregroundReserveAdmitsWholeNearbyEnvelopeOrRejectsBeforeQueueMutation() {
        assertTrue(
            BleNearbyTrafficPolicy.hasForegroundQueueHeadroom(
                currentGlobalPackets = 220,
                currentPeerPackets = 72,
                incomingPackets = 48,
                globalBaseLimit = 220,
                peerBaseLimit = 72,
                reservePackets = 192,
            )
        )
        assertFalse(
            BleNearbyTrafficPolicy.hasForegroundQueueHeadroom(
                currentGlobalPackets = 400,
                currentPeerPackets = 250,
                incomingPackets = 20,
                globalBaseLimit = 220,
                peerBaseLimit = 72,
                reservePackets = 192,
            )
        )
    }

    @Test
    fun bypassesAddressCooldownOnlyForKeyBootstrap() {
        assertTrue(BleNearbyTrafficPolicy.shouldBypassAddressCooldown(MeshTrafficHint.NEARBY_ROOM_KEY_BOOTSTRAP))
        assertFalse(BleNearbyTrafficPolicy.shouldBypassAddressCooldown(MeshTrafficHint.NEARBY_ROOM_CONTROL))
        assertFalse(BleNearbyTrafficPolicy.shouldBypassAddressCooldown(MeshTrafficHint.DEFAULT))
    }

    @Test
    fun prioritizesKeyBootstrapBeforeOtherNearbyRoomControl() {
        assertTrue(BleNearbyTrafficPolicy.shouldPrioritizeBeforeNearbyRoomControl(MeshTrafficHint.NEARBY_ROOM_KEY_BOOTSTRAP))
        assertFalse(BleNearbyTrafficPolicy.shouldPrioritizeBeforeNearbyRoomControl(MeshTrafficHint.NEARBY_ROOM_CONTROL))
        assertFalse(BleNearbyTrafficPolicy.shouldPrioritizeBeforeNearbyRoomControl(MeshTrafficHint.DEFAULT))
    }
}
