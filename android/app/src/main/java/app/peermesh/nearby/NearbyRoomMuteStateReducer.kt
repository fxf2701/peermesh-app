package app.peermesh.nearby

import app.peermesh.data.db.entity.NearbyRoomMuteStateEntity

object NearbyRoomMuteStateReducer {
    sealed interface Decision {
        data class Apply(val state: NearbyRoomMuteStateEntity, val reason: String) : Decision
        data class Skip(val reason: String) : Decision
    }

    fun applyIncomingState(
        existing: NearbyRoomMuteStateEntity?,
        incoming: NearbyRoomMuteStateEntity,
    ): Decision {
        if (existing == null) {
            return Decision.Apply(incoming, if (incoming.is_muted) "new_mute" else "new_unmute")
        }
        if (incoming.updated_at_ms < existing.updated_at_ms) {
            return Decision.Skip("stale_state")
        }
        if (incoming.updated_at_ms == existing.updated_at_ms &&
            incoming.is_muted == existing.is_muted &&
            incoming.actor_device_id == existing.actor_device_id
        ) {
            return Decision.Skip("duplicate_state")
        }
        if (incoming.updated_at_ms == existing.updated_at_ms && incoming.is_muted != existing.is_muted) {
            return Decision.Skip("same_timestamp_conflict")
        }
        return Decision.Apply(
            state = existing.copy(
                actor_device_id = incoming.actor_device_id,
                is_muted = incoming.is_muted,
                updated_at_ms = incoming.updated_at_ms,
                expires_at_ms = maxOf(existing.expires_at_ms, incoming.expires_at_ms),
            ),
            reason = when {
                existing.is_muted == incoming.is_muted && incoming.is_muted -> "refresh_mute"
                existing.is_muted == incoming.is_muted -> "refresh_unmute"
                incoming.is_muted -> "mute_transition"
                else -> "unmute_transition"
            },
        )
    }
}
