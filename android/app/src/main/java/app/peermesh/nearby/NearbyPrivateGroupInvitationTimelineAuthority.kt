package app.peermesh.nearby

import app.peermesh.data.db.entity.NearbyRoomMessageEntity

internal object NearbyPrivateGroupInvitationTimelineAuthority {
    fun authoritativeOwnerId(message: NearbyRoomMessageEntity): String? =
        message.owner_device_id?.trim()?.takeIf { it.isNotBlank() }

    fun resolveSenderDisplayName(
        logicalSenderId: String,
        localParticipantId: String,
        localDisplayName: String?,
        participantDisplayName: String?,
        unknownSenderLabel: String,
    ): String = when {
        logicalSenderId == localParticipantId -> localDisplayName
        else -> participantDisplayName
    }?.trim()?.takeIf { it.isNotBlank() } ?: unknownSenderLabel
}
