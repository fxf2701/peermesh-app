package app.peermesh.nearby.presentation

import app.peermesh.nearby.NearbyPublicGroupInvitationSendResult

data class NearbyPublicInvitationDraft(
    val groupId: String,
    val title: String,
    val description: String,
)

data class NearbyPublicInvitationComposerState(
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

    fun toDraft(): NearbyPublicInvitationDraft? = selectedGroupId?.takeIf { canSend }?.let {
        NearbyPublicInvitationDraft(it, title.trim(), description.trim())
    }

    companion object {
        const val MAX_TITLE_CHARS = 40
        const val MAX_DESCRIPTION_CHARS = 60
    }
}

sealed interface NearbyPublicInvitationComposerOutcome {
    data object Sent : NearbyPublicInvitationComposerOutcome
    data object PersistedForRetry : NearbyPublicInvitationComposerOutcome
    data object SendLimitReached : NearbyPublicInvitationComposerOutcome
    data object RetryableCreationFailure : NearbyPublicInvitationComposerOutcome

    companion object {
        fun from(result: NearbyPublicGroupInvitationSendResult): NearbyPublicInvitationComposerOutcome = when (result) {
            is NearbyPublicGroupInvitationSendResult.Sent -> Sent
            is NearbyPublicGroupInvitationSendResult.PersistedForRetry -> PersistedForRetry
            NearbyPublicGroupInvitationSendResult.SendLimitReached -> SendLimitReached
            else -> RetryableCreationFailure
        }
    }
}
