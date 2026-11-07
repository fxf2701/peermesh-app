package app.peermesh.nearby

/** Presentation-safe public invitation data projected from one authoritative persisted message. */
data class NearbyPublicGroupInvitationPresentation(
    val messageId: String,
    val createdAtMs: Long,
    val logicalSenderId: String,
    val senderDisplayName: String,
    val title: String,
    val shortDescription: String,
) {
    init {
        require(messageId.isNotBlank())
        require(logicalSenderId.isNotBlank())
        require(senderDisplayName.isNotBlank())
        require(title.isNotBlank())
    }
}

sealed interface NearbyPublicGroupInvitationTimelineInspection {
    data class Invitation(
        val presentation: NearbyPublicGroupInvitationPresentation,
    ) : NearbyPublicGroupInvitationTimelineInspection

    data class HiddenLocally(
        val presentation: NearbyPublicGroupInvitationPresentation,
    ) : NearbyPublicGroupInvitationTimelineInspection

    data object MessageNotFound : NearbyPublicGroupInvitationTimelineInspection
    data object NotGroupInvitation : NearbyPublicGroupInvitationTimelineInspection
    data object NotPublicInvitation : NearbyPublicGroupInvitationTimelineInspection
    data object UnsupportedOrInvalidInvitation : NearbyPublicGroupInvitationTimelineInspection
    data object Expired : NearbyPublicGroupInvitationTimelineInspection
    data object MessageIdentityMismatch : NearbyPublicGroupInvitationTimelineInspection
}
