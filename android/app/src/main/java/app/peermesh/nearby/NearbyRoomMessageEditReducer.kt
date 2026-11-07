package app.peermesh.nearby

import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.data.db.entity.NearbyRoomMessageEntity

object NearbyRoomMessageEditReducer {
    sealed interface Decision {
        data class Apply(val message: NearbyRoomMessageEntity, val reason: String) : Decision
        data class Skip(val reason: String) : Decision
    }

    const val DELETED_MESSAGE_BODY = "Deleted"

    fun applyIncomingSnapshot(
        existing: NearbyRoomMessageEntity?,
        incoming: NearbyRoomMessageEntity,
    ): Decision {
        if (GroupInvitationProtocol.isExactGroupInvitationCandidate(incoming.body)) {
            return applyIncomingGroupInvitationSnapshot(existing, incoming)
        }
        if (existing == null) {
            return Decision.Apply(
                incoming.copy(local_render_source_body = null, is_hidden_locally = false),
                "new_snapshot",
            )
        }
        if (existing.delivery_state == NearbyRoomMessageEntity.DELIVERY_SYSTEM) {
            return Decision.Skip("system_message")
        }

        if (GroupInvitationProtocol.isExactGroupInvitationCandidate(existing.body)) {
            return applyImmutableGroupInvitationDuplicate(existing, incoming)
        }

        val existingAuthor = existing.owner_device_id ?: existing.sender_device_id
        val incomingAuthor = incoming.owner_device_id ?: incoming.sender_device_id
        if (existingAuthor != incomingAuthor) {
            return Decision.Skip("actor_mismatch")
        }

        val existingEditedAt = existing.edited_at_ms
        val incomingEditedAt = incoming.edited_at_ms
        val existingDeletedAt = existing.deleted_at_ms
        val incomingDeletedAt = incoming.deleted_at_ms
        val existingReplyTo = existing.reply_to
        val incomingReplyTo = incoming.reply_to

        if (incomingDeletedAt != null && existingDeletedAt != null && incomingDeletedAt < existingDeletedAt) {
            return Decision.Skip("stale_delete")
        }

        if (existingDeletedAt != null && incomingDeletedAt == null) {
            return Decision.Skip("missing_delete_timestamp")
        }

        if (existingEditedAt != null && incomingEditedAt == null && incomingDeletedAt == null) {
            return if (existing.body == incoming.body) {
                Decision.Apply(
                    existing.copy(
                        received_at_ms = maxOf(existing.received_at_ms, incoming.received_at_ms),
                        ttl_expires_at_ms = maxOf(existing.ttl_expires_at_ms, incoming.ttl_expires_at_ms),
                        sender_display_name = incoming.sender_display_name ?: existing.sender_display_name,
                    ),
                    reason = "refresh_existing_snapshot",
                )
            } else {
                Decision.Skip("missing_edit_timestamp")
            }
        }

        if (existingReplyTo != null && incomingReplyTo != null && existingReplyTo != incomingReplyTo) {
            return Decision.Skip("reply_conflict")
        }

        if (incomingEditedAt != null && existingEditedAt != null && incomingEditedAt < existingEditedAt) {
            return Decision.Skip("stale_edit")
        }

        if (incomingEditedAt == null && existingEditedAt == null && existing.body != incoming.body) {
            return Decision.Skip("content_conflict")
        }

        if (existingDeletedAt != null && incomingDeletedAt != null && incoming.body != DELETED_MESSAGE_BODY) {
            return Decision.Skip("deleted_body_conflict")
        }

        val appliedDeletedAt = incomingDeletedAt ?: existingDeletedAt
        val appliedEditedAt = if (appliedDeletedAt != null) {
            null
        } else {
            incomingEditedAt ?: existingEditedAt
        }
        val appliedBody = if (appliedDeletedAt != null) {
            DELETED_MESSAGE_BODY
        } else {
            incoming.body
        }

        val applied = existing.copy(
            owner_device_id = incoming.owner_device_id ?: existing.owner_device_id,
            sender_device_id = incoming.sender_device_id,
            sender_display_name = incoming.sender_display_name ?: existing.sender_display_name,
            reply_to = incomingReplyTo ?: existingReplyTo,
            body = appliedBody,
            created_at_ms = incoming.created_at_ms,
            received_at_ms = maxOf(existing.received_at_ms, incoming.received_at_ms),
            ttl_expires_at_ms = maxOf(existing.ttl_expires_at_ms, incoming.ttl_expires_at_ms),
            delivery_state = incoming.delivery_state,
            edited_at_ms = appliedEditedAt,
            deleted_at_ms = appliedDeletedAt,
        )
        return Decision.Apply(
            applied,
            reason = when {
                incomingDeletedAt != null && existingDeletedAt == null -> "delete_snapshot"
                incomingDeletedAt != null -> "refresh_deleted_snapshot"
                incomingEditedAt != null && existingEditedAt == null -> "edit_snapshot"
                incomingEditedAt != null -> "replace_edited_snapshot"
                incomingReplyTo != null && existingReplyTo == null -> "reply_snapshot"
                else -> "replace_snapshot"
            },
        )
    }

    private fun applyIncomingGroupInvitationSnapshot(
        existing: NearbyRoomMessageEntity?,
        incoming: NearbyRoomMessageEntity,
    ): Decision {
        if (incoming.reply_to != null ||
            incoming.edited_at_ms != null ||
            incoming.deleted_at_ms != null
        ) {
            return Decision.Skip("immutable_group_invitation")
        }
        if (existing == null) {
            return Decision.Apply(
                incoming.copy(local_render_source_body = null, is_hidden_locally = false),
                "new_immutable_group_invitation",
            )
        }
        return applyImmutableGroupInvitationDuplicate(existing, incoming)
    }

    private fun applyImmutableGroupInvitationDuplicate(
        existing: NearbyRoomMessageEntity,
        incoming: NearbyRoomMessageEntity,
    ): Decision {
        val existingAuthor = existing.owner_device_id ?: existing.sender_device_id
        val incomingAuthor = incoming.owner_device_id ?: incoming.sender_device_id
        if (existingAuthor != incomingAuthor ||
            existing.body != incoming.body ||
            existing.reply_to != incoming.reply_to ||
            existing.created_at_ms != incoming.created_at_ms ||
            existing.ttl_expires_at_ms != incoming.ttl_expires_at_ms ||
            existing.edited_at_ms != incoming.edited_at_ms ||
            existing.deleted_at_ms != incoming.deleted_at_ms
        ) {
            return Decision.Skip("immutable_group_invitation")
        }
        return Decision.Apply(
            existing.copy(
                sender_device_id = incoming.sender_device_id,
                sender_display_name = incoming.sender_display_name ?: existing.sender_display_name,
                received_at_ms = maxOf(existing.received_at_ms, incoming.received_at_ms),
                delivery_state = incoming.delivery_state,
            ),
            reason = "refresh_immutable_group_invitation",
        )
    }
}
