package app.peermesh.nearby.presentation

import app.peermesh.nearby.NearbyPrivateGroupInvitationSendResult

data class NearbyPrivateInvitationDraft(
    val groupId: String,
    val title: String,
    val description: String,
)

data class NearbyPrivateInvitationComposerState(
    val selectedGroupId: String? = null,
    val title: String = "",
    val description: String = "",
    val sending: Boolean = false,
    val alreadySent: Boolean = false,
) {
    val canSend: Boolean
        get() = selectedGroupId != null && title.trim().isNotEmpty() &&
            title.length <= MAX_TITLE_CHARS && description.length <= MAX_DESCRIPTION_CHARS &&
            !sending && !alreadySent

    fun toDraft(): NearbyPrivateInvitationDraft? = selectedGroupId?.takeIf { canSend }?.let {
        NearbyPrivateInvitationDraft(it, title.trim(), description.trim())
    }

    companion object {
        const val MAX_TITLE_CHARS = 40
        const val MAX_DESCRIPTION_CHARS = 60
    }
}

sealed interface NearbyPrivateInvitationComposerOutcome {
    data object Sent : NearbyPrivateInvitationComposerOutcome
    data object PersistedForRetry : NearbyPrivateInvitationComposerOutcome
    data object SendLimitReached : NearbyPrivateInvitationComposerOutcome
    data object RetryableCreationFailure : NearbyPrivateInvitationComposerOutcome

    companion object {
        fun from(result: NearbyPrivateGroupInvitationSendResult): NearbyPrivateInvitationComposerOutcome = when (result) {
            is NearbyPrivateGroupInvitationSendResult.Sent -> Sent
            is NearbyPrivateGroupInvitationSendResult.PersistedForRetry -> PersistedForRetry
            NearbyPrivateGroupInvitationSendResult.SendLimitReached -> SendLimitReached
            else -> RetryableCreationFailure
        }
    }
}
