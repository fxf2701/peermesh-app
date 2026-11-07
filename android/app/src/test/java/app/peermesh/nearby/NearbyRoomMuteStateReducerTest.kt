package app.peermesh.nearby

import app.peermesh.data.db.entity.NearbyRoomMuteStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyRoomMuteStateReducerTest {
    @Test
    fun `applyIncomingState applies new mute state`() {
        val decision = NearbyRoomMuteStateReducer.applyIncomingState(
            existing = null,
            incoming = state(isMuted = true, updatedAtMs = 200L),
        )

        assertTrue(decision is NearbyRoomMuteStateReducer.Decision.Apply)
        val applied = (decision as NearbyRoomMuteStateReducer.Decision.Apply).state
        assertEquals(true, applied.is_muted)
        assertEquals(200L, applied.updated_at_ms)
    }

    @Test
    fun `applyIncomingState rejects stale state`() {
        val decision = NearbyRoomMuteStateReducer.applyIncomingState(
            existing = state(isMuted = true, updatedAtMs = 300L),
            incoming = state(isMuted = false, updatedAtMs = 200L),
        )

        assertEquals(NearbyRoomMuteStateReducer.Decision.Skip("stale_state"), decision)
    }

    @Test
    fun `applyIncomingState rejects same timestamp conflict`() {
        val decision = NearbyRoomMuteStateReducer.applyIncomingState(
            existing = state(isMuted = true, updatedAtMs = 300L),
            incoming = state(isMuted = false, updatedAtMs = 300L),
        )

        assertEquals(NearbyRoomMuteStateReducer.Decision.Skip("same_timestamp_conflict"), decision)
    }

    @Test
    fun `applyIncomingState applies unmute tombstone`() {
        val decision = NearbyRoomMuteStateReducer.applyIncomingState(
            existing = state(isMuted = true, updatedAtMs = 300L, expiresAtMs = 1_000L),
            incoming = state(isMuted = false, updatedAtMs = 400L, expiresAtMs = 900L),
        )

        assertTrue(decision is NearbyRoomMuteStateReducer.Decision.Apply)
        val applied = (decision as NearbyRoomMuteStateReducer.Decision.Apply).state
        assertEquals(false, applied.is_muted)
        assertEquals(400L, applied.updated_at_ms)
        assertEquals(1_000L, applied.expires_at_ms)
    }

    private fun state(
        roomId: String = "room-1",
        targetDeviceId: String = "device-b",
        actorDeviceId: String = "device-owner",
        isMuted: Boolean,
        updatedAtMs: Long,
        expiresAtMs: Long = 1_000L,
    ): NearbyRoomMuteStateEntity {
        return NearbyRoomMuteStateEntity(
            room_id = roomId,
            target_device_id = targetDeviceId,
            actor_device_id = actorDeviceId,
            is_muted = isMuted,
            updated_at_ms = updatedAtMs,
            expires_at_ms = expiresAtMs,
        )
    }
}
