package app.peermesh.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyRoomDiscoveryAccumulatorTest {
    @Test
    fun `dedupes repeated advertisers for the same room`() {
        val accumulator = NearbyRoomDiscoveryAccumulator()
        accumulator.record(sighting(roomId = "room-1", sourceDeviceId = "peer-a", seenAtMs = 1_000L, rssi = -70))
        accumulator.record(sighting(roomId = "room-1", sourceDeviceId = "peer-b", seenAtMs = 1_500L, rssi = -60))

        val result = accumulator.evaluate(existingRoomsById = emptyMap(), nowMs = 2_000L)

        assertEquals(1, result.uniqueRoomSightings)
        assertEquals(1, result.decisions.size)
        assertEquals(NearbyRoomDiscoveryAccumulator.DecisionType.INSERT, result.decisions.single().type)
        assertEquals("peer-b", result.decisions.single().sourceDeviceId)
        assertEquals(2, result.decisions.single().advertiserCount)
    }

    @Test
    fun `skips refresh inside throttle window when metadata is unchanged`() {
        val accumulator = NearbyRoomDiscoveryAccumulator(minRoomRefreshIntervalMs = 10_000L)
        accumulator.record(sighting(roomId = "room-1", seenAtMs = 20_000L, rssi = -55))

        val result = accumulator.evaluate(
            existingRoomsById = mapOf(
                "room-1" to existingRoom(
                    roomId = "room-1",
                    lastSeenAtMs = 15_500L,
                    isStale = false,
                )
            ),
            nowMs = 20_000L,
        )

        assertEquals(NearbyRoomDiscoveryAccumulator.DecisionType.SKIP_THROTTLED, result.decisions.single().type)
    }

    @Test
    fun `bypasses throttle when stale room is seen again`() {
        val accumulator = NearbyRoomDiscoveryAccumulator(minRoomRefreshIntervalMs = 10_000L)
        accumulator.record(sighting(roomId = "room-1", seenAtMs = 20_000L, rssi = -55))

        val result = accumulator.evaluate(
            existingRoomsById = mapOf(
                "room-1" to existingRoom(
                    roomId = "room-1",
                    lastSeenAtMs = 19_500L,
                    isStale = true,
                )
            ),
            nowMs = 20_000L,
        )

        assertEquals(NearbyRoomDiscoveryAccumulator.DecisionType.REFRESH, result.decisions.single().type)
    }

    @Test
    fun `prioritizes unknown rooms over recently seen rooms under budget`() {
        val accumulator = NearbyRoomDiscoveryAccumulator(
            maxMetadataPeersPerBurst = 1,
            maxUniqueRoomsPerBurst = 1,
            minRoomRefreshIntervalMs = 10_000L,
        )
        accumulator.record(sighting(roomId = "known-room", seenAtMs = 30_000L, rssi = -40))
        accumulator.record(sighting(roomId = "new-room", seenAtMs = 29_000L, rssi = -80))

        val result = accumulator.evaluate(
            existingRoomsById = mapOf(
                "known-room" to existingRoom(
                    roomId = "known-room",
                    lastSeenAtMs = 5_000L,
                    isStale = false,
                )
            ),
            nowMs = 30_000L,
        )

        assertEquals(2, result.decisions.size)
        assertEquals("new-room", result.decisions.first().roomId)
        assertEquals(NearbyRoomDiscoveryAccumulator.DecisionType.INSERT, result.decisions.first().type)
        assertTrue(result.decisions.any { it.roomId == "known-room" && it.type == NearbyRoomDiscoveryAccumulator.DecisionType.SKIP_BUDGET })
    }

    private fun sighting(
        roomId: String,
        sourceDeviceId: String = "peer-a",
        seenAtMs: Long = 10_000L,
        rssi: Int = -60,
        participantCount: Int? = 3,
        roomName: String = "room",
        expiresAtMs: Long = 100_000L,
        protocolVersion: Int = 1,
    ): NearbyRoomDiscoveryAccumulator.MetadataSighting {
        return NearbyRoomDiscoveryAccumulator.MetadataSighting(
            sourceDeviceId = sourceDeviceId,
            advertisement = NearbyRoomBleAdvertisement(
                roomId = roomId,
                roomName = roomName,
                ownerDeviceId = "owner-$roomId",
                createdAtMs = 1_000L,
                expiresAtMs = expiresAtMs,
                participantCount = participantCount,
                protocolVersion = protocolVersion,
            ),
            seenAtMs = seenAtMs,
            rssi = rssi,
        )
    }

    private fun existingRoom(
        roomId: String,
        lastSeenAtMs: Long,
        isStale: Boolean,
        roomName: String = "room",
        expiresAtMs: Long = 100_000L,
        participantCount: Int? = 3,
        protocolVersion: Int = 1,
    ): NearbyRoomDiscoveryAccumulator.ExistingRoomSnapshot {
        return NearbyRoomDiscoveryAccumulator.ExistingRoomSnapshot(
            roomId = roomId,
            roomName = roomName,
            expiresAtMs = expiresAtMs,
            participantCount = participantCount,
            protocolVersion = protocolVersion,
            lastSeenAtMs = lastSeenAtMs,
            isStale = isStale,
            hasAuthoritativeMetadata = false,
        )
    }
}
