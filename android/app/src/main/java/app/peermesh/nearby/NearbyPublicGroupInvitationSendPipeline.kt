package app.peermesh.nearby

import android.content.Context
import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.core.groupinvitation.InvitationCredential
import app.peermesh.core.groupinvitation.PublicGroupInvitationPayload
import app.peermesh.data.db.entity.NearbyRoomMessageEntity

/** Semantic caller-owned input for one public group-invitation send attempt. */
data class NearbyPublicGroupInvitationSendRequest(
    val roomId: String,
    val title: String,
    val shortDescription: String,
    val invitationCredential: InvitationCredential,
)

sealed interface NearbyPublicGroupInvitationSendResult {
    data class Sent(val message: NearbyRoomMessageEntity) : NearbyPublicGroupInvitationSendResult
    data class PersistedForRetry(val message: NearbyRoomMessageEntity) : NearbyPublicGroupInvitationSendResult
    data object PreparationUnavailable : NearbyPublicGroupInvitationSendResult
    data object InvalidInvitationContent : NearbyPublicGroupInvitationSendResult
    data object InvalidPreparation : NearbyPublicGroupInvitationSendResult
    data object StalePreparation : NearbyPublicGroupInvitationSendResult
    data object RoomUnavailable : NearbyPublicGroupInvitationSendResult
    data object SenderMismatch : NearbyPublicGroupInvitationSendResult
    data object SenderMuted : NearbyPublicGroupInvitationSendResult
    data object SendLimitReached : NearbyPublicGroupInvitationSendResult
    data object MessageIdCollision : NearbyPublicGroupInvitationSendResult
}

/**
 * Public-only GI2E3 orchestration boundary.
 *
 * Repository authority remains responsible for issuing the frozen preparation capability and for
 * atomically validating/inserting the finalized canonical body. Transport is invoked only with the
 * persisted entity returned by that insertion.
 */
class NearbyPublicGroupInvitationSendPipeline internal constructor(
    private val prepare: (String, NearbyGroupInvitationAudience) -> NearbyGroupInvitationPreparation?,
    private val insert: (
        NearbyGroupInvitationPreparation,
        String,
        String?,
    ) -> PreparedGroupInvitationInsertResult,
    private val submitPersistedMessage: (NearbyRoomMessageEntity) -> Boolean,
) {
    constructor(
        context: Context,
        repository: NearbyRoomRepository,
        transportManager: NearbyRoomTransportManager,
    ) : this(
        prepare = repository::prepareGroupInvitationContext,
        insert = repository::insertPreparedGroupInvitation,
        submitPersistedMessage = productionGroupInvitationSubmitter(
            context = context.applicationContext,
            repository = repository,
            transportManager = transportManager,
        ),
    )

    fun send(request: NearbyPublicGroupInvitationSendRequest): NearbyPublicGroupInvitationSendResult {
        if (!hasValidSemanticContent(request)) {
            return NearbyPublicGroupInvitationSendResult.InvalidInvitationContent
        }

        val preparation = prepare(request.roomId, NearbyGroupInvitationAudience.Public)
            ?: return NearbyPublicGroupInvitationSendResult.PreparationUnavailable

        val finalizedOuterBody = try {
            GroupInvitationProtocol.encodePublic(
                PublicGroupInvitationPayload(
                    cardId = preparation.messageId,
                    title = request.title,
                    shortDescription = request.shortDescription,
                    invitationCredential = request.invitationCredential,
                )
            )
        } catch (_: IllegalArgumentException) {
            return NearbyPublicGroupInvitationSendResult.InvalidInvitationContent
        }

        return when (
            val inserted = insert(
                preparation,
                finalizedOuterBody,
                null,
            )
        ) {
            is PreparedGroupInvitationInsertResult.Inserted -> {
                if (submitPersistedMessage(inserted.message)) {
                    NearbyPublicGroupInvitationSendResult.Sent(inserted.message)
                } else {
                    NearbyPublicGroupInvitationSendResult.PersistedForRetry(inserted.message)
                }
            }
            PreparedGroupInvitationInsertResult.InvalidPreparation ->
                NearbyPublicGroupInvitationSendResult.InvalidPreparation
            PreparedGroupInvitationInsertResult.InvalidPreparedContent ->
                NearbyPublicGroupInvitationSendResult.InvalidInvitationContent
            PreparedGroupInvitationInsertResult.StalePreparation ->
                NearbyPublicGroupInvitationSendResult.StalePreparation
            PreparedGroupInvitationInsertResult.RoomUnavailable ->
                NearbyPublicGroupInvitationSendResult.RoomUnavailable
            PreparedGroupInvitationInsertResult.SenderMismatch ->
                NearbyPublicGroupInvitationSendResult.SenderMismatch
            PreparedGroupInvitationInsertResult.SenderMuted ->
                NearbyPublicGroupInvitationSendResult.SenderMuted
            PreparedGroupInvitationInsertResult.SendLimitReached ->
                NearbyPublicGroupInvitationSendResult.SendLimitReached
            PreparedGroupInvitationInsertResult.MessageIdCollision ->
                NearbyPublicGroupInvitationSendResult.MessageIdCollision
        }
    }

    private fun hasValidSemanticContent(
        request: NearbyPublicGroupInvitationSendRequest,
    ): Boolean =
        try {
            PublicGroupInvitationPayload(
                cardId = SEMANTIC_VALIDATION_CARD_ID,
                title = request.title,
                shortDescription = request.shortDescription,
                invitationCredential = request.invitationCredential,
            )
            true
        } catch (_: IllegalArgumentException) {
            false
        }


    private companion object {
        private const val SEMANTIC_VALIDATION_CARD_ID = "validation-card"
    }
}
